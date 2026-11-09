package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.network.TeammatePosPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [BR-MINIMAP] 队友坐标缓存（只读）：本队全员的世界 X/Z + 座号 + 存活状态。
 *
 * <p>数据来源：服务端 {@code TeammatePosSync} 每 10 tick 的定向快照
 * （battleroyale:teammate_pos 通道——包体很小，按 500ms 线性插值补足平滑）。
 * 消费方：{@link MapRenderer} 的小地图 / 全屏地图队友图标层。
 *
 * <p>为什么用服务端坐标而不是查 {@code client.level.getPlayerByUUID}：
 * 原版实体追踪只把视距内的队友送进客户端，地图上几百米外的队友在客户端
 * <b>根本没有实体</b>；“视野外贴边显示”这半边需求必须靠服务端同步才成立。
 *
 * <p>线程约定：收包线程 {@link #apply}，渲染线程 {@link #snapshot()}；
 * 整份不可变 Map 引用一次性替换 + volatile，读端零锁。插值用读到的
 * {@link Entry#arrivedAtMillis} 在渲染侧现算，渲染线程不写任何状态。
 */
public final class ClientTeammatePosState {

    /** 服务端包间隔（毫秒）：0.5 秒，插值窗口按它取。 */
    private static final long PACKET_INTERVAL_MILLIS = 500L;

    /** 一条队友坐标：上一份 (prev) → 当前份 (x/z)，到达时刻用于插值。 */
    public record Entry(int seat, double prevX, double prevZ,
                        double x, double z, long arrivedAtMillis, int status) {
    }

    /** 插值后的渲染位姿（纯值对象，渲染线程按需生成，不做缓存）。 */
    public record Placement(int seat, double x, double z, int status) {
    }

    private static final Map<UUID, Entry> NO_DATA = Map.of();

    private static volatile Map<UUID, Entry> byPlayer = NO_DATA;

    // [r143][BR-MINIMAP] 实机排障日志：只在状态“翻转”时打印（收到首包 / 被清空），
    // 数据持续在流时按 30 秒节流打一行心跳，绝不 250ms 一行刷屏。
    // apply 可能跑在网络线程上，日志状态用锁保护。
    private static final Object LOG_LOCK = new Object();
    private static boolean hadData = false;
    private static long lastLogMillis = 0L;

    private ClientTeammatePosState() {
    }

    /** 收到服务端快照：旧值降级为 prev，新值上墙；空快照 = clear()。 */
    public static void apply(TeammatePosPayload payload) {
        if (payload == null || payload.isEmpty()) {
            byPlayer = NO_DATA;
            logState(false, 0);
            return;
        }
        Map<UUID, Entry> old = byPlayer;
        long now = System.currentTimeMillis();
        Map<UUID, Entry> next = new HashMap<>(payload.entries().size() * 2);
        for (TeammatePosPayload.Entry e : payload.entries()) {
            Entry prev = old.get(e.playerId());
            // 同一份包里没见过的队友（新入队/重建）prev=cur，插值退化为瞬移；
            // prev 与 cur 距离超过一个包能走的合理范围（断传/重生）也直接用新值，
            // 避免一条横穿全图的插值线。
            double prevX = e.x();
            double prevZ = e.z();
            if (prev != null && Math.hypot(prev.x() - e.x(), prev.z() - e.z()) < 64.0) {
                prevX = prev.x();
                prevZ = prev.z();
            }
            next.put(e.playerId(), new Entry(e.seat(), prevX, prevZ, e.x(), e.z(), now, e.status()));
        }
        byPlayer = Map.copyOf(next);
        logState(true, payload.entries().size());
    }

    /**
     * [r143] 收包状态日志：空→非空（收到第一份坐标）与非空→空（被清空）各打一行；
     * 持续有数据时每 30 秒补一行心跳，方便实机确认“包一直通着”。
     */
    private static void logState(boolean hasData, int count) {
        synchronized (LOG_LOCK) {
            long now = System.currentTimeMillis();
            if (hasData && !hadData) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP][客户端] 收到队友坐标包：{} 条（开始显示图标）", count);
                lastLogMillis = now;
            } else if (!hasData && hadData) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP][客户端] 队友坐标包已清空（空包收尾，图标消失）");
            } else if (hasData && now - lastLogMillis >= 30_000L) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP][客户端] 坐标包持续接收中：当前 {} 条", count);
                lastLogMillis = now;
            }
            hadData = hasData;
        }
    }

    /** 判断某个玩家是否出现在服务端定向下发的“我的队伍”坐标表中。 */
    public static boolean contains(UUID playerId) {
        return playerId != null && byPlayer.containsKey(playerId);
    }

    /** 断线 / 退出世界 / 本局销毁：图标全部消失。 */
    public static void clear() {
        byPlayer = NO_DATA;
        synchronized (LOG_LOCK) {
            if (hadData) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP][客户端] 本地清空队友坐标（断线/退图）");
                hadData = false;
            }
        }
    }

    /**
     * 渲染线程取插值后的队友位姿列表。超过 2 倍包间隔没更新（掉包/传送冻结）
     * 就停止插值直接用最后收到的坐标，图标绝不“继续往墙外飘”。
     */
    public static Map<UUID, Placement> interpolatedPositions() {
        Map<UUID, Entry> source = byPlayer;
        if (source.isEmpty()) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        Map<UUID, Placement> out = new HashMap<>(source.size() * 2);
        for (Map.Entry<UUID, Entry> e : source.entrySet()) {
            Entry entry = e.getValue();
            double t = (now - entry.arrivedAtMillis()) / (double) PACKET_INTERVAL_MILLIS;
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
            double x = entry.prevX() + (entry.x() - entry.prevX()) * t;
            double z = entry.prevZ() + (entry.z() - entry.prevZ()) * t;
            out.put(e.getKey(), new Placement(entry.seat(), x, z, entry.status()));
        }
        return out;
    }
}
