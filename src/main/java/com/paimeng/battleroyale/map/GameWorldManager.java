package com.paimeng.battleroyale.map;

import com.google.common.collect.ImmutableList;
import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.game.FlightManager;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.item.ModItems;
import com.paimeng.battleroyale.match.MapQueue;
import com.paimeng.battleroyale.mixin.MinecraftServerAccessor;
import com.paimeng.battleroyale.network.LootPointsSync;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.ServerLevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 游戏维度管理器：把框选区域复制成独立维度 battleroyale_game:&lt;mapId&gt;。
 *
 * <p>与 {@link EditWorldManager}（编辑副本，直接挂载 import 存档目录、不保存）不同：
 * <ul>
 *   <li>选区区块被复制进游戏维度自己的 region 目录（存档根 /battleroyale_game/&lt;mapId&gt;/region），
 *       由 {@link DimensionTypeMixin} 重定向存储目录实现；</li>
 *   <li>区域外是虚空：1.18.2 Yarn 没有 VoidChunkGenerator，这里用空层 FlatLevelSource
 *       （等价原版“Void”超平坦预设）作为区块生成器，未复制的区块自动全是空气；</li>
 *   <li>选区边界不再写屏障墙，改为每 tick 检查玩家：超出选区时静默传送回边界内最近位置；</li>
 *   <li>游戏维度正常落盘（不设置 savingDisabled），出生点设置会保存。</li>
 * </ul>
 *
 * <p><b>线程模型（本轮修复「进维度后客户端一直加载地形」的核心）</b>：
 * <ul>
 *   <li>重 IO（复制几百 MB 的 region 文件、复制几千个 MTR 文件、rebuild 删除旧数据）
 *       全部放到单线程后台执行器 {@link #IO_EXECUTOR}，绝不占服务端主线程——
 *       之前这些操作在 {@code enter} 命令调用栈里同步跑，主线程几十秒不 tick，
 *       区块发不出去，客户端的“加载地形”就永远完不成；</li>
 *   <li>IO 完成后通过 {@code server.execute} 回到主线程，才创建 {@link ServerLevel}
 *       （原版世界构造读写服务器内部状态，必须在主线程，实测通常 1~5 秒）；</li>
 *   <li>创建后给落点区块加 {@link #GAME_SPAWN_TICKET} 强制加载，
 *       并在 {@link #tick} 里等落点区块升到 FULL（最多 30 秒超时兜底）才传送玩家，
 *       保证传送瞬间服务端就能发出区块数据。</li>
 * </ul>
 */
public final class GameWorldManager {
    public static final String DIMENSION_NAMESPACE = "battleroyale_game";
    /** 游戏维度视距（区块数）。 */
    public static final int VIEW_DISTANCE = 12;
    /** 边界软约束余量（格）：玩家坐标允许在 [minX+MARGIN, maxX-MARGIN] 内。 */
    private static final int BOUNDARY_MARGIN = 1;
    /** 落地（出生点）区块加载票据：维度刚创建、玩家还没进时先把出生点区块加载出来。 */
    private static final TicketType<ChunkPos> GAME_SPAWN_TICKET = TicketType.create(
            "battleroyale_game_spawn", Comparator.comparingLong(ChunkPos::toLong));
    /** 出生点票据等级：与原版玩家票据同级，足以把区块推到 FULL。 */
    private static final int GAME_SPAWN_TICKET_LEVEL = 4;
    /** 等待出生点区块加载的最长时间，超时照样传送（比把玩家永远晾在原地好）。 */
    private static final long ENTER_WAIT_TIMEOUT_MS = 30_000L;

    private static final Map<String, ResourceKey<Level>> KEYS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Path> STORAGE_ROOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, MapEditSession.ReturnPoint> RETURNS = new ConcurrentHashMap<>();
    /** 正在后台生成的维度键，防止重复排队。 */
    private static final Set<ResourceKey<Level>> PREPARING = ConcurrentHashMap.newKeySet();
    /** 排队等待进入某维度的玩家（生成完成后按序传送）。 */
    private static final Map<ResourceKey<Level>, Set<UUID>> WAITERS = new ConcurrentHashMap<>();
    /** 这些 UUID 是「开局传送」进来的对局玩家：落地走出生岛/地图中心，不发管理员工具。 */
    private static final Set<UUID> MATCH_WAITERS = ConcurrentHashMap.newKeySet();
    /** 维度已创建、正在等出生点区块 FULL 的传送请求。 */
    private static final ConcurrentLinkedQueue<PendingEnter> PENDING_ENTERS = new ConcurrentLinkedQueue<>();
    /** [BR-WORLD-FIX] 正在卸载 / 关服清理中的游戏维度键：isInstanceAlive 据此判实例无效。 */
    private static final Set<ResourceKey<Level>> UNLOADING = ConcurrentHashMap.newKeySet();
    /** 区块复制 + MTR 复制 + 旧数据删除等重 IO 的后台线程（单线程，避免并发写同一维度目录）。 */
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battleroyale-game-world-io");
        t.setDaemon(true);
        return t;
    });
    private static volatile MinecraftServer currentServer;

    private GameWorldManager() {
    }

    // ==================================================================
    // 维度标识
    // ==================================================================

    public static ResourceLocation dimensionId(String mapId) {
        return new ResourceLocation(DIMENSION_NAMESPACE, MapManager.safeId(mapId).toLowerCase());
    }

    public static ResourceKey<Level> worldKey(String mapId) {
        return KEYS.computeIfAbsent(MapManager.safeId(mapId).toLowerCase(),
                id -> ResourceKey.create(Registries.DIMENSION, new ResourceLocation(DIMENSION_NAMESPACE, id)));
    }

    public static boolean isGameWorld(ResourceKey<Level> key) {
        return key != null && DIMENSION_NAMESPACE.equals(key.location().getNamespace());
    }

    /** 由玩家所在维度的键反查地图配置（维度路径是 safeId 小写）。 */
    public static GameMap mapForWorld(ResourceKey<Level> key) {
        if (!isGameWorld(key)) {
            return null;
        }
        String path = key.location().getPath();
        for (GameMap map : MapManager.INSTANCE.listMaps()) {
            if (MapManager.safeId(map.mapId).toLowerCase().equals(path)) {
                return map;
            }
        }
        return null;
    }

    /**
     * 从维度注册键还原地图表里记录的真实 mapId：
     * 剥离 {@code battleroyale_game:} 前缀得到维度路径（safeId 小写），
     * 再与每张地图的 safeId 松散匹配，返回 JSON 里原样的 mapId；找不到时返回原路径。
     */
    public static String resolveMapId(ResourceKey<Level> key) {
        if (!isGameWorld(key)) {
            return null;
        }
        String dimKey = key.location().toString();
        String path = dimKey.substring(dimKey.indexOf(':') + 1);
        for (GameMap map : MapManager.INSTANCE.listMaps()) {
            if (MapManager.safeId(map.mapId).toLowerCase().equals(path)) {
                return map.mapId;
            }
        }
        return path;
    }

    /** 供 DimensionTypeMixin 重定向存储目录；未注册的键返回 null（走原版逻辑）。 */
    public static Path storageRootFor(ResourceKey<Level> key) {
        return STORAGE_ROOTS.get(key);
    }

    /**
     * 服务器启动（createWorlds 完成时）把磁盘上已存在数据的游戏维度全部挂载进
     * {@code MinecraftServer.worlds}。
     *
     * <p>修复「Unknown respawn dimension battleroyale_game:xxx, defaulting to overworld」：
     * 原版 {@code PlayerManager.createPlayer} 读玩家存档里的 Dimension 标签后用
     * {@code server.getLevel(key)} 找维度；以前游戏维度只在玩家 enter 时才创建，
     * 重启后登录瞬间维度还没挂载，就报这条警告并把人丢回主世界（在维度里下线的人
     * 重生点也找不到维度）。启动时预挂载后，重生点、传送按 key 都能查到世界实例。
     */
    public static void registerSavedWorldsAtStartup(MinecraftServer server) {
        if (server == null) {
            return;
        }
        currentServer = server;
        for (GameMap map : MapManager.INSTANCE.listMaps()) {
            try {
                if (map.mapId == null || map.mapId.isBlank()) {
                    continue;
                }
                ResourceKey<Level> key = worldKey(map.mapId);
                // [BR-WORLD-FIX] 卸载中的键跳过：上一场对局的维度还在关服清理里，不能提前挂新实例。
                if (server.getLevel(key) != null || PREPARING.contains(key) || UNLOADING.contains(key)) {
                    continue;
                }
                Path root = dimensionRoot(server, key);
                if (!hasMcaFiles(root.resolve("region"))) {
                    continue; // 没生成过数据，保持懒创建：首次 enter 再后台复制
                }
                STORAGE_ROOTS.put(key, root);
                ServerLevel world = buildWorld(server, map, key, 0);
                BlockPos landing = landingPos(map, world);
                ChunkPos landingChunk = new ChunkPos(landing);
                world.getChunkSource().addRegionTicket(
                        GAME_SPAWN_TICKET, landingChunk, GAME_SPAWN_TICKET_LEVEL, landingChunk);
                BattleRoyaleMod.LOGGER.info("[GameWorld] 启动预挂载游戏维度 {}（重生点/传送按 key 可查）",
                        key.location());
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("启动预挂载游戏维度 {} 失败：{}", map.mapId, t.toString());
            }
        }
    }

    // ==================================================================
    // 进入 / 后台生成
    // ==================================================================

    /**
     * 管理员进入游戏维度。维度已存在时同步传送；不存在时把重 IO 排到后台线程，
     * 完成回主线程建世界，出生点区块加载到 FULL 后自动传送玩家。
     * 成功（含“已排队后台生成”）返回 null，失败返回错误文案。
     */
    public static String enter(ServerPlayer player, String mapId) {
        return enter(player, mapId, false);
    }

    /** 等待玩家进入报名地图的游戏维度，落点使用出生岛中心，不发管理员工具。 */
    public static String enterAsWaitingPlayer(ServerPlayer player, String mapId) {
        return enter(player, mapId, true);
    }

    /**
     * 进入维度通用流程。
     *
     * @param match true = 开局传送：落地走出生岛中心（未设置则地图中心），
     *              不记录返回点、不发管理员工具；false = 管理员进入。
     */
    private static String enter(ServerPlayer player, String mapId, boolean match) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return "服务器尚未就绪";
        }
        GameMap map = MapManager.INSTANCE.getMap(mapId);
        if (map == null) {
            return "地图不存在：" + mapId;
        }
        ResourceKey<Level> key = worldKey(map.mapId);
        ResourceKey<Level> fromKey = player.level().dimension();
        if (!match && !isGameWorld(fromKey)) {
            RETURNS.put(player.getUUID(), new MapEditSession.ReturnPoint(
                    fromKey, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        }
        ServerLevel existing = server.getLevel(key);
        // [BR-WORLD-FIX] 按 key 能查到实例 ≠ 实例可用：如果它还挂在"正在卸载 /
        // 关服清理 / 失去服务器引用"的半死状态，先强制走完整卸载流程清理旧实例，
        // 再按"维度不存在"的正常路径重建，绝不把玩家塞进半死世界（卡"加载地形中"的根源）。
        if (existing != null && !isInstanceAlive(existing, key)) {
            BattleRoyaleMod.LOGGER.warn("[GameWorld] 游戏维度 {} 的旧实例处于失效/卸载中状态，强制清理后重建",
                    key.location());
            unloadGameWorld(server, existing);
            existing = null;
        }
        if (existing != null) {
            // [BR-SPAWN] 1.20.1 等价写法（旧 1.18.2 为 world.setMobSpawnOptions(false,false)）：
            // 维度级刷怪开关仍藏在 ServerChunkCache 的 spawnFriendlies/spawnEnemies 字段上，
            // Mojang 把 setter 挪成了 ChunkSource#setSpawnSettings(animals, monsters)。
            existing.getChunkSource().setSpawnSettings(false, false);
            // 已经建好的维度：落点区块已 FULL 就立即传送；
            // 刚重启预挂载的世界区块还没加载，先挂票据排队等 FULL，避免客户端卡在“加载地形中”。
            BlockPos landing = match ? matchLanding(map, existing) : landingPos(map, existing);
            ChunkPos landingChunk = new ChunkPos(landing);
            net.minecraft.world.level.chunk.LevelChunk readyChunk = (net.minecraft.world.level.chunk.LevelChunk) existing.getChunkSource().getChunk(
                    landingChunk.x, landingChunk.z, ChunkStatus.FULL, false);
            if (readyChunk != null && readyChunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                if (match) {
                    doMatchTeleport(player, existing, map);
                } else {
                    doTeleport(player, existing, map);
                }
            } else {
                existing.getChunkSource().addRegionTicket(
                        GAME_SPAWN_TICKET, landingChunk, GAME_SPAWN_TICKET_LEVEL, landingChunk);
                PENDING_ENTERS.add(new PendingEnter(player.getUUID(), key, map, match, landing));
                player.displayClientMessage(BrText.of("游戏维度 " + mapId
                        + " 已在服务器里，正在加载落点区块，就绪后自动传送……"), false);
            }
            return null;
        }
        Set<UUID> waiters = WAITERS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        waiters.add(player.getUUID());
        if (match) {
            MATCH_WAITERS.add(player.getUUID());
        }
        if (PREPARING.contains(key)) {
            player.displayClientMessage(BrText.of(match
                    ? "对局地图 " + mapId + " 的游戏维度正在后台生成，完成后会自动把你传送进场。"
                    : "游戏维度 " + mapId + " 正在后台生成，完成后会自动传送你进去。"), false);
            return null;
        }
        player.displayClientMessage(BrText.of("游戏维度 " + mapId
                + " 开始生成：区块复制在后台进行（不会卡住游戏），完成后会自动传送你进去……"), false);
        prepareAsync(server, map, key);
        return null;
    }

    /**
     * 开局传送入口（GameManager 切到 PREPARING 时由主类订阅者调用）：
     * 按每位对局玩家等待队列里的地图，把他们送进对应的游戏维度，
     * 落地出生岛中心（spawnAreaSet=true，Y 取岛内地表顶部），未设置则地图中心。
     */
    public static void teleportRosterToGameWorld(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (String name : GameManager.INSTANCE.getRoster()) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) {
                continue; // 离线报名者：本轮静默跳过
            }
            String mapId = MapQueue.INSTANCE.waitingMapOf(player.getUUID());
            if (mapId == null) {
                player.displayClientMessage(BrText.of("你没在任何地图的等待队列里，无法传送进场；"
                        + "请用 /paimengmods br join <mapId> 加入地图后重新开局"), false);
                continue;
            }
            String error = enter(player, mapId, true);
            if (error != null) {
                player.displayClientMessage(BrText.of("传送进场失败：" + error), false);
            }
        }
    }

    /** 游戏维度落盘根目录：saves/&lt;当前存档&gt;/battleroyale_game/&lt;mapId&gt;。 */
    public static Path dimensionRoot(MinecraftServer server, ResourceKey<Level> key) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(DIMENSION_NAMESPACE)
                .resolve(key.location().getPath());
    }

    /** region 目录：saves/&lt;当前存档&gt;/battleroyale_game/&lt;mapId&gt;/region。 */
    public static Path regionDir(MinecraftServer server, ResourceKey<Level> key) {
        return dimensionRoot(server, key).resolve("region");
    }

    /** MTR 数据目录：saves/&lt;当前存档&gt;/mtr/battleroyale_game/&lt;mapId&gt;。 */
    public static Path mtrDir(MinecraftServer server, ResourceKey<Level> key) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("mtr")
                .resolve(DIMENSION_NAMESPACE)
                .resolve(key.location().getPath());
    }

    /**
     * 硬盘持久化检查（重启后不重复复制的核心）：
     * <ul>
     *   <li>region 目录存在且有 .mca 文件 → 上次已复制过，绝对跳过复制，直接挂载维度；</li>
     *   <li>MTR 目录存在且有文件 → 同样跳过复制；</li>
     *   <li>每次调用都真实 stat 文件系统，不缓存任何 boolean 状态。</li>
     * </ul>
     */
    public static boolean dataAlreadyOnDisk(MinecraftServer server, ResourceKey<Level> key) {
        Path region = regionDir(server, key).toAbsolutePath().normalize();
        boolean hasRegion = hasMcaFiles(region);
        BattleRoyaleMod.LOGGER.info("[GameWorld] 检查 region 路径：{}，存在：{}", region, hasRegion);
        if (hasRegion) {
            return true;
        }
        Path mtr = mtrDir(server, key).toAbsolutePath().normalize();
        boolean hasMtr = hasFiles(mtr);
        BattleRoyaleMod.LOGGER.info("[GameWorld] 检查 MTR 路径：{}，存在：{}", mtr, hasMtr);
        return hasMtr;
    }

    /** 目录（含一层 region 命名）里是否存在 .mca 区块文件。 */
    private static boolean hasMcaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".mca"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 后台线程执行重 IO（复制 region、复制 MTR），完成后回到主线程创建世界。 */
    private static void prepareAsync(MinecraftServer server, GameMap map, ResourceKey<Level> key) {
        PREPARING.add(key);
        Path root = dimensionRoot(server, key);
        STORAGE_ROOTS.put(key, root);
        BattleRoyaleMod.LOGGER.info("游戏维度加载开始：mapId={}", map.mapId);
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 创建游戏维度入口：BrCommand/GameWorldManager.enter → "
                        + "GameWorldManager.prepareAsync（本方法）→ MapManager.copySelectedChunks；"
                        + "参数：mapId={}，维度键={}，选区（已保存的 GameMap 字段）X {}~{}，Z {}~{}，"
                        + "源存档目录（编辑副本挂载的 import 存档）={}，目标 region 目录={}；"
                        + "流程顺序：后台线程先复制区块数据 → 再回主线程 buildWorld 挂载维度（先复制后建维度，复制只跑一次）",
                map.mapId, key.location(), map.minX, map.maxX, map.minZ, map.maxZ,
                map.sourceArchivePath, regionDir(server, key).toAbsolutePath());
        IO_EXECUTOR.execute(() -> {
            String error = null;
            int copied = 0;
            long copyMs = 0;
            boolean skippedCopy = false;
            int mtrFiles = 0;
            long mtrMs = 0;
            try {
                // 每次进入都按硬盘文件系统做持久化检查（不用内存状态缓存）：
                // 目标 region 目录已有 .mca 文件 = 上次已经复制过，绝对跳过复制直接挂载，
                // 否则退出存档重进会再复制一遍几百 MB 数据，严重卡顿。
                skippedCopy = dataAlreadyOnDisk(server, key);
                if (skippedCopy) {
                    BattleRoyaleMod.LOGGER.warn("[BR-COPY-DIAG] 目标游戏维度已有数据（region/MTR 已存在）→ "
                            + "跳过复制，不覆盖已有数据；要按新选区重复制请执行 manager game rebuild {}", map.mapId);
                } else {
                    long t0 = System.currentTimeMillis();
                    MapManager.CopyOutcome outcome =
                            MapManager.INSTANCE.copySelectedChunks(map, regionDir(server, key));
                    copyMs = System.currentTimeMillis() - t0;
                    if (outcome.error != null) {
                        // 复制不到任何区块就中止：继续建维度只会得到一个全虚空的世界。
                        error = outcome.error;
                    } else if (outcome.skipped) {
                        // copySelectedChunks 内部二次守卫发现目标已存在 → 跳过复制，按已有数据挂载。
                        skippedCopy = true;
                    } else {
                        copied = outcome.copied;
                    }
                }
                if (error == null) {
                    long t0 = System.currentTimeMillis();
                    mtrFiles = migrateMtr(server, map, key);
                    mtrMs = System.currentTimeMillis() - t0;
                }
            } catch (Throwable t) {
                error = "游戏维度后台生成异常：" + t;
                BattleRoyaleMod.LOGGER.error("游戏维度 {} 后台生成异常", key.location(), t);
            }
            final String fError = error;
            final int fCopied = copied;
            final long fCopyMs = copyMs;
            final boolean fSkipped = skippedCopy;
            final int fMtrFiles = mtrFiles;
            final long fMtrMs = mtrMs;
            server.execute(() -> finishPrepare(server, map, key, fError, fCopied, fCopyMs, fSkipped, fMtrFiles, fMtrMs));
        });
    }

    /** 主线程回调：IO 完成后创建 ServerLevel、挂出生点票据、把排队玩家转入就绪等待队列。 */
    private static void finishPrepare(MinecraftServer server, GameMap map, ResourceKey<Level> key,
                                      String error, int copied, long copyMs, boolean skippedCopy,
                                      int mtrFiles, long mtrMs) {
        PREPARING.remove(key);
        Set<UUID> waiters = WAITERS.remove(key);
        if (waiters == null) {
            waiters = Set.of();
        }
        if (!server.isRunning()) {
            STORAGE_ROOTS.remove(key);
            BattleRoyaleMod.LOGGER.warn("服务器已停止，放弃游戏维度 {} 的创建（区块数据已复制在磁盘上，下次进入自动复用）",
                    key.location());
            return;
        }
        if (error != null) {
            STORAGE_ROOTS.remove(key);
            BattleRoyaleMod.LOGGER.error("游戏维度生成失败：mapId={}，原因：{}", map.mapId, error);
            notifyPlayers(server, waiters, "游戏维度 " + map.mapId + " 生成失败：" + error);
            return;
        }
        if (skippedCopy) {
            BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 游戏维度区块复制完成：复用已有数据 0 个区块（未覆盖），耗时 0ms，mapId={}", map.mapId);
        } else {
            BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 游戏维度区块复制完成：{} 个区块，耗时 {}ms，mapId={}",
                    copied, copyMs, map.mapId);
        }
        if (mtrFiles > 0) {
            BattleRoyaleMod.LOGGER.info("游戏维度 MTR 数据复制完成：{} 个文件，耗时 {}ms，mapId={}",
                    mtrFiles, mtrMs, map.mapId);
        }
        ServerLevel world;
        long buildStart = System.currentTimeMillis();
        try {
            // 原版世界构造必须在服务端主线程（内部读写服务器共享状态），
            // 但它只做 region 索引扫描与实体分块装载，通常在数秒内完成。
            world = buildWorld(server, map, key, copied);
        } catch (Exception e) {
            STORAGE_ROOTS.remove(key);
            BattleRoyaleMod.LOGGER.error("游戏维度 {} 创建失败", key.location(), e);
            notifyPlayers(server, waiters, "创建游戏维度失败：" + e.getMessage());
            return;
        }
        BattleRoyaleMod.LOGGER.info("游戏维度世界实例创建完成（服务端主线程）：耗时 {}ms，mapId={}",
                System.currentTimeMillis() - buildStart, map.mapId);
        // 出生点区块预加载：加票据让主线程 tick 里把它推到 FULL，就绪后才传送玩家。
        BlockPos adminLanding = landingPos(map, world);
        try {
            world.setDefaultSpawnPos(adminLanding, 0f);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.debug("设置游戏维度出生点失败（不影响进入）: {}", t.toString());
        }
        ChunkPos adminChunk = new ChunkPos(adminLanding);
        world.getChunkSource().addRegionTicket(GAME_SPAWN_TICKET, adminChunk, GAME_SPAWN_TICKET_LEVEL, adminChunk);
        BattleRoyaleMod.LOGGER.info("游戏维度出生点区块已排队加载：{} {}，mapId={}（升到 FULL 后允许玩家进入，最长等 {} 秒）",
                adminChunk.x, adminChunk.z, map.mapId, ENTER_WAIT_TIMEOUT_MS / 1000);
        for (UUID playerId : waiters) {
            boolean match = MATCH_WAITERS.remove(playerId);
            BlockPos landing = match ? matchLanding(map, world) : adminLanding;
            ChunkPos landingChunk = new ChunkPos(landing);
            if (!landingChunk.equals(adminChunk)) {
                world.getChunkSource().addRegionTicket(
                        GAME_SPAWN_TICKET, landingChunk, GAME_SPAWN_TICKET_LEVEL, landingChunk);
            }
            PENDING_ENTERS.add(new PendingEnter(playerId, key, map, match, landing));
        }
    }

    /** 主线程创建 ServerLevel 并注册到服务器（不含任何磁盘重 IO）。 */
    private static ServerLevel buildWorld(MinecraftServer server, GameMap map, ResourceKey<Level> key, int copied) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            throw new IllegalStateException("主世界尚未创建");
        }
        Holder<DimensionType> type = overworld.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD);
        ChunkGenerator generator = createVoidGenerator(server);
        ServerLevelData properties = new ReadOnlyLevelData(
                server.getWorldData().overworldData());
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        ServerLevel world = new ServerLevel(
                server,
                access.battleroyale$getWorkerExecutor(),
                access.battleroyale$getSession(),
                properties,
                key,
                new LevelStem(type, generator),
                SilentWorldGenListener.INSTANCE,
                false,
                overworld.getSeed(),
                ImmutableList.of(),
                false,
                new net.minecraft.world.RandomSequences(0L));
        // [BR-SPAWN] 按维度关闭自然刷怪（替代 1.18.2 的 setMobSpawnOptions(false,false)；
        // 只关我们自己的维度，不碰全局 doMobSpawning 规则，主世界不受影响）。
        world.getChunkSource().setSpawnSettings(false, false);
        world.getChunkSource().setViewDistance(VIEW_DISTANCE);
        world.getChunkSource().setSimulationDistance(VIEW_DISTANCE);
        access.battleroyale$getWorlds().put(key, world);
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 游戏维度 {} 已生成：按选区逐区块复制 {} 个区块，选区 X {}~{}，Z {}~{}，数据目录 {}",
                key.location(), copied, map.minX, map.maxX, map.minZ, map.maxZ, STORAGE_ROOTS.get(key));
        return world;
    }

    /**
     * 虚空生成器：1.18.2 Yarn 没有 VoidChunkGenerator（它是高版本才引入的类名），
     * 这里用 FlatLevelSource + 空 layers，效果等同原版“Void”超平坦预设——
     * 没有复制数据的区块自动生成纯空气，即虚空。
     */
    private static ChunkGenerator createVoidGenerator(MinecraftServer server) {
        Holder<net.minecraft.world.level.biome.Biome> biome = server.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        FlatLevelGeneratorSettings config = new FlatLevelGeneratorSettings(
                java.util.Optional.empty(), biome, List.of());
        return new FlatLevelSource(config);
    }

    /** 复制 import 存档里的 MTR 数据到 存档/mtr/battleroyale_game/&lt;mapId&gt;；返回复制的文件数。 */
    private static int migrateMtr(MinecraftServer server, GameMap map, ResourceKey<Level> key) {
        if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) {
            return 0;
        }
        Path source = Path.of(map.sourceArchivePath).resolve("mtr").resolve("minecraft").resolve("overworld");
        if (!hasFiles(source)) {
            return 0;
        }
        try {
            Path target = mtrDir(server, key);
            if (hasFiles(target)) {
                BattleRoyaleMod.LOGGER.info("[GameWorld] MTR 目录已有数据，跳过复制：{}",
                        target.toAbsolutePath().normalize());
                return 0;
            }
            int count = copyTree(source, target);
            BattleRoyaleMod.LOGGER.info("游戏维度 {} MTR 数据迁移完成，共 {} 个文件", key.location(), count);
            return count;
        } catch (IOException | RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("游戏维度 MTR 数据迁移失败，跳过并继续", e);
            return 0;
        }
    }

    // ==================================================================
    // 落地坐标 / 传送
    // ==================================================================

    /** 玩家落地坐标：优先地图出生点，否则选区中心（y 保底高于世界底部 4 格）。 */
    private static BlockPos landingPos(GameMap map, ServerLevel world) {
        if (map.spawnSet) {
            return new BlockPos((int) map.spawnX, (int) map.spawnY, (int) map.spawnZ);
        }
        int x = Math.floorDiv(map.minX + map.maxX, 2);
        int z = Math.floorDiv(map.minZ + map.maxZ, 2);
        int y = Math.max(64, world.getMinBuildHeight() + 4);
        return new BlockPos(x, y, z);
    }

    private static void doTeleport(ServerPlayer player, ServerLevel world, GameMap map) {
        teleportTo(player, world, map, false);
    }

    /** 开局传送落地：出生岛已设置取岛中心顶部，否则取游戏地图（选区）中心。 */
    private static void doMatchTeleport(ServerPlayer player, ServerLevel world, GameMap map) {
        teleportTo(player, world, map, true);
    }

    /**
     * 把玩家传送到游戏维度。
     *
     * @param match true = 开局传送：优先出生岛中心（Y 取岛内地表顶部），未设置则地图中心；
     *              不发管理员工具。false = 管理员进入：走已保存的出生点或选区中心，并补发工具。
     */
    private static void teleportTo(ServerPlayer player, ServerLevel world, GameMap map, boolean match) {
        // [BR-WORLD-FIX] doTeleport / doMatchTeleport 的唯一出口：传送前必须确认
        // 目标 ServerLevel 仍有效（非空、仍挂在服务器、不在卸载中），无效则中止并提示。
        if (player == null || world == null || !isInstanceAlive(world, world.dimension())) {
            if (player != null) {
                player.displayClientMessage(BrText.of("游戏维度实例已失效，传送中止，请重新执行 enter。"), false);
            }
            BattleRoyaleMod.LOGGER.warn("[GameWorld] 传送被拒绝：目标维度实例无效（{}）",
                    world == null ? "null" : world.dimension().location());
            return;
        }
        double x;
        double y;
        double z;
        if (match) {
            BlockPos target = matchLanding(map, world);
            x = target.getX() + 0.5;
            y = target.getY();
            z = target.getZ() + 0.5;
        } else if (map.spawnSet) {
            x = map.spawnX;
            y = map.spawnY;
            z = map.spawnZ;
        } else {
            BlockPos center = landingPos(map, world);
            x = center.getX() + 0.5;
            y = center.getY();
            z = center.getZ() + 0.5;
        }
        try {
            player.teleportTo(world, x, y, z, player.getYRot(), player.getXRot());
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("传送到游戏维度失败: {}", e.toString());
            player.displayClientMessage(BrText.of("进入游戏维度失败：" + e.getMessage()), false);
            return;
        }
        // 把重生点也设进游戏维度（维度已挂载进 worlds，死亡/传送按 key 查得到）：
        // 否则玩家在维度里死亡会被原版丢回主世界重生。
        try {
            player.setRespawnPosition(world.dimension(), new BlockPos((int) x, (int) y, (int) z),
                    player.getYRot(), false, true);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.debug("设置游戏维度重生点失败（不影响进入）: {}", t.toString());
        }
        // 进入时同步一次物资点（HUD / 全屏地图按维度过滤显示）。
        LootPointsSync.sendTo(player, map);
        // 自动预加载：玩家真正落进游戏维度时检查底图，缺失就丢后台生成（绝不占主线程）。
        ensureBaseImagePreloaded(world, map);
        if (!match) {
            // 管理员进入游戏维度：发放物资点工具和出生岛工具。
            giveAdminTools(player);
        }
    }

    /**
     * [BR-MAP-FIX2] 地图底图自动预加载：br join / 开局传送 / 管理员进图落到游戏维度时，
     * 若该地图还没有 {@link MapBaseImage}（管理员没敲过 preview），就在后台线程触发一次
     * {@link MapColorGenerator#generateMapImage(GameMap)}，并全服聊天栏提示。
     *
     * <p>性能保护（多人同时 join 也只跑一个任务）：
     * <ul>
     *   <li>baseImage 已存在 → 直接跳过；</li>
     *   <li>{@code RUNNING} 集合按 mapId 去重：第二个进图者拿不到标记、只收到
     *       「正在生成中」的返回值，这里静默忽略，不会重复提交、不会重复广播；</li>
     *   <li>生成器本身是单线程执行器，扫描全部在后台线程，主线程只花几微秒做提交。</li>
     * </ul>
     * 生成失败（底图仍为空）时会在下一名玩家进图时自动重试。
     */
    private static void ensureBaseImagePreloaded(ServerLevel world, GameMap map) {
        try {
            if (map == null || map.mapId == null || map.baseImage != null) {
                return;
            }
            if (MapColorGenerator.isGenerating(map.mapId)) {
                return;
            }
            String error = MapColorGenerator.generateMapImage(map);
            if (error == null) {
                BrText.broadcast(world.getServer(), "正在后台预生成地图底图，请稍候...");
                BattleRoyaleMod.LOGGER.info("[BR-MAP-FIX2] 地图 {} 没有底图数据，玩家进入游戏维度触发后台自动预生成",
                        map.mapId);
            } else {
                BattleRoyaleMod.LOGGER.info("[BR-MAP-FIX2] 地图 {} 自动预生成未提交：{}", map.mapId, error);
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-MAP-FIX2] 自动预生成底图异常 mapId={}: {}",
                    map == null ? "?" : map.mapId, t.toString());
        }
    }

    /** 给管理员补发 loot_tool 与 spawn_tool（背包已有则不重复给）。 */
    private static void giveAdminTools(ServerPlayer player) {
        giveIfMissing(player, new ItemStack(ModItems.LOOT_TOOL));
        giveIfMissing(player, new ItemStack(ModItems.SPAWN_TOOL));
        player.displayClientMessage(BrText.of("已发放物资点工具和出生岛工具"), false);
    }

    private static void giveIfMissing(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().contains(stack)) {
            player.getInventory().add(stack);
        }
    }

    /** 开局传送落点：spawnAreaSet 时取出生岛水平中心 + 岛内地表顶部；否则取选区中心。 */
    public static BlockPos matchLanding(GameMap map, ServerLevel world) {
        int cx;
        int cz;
        if (map.spawnAreaSet) {
            cx = Math.floorDiv(Math.min(map.spawnMinX, map.spawnMaxX)
                    + Math.max(map.spawnMinX, map.spawnMaxX), 2);
            cz = Math.floorDiv(Math.min(map.spawnMinZ, map.spawnMaxZ)
                    + Math.max(map.spawnMinZ, map.spawnMaxZ), 2);
        } else {
            cx = Math.floorDiv(Math.min(map.minX, map.maxX) + Math.max(map.minX, map.maxX), 2);
            cz = Math.floorDiv(Math.min(map.minZ, map.maxZ) + Math.max(map.minZ, map.maxZ), 2);
        }
        int y;
        try {
            y = world.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
        } catch (Throwable t) {
            y = Math.max(64, world.getMinBuildHeight() + 4);
        }
        if (y < world.getMinBuildHeight() + 1) {
            y = Math.max(64, world.getMinBuildHeight() + 4);
        }
        return new BlockPos(cx, y, cz);
    }

    /** 离开游戏维度时把重生点也送回目标世界，避免玩家在主世界死亡还被传回游戏维度重生。 */
    private static void resetSpawnToOverworld(ServerPlayer player, ServerLevel target) {
        try {
            player.setRespawnPosition(target.dimension(), target.getSharedSpawnPos(),
                    target.getSharedSpawnAngle(), false, true);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.debug("重置玩家重生点失败: {}", t.toString());
        }
    }

    /**
     * 退出对局（/paimengmods br leave）时的排队清理：把该玩家从所有
     * "等维度生成 / 等落点区块就绪后自动传送" 的记录里拿掉，
     * 避免他回大厅之后维度恰好就绪、又被拽回游戏维度。只删记录，不做传送。
     */
    public static void cancelPendingEnter(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PENDING_ENTERS.removeIf(pending -> playerId.equals(pending.playerId));
        MATCH_WAITERS.remove(playerId);
        for (Set<UUID> waiters : WAITERS.values()) {
            waiters.remove(playerId);
        }
    }

    /**
     * [BR-WORLD-FIX] 游戏维度实例有效性判定：实例非空、仍挂在某个服务器上、
     * 且没有处于我们主动标记的卸载 / 关服清理流程中。
     * 退出重进后如果 worlds 映射里残留半卸载状态的旧实例，按 key 查"存在"但不可用，
     * 玩家被原版直接放进去就会卡在"加载地形中"——所有传送 / 登录路径都先过这道检查。
     */
    private static boolean isInstanceAlive(ServerLevel world, ResourceKey<Level> key) {
        return world != null && world.getServer() != null && !UNLOADING.contains(key);
    }

    /**
     * [BR-WORLD-FIX] 强制卸载一个失效 / 半死的游戏维度实例（流程对齐
     * EditWorldManager.unloadEditWorld：撤票 → 落盘 → 从 server.levels 移除 → 关闭世界），
     * 供 enter() 在检测到旧实例无效时调用；调用方随后走正常重建流程。
     */
    private static void unloadGameWorld(MinecraftServer server, ServerLevel world) {
        ResourceKey<Level> key = world.dimension();
        UNLOADING.add(key);
        try {
            try {
                world.getChunkSource().removeTicketsOnClosing();
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("强制卸载游戏维度 {} 时撤除区块票据失败", key.location(), t);
            }
            try {
                world.getChunkSource().save(true);
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("强制卸载游戏维度 {} 时保存区块失败", key.location(), t);
            }
            if (server != null) {
                ((MinecraftServerAccessor) server).battleroyale$getWorlds().remove(key);
            }
            STORAGE_ROOTS.remove(key);
            try {
                world.close();
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.debug("强制卸载游戏维度 {} 时关闭世界：{}", key.location(), t.toString());
            }
        } finally {
            UNLOADING.remove(key);
        }
    }

    /** 离开游戏维度，返回进入前的位置；成功返回 null，失败返回错误文案。 */
    public static String leave(ServerPlayer player) {
        ServerLevel current = (ServerLevel) player.level();
        if (!isGameWorld(current.dimension())) {
            return "当前不在游戏维度里";
        }
        MapEditSession.ReturnPoint point = RETURNS.remove(player.getUUID());
        MinecraftServer server = player.getServer();
        ServerLevel overworld = server == null ? null : server.overworld();
        try {
            if (point != null && server != null) {
                ServerLevel target = server.getLevel(point.world());
                if (target == null) {
                    target = overworld;
                }
                if (target != null) {
                    player.teleportTo(target, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
                    resetSpawnToOverworld(player, target);
                    return null;
                }
            }
            if (overworld != null) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                resetSpawnToOverworld(player, overworld);
                return null;
            }
            return "主世界不可用，暂时无法离开";
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("离开游戏维度失败", t);
            return "离开游戏维度失败：" + t.getMessage();
        }
    }

    // ==================================================================
    // 重建
    // ==================================================================

    /**
     * 删除游戏维度的落盘数据并重新复制源存档区块（用于修复「复制了 0 个文件、
     * 维度里全是虚空」的旧数据）。删除与复制都在后台线程执行，不卡主线程；
     * 世界当前还在服务器里加载着时不允许删，需要先退出存档（单人）或重启服务器。
     * 返回 null = 已受理（进度走玩家消息）；返回非空 = 前置校验失败。
     */
    public static String rebuild(ServerPlayer player, String mapId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return "服务器尚未就绪";
        }
        GameMap map = MapManager.INSTANCE.getMap(mapId);
        if (map == null) {
            return "地图不存在：" + mapId;
        }
        ResourceKey<Level> key = worldKey(mapId);
        if (server.getLevel(key) != null) {
            return "游戏维度还在加载中，删不掉旧数据。请先 manager game leave，退出存档重进后再执行 rebuild。";
        }
        if (PREPARING.contains(key)) {
            return "该地图的游戏维度正在后台生成，请等它完成后再 rebuild。";
        }
        Path root = dimensionRoot(server, key);
        STORAGE_ROOTS.remove(key);
        UUID playerId = player.getUUID();
        if (!Files.exists(root)) {
            // 没有旧数据，直接走正常进入流程（后台生成）。
            return enter(player, mapId);
        }
        player.displayClientMessage(BrText.of("正在后台删除游戏维度旧数据并重新复制区块（不会卡住游戏）……"), false);
        IO_EXECUTOR.execute(() -> {
            String error = null;
            try {
                deleteTree(root);
                BattleRoyaleMod.LOGGER.info("已删除游戏维度旧数据：{}", root);
            } catch (IOException e) {
                error = "删除旧维度数据失败：" + e;
            }
            final String fError = error;
            server.execute(() -> {
                if (!server.isRunning()) {
                    return;
                }
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p == null) {
                    return;
                }
                if (fError != null) {
                    p.displayClientMessage(BrText.of(fError), false);
                    return;
                }
                String err = enter(p, mapId);
                if (err != null) {
                    p.displayClientMessage(BrText.of("重建后进入失败：" + err), false);
                }
            });
        });
        return null;
    }

    // ==================================================================
    // 出生点
    // ==================================================================

    /** 把玩家当前所在位置设为所在游戏维度的出生点并写回 JSON；成功返回 null。 */
    public static String setSpawnHere(ServerPlayer player) {
        GameMap map = mapForWorld(player.level().dimension());
        if (map == null) {
            return "只能在游戏维度里设置出生点，先执行 /paimengmods manager game enter <mapId>";
        }
        map.spawnX = player.getX();
        map.spawnY = player.getY();
        map.spawnZ = player.getZ();
        map.spawnSet = true;
        MapManager.INSTANCE.saveMap(map);
        try {
            ((net.minecraft.server.level.ServerLevel) player.level()).setDefaultSpawnPos(player.blockPosition(), player.getYRot());
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.debug("设置维度出生点失败（不影响地图 JSON 保存）: {}", t.toString());
        }
        BattleRoyaleMod.LOGGER.info("地图 {} 出生点已保存：({}, {}, {})",
                map.mapId, map.spawnX, map.spawnY, map.spawnZ);
        return null;
    }

    // ==================================================================
    // 主线程 tick：就绪检查 + 边界软约束
    // ==================================================================

    /** 每 tick 由 MinecraftServerMixin 在主线程驱动：先进入就绪传送，再做选区边界检查，最后画光柱。 */
    public static void tick(MinecraftServer server) {
        currentServer = server;
        tickPendingEnters(server);
        tickBoundary(server);
        tickMarkers(server);
    }

    /** 给游戏维度里手持出生岛/物资点工具的管理员画 END_ROD 光柱。 */
    private static void tickMarkers(MinecraftServer server) {
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!isGameWorld(key)) {
                continue;
            }
            GameMap map = mapForWorld(key);
            if (map == null) {
                continue;
            }
            for (ServerPlayer player : world.getPlayers(p -> true)) {
                try {
                    MapEditMarker.tickGame(player, world, map);
                } catch (Throwable t) {
                    BattleRoyaleMod.LOGGER.debug("游戏维度光柱标记失败: {}", t.toString());
                }
            }
        }
    }

    /** 等出生点区块升到 FULL（或超时兜底）后才把排队玩家传送进维度。 */
    private static void tickPendingEnters(MinecraftServer server) {
        int batch = PENDING_ENTERS.size();
        for (int i = 0; i < batch; i++) {
            PendingEnter pending = PENDING_ENTERS.poll();
            if (pending == null) {
                return;
            }
            ServerLevel world = server.getLevel(pending.key);
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
            // [BR-WORLD-FIX] 等待期间世界实例可能已被关服/重建流程卸载：按有效性判定，
            // 半死实例同样视为丢失，取消传送并提示，不等区块、不传送。
            if (!isInstanceAlive(world, pending.key)) {
                if (player != null) {
                    player.displayClientMessage(BrText.of("游戏维度实例已丢失，传送取消，请重新执行 enter。"), false);
                }
                continue;
            }
            if (player == null || !player.isAlive()) {
                continue; // 玩家已退出/死亡，静默放弃
            }
            if (player.level().dimension().equals(pending.key)) {
                continue; // 期间已通过别的途径进来了
            }
            BlockPos landing = pending.landing;
            ChunkPos chunkPos = new ChunkPos(landing);
            net.minecraft.world.level.chunk.ChunkAccess chunk = world.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
            boolean ready = chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FULL);
            boolean timeout = System.currentTimeMillis() >= pending.deadlineMs;
            if (!ready && !timeout) {
                PENDING_ENTERS.add(pending); // 未就绪：放回队尾，下 tick 再查
                continue;
            }
            try {
                world.getChunkSource().removeRegionTicket(
                        GAME_SPAWN_TICKET, chunkPos, GAME_SPAWN_TICKET_LEVEL, chunkPos);
            } catch (Throwable ignored) {
            }
            if (!ready) {
                BattleRoyaleMod.LOGGER.warn("游戏维度出生点区块等待超时（{} 秒），仍然传送玩家：mapId={}，区块 {} {}",
                        ENTER_WAIT_TIMEOUT_MS / 1000, pending.map.mapId, chunkPos.x, chunkPos.z);
            }
            BattleRoyaleMod.LOGGER.info("游戏维度加载完成，允许玩家进入：mapId={}，玩家={}，区块 {} {}，状态={}，等待 {}ms",
                    pending.map.mapId, player.getName().getString(), chunkPos.x, chunkPos.z,
                    chunk == null ? "未加载" : chunk.getStatus().toString(),
                    System.currentTimeMillis() - pending.createdAtMs);
            if (pending.match) {
                doMatchTeleport(player, world, pending.map);
            } else {
                doTeleport(player, world, pending.map);
            }
        }
    }

    // ==================================================================
    // 选区边界软约束（每 tick 检查，超出即静默传送回边界内）
    // ==================================================================

    /**
     * 每 tick 遍历所有游戏维度里的玩家：
     * <ul>
     *   <li>坐标在选区内（允许 [minX+1, maxX-1] × [minZ+1, maxZ-1]）——跳过；</li>
     *   <li>超出选区——用 Mth.clamp 求出边界内最近位置并传送回去，
     *       Y 坐标保持不变（不 clamp Y，避免玩家被卡进地里）；</li>
     *   <li>全程静默，不给玩家任何提示消息。</li>
     * </ul>
     */
    private static void tickBoundary(MinecraftServer server) {
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!isGameWorld(key)) {
                continue;
            }
            List<ServerPlayer> players = world.getPlayers(p -> true);
            if (players.isEmpty()) {
                continue;
            }
            GameMap map = mapForWorld(key);
            if (map == null) {
                continue;
            }
            int minX = Math.min(map.minX, map.maxX) + BOUNDARY_MARGIN;
            int maxX = Math.max(map.minX, map.maxX) - BOUNDARY_MARGIN;
            int minZ = Math.min(map.minZ, map.maxZ) + BOUNDARY_MARGIN;
            int maxZ = Math.max(map.minZ, map.maxZ) - BOUNDARY_MARGIN;
            if (minX > maxX || minZ > maxZ) {
                continue; // 选区过窄（宽/高不足 3 格），不做约束
            }
            for (ServerPlayer player : players) {
                // [BR-PLANE-BOUNDS] 航线阶段被坐标锁定在飞机上的玩家放行：
                // 航线端点现在外延到选区外（PUBG 式进场/离场），每 tick 往回拽会
                // 和 FlightManager 的坐标锁定互相拉扯，导致机上玩家来回瞬移。
                if (FlightManager.INSTANCE.isPlaneLocked(player.getUUID())) {
                    continue;
                }
                double x = player.getX();
                double z = player.getZ();
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    continue; // 在选区内：跳过
                }
                double clampedX = Mth.clamp(x, minX, maxX);
                double clampedZ = Mth.clamp(z, minZ, maxZ);
                try {
                    player.teleportTo(world, clampedX, player.getY(), clampedZ,
                            player.getYRot(), player.getXRot());
                } catch (Throwable t) {
                    BattleRoyaleMod.LOGGER.warn("把玩家 {} 推回选区边界失败：{}",
                            player.getName().getString(), t.toString());
                }
            }
        }
    }

    /** 等待出生点区块就绪后传送的玩家。 */
    private static final class PendingEnter {
        final UUID playerId;
        final ResourceKey<Level> key;
        final GameMap map;
        /** true = 开局传送玩家：落地出生岛/地图中心，不发管理员工具。 */
        final boolean match;
        /** 落点区块判定用的水平位置（Y 在传送时按当时已加载的区块重算）。 */
        final BlockPos landing;
        final long createdAtMs = System.currentTimeMillis();
        final long deadlineMs = createdAtMs + ENTER_WAIT_TIMEOUT_MS;

        PendingEnter(UUID playerId, ResourceKey<Level> key, GameMap map, boolean match, BlockPos landing) {
            this.playerId = playerId;
            this.key = key;
            this.map = map;
            this.match = match;
            this.landing = landing;
        }
    }

    // ==================================================================
    // 关服清理
    // ==================================================================

    /**
     * 服务器关闭：把游戏维度里的玩家送回主世界、撤除全部区块票据、保存并关闭世界实例，
     * 最后清空所有静态集合。
     *
     * <p>[BR-WORLD-FIX] 退出重进"主世界错载游戏维度区块 + 卡加载地形"的防线：
     * <ol>
     *   <li>先把所有游戏维度键标进 {@code UNLOADING}：此后任何 enter / 传送 / 登录校验
     *       都会把这里的实例判为无效，不可能再把玩家塞进正在关闭的世界；</li>
     *   <li>玩家两遍撤空（第二遍兜住第一遍传送失败的漏网者），保证玩家数据随后落盘时
     *       Dimension 记的是主世界；</li>
     *   <li>{@code removeTicketsOnClosing()} 撤掉 GAME_SPAWN_TICKET 等全部票据，
     *       再 {@code save(true)} 强制刷盘，然后才从 {@code server.levels} 映射移除，
     *       最后 {@code world.close()} 关掉区块 IO ——顺序不能反，close 会停掉存储句柄；</li>
     *   <li>finally 里清空包括 STORAGE_ROOTS / KEYS 在内的全部静态集合：
     *       DimensionTypeMixin 的存储目录重定向按这些表查，残留旧存档路径会污染下一个会话。</li>
     * </ol>
     */
    public static void onServerStopping() {
        MinecraftServer server = currentServer;
        try {
            if (server != null) {
                MinecraftServerAccessor access = (MinecraftServerAccessor) server;
                Map<ResourceKey<Level>, ServerLevel> worlds = access.battleroyale$getWorlds();
                List<ResourceKey<Level>> gameKeys = new ArrayList<>();
                for (ResourceKey<Level> key : new HashSet<>(worlds.keySet())) {
                    if (isGameWorld(key)) {
                        gameKeys.add(key);
                    }
                }
                UNLOADING.addAll(gameKeys);
                ServerLevel overworld = server.overworld();
                for (ResourceKey<Level> key : gameKeys) {
                    ServerLevel world = worlds.get(key);
                    if (world == null) {
                        continue;
                    }
                    if (overworld != null) {
                        BlockPos spawn = overworld.getSharedSpawnPos();
                        for (int pass = 0; pass < 2; pass++) {
                            List<ServerPlayer> inside = world.getPlayers(p -> true);
                            if (inside.isEmpty()) {
                                break;
                            }
                            for (ServerPlayer passenger : new ArrayList<>(inside)) {
                                try {
                                    passenger.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(),
                                            spawn.getZ() + 0.5, passenger.getYRot(), passenger.getXRot());
                                    // 玩家数据随后落盘：Dimension/重生点都记为主世界，
                                    // 下次开服登录不会再报 Unknown respawn dimension。
                                    resetSpawnToOverworld(passenger, overworld);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        int left = world.getPlayers(p -> true).size();
                        if (left > 0) {
                            BattleRoyaleMod.LOGGER.warn("游戏维度 {} 关服撤人后仍残留 {} 名玩家（保存其数据时可能被维度校验兜底）",
                                    key.location(), left);
                        }
                    }
                    try {
                        world.getChunkSource().removeTicketsOnClosing();
                    } catch (Throwable t) {
                        BattleRoyaleMod.LOGGER.warn("关服时撤除游戏维度 {} 的区块票据失败", key.location(), t);
                    }
                    try {
                        world.getChunkSource().save(true);
                    } catch (Throwable t) {
                        BattleRoyaleMod.LOGGER.warn("关服时保存游戏维度 {} 失败", key.location(), t);
                    }
                    // save(true) 完成后才移除实例：先落盘、后失联（数据一致性优先）。
                    worlds.remove(key);
                    try {
                        world.close();
                    } catch (Throwable t) {
                        BattleRoyaleMod.LOGGER.debug("关服时关闭游戏维度 {}：{}", key.location(), t.toString());
                    }
                }
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("关服时清理游戏维度失败", t);
        } finally {
            // [BR-WORLD-FIX] 全部静态集合彻底清空：任何一条残留都可能把上一个会话的
            // 强引用/路径带进下一个会话（单人档"退出到标题再进"是同一 JVM 换 server 实例，
            // static 是唯一跨会话的东西）。
            RETURNS.clear();
            PENDING_ENTERS.clear();
            PREPARING.clear();
            WAITERS.clear();
            MATCH_WAITERS.clear();
            MapEditSession.SPAWN_SELECTIONS.clear();
            UNLOADING.clear();
            STORAGE_ROOTS.clear();
            KEYS.clear();
            currentServer = null;
        }
    }

    /**
     * [BR-WORLD-FIX] 登录维度校验（由 {@code PlayerManagerMixin} 在
     * {@code PlayerList.placeNewPlayer} 最开始调用）。
     *
     * <p>原版在创建 ServerPlayer 时就按存档里的 Dimension 键 {@code server.getLevel(key)}
     * 绑定落点世界：键查不到时原版会打警告并回主世界，但**查到一个半死实例**
     * （卸载中 / 失去服务器引用）时它照用不误——玩家被塞进正在关闭的世界，
     * 客户端表现为主世界界面却一直收不到有效区块、卡在"加载地形中"。
     * 这里在玩家真正入场前做最后校验：
     * <ul>
     *   <li>当前绑定维度是 battleroyale_game:/battleroyale_edit: 且实例无效
     *       → 强制改绑主世界出生点；</li>
     *   <li>重生点指向同类失效维度 → 重生点一并重置为主世界出生点，
     *       避免死亡后又被原版丢回死维度。</li>
     * </ul>
     */
    public static void validateLoginPlayer(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return;
        }
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                return;
            }
            Level bound = player.level();
            ResourceKey<Level> boundKey = bound == null ? null : bound.dimension();
            if (boundKey != null && (isGameWorld(boundKey) || EditWorldManager.isEditWorld(boundKey))) {
                ServerLevel boundWorld = bound instanceof ServerLevel sl ? sl : null;
                if (!isInstanceAlive(boundWorld, boundKey) || boundWorld.getServer() != server) {
                    BlockPos spawn = overworld.getSharedSpawnPos();
                    player.setServerLevel(overworld);
                    player.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                            player.getYRot(), player.getXRot());
                    player.setRespawnPosition(Level.OVERWORLD, spawn, 0f, false, true);
                    BattleRoyaleMod.LOGGER.warn("[GameWorld] 玩家 {} 登录时绑定的维度 {} 实例已失效，强制改绑主世界",
                            player.getName().getString(), boundKey.location());
                    return;
                }
            }
            ResourceKey<Level> respawnKey = player.getRespawnDimension();
            if (respawnKey != null && (isGameWorld(respawnKey) || EditWorldManager.isEditWorld(respawnKey))
                    && !isInstanceAlive(server.getLevel(respawnKey), respawnKey)) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.setRespawnPosition(Level.OVERWORLD, spawn, 0f, false, true);
                BattleRoyaleMod.LOGGER.warn("[GameWorld] 玩家 {} 的重生点指向已卸载维度 {}，已重置为主世界出生点",
                        player.getName().getString(), respawnKey.location());
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("登录维度校验异常（忽略，按原版流程继续）: {}", t.toString());
        }
    }

    private static void notifyPlayers(MinecraftServer server, Set<UUID> playerIds, String message) {
        for (UUID id : playerIds) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.displayClientMessage(BrText.of(message), false);
            }
        }
    }

    // ==================================================================
    // 工具方法（与 MtrDataMigrator 一致的目录复制逻辑）
    // ==================================================================

    private static boolean hasFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int copyTree(Path source, Path target) throws IOException {
        int count = 0;
        try (Stream<Path> paths = Files.walk(source)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path destination = target.resolve(source.relativize(file));
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    public static MinecraftServer server() {
        return currentServer;
    }
}
