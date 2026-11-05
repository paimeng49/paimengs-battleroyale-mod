package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.mixin.ServerPlayerAccessor;
import com.paimeng.battleroyale.network.FlightSync;
import com.paimeng.battleroyale.network.ParachuteSync;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-FLIGHT] 航线与跳伞管理器（纯服务端，主线程驱动）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>航线生成</b>：进入 FLIGHT 阶段时，每个有人的游戏维度随机抽一个物资点 L 作锚点，
 *       生成一条穿过选区的直线 A→B（直线严格经过 L，垂直距离恒为 0 &lt;= 100），
 *       飞行高度固定 {@link #FLIGHT_HEIGHT}（受维度高度上限夹取）；</li>
 *   <li><b>载具数据化（[BR-MTR2]）</b>：C-130 <b>不再是 Entity</b>（仿 MTR 4.x 把车辆
 *       模拟从 Minecraft 实例分离的思路）——本类每 tick 用
 *       {@code travel = ticks × speed} 算出权威位置，只负责逻辑；位置经
 *       {@code flight_tick} 通道（serverTime + progressTicks，约 12 字节）每 tick
 *       广播给同维度所有客户端，客户端渲染与镜头各自跑同一条时间纯函数，
 *       不再有任何实体追踪包；</li>
 *   <li><b>玩家跟随（[BR-SILENT] 绝对静默）</b>：[BR-MTR] 彻底废弃原版骑乘
 *       （startRiding 全删）。<b>服务端每 tick 绝不修改玩家坐标</b>（不调 setPos、
 *       不写 prevX/Y/Z、不发位置传送包——旧版每 tick 强设坐标会把客户端上报的
 *       真实位置拉回服务端旧坐标，两端互搏 = 抽搐/区块闪烁的根源）。服务端只做
 *       维持性零功：setNoGravity + 速度清零 + fallDistance 清零 + 豁免对重申；
 *       玩家的真实坐标完全由装了本模组的客户端每 tick 钉到纯函数点后上报
 *       （见 ModKeyBindings#lockOntoPlane），服务端只管收移动包、绝不拉回；
 *       唯一例外是偏离飞机公式位置超过 {@link #STRAGGLER_PULLBACK_DIST} 格、
 *       且连续 {@link #STRAGGLER_STRIKE_TICKS} tick 的掉队保险（advancePlayers），
 *       平时绝对不管（客户端与服务端时钟有恒定相位差，短距离偏差是常态）；
 *       独立无敌集合（mixin 查 {@link #isInvincible}），机上期间 setInvisible
 *       隐藏玩家模型（不挡机舱内视线）；
 *       机上另挂“豁免对”：{@code abilities.mayfly=true}（防原版
 *       “floating too long” 4 秒踢线）+ mixin 写 {@code inTeleportationState=true}
 *       （防原版 “moved wrongly” 每 tick 橡皮筋回弹——正是客户端上报坐标
 *       不被拉回的关键），下机全部归还；</li>
 *   <li><b>跳伞</b>：收到 C2S 跳伞包（[BR-MTR] 客户端按键 = <b>左 Shift 或 F 键</b>，
 *       物理直查，不碰原版任何按键注册表）后解除坐标锁定、恢复重力、
 *       解除无敌、施加 60 秒缓降 + 向前推力；</li>
 *   <li><b>终点强制跳伞（[BR-PLANE-END]）</b>：飞机到达终点 B 的瞬间，仍锁定在机上
 *       的玩家被逐个执行与按键跳伞完全相同的开伞流程（解除锁定 + 恢复重力 +
 *       60 秒缓降 + 落地前无敌防摔死），聊天栏提示“已到达航线终点，强制跳伞！”；</li>
 *   <li><b>落地</b>：isOnGround 立刻清除缓降；强制踢下的玩家落地时才解除无敌；</li>
 *   <li><b>收尾</b>：全员落地（或超时）后广播航线结束，并驱动 FLIGHT → RUNNING。</li>
 * </ul>
 *
 * <p>线程模型：{@link #begin}/{@link #tick} 只在服务端主线程跑；
 * 断线清理（{@link #handleDisconnect}）可能在 netty 线程回调，
 * 因此只允许动 ConcurrentHashMap / newKeySet 这类并发容器，玩家实体操作
 * 一律挪回主线程 tick 里做（玩家离线后 {@code getPlayer} 返回 null 自然被剔除）。
 */
public final class FlightManager {
    public static final FlightManager INSTANCE = new FlightManager();

    /** 需求指定的飞行高度（世界 Y=315，1.18 主世界建筑高度上限 320）。 */
    public static final double FLIGHT_HEIGHT = 315.0;
    /**
     * [BR-R89-FIX → BR-FLIGHT-SPEED] 兜底水平速度：4 格/tick = 80 格/秒。
     * r115 起实际速度优先读地图配置 {@code GameMap.flightSpeed}（GUI 可调 0.5~20），
     * 仅当配置缺失 / 坏值（NaN、越界）时退回本常量，绝不会再出现除以 0 或乱飞。
     */
    private static final double BLOCKS_PER_TICK = GameMap.DEFAULT_FLIGHT_SPEED;

    /**
     * [BR-FLIGHT-SPEED] 取本局生效的飞机速度：地图配置合法就用配置值，
     * 坏值 / 没配过（旧地图）退回 {@link #BLOCKS_PER_TICK} 兜底。
     */
    private static double effectiveFlightSpeed(GameMap map) {
        double v = map == null ? 0.0 : map.flightSpeed;
        return Double.isFinite(v) && v >= GameMap.MIN_FLIGHT_SPEED && v <= GameMap.MAX_FLIGHT_SPEED
                ? v : BLOCKS_PER_TICK;
    }
    /**
     * [BR-ROLLBACK] 坐标锁定的纵向偏移：把玩家钉在飞机坐标上方 1.5 格，
     * 防止人卡进机身碰撞箱（本机没有碰撞，防的是与渲染/摄像头的穿插）。
     */
    private static final double PLANE_LOCK_Y_OFFSET = 1.5;
    /**
     * [BR-SILENT2] 掉队保险距离（格）：机上玩家上报坐标偏离飞机公式位置超过
     * 此值<b>并且连续超过 {@link #STRAGGLER_STRIKE_TICKS} tick</b>才强行拉回一次。
     *
     * <p><b>为什么从 30 提到 120</b>：装了本模组的客户端把坐标钉在
     * {@code f(clientWorld.getGameTime())} 上，服务端算的是 {@code f(flight.ticks)}——
     * 两个时钟之间有网络与客户端帧率造成的<b>恒定相位差</b>。r107 的 latest.log 实测：
     * 玩家稳定落在飞机后方 26~30 格（≈7.5 tick × 4 格），正好压在旧的 30 格线上，
     * 于是"掉队保险"每秒开火七八次，每次 {@code teleport} 都把客户端刚钉好的坐标
     * 拽走、下一 tick 客户端又钉回来——<b>这就是剩下的抽搐与区块闪烁</b>
     * （传送还会让客户端重拉一遍周边区块）。真掉队（没装模组的客户端）是每 tick
     * 漂 4 格、几秒内冲到几百格，120 格照样兜得住。
     */
    private static final double STRAGGLER_PULLBACK_DIST = 120.0;
    /**
     * [BR-SILENT2] 连续超距多少 tick 才允许拉回（2 秒）。相位差是恒定的，
     * 装了模组的客户端永远攒不满；真掉队的人攒满也只多漂 2 秒。
     */
    private static final int STRAGGLER_STRIKE_TICKS = 40;
    /** 跳伞后的缓降时长：60 秒。 */
    private static final int SLOW_FALLING_TICKS = 60 * GameRules.TICKS_PER_SECOND;
    /** 飞机到终点后的落地硬超时（20 秒），防止有人卡在空里把阶段卡死。 */
    private static final int LAND_TIMEOUT_TICKS = 20 * GameRules.TICKS_PER_SECOND;
    /** 航线与物资点的最大垂直距离（需求值）。 */
    private static final double MAX_LOOT_LINE_DISTANCE = 100.0;
    /** 物资点锚点的选区内缩边距：锚点必须严格在选区内。 */
    private static final int REGION_INSET = 4;
    /** [BR-PLANE-BOUNDS] 每多少 tick 打印一次飞机实时坐标（20 = 每秒一条）。 */
    private static final int BOUNDS_LOG_INTERVAL = 20;

    private static final Random RANDOM = new Random();

    /** 当前在飞的航线（一个游戏维度一条）。只在主线程读写。 */
    private final List<Flight> flights = new ArrayList<>();
    /**
     * [BR-AIRDROP 模块三] 每维度最近一次开局使用的航线快照：FLIGHT 收尾后仍保留，
     * 空投 C-130 直接复用同一条 A→B 航线（含速度与高度）。下一局 warmup 重新
     * begin 时会被新航线覆盖；对局从不手工清空。
     */
    private final Map<ResourceKey<Level>, RouteLine> lastRoutes = new ConcurrentHashMap<>();
    /**
     * 热身倒计时开始时就生成的航线预告。进入 FLIGHT 时按维度取出复用，
     * 绝不重新随机，保证玩家看到的路线和飞机实际路线完全一致。
     */
    private final Map<ResourceKey<Level>, Flight> pendingFlights = new LinkedHashMap<>();
    /** 航线阶段 / 未跳伞坠地前的无敌名单。mixin 查它。 */
    private final Set<UUID> flightInvincible = ConcurrentHashMap.newKeySet();
    /**
     * [BR-PLANE-BOUNDS] 当前被坐标锁定在飞机上的玩家。航线端点外延到选区外后，
     * GameWorldManager.tickBoundary 不能再每 tick 把机上玩家拽回边界（否则和
     * FlightManager 的每 tick 传送互相拉扯，玩家会来回瞬移），它查这个名单放行。
     */
    private final Set<UUID> planeLocked = ConcurrentHashMap.newKeySet();
    /** 已跳伞（缓降中）等落地的名单：落地瞬间清缓降效果。 */
    private final Set<UUID> gliding = ConcurrentHashMap.newKeySet();
    /**
     * [BR-MTR] 每名锁定玩家登机前的 {@code abilities.mayfly} 原值。
     * 并发 Map：{@link #handleDisconnect} 可能在 netty 线程回来归还，绝不能碰
     * 主线程的 flights 列表，所以原值不放进 PlayerFlight 而放这里。
     */
    private final Map<UUID, Boolean> hadAllowFlying = new ConcurrentHashMap<>();
    /** 阶段是 FLIGHT 但一条航线都没有时的空转计数（超时直接放行进 RUNNING）。 */
    private int idleTicks;

    private FlightManager() {
    }

    /** 航线阶段 / 落地前无敌（供 LivingEntityMixin 拦截伤害）。 */
    public boolean isInvincible(UUID playerId) {
        return playerId != null && flightInvincible.contains(playerId);
    }

    /**
     * [BR-PLANE-BOUNDS] 玩家当前是否被坐标锁定在飞机上。
     * 供 GameWorldManager.tickBoundary 放行：航线端点在选区外延段时，
     * 不能把机上玩家每 tick 往边界里拽（否则和坐标锁定互相拉扯、来回瞬移）。
     */
    public boolean isPlaneLocked(UUID playerId) {
        return playerId != null && planeLocked.contains(playerId);
    }

    /**
     * [BR-AIRDROP 模块三 2] 取某维度最近一次开局生成的航线（A→B、速度、高度全套）。
     * 本局还没跑过 FLIGHT（或从未进过游戏维度）时返回 null，调用方自行跳过空投。
     */
    public RouteLine routeFor(ResourceKey<Level> worldKey) {
        return worldKey == null ? null : lastRoutes.get(worldKey);
    }

    /**
     * 热身倒计时开始时提前生成并公布航线。只生成一次，FLIGHT 阶段直接复用。
     */
    public void publishRoutes(MinecraftServer server) {
        clearPendingRoutes(server);
        if (server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!GameWorldManager.isGameWorld(key)) {
                continue;
            }
            GameMap map = GameWorldManager.mapForWorld(key);
            if (map == null) {
                continue;
            }
            Flight flight = buildFlight(world, map);
            if (flight == null) {
                BattleRoyaleMod.LOGGER.warn("[BR-C130] 地图 {} 选区过小，无法提前生成航线", map.mapId);
                continue;
            }
            pendingFlights.put(key, flight);
            for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
                // [BR-MTR] 预热预告只画地图线：startTick/speed 传 0，飞机实体还不存在，
                // 客户端纯函数保持未激活，等 FLIGHT 开始的正式包再启用。
                FlightSync.sendRoute(player, true, flight.mapId,
                        flight.ax, flight.az, flight.bx, flight.bz,
                        0L, 0.0, flight.flightY);
            }
            BattleRoyaleMod.LOGGER.info("[BR-C130] 热身倒计时开始，地图 {} 航线已提前公布：A({},{}) -> B({},{})",
                    flight.mapId, fmt(flight.ax), fmt(flight.az), fmt(flight.bx), fmt(flight.bz));
        }
    }

    /**
     * 给倒计时中途加入或重连的玩家补发航线：优先补预告线（startTick=0，
     * [BR-MTR2] 纯函数未激活，只画地图线）；若 FLIGHT 已开始，补在飞的真实航线
     * （带 speed/flightY，客户端等第一发 flight_tick 包即在本地时间轴锚定相位）。
     */
    public void sendPendingRoute(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Flight flight = pendingFlights.get(player.level().dimension());
        if (flight == null) {
            for (Flight active : flights) {
                if (active.worldKey.equals(player.level().dimension())) {
                    flight = active;
                    break;
                }
            }
        }
        if (flight != null) {
            FlightSync.sendRoute(player, true, flight.mapId,
                    flight.ax, flight.az, flight.bx, flight.bz,
                    flight.started ? flight.startTick : 0L,
                    // [BR-FLIGHT-SPEED] 补发在飞航线时下发该航线实际生效的地图配置速度
                    flight.started ? flight.speed : 0.0,
                    flight.flightY);
        }
    }

    /** 清除预告航线；取消倒计时时同步 active=false，避免客户端残留旧线。 */
    public void clearPendingRoutes(MinecraftServer server) {
        if (server != null) {
            for (Flight flight : pendingFlights.values()) {
                ServerLevel world = server.getLevel(flight.worldKey);
                if (world != null) {
                    for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
                        FlightSync.sendRoute(player, false, flight.mapId, 0, 0, 0, 0, 0L, 0.0, 0.0);
                    }
                }
            }
        }
        pendingFlights.clear();
    }

    // ==================================================================
    // 生命周期：进入 / 离开 FLIGHT
    // ==================================================================

    /** 热身倒计时结束进入航线阶段：为每个有人的游戏维度生成航线并拉起玩家。 */
    public void begin(MinecraftServer server) {
        endMatch(server); // 上一局兜底清理（正常应已空）
        idleTicks = 0;
        if (server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!GameWorldManager.isGameWorld(key)) {
                continue;
            }
            GameMap map = GameWorldManager.mapForWorld(key);
            List<ServerPlayer> present = new ArrayList<>(world.getPlayers(p -> true));
            if (map == null || present.isEmpty()) {
                continue;
            }
            Flight flight = pendingFlights.remove(key);
            boolean reused = flight != null;
            if (flight == null) {
                // 兜底：若某条管理路径直接切入 FLIGHT，仍现场生成一条可用航线。
                flight = buildFlight(world, map);
            }
            if (flight == null) {
                BattleRoyaleMod.LOGGER.warn("[BR-FLIGHT] 地图 {} 选区过小，无法生成航线，本维度玩家直接放行落地流程",
                        map.mapId);
                continue;
            }
            flights.add(flight);
            // [BR-AIRDROP 模块三] 留存本局航线快照：毒圈中后期的空投 C-130 复用同一条 A→B。
            lastRoutes.put(key, new RouteLine(flight));
            // [BR-MTR2] 纯函数时钟原点：以本维度当前 world.getGameTime() 为准随航线包下发。
            // 该值仅作日志/诊断参考——客户端不信任跨纪元的时间戳，第一发
            // flight_tick 权威进度包到达即在自己的时间轴上重新锚定
            // （ClientFlightState#applyTick），跨端时区差被从根上消掉。
            flight.startTick = world.getGameTime();
            flight.started = true;
            for (ServerPlayer player : present) {
                flight.players.put(player.getUUID(), new PlayerFlight());
                startFollowing(world, player, flight);
                // [BR-FLIGHT-SPEED] 正式航线包把地图配置读出的速度一并下发，
                // 客户端 ClientFlightState 收到即用（travel = speed × tick），无需改客户端代码。
                FlightSync.sendRoute(player, true, flight.mapId,
                        flight.ax, flight.az, flight.bx, flight.bz,
                        flight.startTick, flight.speed, flight.flightY);
            }
            BattleRoyaleMod.LOGGER.info(
                    "[BR-FLIGHT] 地图 {} 航线开始（{}）：A({},{}) -> B({},{})，Y={}，长度 {} 格，"
                            + "速度 {} 格/tick（地图配置），{} 秒，{} 人；{}",
                    flight.mapId, reused ? "复用倒计时提前公布的航线" : "现场生成", fmt(flight.ax), fmt(flight.az), fmt(flight.bx), fmt(flight.bz),
                    (int) flight.flightY, (int) flight.length, fmt(flight.speed),
                    flight.totalTicks / GameRules.TICKS_PER_SECOND,
                    flight.players.size(),
                    flight.lootName == null
                            ? "选区内没有物资点，航线穿过选区中心"
                            : "途经物资点「" + flight.lootName + "」垂直距离 "
                                    + (int) flight.lootDistance + " 格（要求 <= " + (int) MAX_LOOT_LINE_DISTANCE + "）");
        }
        if (flights.isEmpty()) {
            BattleRoyaleMod.LOGGER.warn("[BR-FLIGHT] 没有任何游戏维度有玩家，航线空转，稍后自动进入进行中阶段");
        }
    }

    // ==================================================================
    // 每 tick 推进
    // ==================================================================

    /** 由 ServerTickEvents.END_SERVER_TICK 驱动（阶段不是 FLIGHT 也要跑：落地清缓降是跨阶段的）。 */
    public void tick(MinecraftServer server) {
        tickGliding(server);
        if (server == null) {
            return;
        }
        if (GameManager.INSTANCE.getPhase() != GamePhase.FLIGHT) {
            if (!flights.isEmpty()) {
                // 被 br stop 等手段中途掐掉：清标记 + 通知客户端收起航线
                endMatch(server);
            }
            return;
        }
        if (flights.isEmpty()) {
            // 一条航线都没有（维度里没人 / 选区太小）：给 2 秒宽限后直接推进，状态机不死锁
            if (++idleTicks >= 2 * GameRules.TICKS_PER_SECOND) {
                GameManager.INSTANCE.beginRunning(server);
            }
            return;
        }
        idleTicks = 0;
        for (Iterator<Flight> it = flights.iterator(); it.hasNext(); ) {
            Flight flight = it.next();
            ServerLevel world = server.getLevel(flight.worldKey);
            if (world == null) {
                dropFlight(flight);
                it.remove();
                continue;
            }
            flight.ticks++;
            // [BR-PLANE-BOUNDS] 进度硬夹取：travel 必须是 [0, length] 内的有限值，
            // 绝不越过终点 B（NaN / 溢出 / 任何异常都按 0 处理，宁可不飞不能乱飞）。
            double travel = flight.ticks * flight.speed; // [BR-FLIGHT-SPEED] 读地图配置速度，不再写死
            if (!Double.isFinite(travel) || travel < 0.0) {
                BattleRoyaleMod.LOGGER.warn("[BR-PLANE-BOUNDS] 地图 {} 进度异常（ticks={}，travel={}），按 0 夹取",
                        flight.mapId, flight.ticks, travel);
                travel = 0.0;
            }
            if (travel > flight.length) {
                travel = flight.length;
            }
            double cx = flight.ax + flight.dirX * travel;
            double cz = flight.az + flight.dirZ * travel;
            // [BR-R89-FIX] 终点判定：只认几何终点 B（totalTicks 已无时长上限，
            // 与 reachedB 同一 tick 触发，仅作为双保险保留）。中途绝不提前赶人。
            boolean reachedB = travel >= flight.length - 1.0E-6;
            if (!flight.planeEnded
                    && (reachedB || flight.ticks >= flight.totalTicks)) {
                flight.planeEnded = true;
                flight.planeEndedAtTick = flight.ticks;
                forceParachuteAtEnd(server, flight);
                BattleRoyaleMod.LOGGER.info(
                        "[BR-FLIGHT] 地图 {} 飞机到达终点 B({},{})（用时 {} 秒）：已收机，未跳伞玩家强制开伞缓降",
                        flight.mapId, fmt(flight.bx), fmt(flight.bz),
                        flight.ticks / GameRules.TICKS_PER_SECOND);
            }
            if (!flight.planeEnded && flight.ticks % BOUNDS_LOG_INTERVAL == 0) {
                BattleRoyaleMod.LOGGER.info("[BR-PLANE-BOUNDS] 地图 {} 飞机 tick {}：位置({}, {}, {})，进度 {}/{} 格",
                        flight.mapId, flight.ticks, fmt(cx), (int) flight.flightY, fmt(cz),
                        (int) travel, (int) flight.length);
            }
            // [BR-MTR2] 载具数据化：本 tick 的权威进度广播给同维度所有客户端
            // （飞行中 = 已推进 tick 数；到终点 = PLANE_FINISHED 哨兵，只发一次）。
            // 这是纯函数对表 / 软校正的唯一权威来源，替代了原来的实体追踪包。
            syncFlightTick(world, flight);
            advancePlayers(server, flight, cx, cz);
            if (allSettled(flight) || landTimedOut(flight)) {
                completeFlight(server, world, flight);
                it.remove();
            }
        }
        if (flights.isEmpty()) {
            // 全部航线完成：收尾进 RUNNING（毒圈 / 统计由既有订阅自动接棒）
            GameManager.INSTANCE.beginRunning(server);
        }
    }

    /** 缓降落地检测（跨阶段）：isOnGround 立刻清除 SLOW_FALLING 与所有航线残留标记。 */
    private void tickGliding(MinecraftServer server) {
        for (Iterator<UUID> it = gliding.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            ServerPlayer player = server == null ? null
                    : server.getPlayerList().getPlayer(id);
            if (player == null) {
                it.remove(); // 断线：下轮登录走 ensureCleanJoin
                continue;
            }
            if (!player.isAlive()) {
                // [BR-PARA-SYNC] 缓降中死亡/淘汰：收掉降落伞状态，防止旁观尸体顶着鬼影伞。
                player.removeEffect(MobEffects.SLOW_FALLING);
                ParachuteSync.broadcast((ServerLevel) player.level(), id, false);
                it.remove();
                continue;
            }
            if (player.onGround()) {
                player.removeEffect(MobEffects.SLOW_FALLING);
                // [BR-PLANE-END] 终点强制踢下的玩家可能还挂在无敌名单里（航线已
                // 因落地超时而收尾、人却还在半空）：真正落地这一刻才一并解除。
                flightInvincible.remove(id);
                planeLocked.remove(id);
                ParachuteSync.broadcast((ServerLevel) player.level(), id, false);
                it.remove();
            }
        }
    }

    /**
     * 把一名玩家挂到航线上：[BR-MTR] 参考 MTR 的登机油路——无敌 + 零重力 + 隐藏模型 +
     * 原版检查豁免（飞行权限位，防 floating 踢出）+ 一次性传送到“飞机”起点上方 1.5 格。
     * 此后进入 [BR-SILENT] 绝对静默期：{@link #lockToPlane} 每 tick 只做维持性零功，
     * <b>不再改玩家坐标</b>——跟随位置完全由客户端纯函数钉好上报（见
     * ModKeyBindings#lockOntoPlane），全程零骑乘、零服务端回拉。
     */
    private void startFollowing(ServerLevel world, ServerPlayer player, Flight flight) {
        flightInvincible.add(player.getUUID());
        planeLocked.add(player.getUUID());
        // 记录原值，下机归还（creative 玩家不受损）
        hadAllowFlying.put(player.getUUID(), player.getAbilities().mayfly);
        grantVanillaCheckExemptions(player);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setInvisible(true); // 机上隐藏玩家模型，不挡机舱内视线（跳伞时恢复）
        // [BR-FLIGHT-VANISH] 再叠一层原版隐身效果：setInvisible 只改实体标记，
        // 玩家装备/手持物品在部分客户端时机仍会显形；MobEffects.INVISIBILITY 会随
        // 实体同步包完整广播，中途进场的旁观者也能立刻看到"空机舱"。
        // ambient=false + visible=false = 不显示粒子（隐藏粒子）；时长按"无限"给
        // （999999 tick ≈ 13 小时，航线实际只有几分钟，下机立刻精准移除）。
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 999999, 0, false, false));
        try {
            // [r124] 角度防呆：登机传送必须原样带上玩家当前的 yaw / pitch（1.20.1 映射
            // getYRot=水平转角、getXRot=俯仰）。旧版这里俯仰写死 0.0F，teleportTo 会用
            // 传入角度整体覆盖客户端视角（teleportTo 内部还会 setYHeadRot(yaw)），
            // 落地表现就是"上飞机后鼠标转不动、视角被锁死"。绝不再写死任何角度。
            player.teleportTo(world, flight.ax, flight.flightY + PLANE_LOCK_Y_OFFSET, flight.az,
                    player.getYRot(), player.getXRot());
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-FLIGHT] {} 拉上航线失败：{}", player.getName().getString(), t.toString());
        }
    }

    /**
     * [BR-MTR] 登机豁免对（MTR 的 mayfly + PlayerTeleportationStateAccessor 同款）：
     * <ol>
     *   <li>{@code abilities.mayfly = true}（只设服务端副本，不发同步包，客户端
     *       不知道也开不了飞）——豁免 {@code ServerPlayNetworkHandler} 的
     *       {@code "floating too long"} 检查：空中滞留、Y 位移≈0、onGround=false 的
     *       玩家 4 秒就会被“Flying is not enabled on this server”踢出，锁定态正是
     *       它的教科书目标；</li>
     *   <li>{@code ServerPlayer.isChangingDimension = true}（official 名，Yarn 里叫
     *       inTeleportationState；[r127]，替代旧
     *       {@code awaitingPositionFromClient} 方案）——豁免 {@code "moved too
     *       quickly"}（100 格阈值）与 {@code "moved wrongly"}（0.0625 容差橡皮筋）：
     *       客户端上报的锁定坐标天然滞后飞机 1~2 tick（≈4~8 格），不豁免每 tick
     *       都会被服务端 requestTeleport 回弹（历史上“飞机/玩家疯狂抽搐”的服务端
     *       一侧根因）。与 awaiting 的本质区别：该字段为 true 时原版<b>照常处理
     *       移动包、照常更新 Yaw/Pitch</b>，只跳过两段距离校验。</li>
     * </ol>
     * 两者都必须每 tick 重申（见 {@link #lockToPlane}）：登机传送的原版
     * teleport-confirm 握手中 {@code hasChangedDimension()} 会把 inTeleportationState
     * 翻回 false，其它逻辑（setGameMode 等）也可能改写 mayfly；重申只走“跳过校验”
     * 分支，不会吞掉任何客户端输入。
     */
    private void grantVanillaCheckExemptions(ServerPlayer player) {
        // putIfAbsent 兜底：中途重连回游戏维度的玩家走的是 lockToPlane 直连路径，
        // 这里以“改写前的当前值”自动补登原值（生存=false，创造/旁观=true），
        // release 时才能准确归还，绝不出现 allowFlying 永久残留。
        hadAllowFlying.putIfAbsent(player.getUUID(), player.getAbilities().mayfly);
        player.getAbilities().mayfly = true;
        // [r127] 橡皮筋豁免改用实体侧 inTeleportationState（1.20.1 仍在，原版两处
        // 移动距离校验都跳过它），绝不再写 awaitingPositionFromClient——那个字段
        // 会让 handleMovePlayer 把移动包整包丢弃（含 Yaw/Pitch），每 20 tick 还会
        // 用冻结的服务端 yaw 补发 teleport，正是“飞机上视角锁死正东”的根源。
        ((ServerPlayerAccessor) player).battleroyale$setInTeleportationState(true);
    }

    /**
     * [BR-MTR] 下机归还物理：所有离开锁定的出口（跳伞 / 掉线 / 跨维度 / 收尾兜底）
     * 一次性恢复 allowFlying（按登机前原值）与 inTeleportationState。
     * MTR 的教训：漏恢复任意一项 = 玩家“永远飘着”或“永远免橡皮筋”的脏状态。
     */
    private void releaseVanillaCheckExemptions(ServerPlayer player) {
        // Map 里没记录 = 没登机过 / 早已归还：整体 no-op。绝不无脑写
        // inTeleportationState=false，免得干扰原版登录传送握手的状态机。
        Boolean had = hadAllowFlying.remove(player.getUUID());
        if (had == null) {
            return;
        }
        player.getAbilities().mayfly = had;
        if (!had) {
            // 防呆：锁定窗口内服务端 allowFlying=true，伪造客户端可能借机自报
            // START_FLYING 把 flying 位点起来；归还原值时一并清掉，防止白嫖创造飞行。
            player.getAbilities().flying = false;
        }
        // [r127] 归还实体侧移动校验豁免；不再通过 awaitingPositionFromClient 维持机上状态。
        ((ServerPlayerAccessor) player).battleroyale$setInTeleportationState(false);
    }

    /**
     * [BR-MTR2] 每 tick 广播权威进度（替代整条实体追踪链路）：
     * payload 只有 (long serverTime, int progressTicks)，约 12 字节 / 玩家 / tick，
     * 比原版每 tick 的位移包还小一个量级。到终点后发一次 {@link FlightSync#PLANE_FINISHED}
     * 哨兵（客户端收机 + 停镜头锁定，与服务端同 tick 强制开伞对齐）并停止发送。
     */
    private void syncFlightTick(ServerLevel world, Flight flight) {
        if (flight.endedSyncSent && flight.planeEnded) {
            return;
        }
        if (!flight.planeEnded) {
            FlightSync.broadcastTick(world, world.getGameTime(), flight.ticks);
            return;
        }
        if (!flight.endedSyncSent) {
            flight.endedSyncSent = true;
            FlightSync.broadcastTick(world, world.getGameTime(), FlightSync.PLANE_FINISHED);
        }
    }

    /**
     * [BR-PLANE-END] 终点强制跳伞：遍历所有仍锁定在飞机上（未跳伞）的玩家，
     * 逐个走与按键跳伞完全相同的开伞核心（解除坐标锁定 → 恢复重力 →
     * 60 秒缓降 → 免摔死保护保留到落地），绝不让人跟着飞机一起消失或悬空。
     */
    private void forceParachuteAtEnd(MinecraftServer server, Flight flight) {
        int kicked = 0;
        for (Map.Entry<UUID, PlayerFlight> entry : flight.players.entrySet()) {
            PlayerFlight pf = entry.getValue();
            if (pf.jumped || pf.landed) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue; // 已离线：advancePlayers 会摘除条目
            }
            parachute(player, flight, pf, true);
            kicked++;
        }
        if (kicked > 0) {
            BattleRoyaleMod.LOGGER.info("[BR-PLANE-END] 地图 {} 终点强制 {} 名未跳伞玩家开伞", flight.mapId, kicked);
        }
    }

    /**
     * 开伞核心（按键跳伞与终点强制共用同一套，行为完全一致）。
     *
     * <p>[BR-ROLLBACK] 跳伞防呆：无论哪条路径进来，都完整走
     * 解除坐标锁定 → 恢复可见 → 解除 noGravity → 清零残余速度 → 60 秒缓降 + 水平推力，
     * 绝不出现“人还挂在已消失的飞机上”或“重力没恢复悬在半空”的中间态。
     *
     * @param forcedKick true = 终点强制踢人：无敌保留到落地再摘（防摔死双保险）；
     *                   false = 玩家主动跳伞：立刻解除无敌，
     *                   靠 SLOW_FALLING + 每 tick 清 fallDistance 免摔。
     */
    private void parachute(ServerPlayer player, Flight flight, PlayerFlight pf, boolean forcedKick) {
        pf.jumped = true;
        pf.forcedKick = forcedKick;
        planeLocked.remove(player.getUUID()); // 先解除坐标锁定，之后不再被传送回飞机
        // [BR-MTR] 与恢复重力同步，一次性归还全部豁免（漏一项 = 橡皮筋免疫/免踢出残留）
        releaseVanillaCheckExemptions(player);
        // [BR-ROLLBACK] 没有骑乘就没有 stopRiding：恢复可见模型 + 恢复重力即可。
        player.setInvisible(false);
        // [BR-FLIGHT-VANISH] 跳伞（离开飞机）：精准移除航线隐身。只 removeEffect
        // INVISIBILITY 这一条，绝不 clear 全部效果——玩家自己另外挂的药效不受牵连。
        player.removeEffect(MobEffects.INVISIBILITY);
        player.setNoGravity(false);           // 恢复重力
        player.setDeltaMovement(new Vec3(flight.dirX * 0.1, 0.0, flight.dirZ * 0.1)); // 清掉锁定残余速度后给前冲推力（高飘参数不变）
        player.fallDistance = 0.0F;
        if (!forcedKick) {
            flightInvincible.remove(player.getUUID());
        }
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
                SLOW_FALLING_TICKS, 0, false, true));
        gliding.add(player.getUUID());
        // [BR-PARA-SYNC] 开伞状态按 UUID 广播给同维度<b>所有</b>客户端（原版药效同步
        // 之外再叠一条模组自己的权威通道），其他玩家客户端渲染降落伞不再只依赖药效包。
        ParachuteSync.broadcast((ServerLevel) player.level(), player.getUUID(), true);
        player.displayClientMessage(BrText.of(forcedKick
                ? "已到达航线终点，强制跳伞！"
                : "已跳伞！60 秒缓降中，可用方向键调整落点，落地自动恢复。"), true);
        BattleRoyaleMod.LOGGER.info("[BR-FLIGHT] {} {}（航线进度 {}/{} tick）",
                player.getName().getString(), forcedKick ? "到达终点被强制踢下飞机开伞" : "跳伞",
                flight.ticks, flight.totalTicks);
    }

    /**
     * [BR-SILENT] <b>绝对静默锁定</b>：机上期间服务端<b>绝不修改玩家坐标</b>——
     * 不调 {@code setPos}、不写 {@code prevX/Y/Z}、不发任何位置传送包。
     * 旧版每 tick {@code setPos} 强设服务端权威坐标，会把客户端每 tick 上报的
     * 真实（纯函数）坐标拉回服务端旧坐标，两端正面互搏——这就是玩家/飞机抽搐、
     * 区块闪烁的根源。现在玩家的真实坐标完全交给客户端
     * （ModKeyBindings#lockOntoPlane）去设置，服务端只做维持性零功：
     * <ol>
     *   <li>{@code setNoGravity(true)}：空中不下坠；</li>
     *   <li>{@code reaffirmFlightExemption}：轻量重申<b>实体侧</b>豁免对
     *       （mayfly 防 floating 踢出 + {@code inTeleportationState} 防
     *       "moved wrongly / moved too quickly" 橡皮筋——<b>这正是客户端上报坐标
     *       不被拉回的关键</b>；[r127] 绝不再走 awaitingPositionFromClient，
     *       那个字段会整包吞掉移动/视角包）；</li>
     *   <li>{@code setDeltaMovement(ZERO)} + {@code fallDistance=0}：残余速度与摔落清零。</li>
     * </ol>
     * 唯一例外是 {@link #advancePlayers} 里"偏离超过 {@link #STRAGGLER_PULLBACK_DIST}
     * 格且连续 {@link #STRAGGLER_STRIKE_TICKS} tick"的掉队保险——正常在机上的人
     * 因为客户端/服务端时钟相位差会恒定漂在飞机后方二三十格，永远攒不满这个条件。
     */
    private void lockToPlane(ServerPlayer player, Flight flight) {
        player.setNoGravity(true);
        reaffirmFlightExemption(player);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    /**
     * [r127] 每 tick 轻量重申“实体侧”豁免（替代旧版每 tick 调用
     * {@code grantVanillaCheckExemptions}）：只写 mayfly 与
     * {@code ServerPlayer.isChangingDimension}（Yarn 名 inTeleportationState）两个字段。
     *
     * <p>为什么必须重申：登机 {@code teleportTo} 的原版握手完成后，
     * {@code handleTeleportConfirm → hasChangedDimension()} 会把 inTeleportationState
     * 翻回 false（1.20.1 字节码已核实），不重申则“moved too quickly /
     * moved wrongly”校验立刻恢复、每 tick 橡皮筋。
     *
     * <p>为什么重申它是安全的：该标志在 {@code handleMovePlayer} 里<b>只包住两段
     * 距离校验</b>——位置采纳、{@code player.move()} 与 Yaw/Pitch 更新全部照常执行，
     * 不丢弃任何客户端输入，也不触发 20-tick teleport resend（那是
     * {@code awaitingPositionFromClient} 的状态，本模组全程不写它）。
     */
    private void reaffirmFlightExemption(ServerPlayer player) {
        player.getAbilities().mayfly = true;
        ((ServerPlayerAccessor) player).battleroyale$setInTeleportationState(true);
    }


    /** 每 tick 推进航线上的所有玩家。[BR-ROLLBACK] 未跳伞玩家每 tick 强制坐标锁定。 */
    private void advancePlayers(MinecraftServer server, Flight flight, double cx, double cz) {
        for (Iterator<Map.Entry<UUID, PlayerFlight>> it = flight.players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerFlight> entry = it.next();
            UUID id = entry.getKey();
            PlayerFlight pf = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !player.isAlive()) {
                flightInvincible.remove(id);
                planeLocked.remove(id);
                gliding.remove(id);
                if (player != null) {
                    // 实体还在（只是死了）：必须归还豁免——重生会 copyFrom 旧实体的
                    // PlayerAbilities，allowFlying=true 会被带进新命。
                    releaseVanillaCheckExemptions(player);
                    // [BR-FLIGHT-VANISH] 死亡/离场的实体还在：一并精准收回航线隐身。
                    player.removeEffect(MobEffects.INVISIBILITY);
                    // [BR-PARA-SYNC] 死亡/淘汰：向同维度所有客户端收掉他的降落伞。
                    ParachuteSync.broadcast((ServerLevel) player.level(), id, false);
                } else {
                    hadAllowFlying.remove(id);
                }
                it.remove();
                continue;
            }
            if (!player.level().dimension().equals(flight.worldKey)) {
                // br leave / 被传送出维度：交还重力与无敌、通知他那个客户端收起航线，再摘除
                player.setNoGravity(false);
                releaseVanillaCheckExemptions(player);
                // [BR-FLIGHT-VANISH] 离开航线维度：精准收回航线隐身。
                player.setInvisible(false);
                player.removeEffect(MobEffects.INVISIBILITY);
                flightInvincible.remove(id);
                planeLocked.remove(id);
                FlightSync.sendRoute(player, false, flight.mapId, 0, 0, 0, 0, 0L, 0.0, 0.0);
                it.remove();
                continue;
            }
            if (pf.landed) {
                continue;
            }
            if (!pf.jumped && !flight.planeEnded) {
                // [BR-SILENT] 绝对静默锁定：服务端每 tick 只做零功（无重力、豁免重申、
                // 清速度、清 fallDistance），<b>绝不碰玩家坐标</b>。真实坐标完全由
                // 客户端（ModKeyBindings#lockOntoPlane）每 tick 钉到纯函数点上上报。
                lockToPlane(player, flight);
                // [BR-SILENT2] 掉队保险：偏离超过 120 格、且连续 40 tick 都这么远，
                // 才拉回一次。客户端与服务端的时钟相位差是恒定的（实测 26~30 格），
                // 旧版 30 格阈值正好压在这条线上，每 tick 传送一次 = 抽搐 + 区块闪烁。
                double lockY = flight.flightY + PLANE_LOCK_Y_OFFSET;
                double dx = player.getX() - cx;
                double dy = player.getY() - lockY;
                double dz = player.getZ() - cz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > STRAGGLER_PULLBACK_DIST * STRAGGLER_PULLBACK_DIST) {
                    pf.stragglerTicks++;
                    if (pf.stragglerTicks >= STRAGGLER_STRIKE_TICKS) {
                        BattleRoyaleMod.LOGGER.info(
                                "[BR-SILENT2] {} 连续 {} tick 偏离飞机 {} 格（阈值 {}），掉队保险拉回一次（{} → {}）",
                                player.getName().getString(), pf.stragglerTicks,
                                fmt(Math.sqrt(distSq)), (int) STRAGGLER_PULLBACK_DIST,
                                fmt(player.getX()) + "," + fmt(player.getZ()), fmt(cx) + "," + fmt(cz));
                        player.teleportTo((ServerLevel) player.level(), cx, lockY, cz,
                                player.getYRot(), player.getXRot());
                        player.setDeltaMovement(Vec3.ZERO);
                        player.fallDistance = 0.0F;
                        pf.stragglerTicks = 0;
                    }
                } else {
                    pf.stragglerTicks = 0;
                }
            } else if (pf.jumped) {
                // 缓降中（主动跳伞或终点强制踢下都走这里）：SLOW_FALLING 本身免疫
                // 摔落伤害，这里再每 tick 清一次 fallDistance 做双保险（效果被意外
                // 顶掉也不会摔死）。落地标记后由 tickGliding 统一清除缓降。
                player.fallDistance = 0.0F;
                if (player.onGround()) {
                    pf.landed = true;
                    if (pf.forcedKick) {
                        // [BR-PLANE-END] 强制踢下的玩家：落地瞬间才解除“航线中”无敌
                        flightInvincible.remove(id);
                    }
                }
            } else {
                // 兜底（理论上到不了这）：planeEnded 后仍未跳伞的漏网玩家自由落体，落地解除无敌
                if (player.onGround()) {
                    pf.landed = true;
                    flightInvincible.remove(id);
                }
            }
        }
    }

    /** 航线内玩家全部落地（或掉线摘除）。 */
    private boolean allSettled(Flight flight) {
        if (!flight.planeEnded) {
            return false;
        }
        for (PlayerFlight pf : flight.players.values()) {
            if (!pf.landed) {
                return false;
            }
        }
        return true;
    }

    private boolean landTimedOut(Flight flight) {
        return flight.planeEnded && flight.ticks - flight.planeEndedAtTick > LAND_TIMEOUT_TICKS;
    }

    /** 一条航线收尾：通知客户端收线、清所有玩家标记。 */
    private void completeFlight(MinecraftServer server, ServerLevel world, Flight flight) {
        broadcastEnded(server, world, flight);
        dropFlight(flight);
        BattleRoyaleMod.LOGGER.info("[BR-FLIGHT] 地图 {} 航线阶段完成，玩家已全部落地", flight.mapId);
    }

    /** 给本航线维度里的所有客户端发 active=false，收起地图上的航线。 */
    private void broadcastEnded(MinecraftServer server, ServerLevel world, Flight flight) {
        if (world != null) {
            for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
                FlightSync.sendRoute(player, false, flight.mapId, 0, 0, 0, 0, 0L, 0.0, 0.0);
            }
        } else {
            for (UUID id : flight.players.keySet()) {
                ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
                if (player != null) {
                    FlightSync.sendRoute(player, false, flight.mapId, 0, 0, 0, 0, 0L, 0.0, 0.0);
                }
            }
        }
    }

    /** 摘除一条航线的所有玩家标记（不发客户端包）。 */
    private void dropFlight(Flight flight) {
        MinecraftServer server = flight.cachedServer;
        for (UUID id : flight.players.keySet()) {
            planeLocked.remove(id);
            // [BR-PLANE-END] 还在缓降名单里的人 = 落地超时收尾时仍在半空：
            // 无敌保留，由 tickGliding 在他真正落地那一刻统一解除（防摔死）。
            if (!gliding.contains(id)) {
                flightInvincible.remove(id);
            }
            ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
            if (player != null) {
                // [BR-ROLLBACK] 任何异常收尾路径都兜底还原玩家状态：恢复可见、恢复重力；
                // [BR-MTR] 连同豁免对（allowFlying / inTeleportationState）一起归还，幂等。
                releaseVanillaCheckExemptions(player);
                player.setNoGravity(false);
                player.setInvisible(false);
                // [BR-FLIGHT-VANISH] 航线收尾（FLIGHT→RUNNING / br stop / 全员落地）：
                // 精准移除本模组挂上的原版隐身效果。
                player.removeEffect(MobEffects.INVISIBILITY);
                if (!gliding.contains(id)) {
                    // 仍在缓降名单的人（落地超时收尾时还在半空）不收伞：
                    // 由 tickGliding 在真正落地那一刻统一广播 false。
                    ParachuteSync.broadcast((ServerLevel) player.level(), id, false);
                }
            } else {
                hadAllowFlying.remove(id);
            }
        }
        flight.players.clear();
    }

    /** 对局中途被掐（br stop 等）：通知客户端收线并清所有航线状态。只在主线程调用。 */
    public void endMatch(MinecraftServer server) {
        for (Flight flight : flights) {
            broadcastEnded(server, server == null ? null : server.getLevel(flight.worldKey), flight);
            dropFlight(flight);
        }
        flights.clear();
        idleTicks = 0;
    }

    /**
     * [BR-FLIGHT-VANISH] FLIGHT → RUNNING 切换兜底扫除：给游戏维度里所有不在机上的
     * 玩家精准移除本模组挂上的原版隐身（removeEffect 只点名 INVISIBILITY 这一条，
     * 绝不整表清空，玩家自己的其余药效不受影响）。正常航线收尾时 dropFlight 已逐个
     * 移除，这里兜住中途掉线、被淘汰观战等脱离 flight.players 名单的漏网者。
     */
    public void clearFlightInvisibility(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            if (!GameWorldManager.isGameWorld(world.dimension())) {
                continue;
            }
            for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
                if (planeLocked.contains(player.getUUID())) {
                    continue; // 理论上 RUNNING 后不会有人仍在机上：防御性保留
                }
                player.setInvisible(false);
                player.removeEffect(MobEffects.INVISIBILITY);
            }
        }
    }

    // ==================================================================
    // 跳伞入口（network.FlightSync 的 C2S 接收者转发到这里）
    // ==================================================================

    /**
     * [BR-MTR] 收到跳伞包（客户端按键 = <b>左 Shift 或 F 键</b>）：解除坐标锁定 → 恢复可见与重力
     * → 清零残余速度 → 60 秒缓降 + 水平推力。移动状态复位全部走 {@link #parachute}
     * 核心，与终点强制踢人同一实现，不存在两条路径状态不一致的窗口。
     */
    public void handleParachute(ServerPlayer player) {
        if (player == null || GameManager.INSTANCE.getPhase() != GamePhase.FLIGHT || !player.isAlive()) {
            return;
        }
        Flight flight = flightOf(player.getUUID());
        PlayerFlight pf = flight == null ? null : flight.players.get(player.getUUID());
        if (pf == null || pf.jumped || pf.landed) {
            return; // 不在航线上 / 已跳过：静默忽略（防连点防伪造）
        }
        parachute(player, flight, pf, false);
    }

    private Flight flightOf(UUID id) {
        for (Flight flight : flights) {
            if (flight.players.containsKey(id)) {
                return flight;
            }
        }
        return null;
    }

    // ==================================================================
    // 断线 / 重连接口（可在 netty 线程回调，只动并发容器）
    // ==================================================================

    /** 玩家断线：从无敌 / 缓降 / 锁定名单摘除；航线内的条目由主线程 tick 检测离线后剔除。 */
    public void handleDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        flightInvincible.remove(player.getUUID());
        planeLocked.remove(player.getUUID());
        gliding.remove(player.getUUID());
        player.setNoGravity(false);
        // [BR-FLIGHT-VANISH] 断线保存玩家 NBT 前必须摘掉航线隐身：MobEffect 会随
        // ActiveEffects 标签落盘，不摘的话他重连回大厅会顶着一个无限时长隐身。
        player.setInvisible(false);
        player.removeEffect(MobEffects.INVISIBILITY);
        // [BR-MTR] 断线保存 .dat 前必须归还豁免（并发 Map + 实体字段写，netty 线程安全）。
        // 断线瞬间 allowFlying=true 若被写进玩家存档，重连后会“白嫖”创造飞行权限。
        releaseVanillaCheckExemptions(player);
    }

    /**
     * 玩家登录兜底：正常大厅登录清掉残留的零重力与缓降；
     * 如果是在航线阶段中途重连回游戏维度（落地在高空），给他一张应急缓降伞。
     */
    public void ensureCleanJoin(ServerPlayer player, MinecraftServer server) {
        if (player == null) {
            return;
        }
        player.setNoGravity(false);
        flightInvincible.remove(player.getUUID());
        planeLocked.remove(player.getUUID());
        // [BR-FLIGHT-VANISH] 登录兜底：隐身效果会随玩家存档 ActiveEffects 落盘，
        // 崩溃等异常路径没来得及在断线时摘除的，重连进大厅/回场这一刻清掉。
        // 重连回场的玩家已不在 flight.players 名单里（掉线时被摘除），不会再被
        // 钉回飞机，所以这里无条件收回航线隐身是安全的。
        player.setInvisible(false);
        player.removeEffect(MobEffects.INVISIBILITY);
        // [BR-MTR] 登录兜底：把任何可能因崩溃/异常路径残留的豁免状态归零
        // （存档里 allowFlying 被污染的极端情况由玩家原版权限重读修正：
        // 生存模式登录时 vanilla 不主动重置该位，这里幂等 release 只在有记录时归还）。
        releaseVanillaCheckExemptions(player);
        boolean backMidFlight = server != null
                && GameManager.INSTANCE.getPhase() == GamePhase.FLIGHT
                && GameWorldManager.isGameWorld(player.level().dimension())
                && player.getY() > 200 && !player.onGround();
        sendPendingRoute(player);
        if (backMidFlight) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
                    SLOW_FALLING_TICKS, 0, false, true));
            gliding.add(player.getUUID());
            // [BR-PARA-SPEC] 中途重连补的应急伞：向同维度所有客户端广播开伞状态。
            ParachuteSync.broadcast((ServerLevel) player.level(), player.getUUID(), true);
        } else {
            player.removeEffect(MobEffects.SLOW_FALLING);
            gliding.remove(player.getUUID());
            // [BR-PARA-SYNC] 登录清残留：收掉可能挂在其他客户端上的鬼影伞状态。
            ParachuteSync.broadcast((ServerLevel) player.level(), player.getUUID(), false);
        }
    }

    /**
     * [BR-PARA-SYNC] 原版重生（死亡后）兜底：缓降/锁定名单摘除、向同维度客户端收伞。
     * 由主类 AFTER_RESPAWN 钩子调用。
     */
    public void handleRespawn(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (gliding.remove(id)) {
            ParachuteSync.broadcast((ServerLevel) player.level(), id, false);
        }
        planeLocked.remove(id);
        flightInvincible.remove(id);
    }

    /**
     * [r-LEAVE] br leave / 断线判阵亡专用兜底（传送回大厅<b>之前</b>调用）：
     * 向同维度所有客户端广播收伞（正在缓降时别人不能再看到他的鬼影伞）、
     * 从缓降名单摘除、归还零重力与飞行豁免、解除航线无敌与坐标锁定。
     * 航线 players 表里的条目由下一 tick 的维度变化分支自动剔除并收起他的航线显示。
     * 对不在任何飞行名单里的玩家完全幂等（静默只发一次收伞广播）。
     */
    public void handleDesert(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        gliding.remove(id);
        planeLocked.remove(id);
        flightInvincible.remove(id);
        player.setNoGravity(false);
        releaseVanillaCheckExemptions(player);
        if (player.level() instanceof ServerLevel level) {
            ParachuteSync.broadcast(level, id, false);
        }
    }

    // ==================================================================
    // 航线生成算法
    // ==================================================================

    /**
     * 生成穿过选区的直线 A→B（[BR-FLIGHT-SHORT] 端点严格限制在选区内）：
     * <ol>
     *   <li>从地图物资点里随机抽一个「落在选区内」的点 L（没有就把选区中心当 L）；</li>
     *   <li>随机角度向两边求射线与选区矩形的交点，即弦 A₀→B₀ —— 直线严格经过 L，
     *       垂直距离恒为 0，天然满足 &lt;= {@value #MAX_LOOT_LINE_DISTANCE} 的要求；</li>
     *   <li>起飞点 A / 终点 B 直接取弦端点：它们恰好落在选区边界线
     *       （minX/maxX/minZ/maxZ）上，绝不外延、绝不越界（任何异常坐标会被
     *       硬夹取回选区矩形内）；</li>
     *   <li>弦太短（少于选区短边 6 成）重试 12 次，兜底取水平穿线。</li>
     * </ol>
     */
    private Flight buildFlight(ServerLevel world, GameMap map) {
        int regionMinX = Math.min(map.minX, map.maxX);
        int regionMaxX = Math.max(map.minX, map.maxX);
        int regionMinZ = Math.min(map.minZ, map.maxZ);
        int regionMaxZ = Math.max(map.minZ, map.maxZ);
        int minX = regionMinX + REGION_INSET;
        int maxX = regionMaxX - REGION_INSET;
        int minZ = regionMinZ + REGION_INSET;
        int maxZ = regionMaxZ - REGION_INSET;
        if (minX >= maxX || minZ >= maxZ) {
            return null;
        }
        // 锚点 L：随机抽一个在选区内的物资点
        List<LootPoint> candidates = new ArrayList<>();
        if (map.lootPoints != null) {
            for (LootPoint lp : map.lootPoints) {
                if (lp == null || !Double.isFinite(lp.x) || !Double.isFinite(lp.z)) {
                    continue;
                }
                if (lp.x >= minX && lp.x <= maxX && lp.z >= minZ && lp.z <= maxZ) {
                    candidates.add(lp);
                }
            }
        }
        double lx;
        double lz;
        LootPoint anchor = null;
        if (candidates.isEmpty()) {
            lx = (minX + (double) maxX) / 2.0;
            lz = (minZ + (double) maxZ) / 2.0;
        } else {
            anchor = candidates.get(RANDOM.nextInt(candidates.size()));
            lx = anchor.x;
            lz = anchor.z;
        }
        double width = maxX - minX;
        double depth = maxZ - minZ;
        double shortest = Math.max(64.0, Math.min(width, depth) * 0.6);
        // 兜底航线：水平穿选区（端点正好在选区左右边界上，不外延）
        double dirX = 1.0;
        double dirZ = 0.0;
        double startX = regionMinX;
        double startZ = lz;
        double endX = regionMaxX;
        double endZ = lz;
        boolean found = false;
        for (int attempt = 0; attempt < 12 && !found; attempt++) {
            double angle = attempt == 11 ? 0.0 : RANDOM.nextDouble() * Math.PI * 2.0;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double tMin = -1e18;
            double tMax = 1e18;
            if (Math.abs(dx) < 1e-9) {
                if (lx < regionMinX || lx > regionMaxX) {
                    continue;
                }
            } else {
                double ta = (regionMinX - lx) / dx;
                double tb = (regionMaxX - lx) / dx;
                tMin = Math.max(tMin, Math.min(ta, tb));
                tMax = Math.min(tMax, Math.max(ta, tb));
            }
            if (Math.abs(dz) < 1e-9) {
                if (lz < regionMinZ || lz > regionMaxZ) {
                    continue;
                }
            } else {
                double ta = (regionMinZ - lz) / dz;
                double tb = (regionMaxZ - lz) / dz;
                tMin = Math.max(tMin, Math.min(ta, tb));
                tMax = Math.min(tMax, Math.max(ta, tb));
            }
            if (tMax <= tMin) {
                continue;
            }
            double cx1 = lx + dx * tMin;
            double cz1 = lz + dz * tMin;
            double cx2 = lx + dx * tMax;
            double cz2 = lz + dz * tMax;
            if (Math.hypot(cx2 - cx1, cz2 - cz1) < shortest) {
                continue;
            }
            dirX = dx;
            dirZ = dz;
            startX = cx1;
            startZ = cz1;
            endX = cx2;
            endZ = cz2;
            found = true;
        }
        if (!found && regionMaxX - regionMinX < 16) {
            // 兜底：选区太窄，改竖直穿线（仍是合法航线）
            dirX = 0.0;
            dirZ = 1.0;
            startX = lx;
            startZ = regionMinZ;
            endX = lx;
            endZ = regionMaxZ;
        }
        // [BR-FLIGHT-SHORT] 无任何外延：A/B 直接取弦端点。弦端点是射线与选区矩形
        // 边界的交点，本来就落在 minX/maxX/minZ/maxZ 上；再硬夹取一次做保险，
        // 任何异常坐标（NaN 已单独拒绝）最坏也只是贴回选区边界，绝不越界一格。
        double ax = startX;
        double az = startZ;
        double bx = endX;
        double bz = endZ;
        if (!Double.isFinite(ax) || !Double.isFinite(az) || !Double.isFinite(bx) || !Double.isFinite(bz)) {
            BattleRoyaleMod.LOGGER.error("[BR-FLIGHT-SHORT] 地图 {} 航线端点出现非法坐标（A({},{}) B({},{})），拒绝生成",
                    map.mapId, ax, az, bx, bz);
            return null;
        }
        ax = Mth.clamp(ax, regionMinX, regionMaxX);
        bx = Mth.clamp(bx, regionMinX, regionMaxX);
        az = Mth.clamp(az, regionMinZ, regionMaxZ);
        bz = Mth.clamp(bz, regionMinZ, regionMaxZ);
        double length = Math.hypot(bx - ax, bz - az);
        if (length < 16.0 || !Double.isFinite(length)) {
            return null;
        }
        // [BR-FLIGHT-SPEED] 速度读自当前地图配置 map.flightSpeed（坏值兜底 4.0）
        double speed = effectiveFlightSpeed(map);
        int flySeconds = (int) Math.ceil(length / speed / GameRules.TICKS_PER_SECOND);
        BattleRoyaleMod.LOGGER.info(
                "[BR-FLIGHT] 地图 {} 选区 X[{},{}] Z[{},{}]（{}x{}）；航线 A({},{}) -> B({},{})，"
                        + "总长 {} 格（端点在选区边界内，无外延），速度 {} 格/tick（{} 格/秒，地图配置），飞完全程约 {} 秒",
                map.mapId, regionMinX, regionMaxX, regionMinZ, regionMaxZ,
                regionMaxX - regionMinX, regionMaxZ - regionMinZ,
                fmt(ax), fmt(az), fmt(bx), fmt(bz), (int) length,
                fmt(speed), fmt(speed * GameRules.TICKS_PER_SECOND), flySeconds);
        Flight flight = new Flight(world.dimension(), map.mapId, ax, az, bx, bz,
                Math.min(FLIGHT_HEIGHT, world.getMaxBuildHeight() - 5.0), speed);
        flight.cachedServer = world.getServer();
        if (anchor != null) {
            flight.lootName = anchor.name == null || anchor.name.isBlank() ? "物资点" : anchor.name;
            // L 到直线 AB 的垂直距离（叉积 / 弦长）
            flight.lootDistance = Math.abs((anchor.x - ax) * flight.dirZ - (anchor.z - az) * flight.dirX);
        }
        return flight;
    }

    private static String fmt(double v) {
        return String.valueOf((int) Math.round(v));
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    /**
     * 一条航线（一个游戏维度）。
     *
     * [BR-AIRDROP 模块三] 对外暴露的只读航线快照：空投飞机直接复用开局航线
     * 的 A→B 端点、速度与高度（{@link #routeFor}），不再重新随机。
     */
    public static final class RouteLine {
        public final ResourceKey<Level> worldKey;
        public final String mapId;
        public final double ax;
        public final double az;
        public final double bx;
        public final double bz;
        public final double dirX;
        public final double dirZ;
        public final double length;
        public final double speed;
        public final double flightY;
        /** 飞完全程需要的 tick 数（与开局航线同一口径）。 */
        public final int totalTicks;

        /** 为中途空投按指定 A→B 端点创建独立航线，不读取开局航线缓存。 */
        public static RouteLine create(ResourceKey<Level> worldKey, String mapId,
                                        double ax, double az, double bx, double bz,
                                        double flightY, double speed) {
            double safeSpeed = Double.isFinite(speed) ? Mth.clamp(speed, 0.5, 20.0) : 4.0;
            return new RouteLine(worldKey, mapId, ax, az, bx, bz, flightY, safeSpeed);
        }

        private RouteLine(ResourceKey<Level> worldKey, String mapId,
                          double ax, double az, double bx, double bz,
                          double flightY, double speed) {
            this.worldKey = worldKey;
            this.mapId = mapId == null ? "" : mapId;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.dirX = (bx - ax) / Math.hypot(bx - ax, bz - az);
            this.dirZ = (bz - az) / Math.hypot(bx - ax, bz - az);
            this.length = Math.hypot(bx - ax, bz - az);
            this.speed = speed;
            this.flightY = flightY;
            this.totalTicks = Math.max(1, (int) Math.ceil(this.length / this.speed));
        }

        RouteLine(Flight flight) {
            this.worldKey = flight.worldKey;
            this.mapId = flight.mapId;
            this.ax = flight.ax;
            this.az = flight.az;
            this.bx = flight.bx;
            this.bz = flight.bz;
            this.dirX = flight.dirX;
            this.dirZ = flight.dirZ;
            this.length = flight.length;
            this.speed = flight.speed;
            this.flightY = flight.flightY;
            this.totalTicks = flight.totalTicks;
        }
    }

    private static final class Flight {
        final ResourceKey<Level> worldKey;
        final String mapId;
        final double ax;
        final double az;
        final double bx;
        final double bz;
        final double dirX;
        final double dirZ;
        final double length;
        /** [BR-FLIGHT-SPEED] 本航线生效的水平速度（格/tick），建线时读自地图配置 map.flightSpeed。 */
        final double speed;
        final double flightY;
        /** 机头朝向（MC yaw：前进向量 = (-sin yaw, cos yaw)）。 */
        final float yaw;
        final int totalTicks;
        /**
         * [BR-MTR2] 纯函数时钟原点参考值：begin() 拉航线那一刻本维度的 world.getGameTime()。
         * 客户端不再信任这个跨纪元时间戳（渲染用的 startTick 由第一发 flight_tick
         * 包在自己的时间轴上锚定），该值仅作日志/诊断。
         */
        long startTick;
        /** [BR-MTR] begin() 已执行（startTick 有效、玩家已上“机”）。 */
        boolean started;
        final Map<UUID, PlayerFlight> players = new ConcurrentHashMap<>();
        int ticks;
        boolean planeEnded;
        int planeEndedAtTick;
        /** [BR-MTR2] 到终点后 PLANE_FINISHED 哨兵包只发一次。 */
        boolean endedSyncSent;
        MinecraftServer cachedServer;
        String lootName;
        double lootDistance;

        Flight(ResourceKey<Level> worldKey, String mapId,
               double ax, double az, double bx, double bz, double flightY, double speed) {
            this.worldKey = worldKey;
            this.mapId = mapId == null ? "" : mapId;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.length = Math.hypot(bx - ax, bz - az);
            this.dirX = (bx - ax) / this.length;
            this.dirZ = (bz - az) / this.length;
            this.flightY = flightY;
            // [BR-FLIGHT-SPEED] 速度已在 buildFlight 过兜底闸（0.5~20），这里绝不可能除零。
            this.speed = speed;
            this.yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dirX, dirZ)));
            // [BR-R89-FIX] 无任何时长上限：飞完全程需要的 tick 数，只与几何终点一致。
            this.totalTicks = Math.max(1, (int) Math.ceil(this.length / this.speed));
        }
    }

    /** 一名玩家在本条航线上的状态。 */
    private static final class PlayerFlight {
        volatile boolean jumped;
        volatile boolean landed;
        /** [BR-PLANE-END] true = 终点被强制踢下飞机：落地前保留无敌（防摔死）。 */
        volatile boolean forcedKick;
        /**
         * [BR-SILENT2] 连续偏离超过 {@link #STRAGGLER_PULLBACK_DIST} 格的 tick 数，
         * 攒满 {@link #STRAGGLER_STRIKE_TICKS} 才拉回；一旦回到阈值内立即清零。
         */
        int stragglerTicks;
    }
}
