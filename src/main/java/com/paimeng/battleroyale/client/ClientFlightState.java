package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.network.FlightSync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * [BR-MTR2] 客户端航线状态（数据驱动，MTR 架构第二轮重构的核心）。
 *
 * <p>C-130 已不再是 Entity：客户端没有任何实体插值状态可读（lastRender / prev
 * 全部随实体一起删除）。飞机的唯一位置来源是本类里的<b>时间纯函数</b>——
 * 与 MTR 的 Transport-Simulation-Core 同一思想：服务端按世界 tick 模拟权威进度
 * （{@code progress = ticks × speed}），经 {@code flight_tick} 通道每 tick 下发；
 * 客户端把进度写成连续时间的函数：
 * <pre>
 * travel(t) = clamp(speed × (t − startTick), 0, length)
 * pos(t)    = (ax + dirX·travel, flightY, az + dirZ·travel)
 * </pre>
 * 渲染（{@link C130WorldRenderer}）与机上玩家锁定（{@link ModKeyBindings}）读
 * <b>同一条直线的同一时刻</b>，机身与镜头在数学上恒等——不存在"追得上追不上"，
 * 也就没有任何抽搐可言。
 *
 * <p><b>时钟对表（彻底告别 startTick 死锁）</b>：服务端纪元的 startTick 只作日志
 * 参考，<b>渲染真正用的 startTick 由第一发 flight_tick 包锚定</b>：
 * {@code startTick = 收包时的客户端世界时间 − 权威进度tick}。锚定的是"当下"，
 * 天然不可能出现"客户端时间 &lt; startTick → travel 恒被夹 0 → 永远卡在起点 A"。
 *
 * <p><b>[BR-SILENT] 绝对静默：彻底匀速，不做任何软校正</b>：锚定之后
 * startTick 与 speed 在整段飞行期间<b>永不改动</b>，{@code applyTick} 收到后续
 * tick 包只做终点判断，不再碰任何状态。旧版每包按比例微调 speedOffset 会让
 * 客户端自算速度不断震荡，与服务端形成拉扯（玩家抽搐、区块闪烁的客户端一侧
 * 根因）。短短几十秒的飞行，纯匀速 {@code travel = speed × (t − startTick)}
 * 根本飘不远，换来的是数学上严格的一条直线——丝滑得不可思议。
 */
@Environment(EnvType.CLIENT)
public final class ClientFlightState {
    /** 未对表前纯函数整体不激活（渲染与锁定都不启动，等第一发 tick 包）。 */
    private static volatile boolean clockAnchored;
    /**
     * 飞机是否还在飞：航线 begin 时 true；服务端到终点 B 下发 progressTicks=-1
     * 时 false（收机 + 停锁定）。与 active（地图上画不画线）解耦：线保留到全员
     * 落地，飞机到 B 即刻消失——和"实体 discard"时代的观感一致。
     */
    private static volatile boolean planeRunning;

    private static volatile boolean active;
    private static volatile String mapId = "";
    private static volatile double startX;
    private static volatile double startZ;
    private static volatile double endX;
    private static volatile double endZ;
    /** 本地防连点：本轮航线已经按过跳伞键（服务端才是权威判定）。 */
    private static volatile boolean jumpSent;

    // ---- 纯函数参数（volatile：netty→主线程写，渲染线程读）----
    /** 航线起点 A（世界坐标）。 */
    private static volatile double ax;
    private static volatile double az;
    /** 单位方向向量 A→B。 */
    private static volatile double dirX;
    private static volatile double dirZ;
    /** 水平速度（格/tick）。 */
    private static volatile double speed;
    /** 飞行高度（服务端已夹取）。 */
    private static volatile double flightY;
    /** 航线全长（travel 上限）。 */
    private static volatile double length;
    /** 有效起始 tick（首包锚定一次后，整段飞行永不改动——绝对静默的根基）。 */
    private static volatile double startTick;

    private ClientFlightState() {
    }

    /** 收航线包（开始 / 预热预告 / 结束）。在客户端主线程调用。 */
    public static void apply(FlightSync.Payload payload) {
        if (payload == null) {
            clear();
            return;
        }
        if (!payload.active()) {
            // 航线结束：收线收机，但保留 mapId 供日志排查
            active = false;
            planeRunning = false;
            clockAnchored = false;
            jumpSent = false;
            return;
        }
        mapId = payload.mapId() == null ? "" : payload.mapId();
        startX = payload.ax();
        startZ = payload.az();
        endX = payload.bx();
        endZ = payload.bz();
        ax = payload.ax();
        az = payload.az();
        double len = Math.hypot(payload.bx() - payload.ax(), payload.bz() - payload.az());
        length = len;
        if (Double.isFinite(len) && len > 1.0e-6) {
            dirX = (payload.bx() - payload.ax()) / len;
            dirZ = (payload.bz() - payload.az()) / len;
        } else {
            dirX = 0.0;
            dirZ = 0.0;
        }
        speed = payload.speed();
        flightY = payload.flightY();
        startTick = payload.startTick();
        // [BR-MTR2] 新一轮航线：纪元重新对表。speed>0 = 飞机已在飞（begin 正式包）；
        // 预热预告包 speed=0，只画地图线，纯函数保持未激活。
        clockAnchored = false;
        planeRunning = speed > 1.0e-9;
        jumpSent = false;
        active = true;
    }

    /**
     * [BR-MTR2 / BR-SILENT] 收服务端每 tick 的权威进度包（{@code flight_tick} 通道）。
     * 在客户端主线程调用（netty 线程 read 完 client.execute 投递）。
     *
     * <p><b>绝对静默</b>：本函数只在首包锚定一次 startTick；此后每发 tick 包
     * 唯一职责是判断"飞机是否已到终点"，<b>不再修改任何飞行状态</b>。速度微调
     * （speedOffset）已整套删除——纯函数永远只算 {@code speed × (t − startTick)}，
     * 与服务端、镜头、小地图共享同一条严格直线，客户端一侧绝无震荡拉扯。
     *
     * @param serverTime    发包瞬间服务端世界时间（仅日志/诊断，不参与计算）
     * @param progressTicks 权威进度（航线开始后的 tick 数；负值 = 飞机已到终点）
     * @param clientTime    收包时刻的客户端世界时间
     */
    public static void applyTick(long serverTime, int progressTicks, long clientTime) {
        if (!active || progressTicks < 0) {
            if (progressTicks < 0) {
                planeRunning = false; // 到终点：收机（地图线保留，等 active=false 收）
            }
            return;
        }
        if (!planeRunning || speed <= 1.0e-9 || !Double.isFinite(startTick) || clientTime <= 0L) {
            return; // 还没进世界（时间读数无效）：等下一发包，绝不拿 0 纪元去锚
        }
        if (!clockAnchored) {
            // 首包锚定（唯一一次写 startTick）：把"权威进度当下就在客户端时间轴上"
            // 作为唯一基准。这一步之后渲染 / 锁定的所有采样都用本地纪元，服务端
            // 纪元的 startTick 值（可能大于客户端时间）彻底作废——卡起点死锁的
            // 根源消除。此后整段飞行绝对静默：startTick、speed 永不改动。
            startTick = clientTime - progressTicks;
            clockAnchored = true;
        }
        // 后续 tick 包：绝对静默，不做任何校正、不写任何状态。
    }

    public static void clear() {
        active = false;
        jumpSent = false;
        clockAnchored = false;
        planeRunning = false;
        mapId = "";
        startX = 0;
        startZ = 0;
        endX = 0;
        endZ = 0;
        ax = 0;
        az = 0;
        dirX = 0;
        dirZ = 0;
        speed = 0;
        flightY = 0;
        length = 0;
        startTick = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isJumpSent() {
        return jumpSent;
    }

    public static void markJumpSent() {
        jumpSent = true;
    }

    public static double startX() {
        return startX;
    }

    public static double startZ() {
        return startZ;
    }

    public static double endX() {
        return endX;
    }

    public static double endZ() {
        return endZ;
    }

    // ==================================================================
    // 纯函数位置：渲染器与玩家锁定唯一合法的位置来源
    // ==================================================================

    /**
     * 纯函数是否可用 = 参数齐全 + 已在飞 + <b>已完成首包锚定</b>。
     * 锚定前（航线开始的最多 1~2 个 tick 窗口）渲染器不画、锁定不钉，
     * 玩家由服务端权威传送稳稳按在起点，第一发 tick 包到达即上岗——
     * 不存在任何"卡在起点 A"的死锁路径。
     */
    public static boolean planeSyncActive() {
        return active && planeRunning && clockAnchored
                && speed > 1.0e-9 && length > 1.0e-9
                && Double.isFinite(ax) && Double.isFinite(az) && Double.isFinite(startTick)
                && Double.isFinite(dirX) && Double.isFinite(dirZ) && Double.isFinite(flightY);
    }

    /** 是否已被权威 tick 包锚定（日志/诊断用）。 */
    public static boolean isClockAnchored() {
        return clockAnchored;
    }

    /**
     * 沿程位移 travel = speed × (time − startTick)，硬夹取 [0, length]
     * （与服务端同款夹取）。[BR-SILENT] startTick 首包锚定后恒定不变、speed
     * 全程不变——本函数对时间是<b>严格线性</b>函数：渲染器、镜头锁定、小地图
     * 在同一时刻取到同一条直线上的同一点，绝无相位撕裂，也绝无校正震荡。
     */
    public static double travelAt(double time) {
        double travel = speed * (time - startTick);
        if (!Double.isFinite(travel) || travel < 0.0) {
            return 0.0;
        }
        return travel > length ? length : travel;
    }

    /** 时刻 time（world time − 1 + tickDelta）的渲染 X。 */
    public static double planeXAt(double time) {
        return ax + dirX * travelAt(time);
    }

    /** 时刻 time 的渲染 Z。 */
    public static double planeZAt(double time) {
        return az + dirZ * travelAt(time);
    }

    /** 渲染 Y：航线全程恒定高度。 */
    public static double planeY() {
        return flightY;
    }

    /** 机头朝向（MC yaw：前进向量 = (-sin yaw, cos yaw)），由 A→B 方向反推。 */
    public static float planeYaw() {
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dirX, dirZ)));
    }

    /**
     * 当前维度是否可以画航线 / 飞机：在游戏维度时必须与本航线所属地图匹配
     * （判定口径与 {@link ClientLootPointsState#visibleIn} 一致）。
     */
    public static boolean visibleIn(ResourceKey<Level> worldKey) {
        if (!active) {
            return false;
        }
        if (worldKey == null) {
            return true;
        }
        ResourceLocation value = worldKey.location();
        if (GameWorldManager.DIMENSION_NAMESPACE.equals(value.getNamespace())) {
            return MapManager.safeId(mapId).toLowerCase().equals(value.getPath());
        }
        return true;
    }
}
