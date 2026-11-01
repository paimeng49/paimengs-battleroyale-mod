package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.zone.ZonePhase;
import com.paimeng.battleroyale.zone.ZoneSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端这一侧的毒圈状态（只读缓存，r109 协议 v2）。
 *
 * <p>数据来源：服务端 {@code network.ZoneSync} 按玩家所在维度路由发来的快照，
 * 由 {@code BattleRoyaleModClient} 收包写进这里；
 * 消费方：{@link ZoneWallRenderer}（蓝色能量墙）、{@link MapRenderer}（小地图圈层）、
 * {@link MapHudRenderer}（缩圈进度条）。
 *
 * <p><b>模块六（维度隔离）</b>：快照带毒圈所属维度字符串；
 * 渲染前一律先过 {@link #visibleIn}——维度不匹配（主城 / 下界 / 别的地图）
 * 返回 false，连小地图下方的进度条都不画。服务端每 tick 会把「本维度没圈」的
 * 玩家补一发 IDLE 包当场清空；这里再加一层本地保险 {@link #expireIfNotIn}：
 * 维度一切换（被淘汰传送、/br leave 回大厅）立刻自清，不留幻影。
 *
 * <p>蓝圈（收缩起点）与白圈（目标）分开存；实时半径 / 圆心按进度 lerp，
 * 由客户端自己算（需求模块三：客户端根据总进度百分比插值）。
 *
 * <p>字段全部 {@code volatile}：收包与渲染的先后次序不保证，volatile 保证写完
 * 立刻对渲染可见；极端情况下读到一次"半新半旧"的组合，最多一帧偏差，下一 tick 自愈。
 */
public final class ClientZoneState {

    private static volatile boolean present = false;
    private static volatile ZonePhase phase = ZonePhase.IDLE;
    private static volatile int stageIndex = -1;
    private static volatile String mapId = "";
    /** 毒圈所属维度（"battleroyale_game:xxx" 字符串，客户端做严格相等比对）。 */
    private static volatile String dimensionId = "";

    // 蓝圈 = 本轮收缩起点（WAITING 期间即当前安全区边界）
    private static volatile double blueX = 0.0;
    private static volatile double blueZ = 0.0;
    private static volatile double blueRadius = 0.0;
    // 白圈 = 本轮收缩目标（下个安全区）
    private static volatile double whiteX = 0.0;
    private static volatile double whiteZ = 0.0;
    private static volatile double whiteRadius = 0.0;

    private static volatile int remainingSeconds = 0;
    private static volatile double progress = 0.0;

    private ClientZoneState() {
    }

    // ------------------------------------------------------------------
    // 写入（只由收包 / 维度切换清理逻辑调用）
    // ------------------------------------------------------------------

    /** 收到服务端快照：IDLE（毒圈关闭 / 本维度无圈）等同于清空。 */
    public static void apply(ZoneSnapshot snapshot) {
        if (snapshot == null || !snapshot.active()) {
            clear();
            return;
        }
        phase = snapshot.phase();
        stageIndex = snapshot.stageIndex();
        mapId = snapshot.mapId() == null ? "" : snapshot.mapId();
        dimensionId = snapshot.dimensionId() == null ? "" : snapshot.dimensionId();
        blueX = snapshot.blueCenterX();
        blueZ = snapshot.blueCenterZ();
        blueRadius = snapshot.blueRadius();
        whiteX = snapshot.whiteCenterX();
        whiteZ = snapshot.whiteCenterZ();
        whiteRadius = snapshot.whiteRadius();
        remainingSeconds = snapshot.remainingSeconds();
        progress = snapshot.clampedProgress();
        present = true;
    }

    /** 清空：断线、退出世界、维度切换、服务端毒圈关闭时调用，之后什么都不画。 */
    public static void clear() {
        present = false;
        phase = ZonePhase.IDLE;
        stageIndex = -1;
        mapId = "";
        dimensionId = "";
        blueX = 0.0;
        blueZ = 0.0;
        blueRadius = 0.0;
        whiteX = 0.0;
        whiteZ = 0.0;
        whiteRadius = 0.0;
        remainingSeconds = 0;
        progress = 0.0;
    }

    /**
     * 本地维度保险（模块六 3）：毒圈还在、但玩家已经不在那个维度
     * （被淘汰传送回大厅等）→ 立刻清空，不等服务端的下一发 IDLE 包。
     */
    public static void expireIfNotIn(ResourceKey<Level> worldKey) {
        if (present && !visibleIn(worldKey)) {
            clear();
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 有没有可用数据。false = 什么都不该画。 */
    public static boolean isPresent() {
        return present;
    }

    /**
     * 当前维度是否可以显示这圈毒（严格相等：主城、下界、别的地图维度全部 false）。
     * HUD / 能量墙 / 小地图圈层渲染前必须先过这道闸。
     */
    public static boolean visibleIn(ResourceKey<Level> worldKey) {
        if (!present || worldKey == null) {
            return false;
        }
        ResourceLocation value = worldKey.location();
        return value != null && dimensionId.equals(value.toString());
    }

    public static ZonePhase phase() {
        return phase;
    }

    public static int stageIndex() {
        return stageIndex;
    }

    public static String mapId() {
        return mapId;
    }

    public static double blueCenterX() {
        return blueX;
    }

    public static double blueCenterZ() {
        return blueZ;
    }

    public static double blueRadius() {
        return blueRadius;
    }

    public static double whiteCenterX() {
        return whiteX;
    }

    public static double whiteCenterZ() {
        return whiteZ;
    }

    public static double whiteRadius() {
        return whiteRadius;
    }

    public static int remainingSeconds() {
        return remainingSeconds;
    }

    public static double progress() {
        return progress;
    }

    /** 实时蓝圈半径 = lerp(收缩起点 → 白圈, 总进度)：需求模块三的客户端插值。 */
    public static double liveRadius() {
        return blueRadius + (whiteRadius - blueRadius) * progress;
    }

    /** 实时蓝圈圆心（收缩过程中圆心也在移动，与伤害判定同值）。 */
    public static double liveCenterX() {
        return blueX + (whiteX - blueX) * progress;
    }

    public static double liveCenterZ() {
        return blueZ + (whiteZ - blueZ) * progress;
    }

    /** 平面坐标是否已在实时圈外（HUD 的圈外判定 / 虚线开关）。 */
    public static boolean isOutsideLive(double x, double z) {
        if (!present) {
            return false;
        }
        double radius = liveRadius();
        return Math.hypot(x - liveCenterX(), z - liveCenterZ()) > radius;
    }

    /**
     * 平面坐标是否在<b>白圈</b>（下一个安全区）外：进圈指引虚线的开关。
     *
     * <p>需求口径（r113 修正）：只要 {@code dist(玩家, 白圈圆心) > 白圈半径} 就要画
     * 从玩家指向白圈中心的虚线——<b>不管玩家在不在蓝圈里</b>。
     * 玩家哪怕已经躲进蓝圈，只要蓝圈整体会缩进白圈，也得知道该往哪跑。
     */
    public static boolean isOutsideWhite(double x, double z) {
        if (!present) {
            return false;
        }
        return Math.hypot(x - whiteX, z - whiteZ) > whiteRadius;
    }
}
