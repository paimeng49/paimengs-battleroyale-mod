package com.paimeng.battleroyale.map;

import com.google.common.collect.ImmutableList;
import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.item.ModItems;
import com.paimeng.battleroyale.mixin.MinecraftServerAccessor;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.ServerLevelData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 把 import 存档挂成可进入的编辑副本世界。 */
public final class EditWorldManager {
    public static final String DIMENSION_NAMESPACE = "battleroyale_edit";
    public static final int VIEW_CHUNKS = 8;
    public static final TicketType<ChunkPos> EDIT_TICKET = TicketType.create(
            "battleroyale_edit", Comparator.comparingLong(ChunkPos::toLong), 40);

    private static final Map<String, ResourceKey<Level>> KEYS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Path> ARCHIVE_ROOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, ChunkPos> LAST_TICKETS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Set<ChunkPos>> WORLD_TICKETS = new ConcurrentHashMap<>();
    private static final Set<ResourceKey<Level>> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> UNLOADING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<ServerLevel> PENDING_UNLOADS = new ConcurrentLinkedQueue<>();
    private static MinecraftServer currentServer;

    private EditWorldManager() {}

    public static ResourceLocation dimensionId(String archiveName) {
        return new ResourceLocation(DIMENSION_NAMESPACE, MapManager.safeId(archiveName).toLowerCase());
    }

    public static ResourceKey<Level> worldKey(String archiveName) {
        return KEYS.computeIfAbsent(MapManager.safeId(archiveName).toLowerCase(),
                id -> ResourceKey.create(Registries.DIMENSION, new ResourceLocation(DIMENSION_NAMESPACE, id)));
    }

    public static boolean isEditWorld(ResourceKey<Level> key) {
        return key != null && DIMENSION_NAMESPACE.equals(key.location().getNamespace());
    }

    public static Path archiveRootFor(ResourceKey<Level> key) {
        return ARCHIVE_ROOTS.get(key);
    }

    /** 停止副本 tick、卸载区块并释放运行期引用，供服务器关闭时调用。 */
    public static void onServerStopping() {
        MinecraftServer server = currentServer;
        try {
            if (server != null) {
                MinecraftServerAccessor access = (MinecraftServerAccessor) server;
                Map<ResourceKey<Level>, ServerLevel> worlds = access.battleroyale$getWorlds();
                for (ResourceKey<Level> key : new HashSet<>(worlds.keySet())) {
                    if (!isEditWorld(key)) {
                        continue;
                    }
                    ServerLevel world = worlds.get(key);
                    if (world != null) {
                        requestUnload(server, world);
                    }
                    // 服务器正在关闭，移除副本实例，避免后续关服保存流程再次触碰外部存档世界。
                    worlds.remove(key);
                    ARCHIVE_ROOTS.remove(key);
                    KEYS.values().removeIf(value -> value.equals(key));
                }
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("关闭服务器时清理副本维度失败", t);
        } finally {
            processPendingUnloads(server);
            PENDING_UNLOADS.clear();
            ACTIVE.clear();
            UNLOADING.clear();
            LAST_TICKETS.clear();
            WORLD_TICKETS.clear();
            // [BR-WORLD-FIX] 编辑维度同样彻底清空静态表：
            // ARCHIVE_ROOTS 是 DimensionTypeMixin 存储重定向的数据源，残留旧会话路径
            // 会把下一个会话的区块请求引到上一次的外部存档上。
            ARCHIVE_ROOTS.clear();
            KEYS.clear();
            currentServer = null;
        }
    }

    /** 服务器启动后预注册每个 import 存档对应的空世界，但不加载区块、不 tick。 */
    public static void registerAll(MinecraftServer server) {
        currentServer = server;
        for (ArchiveEntry entry : MapManager.INSTANCE.scanImports()) {
            try {
                // MTR 会在副本世界注册时读取目录，因此迁移必须发生在注册之前。
                MtrDataMigrator.migrateFor(server, entry);
                ensureWorld(server, entry);
            } catch (Exception e) {
                BattleRoyaleMod.LOGGER.warn("预注册编辑世界 {} 失败: {}", entry.name(), e.toString());
            }
        }
    }

    public static ServerLevel ensureWorld(MinecraftServer server, ArchiveEntry entry) throws Exception {
        currentServer = server;
        ResourceKey<Level> key = worldKey(entry.name());
        ARCHIVE_ROOTS.put(key, entry.root());
        ServerLevel existing = server.getLevel(key);
        if (existing != null) {
            existing.noSave = true;
            // [BR-SPAWN] 1.20.1 等价写法：维度级刷怪开关挪到了 ChunkSource#setSpawnSettings。
            existing.getChunkSource().setSpawnSettings(false, false);
            return existing;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            throw new IllegalStateException("主世界尚未创建");
        }
        Path region = entry.root().resolve("region");
        if (!Files.isDirectory(region)) {
            throw new IllegalStateException("存档缺少 region 文件夹");
        }
        Holder<DimensionType> type = overworld.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD);
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
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
        world.noSave = true;
        // [BR-SPAWN] 按维度关闭自然刷怪（替代 1.18.2 的 setMobSpawnOptions(false,false)）。
        world.getChunkSource().setSpawnSettings(false, false);
        world.getChunkSource().setViewDistance(VIEW_CHUNKS);
        world.getChunkSource().setSimulationDistance(VIEW_CHUNKS);
        access.battleroyale$getWorlds().put(key, world);
        BattleRoyaleMod.LOGGER.info("已挂载编辑世界 {} -> {}", key.location(), entry.root());
        return world;
    }

    public static String enter(ServerPlayer player, String archiveName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return "服务器尚未就绪";
        }
        ArchiveEntry entry = MapManager.INSTANCE.findArchive(archiveName);
        if (entry == null) {
            return "找不到存档 " + archiveName + "，请先放到 config/battleroyale/import/";
        }
        ServerLevel world;
        try {
            ResourceKey<Level> key = worldKey(entry.name());
            // 已加载的副本已经在首次注册前完成迁移，重复进入直接复用。
            if (server.getLevel(key) == null) {
                MtrDataMigrator.migrateFor(server, entry);
            }
            world = ensureWorld(server, entry);
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("加载编辑世界失败: {}", e.toString());
            return "加载副本失败：" + e.getMessage();
        }
        if (!isEditWorld(player.level().dimension())) {
            MapEditSession.rememberReturn(player);
        }
        SelectionData selection = MapEditSession.selectionOf(player.getUUID());
        selection.archiveName = entry.name();
        selection.archivePath = entry.root().toString();
        GameMap draft = MapEditSession.prepareDraftForArchive(player.getUUID(), entry.root().toString());
        draft.sourceArchivePath = entry.root().toString();
        ACTIVE.add(world.dimension());
        UNLOADING.remove(world.dimension());
        try {
            player.teleportTo(world, 0.5, 64.0, 0.5, player.getYRot(), player.getXRot());
            giveTools(player);
            refreshTicket(player, world);
            return null;
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("传送到编辑世界失败: {}", e.toString());
            ACTIVE.remove(world.dimension());
            return "进入副本失败：" + e.getMessage();
        }
    }

    public static String leave(ServerPlayer player) {
        MapEditSession.ReturnPoint point = MapEditSession.RETURNS.remove(player.getUUID());
        LAST_TICKETS.remove(player.getUUID());
        ServerLevel current = (ServerLevel) player.level();
        ResourceKey<Level> currentKey = current.dimension();
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
                }
            } else if (overworld != null) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("离开编辑世界失败", t);
            return "离开副本失败：" + t.getMessage();
        }
        try {
            if (isEditWorld(currentKey) && current.getPlayers(p -> true).isEmpty()) {
                requestUnload(server, current);
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("离开副本后的清理失败", t);
            return "离开副本失败：" + t.getMessage();
        }
        return null;
    }

    public static void tick(MinecraftServer server) {
        currentServer = server;
        processPendingUnloads(server);
        Set<ResourceKey<Level>> occupied = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel world = (ServerLevel) player.level();
            if (!isEditWorld(world.dimension()) || UNLOADING.contains(world.dimension())) {
                continue;
            }
            occupied.add(world.dimension());
            ACTIVE.add(world.dimension());
            world.noSave = true;
            // [BR-SPAWN] 每 tick 重申按维度刷怪关闭（防其它流程把开关打开）。
            world.getChunkSource().setSpawnSettings(false, false);
            suppressWorldRules(world);
            refreshTicket(player, world);
            MapEditMarker.tick(player, world);
        }
        for (ResourceKey<Level> key : new HashSet<>(ACTIVE)) {
            if (occupied.contains(key)) {
                continue;
            }
            ServerLevel world = server.getLevel(key);
            if (world != null && world.getPlayers(p -> true).isEmpty()) {
                requestUnload(server, world);
            } else if (world == null) {
                ACTIVE.remove(key);
            }
        }
    }

    public static boolean shouldSkipTick(ServerLevel world) {
        ResourceKey<Level> key = world.dimension();
        return isEditWorld(key) && (UNLOADING.contains(key) || !ACTIVE.contains(key));
    }

    public static void handleDisconnect(ServerPlayer player) {
        try {
            LAST_TICKETS.remove(player.getUUID());
            ServerLevel world = (ServerLevel) player.level();
            if (world != null && isEditWorld(world.dimension()) && world.getPlayers(p -> true).size() <= 1) {
                requestUnload(world.getServer(), world);
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("玩家断线后的副本清理失败", t);
        }
    }

    public static void handleRespawn(ServerPlayer player) {
        try {
            LAST_TICKETS.remove(player.getUUID());
            // 重生后若离开了副本，后续 tick 会在无人时执行统一卸载。
            if (player.level() == null) {
                return;
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("玩家重生后的副本清理失败", t);
        }
    }

    private static void requestUnload(MinecraftServer server, ServerLevel world) {
        ResourceKey<Level> key = world.dimension();
        if (UNLOADING.contains(key)) {
            return;
        }
        for (ServerLevel pending : PENDING_UNLOADS) {
            if (pending == world) {
                return;
            }
        }
        PENDING_UNLOADS.add(world);
        BattleRoyaleMod.LOGGER.info("副本维度 {} 卸载请求已排队", key.location());
        if (server != null && server.isSameThread()) {
            processPendingUnloads(server);
        }
    }

    private static void processPendingUnloads(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            server.execute(() -> processPendingUnloads(server));
            return;
        }
        while (!PENDING_UNLOADS.isEmpty()) {
            ServerLevel world = PENDING_UNLOADS.poll();
            if (world == null) {
                continue;
            }
            unloadEditWorld(world);
        }
    }

    private static void unloadEditWorld(ServerLevel world) {
        ResourceKey<Level> key = world.dimension();
        if (!UNLOADING.add(key)) {
            return;
        }
        BattleRoyaleMod.LOGGER.info("副本维度 {} 正在卸载", key.location());
        try {
            unloadChunks(world);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("卸载副本维度 {} 的区块失败", key.location(), t);
        } finally {
            // 区块处理完成后才释放引用，确保下次进入时能重新注册缺失的世界实例。
            ACTIVE.remove(key);
            WORLD_TICKETS.remove(key);
            UNLOADING.remove(key);
        }
        BattleRoyaleMod.LOGGER.info("副本维度 {} 在主线程卸载完成", key.location());
    }

    private static void refreshTicket(ServerPlayer player, ServerLevel world) {
        ChunkPos now = new ChunkPos(player.blockPosition());
        ChunkPos previous = LAST_TICKETS.put(player.getUUID(), now);
        ResourceKey<Level> key = world.dimension();
        Set<ChunkPos> tickets = WORLD_TICKETS.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
        if (previous != null && !previous.equals(now)) {
            world.getChunkSource().removeRegionTicket(EDIT_TICKET, previous, VIEW_CHUNKS, previous);
            tickets.remove(previous);
        }
        world.getChunkSource().addRegionTicket(EDIT_TICKET, now, VIEW_CHUNKS, now);
        tickets.add(now);
        world.getChunkSource().setViewDistance(VIEW_CHUNKS);
        world.getChunkSource().setSimulationDistance(VIEW_CHUNKS);
    }

    private static void unloadChunks(ServerLevel world) {
        ResourceKey<Level> key = world.dimension();
        Set<ChunkPos> tickets = WORLD_TICKETS.getOrDefault(key, Set.of());
        for (ChunkPos pos : new HashSet<>(tickets)) {
            try {
                world.getChunkSource().removeRegionTicket(EDIT_TICKET, pos, VIEW_CHUNKS, pos);
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.debug("移除副本维度 {} 区块票据失败: {}", key.location(), t.toString());
            }
        }
        try {
            world.getChunkSource().removeTicketsOnClosing();
            world.getChunkSource().save(true);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("保存副本维度 {} 区块时发生异常", key.location(), t);
        }
    }

    private static void suppressWorldRules(ServerLevel world) {
        try {
            GameRules rules = world.getGameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, world.getServer());
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, world.getServer());
            rules.getRule(GameRules.RULE_DOFIRETICK).set(false, world.getServer());
            rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, world.getServer());
            rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, world.getServer());
            rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, world.getServer());
            world.setWeatherParameters(999999, 0, false, false);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.debug("抑制编辑世界规则失败: {}", e.toString());
        }
    }

    private static void giveTools(ServerPlayer player) {
        giveIfMissing(player, new ItemStack(ModItems.SELECTION_TOOL));
        giveIfMissing(player, new ItemStack(ModItems.LOOT_TOOL));
        player.displayClientMessage(BrText.of("已发放选区工具和物资点工具"), false);
    }

    private static void giveIfMissing(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().contains(stack)) {
            player.getInventory().add(stack);
        }
    }

    public static MinecraftServer server() {
        return currentServer;
    }

    public static Vec3 spawn() {
        return new Vec3(0.5, 64.0, 0.5);
    }
}
