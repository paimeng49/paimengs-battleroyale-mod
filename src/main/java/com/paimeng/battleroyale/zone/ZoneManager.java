package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.ZoneStageChangedEvent;
import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GameRules;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.ZonePhaseConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
 * <p>升级预留：只有 {@link ZoneRuntime#applyZoneDamage} 里一处碰易变 API
 * （DamageSource 常量取法，已标 [UPGRADE]）；ResourceKey 比对在高版本换 LevelKey，一行事。
 */
public final class ZoneManager {

    public static final ZoneManager INSTANCE = new ZoneManager();

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

    /** /paimengmods br setcenter：把圆心设到执行者所在维度的那圈（无运行时返回 false，指令层落地图 JSON）。 */
    public boolean setCenterAt(ResourceKey<Level> worldKey, double x, double z) {
        ZoneRuntime zone = runtimeAt(worldKey);
        if (zone == null) {
            return false;
        }
        zone.setCenter(x, z);
        return true;
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
            // setcenter 记录过圆心的仍然尊重（只挪超大圆的圆心，半径照样按"罩住这个
            // 圆心所在选区的最远角 × 2"算，绝不会出现开局就有人被毒的情况）。
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
                    stage = safe.toStage(i);   // 展示名用原始配置下标，和 JSON 一一对应
                }
                stages.add(stage);
                if (i > 0) {
                    table.append("; ");
                }
                table.append("config[").append(i).append("] 半径=").append((int) stage.targetRadius())
                        .append(" 等待=").append(stage.waitSeconds())
                        .append(" 缩圈=").append(stage.shrinkSeconds());
            }
            if (stages.isEmpty()) {
                return ZoneStages.defaultsFor(maxRadius);
            }
            BattleRoyaleMod.LOGGER.info("[BR-ZONE] 地图 {} 阶段表（{} 项，按 config 下标原位对齐）：{}",
                    map.mapId, stages.size(), table);
            return stages;
        }

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
        // 设置
        // ----------------------------------------------------------------

        /** setcenter：把蓝圈圆心挪到指定点（半径不变；圆太大放不下就夹回选区内）。 */
        public void setCenter(double x, double z) {
            double loX = bounds.minX() + blueRadius;
            double hiX = bounds.maxX() - blueRadius;
            double loZ = bounds.minZ() + blueRadius;
            double hiZ = bounds.maxZ() - blueRadius;
            blueX = hiX < loX ? (bounds.minX() + bounds.maxX()) / 2.0 : clamp(x, loX, hiX);
            blueZ = hiZ < loZ ? (bounds.minZ() + bounds.maxZ()) / 2.0 : clamp(z, loZ, hiZ);
        }

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

        private void applyZoneDamage(MinecraftServer server) {
            if (server == null) {
                return;
            }
            ZoneStage stage = currentStage();
            float damage = stage == null ? 0.0f : stage.damagePerSecond();
            if (damage <= 0.0f) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // ---- 模块六硬规则：维度不同的玩家（主城 / 下界 / 别的地图）直接跳过 ----
                if (player.level().dimension() != dimension) {
                    continue;
                }
                // 本局名单外的（管理员进维度配物资等）不吃毒圈伤害。
                if (!GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                    continue;
                }
                if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                    continue;   // 旁观/创造不参与毒圈
                }
                // 倒地玩家的血量由 DownedManager 按曲线统一控制（它自己会查维度），毒圈不叠加。
                if (DownedManager.INSTANCE.isDowned(player.getUUID())) {
                    continue;
                }
                if (!isOutside(player.getX(), player.getZ())) {
                    continue;
                }
                // 1.18.2 里伤害源是 DamageSource 类上的静态常量。
                // [UPGRADE] 1.19+ 改为 server.getDamageSources().magic()；
                //           1.20.1+ 改为 player.hurt(server.getDamageSources()
                //                     .create(DamageTypes.MAGIC), damage)。其余逻辑不动。
                // 毒圈伤害不应造成击退、受击抖动或无敌帧；先清理相关状态，伤害后再次清理。
                player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
                player.hasImpulse = false;
                player.hurtTime = 0;
                // [1.20-PORT] maxHurtTime 原版改为不可直接写：player.maxHurtTime = 0;
                player.invulnerableTime = 0;
                player.hurt(player.damageSources().magic(), damage);
                player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
                player.hasImpulse = false;
                player.hurtTime = 0;
                // [1.20-PORT] maxHurtTime 原版改为不可直接写：player.maxHurtTime = 0;
                player.invulnerableTime = 0;
            }
        }

        // ----------------------------------------------------------------
        // 快照 / 事件
        // ----------------------------------------------------------------

        /** 打包当前几何状态（含白圈 / 倒计时 / 进度 / 维度），交给 ZoneSync 广播。 */
        public ZoneSnapshot snapshot() {
            // DONE（全部阶段收完）在 HUD 语义上等于「推进到底」：进度按 1.0 上报，
            // 客户端的 | 停在最右，不会在收圈后跳回最左。
            double progress = phase == ZonePhase.DONE ? 1.0 : shrinkProgress();
            return new ZoneSnapshot(phase, stageIndex, mapId, dimensionValue,
                    blueX, blueZ, blueRadius,
                    whiteX, whiteZ, whiteRadius,
                    remainingSeconds(), progress);
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
