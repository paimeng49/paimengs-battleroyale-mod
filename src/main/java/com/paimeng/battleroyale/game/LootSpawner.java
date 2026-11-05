package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.ManualLootSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [BR-LOOT 模块二] 普通物资刷新引擎（合法刷新点库 + 开局全图预铺）。
 *
 * <p>进入 PREPARING 时把选区交给 {@link LootPointScanner} 后台异步扫描（直读游戏维度磁盘
 * .mca，不阻塞主线程），找出空气净空、实体地板且上方有屋顶遮挡的室内坐标。扫描结果每 tick
 * 汇入主线程点库；每个坐标取出即消费，服务端主动强制加载目标区块并放置箱子。
 *
 * <p>硬性条件：当前格与上方一格必须是空气，下方必须是实体方块，正上方 1~15 格内有实体屋顶。
 * 通过后生成原版箱子，从 {@link GameMap#lootPool} 洗牌抽取 {@value #MIN_GROUPS}~{@value #MAX_GROUPS}
 * 个互不重复的物资组放入。随机撒点路径不再参与实际生成，避免“玩家走到哪才刷到哪”。
 *
 * <p>数量控制：进入 PREPARING 后持续从扫描点库预铺，直到达到 {@link GameMap#maxLootBoxes}；
 * 每 tick 最多处理一小批，几十秒内完成全图铺设。扫描线程只读地图文件，所有区块加载和箱子放置都在服务端主线程完成。
 *
 * <p>生命周期：随 PREPARING 启动并贯穿 FLIGHT / RUNNING，离开本局活动阶段后停止（主类订阅 GameStateChangedEvent 接线）；
 * 安全重置（{@link MatchResetManager} → {@code GameWorldManager.unloadMatchWorlds}）
 * 前调用 {@link #clearLootChests} 按台账清掉本局刷出的所有箱子与坐标记录。
 *
 * <p>线程模型：只在服务端主线程（ServerTickEvents）访问，无并发容器；
 * 扫描线程只通过 {@link LootPointScanner} 的并发队列与主线程交换数据。
 */
public final class LootSpawner {

    public static final LootSpawner INSTANCE = new LootSpawner();

    /** 每个服务端 tick 最多预铺的箱子数；世界改动始终在主线程完成。 */
    public static final int PRELOAD_BATCH_PER_TICK = 8;
    /** 仅供旧诊断日志换算时间，不再作为生成定时器。 */
    public static final int SPAWN_INTERVAL_TICKS = 40;
    /** 仅保留给旧诊断常量，开局预铺不再使用随机概率撒点。 */
    public static final int COORDINATES_PER_ROUND = 50;
    public static final int SPAWN_CHANCE_PERCENT = 10;
    public static final int MAX_SPAWNS_PER_ROUND = PRELOAD_BATCH_PER_TICK;
    /** 建筑扫描的最低 / 最高高度：每个候选点直接随机 Y，不再依赖 Heightmap。 */
    public static final int INDOOR_MIN_Y = 60;
    public static final int INDOOR_MAX_Y = 150;
    /** 天花板只需在当前位置上方 1~15 格内（与刷新点库扫描同一口径）。 */
    public static final int ROOF_SCAN_RANGE = LootPointScanner.CEILING_RANGE;
    /** 保留旧墙体检测半径常量；当前主判定已不再要求碰墙。 */
    public static final int WALL_SCAN_RANGE = 2;
    /** 保留旧最少墙壁方向常量；当前主判定已不再要求碰墙。 */
    public static final int MIN_WALL_DIRECTIONS = 1;
    /** 每 tick 从已扫描点库预铺，直到达到地图 maxLootBoxes。 */
    public static final int FLIGHT_WARMUP_ROUNDS = 1;
    /** 每箱抽取的物资组数下限 / 上限。 */
    public static final int MIN_GROUPS = 2;
    public static final int MAX_GROUPS = 3;

    /** 仅供旧调用方读取；当前预铺每 tick 直接消费点库，不再按固定抽取轮数运行。 */
    public static final int LIBRARY_DRAWS_PER_ROUND = PRELOAD_BATCH_PER_TICK;
    /** 每个 tick 从异步扫描器取回的上限（扫描完成后可一次性取完全部剩余点）。 */
    public static final int LIBRARY_DRAIN_LIMIT = 100000;

    /**
     * [BR-LOOT-DIAG] 预铺失败原因诊断计数下标：每个消费点按原因归类计数，
     * 每 {@link #DIAG_REPORT_ROUNDS} 轮打一条汇总日志（排查"物资一个都不刷"用）。
     */
    private static final int DIAG_CHUNK = 0;    // 强制加载后仍未进入 FULL（理论上极少）
    private static final int DIAG_NO_SPACE = 1; // Y 50~100 区间找不到合格室内空间
    private static final int DIAG_DUP = 2;      // 该区块 / 坐标本局已刷过
    private static final int DIAG_NOT_SOLID = 3;// 下方不是实体方块
    private static final int DIAG_NATURAL = 4;  // 旧版保留位（当前不再用自然方块拦截）
    private static final int DIAG_BLOCKED = 5;  // 当前格被占用（非空气 / 非植物）
    private static final int DIAG_SPAWNED = 6;  // 成功刷出
    private static final int DIAG_ERROR = 7;    // 抛异常（照实记录，不再静默）
    private static final int DIAG_REASONS = 8;
    /** 诊断汇总周期：每 15 轮（30 秒）打一条 [BR-LOOT-DIAG] 日志。 */
    private static final int DIAG_REPORT_ROUNDS = 15;
    /** 诊断计数：每轮固定尝试 50 个候选点，其中只有命中 10% 的点才进入建筑判定。 */
    private static final int DIAG_ATTEMPTED = 8;
    private static final int DIAG_TOTAL_REASONS = 9;

    private static final Random RANDOM = new Random();
    /** 网格配额计算只处理坐标快照，不碰世界方块，放在后台线程执行。 */
    private static final ExecutorService QUOTA_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battleroyale-loot-quota");
        t.setDaemon(true);
        return t;
    });
    private static final int LOOT_GRID_SIZE = 100;

    /** 箱子朝向候选（原版 getRandomDirection 要 RandomSource，这里直接四个水平向）。 */
    private static final Direction[] HORIZONTAL_FACINGS =
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** 每维度一份刷新运行时（只在主线程读写）。 */
    private final Map<ResourceKey<Level>, DimRuntime> runtimes = new LinkedHashMap<>();

    /** 已生成物资箱台账（重置时按维度清）：维度 → 箱格坐标。 */
    private static final Map<ResourceKey<Level>, List<BlockPos>> PLACED_CHESTS = new LinkedHashMap<>();

    /** 旧计时器字段仅用于兼容诊断，不再控制生成节奏。 */
    private int tickCounter;

    private LootSpawner() {
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 进入 PREPARING：给每个有对局玩家的游戏维度建立预铺运行时，并启动后台点库扫描。 */
    public void start(MinecraftServer server) {
        stop(server);
        if (server == null) {
            return;
        }
        tickCounter = 0;
        // 上一局的台账（若有残留）随新一局开始作废：方块清理已在 ENDED→安全重置里做过。
        PLACED_CHESTS.clear();
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!GameWorldManager.isGameWorld(key)) {
                continue;
            }
            GameMap map = GameWorldManager.mapForWorld(key);
            if (map == null || !hasMatchPlayer(world)) {
                continue;
            }
            map.normalizeLootConfiguration();
            DimRuntime runtime = new DimRuntime(key, map);
            runtime.manualPoints.addAll(map.manualLootSpawns == null ? List.of() : map.manualLootSpawns);
            runtime.poolConfig = LootPoolParser.cleanConfiguration(map.lootPool);
            // [BR-LOOT-SAVE-FIX] 传原始串（不是清洗后的）：判空回退时日志要照原样打出来，
            // 才能分清是「网络包传了个空串」还是「解析器把配置切坏了」。
            runtime.pool = resolvePool(map.lootPool, map.mapId);
            if (runtime.pool.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 连内置默认池都不可用：本局不刷新物资箱",
                        map.mapId);
                BattleRoyaleMod.LOGGER.warn("原始配置字符串：[{}]", map.lootPool);
                continue;
            }
            runtimes.put(key, runtime);
            PLACED_CHESTS.put(key, new ArrayList<>());
            BattleRoyaleMod.LOGGER.info("[BR-LOOT] 地图 {} 物资预铺启动：使用 {} 个物资组，目标 {} 箱（物资池配置：{}）",
                    map.mapId, runtime.pool.size(), runtime.cap,
                    runtime.poolConfig.isEmpty() ? "内置默认池" : runtime.poolConfig);
            // 后台异步扫描选区建立合法刷新点库（读磁盘 .mca，绝不占主线程）。
            LootPointScanner.INSTANCE.begin(world, map);
        }
        if (runtimes.isEmpty()) {
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT] PREPARING 已开启但没有任何游戏维度进入全图预铺"
                    + "（没有参赛者在游戏维度，或维度未映射到地图）");
        } else {
            BattleRoyaleMod.LOGGER.info("[BR-LOOT] PREPARING 已启动全图预铺：扫描点库就绪后由服务端主线程每 tick 强制加载区块并批量放置，直至铺满各地图上限");
        }
    }

    /**
     * [BR-LOOT-FIX] 配置串 → 物资组列表：空串或全部非法时不再返回空列表让整局停摆，
     * 而是回退 {@link DefaultLootPools#normal()} 内置默认池（开箱即可刷出物资）。
     *
     * <p>[BR-LOOT-SAVE-FIX] 参数改收<b>未清洗的原始配置串</b>：只要判定「未配置或全部非法」，
     * 就把原始串照原样打进日志。管理员据此一眼分清是<b>网络包传了个空串</b>，
     * 还是<b>解析器把配置切坏了</b>（后者日志里还会带每条具体片段）。
     *
     * <p>「全部非法」的判据用 {@link LootPoolParser#hasAnyRealItem}：解析器现在会给写错的
     * 物资组补一张占位纸，所以不能只看列表空不空——只剩占位纸等价于「一个真东西都没配出来」，
     * 这种图必须回退默认池，否则全图箱子刷成一沓纸。
     */
    private static List<List<ItemStack>> resolvePool(String rawConfig, String mapId) {
        String cleaned = LootPoolParser.cleanConfiguration(rawConfig);
        if (cleaned.isEmpty()) {
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 未配置物资池（服务端收到空串）：已回退内置默认物资池",
                    mapId);
            BattleRoyaleMod.LOGGER.warn("原始配置字符串：[{}]", rawConfig);
            return DefaultLootPools.orFallback("BR-LOOT", mapId, List.of(), false);
        }
        List<List<ItemStack>> parsed = LootPoolParser.parse(cleaned, "BR-LOOT", mapId);
        if (!LootPoolParser.hasAnyRealItem(parsed)) {
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 物资池解析不出任何真实物品（{} 个组全是占位）："
                            + "已回退内置默认物资池，请对照上面每条「具体片段」日志修改配置",
                    mapId, parsed.size());
            BattleRoyaleMod.LOGGER.warn("原始配置字符串：[{}]", rawConfig);
            BattleRoyaleMod.LOGGER.warn("清洗后配置字符串：[{}]", cleaned);
            return DefaultLootPools.orFallback("BR-LOOT", mapId, List.of(), false);
        }
        return DefaultLootPools.orFallback("BR-LOOT", mapId, parsed, false);
    }

    /**
     * 离开航线 / 正式对局：停止定时器。
     * <b>台账（已生成坐标）此刻不作废</b>——5 秒后 MatchResetManager 的安全重置
     * 要按台账清掉地图里的箱子方块，清早了箱子就永远留在地图上。
     */
    public void stop(MinecraftServer server) {
        runtimes.clear();
        tickCounter = 0;
        // [BR-LOOT-SCAN] 整局结束：作废所有维度的扫描任务（下一局 region 重新复制必须重扫）。
        LootPointScanner.INSTANCE.cancelAll();
    }

    /**
     * [BR-LOOT 模块四 2] 安全重置入口：按台账清掉本维度本局刷出的所有物资箱
     * （方块与内容直接抹掉、不掉落），坐标记录一并作废。region 文件不动，
     * 与死亡箱同一套口径。
     */
    public static void clearLootChests(ServerLevel level) {
        if (level == null) {
            return;
        }
        List<BlockPos> positions = PLACED_CHESTS.remove(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }
        int removed = 0;
        for (BlockPos pos : positions) {
            try {
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.CHEST)) {
                    level.removeBlock(pos, false);
                    level.removeBlockEntity(pos);
                    removed++;
                }
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 清除物资箱 {} 失败：{}", pos.toShortString(), t);
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-LOOT] 安全重置：维度 {} 清除物资箱 {} 个",
                level.dimension().location(), removed);
    }

    /** 关服 / 全局重置兜底：台账整体作废（磁盘状态由各维度的 save 流程负责）。 */
    public static void clearTracking() {
        PLACED_CHESTS.clear();
    }

    // ==================================================================
    // 每 tick 推进（由 ServerTickEvents.END_SERVER_TICK 驱动）
    // ==================================================================

    public void tick(MinecraftServer server) {
        if (server == null || runtimes.isEmpty()) {
            return;
        }
        GamePhase phase = GameManager.INSTANCE.getPhase();
        if (phase != GamePhase.PREPARING && phase != GamePhase.FLIGHT && phase != GamePhase.RUNNING) {
            return; // 双保险：正常生命周期里离开本局已 stop()
        }
        // 扫描器异步读磁盘；这里每 tick 在服务端主线程推进全图预铺，不再等待玩家加载区块。
        preloadTick(server);
    }

    /**
     * 开局全图预铺：扫描点一到达主线程，就强制加载其区块并立即放置箱子。
     * 不再使用随机撒点、玩家附近加载判断或两秒一次的定时器。
     */
    private void preloadTick(MinecraftServer server) {
        for (DimRuntime runtime : runtimes.values()) {
            if (runtime.count >= runtime.cap) {
                LootPointScanner.INSTANCE.cancel(runtime.dimension);
                continue;
            }
            ServerLevel world = server.getLevel(runtime.dimension);
            if (world == null) {
                continue;
            }
            refreshPoolIfNeeded(runtime);
            updateLibrary(world, runtime);
            int processed = 0;
            while (processed < PRELOAD_BATCH_PER_TICK
                    && runtime.count < runtime.cap
                    && !runtime.manualPoints.isEmpty()) {
                try {
                    int reason = trySpawnManual(world, runtime);
                    if (reason >= 0) {
                        runtime.diag[reason]++;
                    }
                    processed++;
                } catch (Throwable t) {
                    runtime.diag[DIAG_ERROR]++;
                    processed++;
                    BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 全图预铺异常：{}", runtime.map.mapId, t.toString());
                }
            }
            while (processed < PRELOAD_BATCH_PER_TICK
                    && runtime.count < runtime.cap
                    && !runtime.plannedPoints.isEmpty()) {
                try {
                    int reason = trySpawnFromLibrary(world, runtime);
                    if (reason >= 0) {
                        runtime.diag[reason]++;
                    }
                    processed++;
                } catch (Throwable t) {
                    runtime.diag[DIAG_ERROR]++;
                    processed++;
                    BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 全图预铺异常：{}", runtime.map.mapId, t.toString());
                }
            }
            reportDiagnostics(runtime);
            if (runtime.count >= runtime.cap) {
                LootPointScanner.INSTANCE.cancel(runtime.dimension);
                BattleRoyaleMod.LOGGER.info("[BR-LOOT] 地图 {} 全图预铺完成：{} / {} 箱",
                        runtime.map.mapId, runtime.count, runtime.cap);
            }
        }
    }

    /**
     * [BR-LOOT-SCAN 方案一] 每轮从后台扫描器取回新扫出的合法室内坐标追加进刷新点库，
     * 并在关键节点打日志：首次可用（提前结束撒点兜底模式）、扫描完成（报总点数）。
     */
    private void updateLibrary(ServerLevel world, DimRuntime runtime) {
        int drained = LootPointScanner.INSTANCE.drainInto(
                runtime.dimension, runtime.library, LIBRARY_DRAIN_LIMIT);
        if (drained > 0 && !runtime.libraryAnnounced) {
            runtime.libraryAnnounced = true;
            BattleRoyaleMod.LOGGER.info("[BR-LOOT-SCAN] 地图 {} 刷新点库开始生效：首批 {} 个合法室内坐标",
                    runtime.map.mapId, runtime.library.size());
        }
        if (!runtime.scanFinishedLogged && LootPointScanner.INSTANCE.isComplete(runtime.dimension)) {
            runtime.scanFinishedLogged = true;
            long total = LootPointScanner.INSTANCE.totalPoints(runtime.dimension);
            BattleRoyaleMod.LOGGER.info("[BR-LOOT-SCAN] 地图 {} 刷新点库扫描完成：共 {} 个合法刷新点",
                    runtime.map.mapId, total);
        }
        if (runtime.scanFinishedLogged && !runtime.quotaSubmitted) {
            runtime.quotaSubmitted = true;
            List<BlockPos> snapshot = new ArrayList<>(runtime.library);
            runtime.library.clear();
            CompletableFuture.supplyAsync(() -> buildQuotaPlan(snapshot, runtime.cap, runtime.map.mapId),
                    QUOTA_EXECUTOR).whenComplete((plan, error) -> {
                if (error != null || plan == null) {
                    runtime.quotaFailed = true;
                    BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 网格配额计算失败：{}",
                            runtime.map.mapId, error == null ? "未知错误" : error.toString());
                } else {
                    runtime.plannedPoints.addAll(plan);
                    runtime.quotaReady = true;
                }
            });
        }
    }

    /** 后台按 100x100 网格分组、分配配额；只操作坐标快照，不读取世界。 */
    private static List<BlockPos> buildQuotaPlan(List<BlockPos> points, int cap, String mapId) {
        Map<Long, List<BlockPos>> cells = new LinkedHashMap<>();
        for (BlockPos pos : points) {
            long gx = Math.floorDiv(pos.getX(), LOOT_GRID_SIZE);
            long gz = Math.floorDiv(pos.getZ(), LOOT_GRID_SIZE);
            long key = (gx << 32) ^ (gz & 0xffffffffL);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
        }
        List<Map.Entry<Long, List<BlockPos>>> entries = new ArrayList<>(cells.entrySet());
        Collections.shuffle(entries, ThreadLocalRandom.current());
        int valid = entries.size();
        long minGx = 0, maxGx = -1, minGz = 0, maxGz = -1;
        if (!entries.isEmpty()) {
            minGx = Long.MAX_VALUE; minGz = Long.MAX_VALUE;
            maxGx = Long.MIN_VALUE; maxGz = Long.MIN_VALUE;
            for (Long key : cells.keySet()) {
                long gx = key >> 32;
                long gz = (int) (long) key;
                minGx = Math.min(minGx, gx); maxGx = Math.max(maxGx, gx);
                minGz = Math.min(minGz, gz); maxGz = Math.max(maxGz, gz);
            }
        }
        long totalGridCount = entries.isEmpty() ? 0 : (maxGx - minGx + 1) * (maxGz - minGz + 1);
        int target = Math.min(Math.max(0, cap), points.size());
        int base = valid == 0 ? 0 : target / valid;
        List<BlockPos> result = new ArrayList<>(target);
        Map<Long, Integer> quotas = new LinkedHashMap<>();
        Map<Long, Integer> capacities = new LinkedHashMap<>();
        for (Map.Entry<Long, List<BlockPos>> entry : entries) {
            int capacity = entry.getValue().size();
            capacities.put(entry.getKey(), capacity);
            int quota = Math.min(entry.getValue().size(), target >= valid ? base : 0);
            quotas.put(entry.getKey(), quota);
            List<BlockPos> cell = entry.getValue();
            Collections.shuffle(cell, ThreadLocalRandom.current());
            result.addAll(cell.subList(0, quota));
        }
        int remaining = target - result.size();
        while (remaining > 0) {
            boolean added = false;
            for (Map.Entry<Long, List<BlockPos>> entry : entries) {
                int used = quotas.get(entry.getKey());
                int capacity = capacities.get(entry.getKey());
                if (used >= capacity) continue;
                result.add(entry.getValue().get(used));
                quotas.put(entry.getKey(), used + 1);
                remaining--;
                added = true;
                if (remaining == 0) break;
            }
            if (!added) break;
        }
        Collections.shuffle(result, ThreadLocalRandom.current());
        double average = valid == 0 ? 0.0 : (double) result.size() / valid;
        BattleRoyaleMod.LOGGER.info("[BR-LOOT-GRID] 地图 {}：划分 {} 个网格，有效网格 {} 个，平均每网格 {} 箱",
                mapId, totalGridCount, valid, String.format(java.util.Locale.ROOT, "%.2f", average));
        return result;
    }

    /** 手动点优先：强制加载点位所在区块，直接在标记方块上方生成箱子。 */
    private int trySpawnManual(ServerLevel world, DimRuntime runtime) {
        ManualLootSpawn spawn = runtime.manualPoints.remove(0);
        BlockPos pos = spawn.blockPos();
        if (pos.getY() < world.getMinBuildHeight() || pos.getY() >= world.getMaxBuildHeight() - 1) {
            return DIAG_NO_SPACE;
        }
        // 工具记录的是最终箱子坐标；若该格后来被占用，才向上找一个可放置格，避免覆盖建筑。
        while (pos.getY() < world.getMaxBuildHeight() - 2
                && (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.above()).isAir())) {
            pos = pos.above();
        }
        ChunkAccess chunk = world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true);
        if (chunk == null || !chunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
            return DIAG_CHUNK;
        }
        runtime.diag[DIAG_ATTEMPTED]++;
        if (runtime.placed.contains(pos) || !world.getBlockState(pos).isAir()
                || !world.getBlockState(pos.above()).isAir()) {
            return DIAG_DUP;
        }
        return placeLootChest(world, runtime, pos) ? DIAG_SPAWNED : DIAG_ERROR;
    }

    /**
     * [BR-LOOT-SCAN 方案一] 从刷新点库随机抽一个坐标生成物资箱。
     *
     * <p>库点来自后台磁盘快照，运行中会主动强制加载目标区块，随后做坐标去重和
     * 方块状态实时复核（玩家可能改过建筑）。复核沿用
     * {@link #isIndoorSpace}（两格净空 + 实体地板 + 近距离屋顶），
     * 磁盘快照里更宽松的天花板窗口（10 格）命中后实时复核条件是其子集，
     * 不会把合法室内点错杀，只会拦下确实被玩家改动过的坐标。
     */
    private int trySpawnFromLibrary(ServerLevel world, DimRuntime runtime) {
        // 取出即消费：坏点或已占点不会反复抽中，预铺才能稳定在几十秒内完成。
        if (runtime.plannedPoints.isEmpty()) {
            return -1;
        }
        BlockPos pos = runtime.plannedPoints.poll();
        // true 表示允许服务端主动加载 FULL 区块；不再把未加载区块计作失败。
        ChunkAccess chunk = world.getChunkSource().getChunk(
                pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true);
        if (chunk == null || !chunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
            return DIAG_CHUNK;
        }
        runtime.diag[DIAG_ATTEMPTED]++;
        if (runtime.placed.contains(pos)) {
            return DIAG_DUP;
        }
        if (!isIndoorSpace(world, pos)) {
            return DIAG_NO_SPACE;
        }
        if (!placeLootChest(world, runtime, pos)) {
            return DIAG_ERROR;
        }
        // 扫描点已被消费；同一区块内的其他合法点仍可继续铺设。
        return DIAG_SPAWNED;
    }

    /** 每 30 秒打一条预铺诊断汇总：概率通过率、成功数、各类失败原因占比。 */
    private void reportDiagnostics(DimRuntime runtime) {
        runtime.diagRounds++;
        if (runtime.diagRounds < DIAG_REPORT_ROUNDS) {
            return;
        }
        runtime.diagRounds = 0;
        int[] d = runtime.diag;
        BattleRoyaleMod.LOGGER.info("[BR-LOOT-DIAG] 地图 {} 近 {} 轮（{} 秒）：成功放置箱子数 {} | 有效尝试 {} | {} | "
                        + "因未加载跳过数 {} | 因空间不符跳过数 {} | 坐标或区块已占用 {} | 下方非实体 {} | "
                        + "旧自然方块计数 {} | 空间被占 {} | 异常 {}",
                runtime.map.mapId, DIAG_REPORT_ROUNDS,
                DIAG_REPORT_ROUNDS * SPAWN_INTERVAL_TICKS / 20,
                d[DIAG_SPAWNED], d[DIAG_ATTEMPTED],
                runtime.library.isEmpty()
                        ? "刷新点库尚未产出（撒点兜底模式）"
                        : "刷新点库 " + runtime.library.size() + " 点（抽点模式）",
                d[DIAG_CHUNK], d[DIAG_NO_SPACE], d[DIAG_DUP], d[DIAG_NOT_SOLID],
                d[DIAG_NATURAL], d[DIAG_BLOCKED], d[DIAG_ERROR]);
        java.util.Arrays.fill(d, 0);
    }

    /** lootPool 字符串改过就重解析（详情面板热改配置无需重启对局）；空 / 全非法自动回退默认池。 */
    private void refreshPoolIfNeeded(DimRuntime runtime) {
        String config = LootPoolParser.cleanConfiguration(runtime.map.lootPool);
        if (config.equals(runtime.poolConfig)) {
            return;
        }
        runtime.poolConfig = config;
        // [BR-LOOT-SAVE-FIX] 同样传原始串：热更新回退默认池时，日志要打的是服务端真实收到的原文。
        runtime.pool = resolvePool(runtime.map.lootPool, runtime.map.mapId);
        BattleRoyaleMod.LOGGER.info("[BR-LOOT] 地图 {} lootPool 已热更新：现使用 {} 个物资组（配置：{}）",
                runtime.map.mapId, runtime.pool.size(),
                config.isEmpty() ? "内置默认池" : config);
    }

    /**
     * 尝试刷一个物资箱；返回诊断原因码（DIAG_* 常量）。
     *
     * <p>每轮随机抽取 X、Z，再在 {@value #INDOOR_MIN_Y}~{@value #INDOOR_MAX_Y}
     * 中随机抽取 Y；不再用 Heightmap 把扫描范围错误地压到地表以下。
     * 条件不满足就返回，不会强制加载区块。
     */
    private int trySpawnOne(ServerLevel world, DimRuntime runtime) {
        GameMap map = runtime.map;
        int minX = Math.min(map.minX, map.maxX);
        int maxX = Math.max(map.minX, map.maxX);
        int minZ = Math.min(map.minZ, map.maxZ);
        int maxZ = Math.max(map.minZ, map.maxZ);
        if (maxX < minX || maxZ < minZ) {
            return DIAG_BLOCKED;
        }
        // X/Z 与 Y 都独立随机；Y 范围覆盖公寓、超市二层等高处建筑。
        int x = minX + RANDOM.nextInt(maxX - minX + 1);
        int z = minZ + RANDOM.nextInt(maxZ - minZ + 1);

        // 区块级去重：同一个区块本局只允许刷一次，避免箱子全挤在相邻坐标。
        long chunkKey = (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL);
        if (runtime.generatedChunks.contains(chunkKey)) {
            return DIAG_DUP;
        }

        // 区块必须已加载：绝不为刷一个箱子强制生成 / 加载区块（性能保护）。
        ChunkAccess chunk = world.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null || !chunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
            // false 参数保证不会在定时刷新中偷偷加载远处区块；此点直接跳过。
            return DIAG_CHUNK;
        }
        // 只有确认区块已完整加载后，才把这个点计入有效尝试。
        runtime.diag[DIAG_ATTEMPTED]++;

        int scanMinY = clampIndoorMinY(world);
        int scanMaxY = clampIndoorMaxY(world);
        if (scanMaxY < scanMinY) {
            return DIAG_NO_SPACE;
        }
        int y = scanMinY + RANDOM.nextInt(scanMaxY - scanMinY + 1);
        if (!runtime.yRangeLogged) {
            BattleRoyaleMod.LOGGER.info("[BR-LOOT-DIAG] 地图 {} 当前随机扫描 Y 范围：{}~{}（每个候选点随机取高度）",
                    map.mapId, scanMinY, scanMaxY);
            runtime.yRangeLogged = true;
        }

        // 放宽室内判定：两格净空、实体地板、10 格内有屋顶或楼板遮挡。
        BlockPos chestPos = new BlockPos(x, y, z);
        if (!isIndoorSpace(world, chestPos)) {
            return DIAG_NO_SPACE;
        }
        // 大型建筑友好：不再用 Flood Fill 房间大小否决机场、大商场等大平层。
        // 同一坐标防重叠仍是必要兜底。
        if (runtime.placed.contains(chestPos)) {
            return DIAG_DUP;
        }

        if (!placeLootChest(world, runtime, chestPos)) {
            return DIAG_ERROR;
        }
        // 只有实际生成成功才永久占用区块；失败坐标仍允许换格重试。
        runtime.generatedChunks.add(chunkKey);
        return DIAG_SPAWNED;
    }

    /** 区间夹到安全建筑范围：[INDOOR_MIN_Y, INDOOR_MAX_Y] ∩ [minBuildHeight+1, maxBuildHeight-2]。 */
    private static int clampIndoorMinY(ServerLevel world) {
        return Math.max(INDOOR_MIN_Y, world.getMinBuildHeight() + 1);
    }

    private static int clampIndoorMaxY(ServerLevel world) {
        return Math.min(INDOOR_MAX_Y, world.getMaxBuildHeight() - 2);
    }

    /**
     * 放宽室内检测：当前位置与头顶一格保持净空，下方有实体地板，且上方 1~6 格有实体遮挡。
     * 近距离墙体检测只用于识别贴墙的建筑空间，不再要求两面墙；没有墙也不拦截正常屋檐下位置。
     */
    private static boolean isIndoorSpace(ServerLevel world, BlockPos pos) {
        if (pos.getY() < clampIndoorMinY(world) || pos.getY() > clampIndoorMaxY(world)) {
            return false;
        }
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (pos.getY() > surfaceY + 5) {
            return false;
        }
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockState floor = world.getBlockState(pos.below());
        if (!isSolid(floor)) {
            return false;
        }
        return hasCeilingWithinRange(world, pos);
    }

    /** 屋顶/楼板检测范围：从脚下位置向上寻找实体遮挡。 */
    private static boolean hasCeilingWithinRange(ServerLevel world, BlockPos pos) {
        int top = Math.min(pos.getY() + ROOF_SCAN_RANGE, world.getMaxBuildHeight() - 1);
        for (int y = pos.getY() + 1; y <= top; y++) {
            if (isSolid(world.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * [BR-LOOT-SCAN 方案二] 实时 Flood Fill 封闭空间复核（仅在刷新点库还没产出的撒点
     * 兜底路径里用，且必须先通过 {@link #isIndoorSpace} 廉价预检才走到这一步）。
     *
     * <p>从候选点出发穿过空气格做有上限（{@link LootPointScanner#FLOOD_CAP} 格）的 BFS：
     * 扩散被实体方块封住 = 真室内；撑满上限、漏到选区外或读到未加载区块 = 判开放放弃。
     * 只查已 FULL 的区块，绝不为了复核强制加载区块。
     */
    private static boolean isEnclosedRoomLive(ServerLevel world, BlockPos start) {
        final int cap = LootPointScanner.FLOOD_CAP;
        java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        queue.add(start.immutable());
        seen.add(start.asLong());
        int count = 0;
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (seen.size() > cap) {
                return false; // 撑满扩散上限仍未封住 = 开放空间
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = cur.relative(dir);
                long key = next.asLong();
                if (seen.contains(key)) {
                    continue;
                }
                // 只读已 FULL 区块；越界到未加载区块一律判开放，避免强制加载。
                ChunkAccess chunk = world.getChunkSource().getChunk(
                        next.getX() >> 4, next.getZ() >> 4, ChunkStatus.FULL, false);
                if (chunk == null || !chunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                    return false;
                }
                if (next.getY() <= world.getMinBuildHeight()
                        || next.getY() >= world.getMaxBuildHeight() - 1) {
                    return false;
                }
                seen.add(key);
                BlockState state = world.getBlockState(next);
                if (!state.isAir()) {
                    continue; // 实体/水/植物等：扩散在此封住（不穿过）
                }
                count++;
                queue.add(next.immutable());
            }
        }
        // 空气域在封顶前自然结束且全部落在已加载区块内：判定为封闭室内。
        return count <= cap;
    }



    /**
     * 按原版容器规则加入物品：先合并同物品同 NBT 的已有堆，再按动态最大堆叠拆入空槽。
     * 这是 1.20.1 官方映射下 {@code Container} 的等价 add 行为，特别兼容 TACZ 的 NBT 堆叠上限。
     */
    static void addToContainer(Container container, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int maxStack = Math.max(1, stack.getMaxStackSize());
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) {
                continue;
            }
            int limit = Math.min(maxStack, Math.max(1, existing.getMaxStackSize()));
            int room = limit - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, stack.getCount());
            existing.grow(moved);
            stack.shrink(moved);
            container.setItem(slot, existing);
        }
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(maxStack, stack.getCount());
            ItemStack placed = stack.copy();
            placed.setCount(moved);
            container.setItem(slot, placed);
            stack.shrink(moved);
        }
    }

    /** 调试用：在指定位置强制生成一个普通物资箱，绕过随机点、概率和本轮上限。 */
    /** 返回指定维度内最近生成的若干物资箱坐标；台账本身按生成顺序保存。 */
    public List<BlockPos> latestPlacedChests(ResourceKey<Level> dimension, int limit) {
        List<BlockPos> positions = PLACED_CHESTS.get(dimension);
        if (positions == null || positions.isEmpty() || limit <= 0) {
            return List.of();
        }
        int from = Math.max(0, positions.size() - limit);
        return new ArrayList<>(positions.subList(from, positions.size()));
    }

    public boolean forceSpawnDebug(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !GameWorldManager.isGameWorld(player.level().dimension())) {
            return false;
        }
        ServerLevel world = player.serverLevel();
        GameMap map = GameWorldManager.mapForWorld(world.dimension());
        if (map == null) {
            return false;
        }
        map.normalizeLootConfiguration();
        DimRuntime runtime = new DimRuntime(world.dimension(), map);
        runtime.pool = resolvePool(map.lootPool, map.mapId);
        if (runtime.pool.isEmpty() || !world.getBlockState(pos).isAir()) {
            return false;
        }
        return placeLootChest(world, runtime, pos);
    }

    /** 生成原版箱子并填 2~3 个物资组；成功 true，异常 false（已记日志）。 */
    private boolean placeLootChest(ServerLevel world, DimRuntime runtime, BlockPos pos) {
        try {
            Direction facing = HORIZONTAL_FACINGS[RANDOM.nextInt(HORIZONTAL_FACINGS.length)];
            BlockState state = Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, facing)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE);
            world.setBlockAndUpdate(pos, state);
            runtime.placed.add(pos);
            runtime.count++;
            List<BlockPos> ledger = PLACED_CHESTS.computeIfAbsent(runtime.dimension,
                    key -> new ArrayList<>());
            ledger.add(pos);

            if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                int groups = Math.min(MIN_GROUPS + RANDOM.nextInt(MAX_GROUPS - MIN_GROUPS + 1),
                        runtime.pool.size());
                // [BR-LOOT-SHUFFLE] 先复制再洗牌，直接取前 groups 个物资组；
                // 同一个箱子里绝不会重复抽中同一组，且不会打乱缓存池本身。
                List<List<ItemStack>> shuffled = new ArrayList<>(runtime.pool);
                Collections.shuffle(shuffled, RANDOM);
                for (int i = 0; i < groups; i++) {
                    for (ItemStack item : shuffled.get(i)) {
                        // [BR-LOOT-BULLET-FIX] 统一走容器加入逻辑：先合并再按动态堆叠上限拆槽，
                        // 传副本避免修改缓存池里的 ItemStack。
                        addToContainer(chest, item.copy());
                    }
                }
                chest.setChanged();
            }
            BattleRoyaleMod.LOGGER.info("[BR-LOOT] 地图 {} 刷新物资箱 #{}：{}",
                    runtime.map.mapId, runtime.count, pos.toShortString());
            return true;
        } catch (Throwable t) {
            // 单箱失败绝不影响定时器与对局（只记日志，坐标留痕防同点反复重试）。
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 地图 {} 生成物资箱 {} 失败（异常详情照抄在此）：{}",
                    runtime.map.mapId, pos.toShortString(), t.toString());
            return false;
        }
    }

    // ==================================================================
    // 方块判定
    // ==================================================================

    /**
     * [BR-LOOT] 明确列入建筑常用材质表，确保它们不会被当成室外自然地形而漏刷。
     *
     * <p>当前生成规则不再用“自然方块黑名单”拦截地板：石砖、平滑石、安山岩、各种混凝土、
     * 羊毛和木板等常见城市建筑方块均可作为箱子地板。此表保留为配置/诊断参考。
     */
    private static final Set<Block> BUILDING_MATERIAL_EXAMPLES = Set.of(
            Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
            Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB, Blocks.ANDESITE, Blocks.POLISHED_ANDESITE,
            Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.GRANITE, Blocks.POLISHED_GRANITE,
            Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.BLACK_CONCRETE,
            Blocks.BROWN_CONCRETE, Blocks.RED_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.YELLOW_CONCRETE,
            Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.GRAY_WOOL, Blocks.BLACK_WOOL,
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS);

    /** 实体方块判定（空气、植物、水等一律不算，箱子不能悬空 / 泡水）。 */
    private static boolean isSolid(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        try {
            return state.isSolid();
        } catch (Throwable t) {
            return false;
        }
    }


    private static boolean hasMatchPlayer(ServerLevel world) {
        for (ServerPlayer player : world.getPlayers(p -> true)) {
            if (GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 每维度运行时
    // ==================================================================

    /** 一个游戏维度的刷新运行时：地图、池缓存（按配置串失效）、坐标台账、计数、诊断。 */
    private static final class DimRuntime {
        final ResourceKey<Level> dimension;
        final GameMap map;
        final int cap;
        final Set<BlockPos> placed = new HashSet<>();
        /** 地图配置里的手动物资点，按开局顺序逐 tick 消费，优先于自动扫描点。 */
        final List<ManualLootSpawn> manualPoints = new ArrayList<>();
        /** 本维度本局已经刷过物资箱的区块，按 (chunkX, chunkZ) 编码去重。 */
        final Set<Long> generatedChunks = new HashSet<>();
        /**
         * [BR-LOOT-SCAN 方案一] 合法刷新点库：由 {@link LootPointScanner} 后台扫描产出、
         * 每轮 {@code drain} 追加；非空时撒点引擎优先从这里随机抽坐标直接生成箱子。
         */
        final List<BlockPos> library = new ArrayList<>();
        /** 后台网格配额计划；主线程只消费，不参与分组和配额计算。 */
        final ConcurrentLinkedQueue<BlockPos> plannedPoints = new ConcurrentLinkedQueue<>();
        boolean quotaSubmitted;
        boolean quotaReady;
        boolean quotaFailed;
        /** 扫描是否已结束（完成/出错/取消），用于诊断日志只报一次。 */
        boolean scanFinishedLogged;
        /** 刷新点库第一次可用时打一条日志。 */
        boolean libraryAnnounced;
        /** [BR-LOOT-DIAG] 撒点与命中计数（每 30 秒汇总打印后清零）。 */
        final int[] diag = new int[DIAG_TOTAL_REASONS];
        int diagRounds;
        boolean yRangeLogged;
        int count;
        String poolConfig = "\u0000";
        List<List<ItemStack>> pool = List.of();

        DimRuntime(ResourceKey<Level> dimension, GameMap map) {
            this.dimension = dimension;
            this.map = map;
            this.cap = map.maxLootBoxes;
        }
    }
}
