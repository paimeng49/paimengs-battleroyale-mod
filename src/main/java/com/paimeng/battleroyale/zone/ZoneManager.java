package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.ZoneStageChangedEvent;
import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.FlightManager;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GameRules;
import com.paimeng.battleroyale.game.WarmupManager;
import com.paimeng.battleroyale.game.ZoneDamage;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.ZonePhaseConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 毒圈核心管理器（r109 重构：一个游戏维度一份毒圈运行时）。
 *
 * <p><b>为什么是“每维度一份”（模块六）</b>：一局 br start 可能同时开进多张地图
 * （Matchmaker 把不同等待队列的人传进各自的 {@code battleroyale_game:<mapId>} 维度）。
 * 毒圈必须严格属于它所在的那个维度：
 * <ul>
 *   <li>伤害：{@link ZoneRuntime#applyZoneDamage} 只对 {@code player.level().dimension()}
 *       等于本圈 {@link ZoneRuntime#dimension} 的玩家结算——主城 / 下界的人绝对毒不到；</li>
 *   <li>同步：{@code network.ZoneSync} 按玩家所在维度取对应运行时，收不到就发 IDLE；</li>
 *   <li>阶段配置：读该地图 JSON 里的 {@code zonePhases}（模块一），没配则用
 *       {@link ZoneStages#defaultsFor} 按选区缩放（模块三兜底）。</li>
 * </ul>
 *
 * <p><b>状态机（模块三 + r130 阶段零）</b>：每个运行时独立走
 * 阶段零 WAITING(全图安全倒计时) → SHRINKING(超大蓝圈收到第 1 白圈) →
 * 下一阶段 WAITING → … → DONE。
 * 开局蓝圈半径取「罩住全图还要翻倍」的极大值：落地跳伞阶段没有任何圈外伤害，
 * 玩家只需要盯着白圈跑；第一次缩圈开始才有真实边缘伤害。
 * 进入一个阶段的 WAITING 时就用 {@link ZonePlanner} 摇出该阶段的目标白圈
 * （弱排水 + 绝对不出界 + 防卡死兜底，全部只查异步预生成的底图网格）。
 *
 * <p><b>蓝/白圈语义（给客户端 lerp 用）</b>：
 * {@code blue*} 是本轮收缩的起点圆（WAITING 期间它就是当前圈），
 * {@code white*} 是本轮收缩目标；客户端实时蓝圈 = lerp(blue → white, progress)。
 * 收缩完成的瞬间 blue := white，再摇下一个 white。
 *
 * <p>生命周期：GameManager 进入 RUNNING 时 {@link #start}，离开 RUNNING 时 {@link #stop}；
 * IDLE（没有任何运行时）时 {@link #tick} 直接返回，零副作用。
 *
 * <p>[r148 毒圈 TTK 模型] 圈外掉血不再"每秒直接 hurt 一个浮点数"：
 * 每个局内玩家在 {@link ZoneRuntime#accumulatedZoneDamage} 里有一个 float 池，
 * 每秒加进 {@code damagePerSecond = player.getMaxHealth() / 当前阶段淘汰秒数}
 * （double，0.08、2.5 这种小数都合法），
 * <b>攒满 1 点才真扣 1 点</b>，零头留在池子里滚下一秒；进安全区立刻清零。
 * 扣血走自定义伤害类型 {@code battleroyale:zone_damage}（绕过护甲 + 无敌帧 + 抗性），
 * 详见 {@link com.paimeng.battleroyale.game.ZoneDamage}。
 *
 * <p>升级预留：只有 {@link ZoneRuntime#applyZoneDamage} 里一处碰易变 API
 * （伤害源构造，已收进 {@link ZoneDamage}）；ResourceKey 比对在高版本换 LevelKey，一行事。
 */
public final class ZoneManager {

    public static final ZoneManager INSTANCE = new ZoneManager();

    /**
     * [r148] 秒数文案：整数不带 .0，小数原样（250 / 12.5 / 8 都要一眼看得懂）。
     * 播报、GUI 回执、日志共用（放在外层类，{@code ZoneRuntime} 内外都能直接调）。
     */
    public static String formatSeconds(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    /** 每个游戏维度一份毒圈运行时；只在服务端主线程读写，不需要并发容器。 */
    private final Map<ResourceKey<Level>, ZoneRuntime> runtimes = new LinkedHashMap<>();

    /** 选圈共用随机源（主线程使用）。 */
    private final Random random = new Random();

    private ZoneManager() {
    }

    // ==================================================================
    // 生命周期（由主类订阅 GameStateChangedEvent 后调用）
    // ==================================================================

    /**
     * 开局（RUNNING）：给每一个「加载了、且维度里站着本局对局玩家」的游戏维度
     * 各起一份毒圈运行时。主城维度、没人的游戏维度（管理员在里面配物资）都不会有圈。
     */
    public void start(MinecraftServer server) {
        stop(server);   // 上一局兜底清理（正常应已空）
        if (server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!GameWorldManager.isGameWorld(key)) {
                continue;
            }
            GameMap map = GameWorldManager.mapForWorld(key);
            if (map == null || !hasMatchPlayer(world)) {
                continue;
            }
            ZoneRuntime zone = new ZoneRuntime(map, key, world, random);
            runtimes.put(key, zone);
            BattleRoyaleMod.LOGGER.info("[BR-ZONE] 地图 {}（维度 {}）毒圈启动：阶段零，蓝圈圆心 ({}, {}) 半径 {}（罩住全图），"
                            + "目标白圈 圆心 ({}, {}) 半径 {}，{} 个阶段",
                    zone.mapId, key.location().toString(),
                    fmt(zone.blueX), fmt(zone.blueZ), fmt(zone.blueRadius),
                    fmt(zone.whiteX), fmt(zone.whiteZ), fmt(zone.whiteRadius), zone.stages.size());
            zone.publish(server);
        }
    }

    /** 收尾（/paimengmods br stop、超时回大厅都会走到）：全部运行时进 IDLE 并清表。 */
    public void stop(MinecraftServer server) {
        if (runtimes.isEmpty()) {
            return;
        }
        for (ZoneRuntime zone : runtimes.values()) {
            zone.phase = ZonePhase.IDLE;
            zone.stageIndex = -1;
            zone.stageTicks = 0;
            zone.phaseZero = false;
            zone.clearZoneDamagePools();   // [r147] 下一局从零开始攒伤害，不带上本局的零头
        }
        runtimes.clear();
        if (server != null) {
            // 给每个维度广播一次 IDLE：客户端收到立刻清空本地状态（模块六 3）。
            com.paimeng.battleroyale.network.ZoneSync.syncNow(server);
        }
    }

    /** 维度里是否有本局名单上的玩家（管理员在游戏维度配物资不该被毒）。 */
    private static boolean hasMatchPlayer(ServerLevel world) {
        for (ServerPlayer player : world.getPlayers(p -> true)) {
            if (GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 查询 / 设置
    // ==================================================================

    /** 有没有任何毒圈在跑（ZoneSync 决定发包节奏用）。 */
    public boolean anyActive() {
        return !runtimes.isEmpty();
    }

    /** 某维度的毒圈运行时；不在游戏维度 / 该维度没开圈返回 null（ZoneSync 据此发 IDLE）。 */
    public ZoneRuntime runtimeAt(ResourceKey<Level> worldKey) {
        return worldKey == null ? null : runtimes.get(worldKey);
    }

    public Collection<ZoneRuntime> runtimes() {
        return runtimes.values();
    }


    // ==================================================================
    // 每 tick 推进：由 ServerTickEvents.END_SERVER_TICK 驱动
    // ==================================================================

    public void tick(MinecraftServer server) {
        if (runtimes.isEmpty()) {
            return;
        }
        // LinkedHashMap 保序遍历；tick 内部只改字段不增删运行时，遍历安全。
        for (ZoneRuntime zone : runtimes.values()) {
            zone.tick(server);
        }
    }

    private static String fmt(double v) {
        return String.valueOf((int) Math.round(v));
    }

    // ==================================================================
    // 一个维度的毒圈运行时
    // ==================================================================

    /**
     * 单维度毒圈：几何状态 + 阶段推进 + 圈外伤害。
     * 只在服务端主线程读写；{@link #snapshot()} 打包后经 ZoneSync 发客户端。
     */
    public static final class ZoneRuntime {

        // ---- 身份（模块六：维度隔离的判据） ----
        final String mapId;
        final ResourceKey<Level> dimension;
        final String dimensionValue;

        // ---- 选区与阶段表 ----
        private final ZonePlanner.Bounds bounds;
        private final double maxRadius;
        private final List<ZoneStage> stages;
        /** 底图来源（异步生成的水域网格）；生成中/缺失时为 null，选圈自动跳过排水。 */
        private final GameMap map;

        // ---- 蓝圈（本轮收缩起点；WAITING 期间即当前安全区边界） ----
        private double blueX;
        private double blueZ;
        private double blueRadius;
        // ---- 白圈（本轮收缩目标） ----
        private double whiteX;
        private double whiteZ;
        private double whiteRadius;

        // ---- 推进状态 ----
        private ZonePhase phase = ZonePhase.WAITING;
        private int stageIndex = 0;
        private int stageTicks = 0;
        private int damageTicks = 0;
        /** [r130 阶段零] 还没走完第一次收缩前为 true：全图安全，播报 / 渲染按它防呆。 */
        private boolean phaseZero = true;

        /**
         * [r148 伤害累加器] 圈外伤害的小数池：玩家 UUID → 还没攒满 1 点的零头
         * （外加"本秒应加但还没结算"的部分，结算后一定 &lt; 1.0）。
         *
         * <p>为什么必须有它：原版 {@code hurt()} 打完就把 {@code invulnerableTime} 设成
         * 10~20 tick，紧接着的第二次结算会被整段吞掉；TTK 反推出来的伤害常常
         * 不到 1 点（第 1 阶段 0.08 点/秒），直接 hurt 等于"扣了个寂寞"。
         * 所以每秒只做加法，攒够整数才真扣血。
         *
         * <p>只在服务端主线程读写（{@link #tick} 里），不需要并发容器；
         * 进安全区 / 倒地 / 免伤 / 淘汰 / 换维度都会当场清掉这个键，不会带着零头爆伤。
         */
        private final Map<UUID, Float> accumulatedZoneDamage = new HashMap<>();

        /**
         * [r148] 小数池封顶下限（防"攒一堆突然爆伤"）：实际上限取
         * {@code max(4, 本玩家本秒伤害 × 2)}——正常配置最多攒 4 点血，
         * 即使玩家被别的免伤规则挡住一整轮，恢复可伤害那一刻也炸不出超过 4 点的瞬间掉血。
         */
        private static final float ACCUMULATOR_FLOOR_CAP = 4.0f;

        /** 阶段零初始蓝圈半径系数：罩住全图（圆心到最远角）所需半径的 2 倍起步。 */
        private static final double PHASE0_BLUE_FACTOR = 2.0;

        private final Random random;

        ZoneRuntime(GameMap map, ResourceKey<Level> dimension, ServerLevel world, Random random) {
            this.map = map;
            this.mapId = map.mapId == null ? "" : map.mapId;
            this.dimension = dimension;
            this.dimensionValue = dimension.location().toString();
            this.bounds = ZonePlanner.Bounds.of(map.minX, map.maxX, map.minZ, map.maxZ);
            this.maxRadius = this.bounds.maxRadius();
            this.stages = resolveStages(map, this.maxRadius);
            this.random = random;

            // ---- [r130 阶段零] 初始蓝圈：罩住全图的超大圆，圆心 = 选区中心 ----
            // 需求：开局（跳伞落地）全图都在圈内，零圈外伤害；玩家只看得到白圈。
            // 地图配置指定过圆心时仍然尊重（只挪超大圆的圆心，半径照样按“罩住这个
            // 圆心所在选区的最远角 × 2”计算，绝不会出现开局就有人被毒的情况）。
            double cx = this.bounds.centerX();
            double cz = this.bounds.centerZ();
            if (map.zoneCenterSet) {
                cx = clamp(map.zoneCenterX, this.bounds.minX(), this.bounds.maxX());
                cz = clamp(map.zoneCenterZ, this.bounds.minZ(), this.bounds.maxZ());
            }
            this.blueX = cx;
            this.blueZ = cz;
            double cover = Math.max(this.bounds.cornerDistance(cx, cz), this.bounds.shortSide());
            this.blueRadius = Math.max(1.0, PHASE0_BLUE_FACTOR * cover);

            this.phase = ZonePhase.WAITING;
            this.stageIndex = 0;
            this.stageTicks = 0;
            this.damageTicks = 0;
            this.phaseZero = true;
            // 第一个白圈立刻摇出来（用第 1 阶段配置的半径）：跳伞时就能看到要往哪落。
            regenerateFirstTarget(world);
        }

        // ----------------------------------------------------------------
        // 配置解析
        // ----------------------------------------------------------------

        /** 阶段表：地图 JSON 配了就用（夹硬限制），没配用按选区缩放的默认表。 */
        private static List<ZoneStage> resolveStages(GameMap map, double maxRadius) {
            // [r148 TTK 模型] 地图级圈外淘汰秒数（double，默认 250）：
            // 阶段自己填了 >0 的值以阶段为准，填 0 一律回落到这个全局值。
            final double globalTtk = Math.max(GameMap.MIN_ZONE_TTK_SECONDS,
                    Math.min(GameMap.MAX_ZONE_TTK_SECONDS, map.zoneTtkSeconds));
            List<ZonePhaseConfig> configs = map.zonePhases;
            if (configs == null || configs.isEmpty()) {
                return ZoneStages.defaultsFor(maxRadius);
            }
            // [r131 索引防错位] stages[i] 必须永远对应 configs[i]：配置里遇到空洞（null）
            // 用默认表**原位补齐**，绝不 continue 跳过——跳过一次，config[1] 就冒充
            // config[0]，开局白圈会整整小一档（本轮事故的根源之一）。
            List<ZoneStage> stages = new ArrayList<>();
            StringBuilder table = new StringBuilder();
            for (int i = 0; i < configs.size() && i < ZonePhaseConfig.MAX_PHASES; i++) {
                ZonePhaseConfig config = configs.get(i);
                ZoneStage stage;
                if (config == null) {
                    stage = ZoneStages.defaultStageAt(i, maxRadius);
                    BattleRoyaleMod.LOGGER.warn("[BR-ZONE] 地图 {} 的 config[{}] 是空洞（null），"
                                    + "已按默认表原位补齐：半径 {}（阶段表没有整体前移）",
                            map.mapId, i, fmt(stage.targetRadius()));
                } else {
                    ZonePhaseConfig safe = config.copy();
                    safe.clampIntoRange(maxRadius);
                    stage = safe.toStage(i, globalTtk);   // 展示名用原始配置下标，和 JSON 一一对应
                }
                stages.add(stage);
                if (i > 0) {
                    table.append("; ");
                }
                table.append("config[").append(i).append("] 半径=").append((int) stage.targetRadius())
                        .append(" 等待=").append(stage.waitSeconds())
                        .append(" 缩圈=").append(stage.shrinkSeconds())
                        .append(" 圈外淘汰=").append(formatSeconds(stage.zoneTtkSeconds())).append("秒")
                        .append(config == null || config.zoneTtkSeconds <= 0.0 ? "（跟随全局）" : "");
            }
            if (stages.isEmpty()) {
                return ZoneStages.defaultsFor(maxRadius);
            }
            BattleRoyaleMod.LOGGER.info("[BR-ZONE] 地图 {} 阶段表（{} 项，按 config 下标原位对齐，全局圈外淘汰 {} 秒）：{}",
                    map.mapId, stages.size(), formatSeconds(globalTtk), table);
            return stages;
        }

        /** [r148] 秒数文案已上提到外层 ZoneManager.formatSeconds（GUI / 播报跨类共用）。 */

        // ----------------------------------------------------------------
        // 查询
        // ----------------------------------------------------------------

        public ZonePhase phase() {
            return phase;
        }

        public int stageIndex() {
            return stageIndex;
        }

        public String mapId() {
            return mapId;
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        /** 当前阶段配置；越界返回 null。 */
        public ZoneStage currentStage() {
            return (stageIndex >= 0 && stageIndex < stages.size()) ? stages.get(stageIndex) : null;
        }

        /** 此刻的实时圆心/半径（SHRINKING 阶段按 progress lerp，和客户端画面一致）。 */
        public double liveCenterX() {
            double t = shrinkProgress();
            return blueX + (whiteX - blueX) * t;
        }

        public double liveCenterZ() {
            double t = shrinkProgress();
            return blueZ + (whiteZ - blueZ) * t;
        }

        public double liveRadius() {
            double t = shrinkProgress();
            return blueRadius + (whiteRadius - blueRadius) * t;
        }

        /** 某平面坐标是否已在圈外（只看 X/Z，不管 Y 层）。 */
        public boolean isOutside(double x, double z) {
            return Math.hypot(x - liveCenterX(), z - liveCenterZ()) > liveRadius();
        }

        /** 缩圈总进度 0.0~1.0：SHRINKING 才有值，其它状态 0。 */
        public double shrinkProgress() {
            if (phase != ZonePhase.SHRINKING) {
                return 0.0;
            }
            ZoneStage stage = currentStage();
            if (stage == null || stage.shrinkTicks() <= 0) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, (double) stageTicks / stage.shrinkTicks()));
        }

        /** 剩余倒计时（秒）：WAITING = 等待剩余；SHRINKING / DONE 锁定 0（需求原文）。 */
        public int remainingSeconds() {
            if (phase != ZonePhase.WAITING) {
                return 0;
            }
            ZoneStage stage = currentStage();
            if (stage == null) {
                return 0;
            }
            int left = stage.waitTicks() - stageTicks;
            return (int) Math.ceil(Math.max(0, left) / (double) GameRules.TICKS_PER_SECOND);
        }

        // ----------------------------------------------------------------

        // ----------------------------------------------------------------
        // 每 tick 推进
        // ----------------------------------------------------------------

        void tick(MinecraftServer server) {
            if (phase == ZonePhase.IDLE) {
                return;
            }
            stageTicks++;
            ZoneStage stage = currentStage();

            switch (phase) {
                case WAITING -> {
                    if (stage != null && stageTicks >= stage.waitTicks()) {
                        phase = ZonePhase.SHRINKING;
                        phaseZero = false;   // [r130] 第一次收缩开始：阶段零结束，边缘伤害生效
                        stageTicks = 0;   // lerp 从 0 开始
                        publish(server);
                    }
                }
                case SHRINKING -> {
                    if (stage != null && stageTicks >= stage.shrinkTicks()) {
                        // 收缩到位：蓝圈接管白圈，再摇下一个白圈。
                        blueX = whiteX;
                        blueZ = whiteZ;
                        blueRadius = whiteRadius;
                        stageTicks = 0;
                        if (stageIndex + 1 < stages.size()) {
                            stageIndex++;
                            phase = ZonePhase.WAITING;
                            regenerateTarget(server == null ? null : server.getLevel(dimension));
                        } else {
                            phase = ZonePhase.DONE;
                        }
                        publish(server);
                    }
                }
                case DONE -> {
                    // 什么都不做：伤害结算照常走，等状态机把对局收尾。
                }
                default -> {
                    // IDLE 已在入口返回
                }
            }

            damageTicks++;
            if (damageTicks >= ZoneStages.DAMAGE_INTERVAL_TICKS) {
                damageTicks = 0;
                applyZoneDamage(server);
            }
        }

        /** [r130 阶段零] 开局目标白圈：用第 1 阶段配置（config[0]）的半径，在选区内均匀采样 + 弱排水。 */
        private void regenerateFirstTarget(ServerLevel world) {
            if (stages.isEmpty()) {
                whiteX = blueX;
                whiteZ = blueZ;
                whiteRadius = blueRadius;
                return;
            }
            // 需求硬规定：玩家落地看到的第一个白圈 = phases[0]，这里只允许读下标 0。
            ZoneStage first = stages.get(0);
            ZonePlanner.Result result = ZonePlanner.firstTarget(
                    first.targetRadius(), bounds, map.baseImage, random);
            whiteX = result.centerX();
            whiteZ = result.centerZ();
            whiteRadius = result.radius();
            if (world != null) {
                BattleRoyaleMod.LOGGER.info("[BR-ZONE] 初始白圈生成：使用阶段 1 (config[0])，"
                                + "半径={}（配置原值 {}，可能被短边一半夹过），圆心 ({}, {})，排水网格可用：{}",
                        fmt(whiteRadius), fmt(first.targetRadius()), fmt(whiteX), fmt(whiteZ),
                        map.baseImage != null);
            }
        }

        /** 用弱排水算法摇出本阶段的下一个白圈（只查内存网格，主线程零方块遍历）。 */
        private void regenerateTarget(ServerLevel world) {
            ZoneStage stage = currentStage();
            if (stage == null) {
                return;
            }
            ZonePlanner.Result result = ZonePlanner.next(
                    new ZonePlanner.Result(blueX, blueZ, blueRadius),
                    stage.targetRadius(), bounds, map.baseImage, random);
            whiteX = result.centerX();
            whiteZ = result.centerZ();
            whiteRadius = result.radius();
            if (world != null) {
                // [r131 日志可核对] 明确打出用的是 config[stageIndex]：推进后 stageIndex
                // 已 +1，这里就是 config[1]（第二次缩圈目标），与需求索引一一对得上。
                BattleRoyaleMod.LOGGER.info("[BR-ZONE] 白圈生成：使用阶段 {} (config[{}])，"
                                + "半径={}（配置原值 {}），圆心 ({}, {})，排水网格可用：{}",
                        stageIndex + 1, stageIndex, fmt(whiteRadius), fmt(stage.targetRadius()),
                        fmt(whiteX), fmt(whiteZ), map.baseImage != null);
            }
        }

        // ----------------------------------------------------------------
        // 圈外伤害：每 20 tick 一次；模块六——先校验维度，再打
        // ----------------------------------------------------------------

        /**
         * [r148] 本圈当前生效的「圈外淘汰时间」（秒，double）：
         * 阶段值 &gt; 0 用阶段值；否则回落到地图全局 {@code GameMap.zoneTtkSeconds}。
         * 倒地玩家的掉血曲线（{@code game.DownedManager}）读的是同一个口径。
         */
        public double zoneTtkSeconds() {
            ZoneStage stage = currentStage();
            if (stage != null && stage.zoneTtkSeconds() > 0.0) {
                return stage.zoneTtkSeconds();
            }
            return map == null ? GameMap.DEFAULT_ZONE_TTK_SECONDS
                    : Math.max(GameMap.MIN_ZONE_TTK_SECONDS, map.zoneTtkSeconds);
        }

        /** 清掉本圈所有玩家的伤害小数池（毒圈关闭 / 换局时调用）。 */
        void clearZoneDamagePools() {
            accumulatedZoneDamage.clear();
        }

        /**
         * [r148 TTK 伤害累加器] 每 20 tick（= 1 秒）结算一次圈外伤害。
         *
         * <p>每秒伤害是<b>反推</b>出来的：{@code damagePerSecond = 玩家最大生命值 / 当前阶段
         * 淘汰秒数}（例：第 1 阶段 20 / 250 = 0.08 点/秒，终局 20 / 8 = 2.5 点/秒；
         * 有额外血量上限的玩家按 {@code getMaxHealth()} 实际值算）。结算严格按"加法进池、
         * 攒满 1 点才扣"走：
         * <ol>
         *   <li>圈外 → {@code pool += damagePerSecond}（0.08、2.5 这样的小数先进池，不直接 hurt）；</li>
         *   <li>{@code pool >= 1.0} → {@code damageToDeal = (int) pool} 真扣血（向下取整），
         *       {@code pool -= damageToDeal} 把零头留给下一秒；</li>
         *   <li>进安全区 / 倒地 / 免伤 / 旁观创造 / 换维度 / 离场 → 池子当场清零，
         *       杜绝"边缘反复横跳攒出一大坨突然爆伤"。</li>
         * </ol>
         *
         * <p>扣血走 {@link com.paimeng.battleroyale.game#ZoneDamage} 的自定义伤害类型
         * {@code battleroyale:zone_damage}：绕过护甲、绕过受击无敌帧、无视抗性效果，
         * 所以 1 点就是 1 点，不会被盔甲或"刚被打过"吞掉。玩家因此死亡时
         * {@code ALLOW_DEATH → DownedManager.handleDeath} 照常触发（伤害源没有攻击者实体，
         * 判定为环境致死：有队友→击倒，无队友→淘汰，播报走"在安全区外停留太久"口径）。
         */
        private void applyZoneDamage(MinecraftServer server) {
            if (server == null) {
                return;
            }
            double ttk = zoneTtkSeconds();
            if (!(ttk > 0.0)) {
                accumulatedZoneDamage.clear();   // 本圈不打伤害：池子清空，别把零头带进下一阶段
                return;
            }
            final Set<UUID> inScope = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // ---- 模块六硬规则：维度不同的玩家（主城 / 下界 / 别的地图）直接跳过 ----
                if (player.level().dimension() != dimension) {
                    continue;
                }
                UUID id = player.getUUID();
                // 本局名单外的（管理员进维度配物资等）不吃毒圈伤害，也不进池。
                if (!GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                    continue;
                }
                inScope.add(id);
                if (!shouldSettleZoneDamage(player, id)) {
                    accumulatedZoneDamage.remove(id);   // 不该吃毒的状态：零头当场作废
                    continue;
                }
                if (!isOutside(player.getX(), player.getZ())) {
                    accumulatedZoneDamage.remove(id);   // 需求：进安全区直接清零
                    continue;
                }
                // [r148 反向推导] 每人每秒应加的伤害：实际上限血量 / 本阶段淘汰秒数。
                float step = (float) (player.getMaxHealth() / ttk);
                if (!(step > 0.0f)) {
                    accumulatedZoneDamage.remove(id);
                    continue;
                }
                // 小数池封顶：本步伤害的两倍与 4 点取大，横跳 / 长期免伤都炸不出爆伤。
                float poolCap = Math.max(ACCUMULATOR_FLOOR_CAP, step * 2.0f);
                float pool = accumulatedZoneDamage.getOrDefault(id, 0.0f) + step;
                if (pool > poolCap) {
                    pool = poolCap;
                }
                int damageToDeal = (int) pool;
                if (damageToDeal <= 0) {
                    accumulatedZoneDamage.put(id, pool);   // 还没攒满 1 点：只进池，不打伤害
                    continue;
                }
                accumulatedZoneDamage.put(id, pool - damageToDeal);
                // 毒圈伤害不该造成击退与受击抖动：伤害前后各清一次相关状态。
                clearKnockbackAndFrames(player);
                boolean applied = player.hurt(ZoneDamage.source(player), damageToDeal);
                clearKnockbackAndFrames(player);
                if (!applied) {
                    // 被别的免伤规则挡下了（热身无敌 / 航线无敌 / 击倒独立血量池等）：
                    // 这整数点退回池子，等能扣血的那一秒一起结，绝不"白扣"也绝不翻倍累积。
                    float refunded = Math.min(poolCap, accumulatedZoneDamage.getOrDefault(id, 0.0f)
                            + damageToDeal);
                    accumulatedZoneDamage.put(id, refunded);
                }
            }
            // 断线 / 传走 / 淘汰出本维度的玩家：池子条目随手清掉，不积累垃圾键。
            accumulatedZoneDamage.keySet().retainAll(inScope);
        }

        /**
         * 这名玩家此刻该不该结算毒圈伤害：存活、非旁观非创造、没倒地、没在无敌名单里。
         * 倒地玩家的掉血由 {@code DownedManager} 按独立血量池统一控制（不叠加、不吃无敌帧）。
         */
        private boolean shouldSettleZoneDamage(ServerPlayer player, UUID id) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                return false;
            }
            if (DownedManager.INSTANCE.isDowned(id)) {
                return false;
            }
            return !WarmupManager.INSTANCE.isInvincible(id) && !FlightManager.INSTANCE.isInvincible(id);
        }

        /** 清掉击退 / 受击抖动 / 无敌帧残留（伤害源本身已绕过无敌帧，这里只处理手感）。 */
        private static void clearKnockbackAndFrames(ServerPlayer player) {
            player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
            player.hasImpulse = false;
            player.hurtTime = 0;
            // [1.20-PORT] maxHurtTime 原版改为不可直接写：player.maxHurtTime = 0;
            player.invulnerableTime = 0;
        }

        // ----------------------------------------------------------------
        // 快照 / 事件
        // ----------------------------------------------------------------

        /** 打包当前几何状态（含白圈 / 倒计时 / 进度 / 维度），交给 ZoneSync 广播。 */
        public ZoneSnapshot snapshot() {
            // DONE（全部阶段收完）在 HUD 语义上等于「推进到底」：进度按 1.0 上报，
            // 客户端的 | 停在最右，不会在收圈后跳回最左。
            double progress = phase == ZonePhase.DONE ? 1.0 : shrinkProgress();
            // [BR-ZONE-HUD 模块四] 阶段零（第一次收缩前）全图免伤，TTK 按 0 上报，
            // 客户端的"圈外淘汰倒计时"在真正开始掉血前不会出现。
            double ttk = phaseZero ? 0.0 : zoneTtkSeconds();
            return new ZoneSnapshot(phase, stageIndex, mapId, dimensionValue,
                    blueX, blueZ, blueRadius,
                    whiteX, whiteZ, whiteRadius,
                    remainingSeconds(), progress, ttk,
                    // [r198] damagePerSecond 按"该玩家血量 / TTK"逐人算，占位 0，
                    // 由 network.ZoneSync.sendTo 在发包前 withDamagePerSecond 注入。
                    0.0);
        }

        private void publish(MinecraftServer server) {
            EventBus.INSTANCE.publish(new ZoneStageChangedEvent(
                    stageIndex, phase, currentStage(), blueX, blueZ, blueRadius, phaseZero,
                    mapId, server));
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
