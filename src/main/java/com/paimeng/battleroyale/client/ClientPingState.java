package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.network.PingSync;
import com.paimeng.battleroyale.team.PingType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端这一侧的活动标点列表。
 *
 * <p>生命周期按需求在客户端决定：
 * <ul>
 *   <li>ENEMY：世界渲染 10 秒；小地图标记由 {@link MarkManager} 10 秒后删除；</li>
 *   <li>自己发的 WAYPOINT：世界渲染永久；小地图永久；</li>
 *   <li>队友发的 WAYPOINT：世界渲染 10 秒；小地图永久。</li>
 * </ul>
 * 服务端包带发起者 UUID，因此收包端可以准确区分"自己"与"队友"。
 *
 * <p><b>R15 替换规则</b>：同一发送者的同类型标点只保留最新一条——新标点到达时
 * 立刻移除旧的世界渲染标记，并同步调用 {@link MarkManager#removeMapMark(String)}
 * 删掉旧的小地图标记（Marker 里保存了 addMapMark 返回的 markId）。
 * 不同玩家、不同类型之间互不影响。
 */
@Environment(EnvType.CLIENT)
public final class ClientPingState {

    private static final long TEN_SECONDS_MS = 10_000L;

    /** 活动世界标记的软上限；优先清理临时标记，不淘汰永久路标。 */
    public static final int MAX_MARKERS = 128;

    /** 一个活动标点；ttlMs <= 0 表示世界渲染永久。mapMarkId 用于替换时删旧小地图标记。 */
    public record Marker(double x, double y, double z, PingType type,
                         int memberNumber, String initiatorName, UUID initiatorUuid,
                         long bornAtMs, long ttlMs, String mapMarkId) {
    }

    private static final List<Marker> MARKERS = new ArrayList<>();

    private ClientPingState() {
    }

    /** 收到服务端广播的标点：按类型和归属入列，并登记小地图接口标记。 */
    public static void add(PingSync.Show show) {
        BattleRoyaleMod.LOGGER.info("[Ping] 收到标点包: {}", show);
        if (show == null || show.type() == null || show.initiatorUuid() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID selfId = null;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            selfId = client.player.getUUID();
        }
        boolean ownWaypoint = show.type() == PingType.WAYPOINT
                && show.initiatorUuid().equals(selfId);
        long worldTtl = ownWaypoint ? 0L : TEN_SECONDS_MS;

        // 同发送者 + 同类型只保留最新：先摘掉旧标点并收集它们的小地图 id。
        List<String> staleMapIds = new ArrayList<>();
        synchronized (MARKERS) {
            prune(now);
            MARKERS.removeIf(old -> {
                if (old.initiatorUuid().equals(show.initiatorUuid()) && old.type() == show.type()) {
                    if (old.mapMarkId() != null && !old.mapMarkId().isEmpty()) {
                        staleMapIds.add(old.mapMarkId());
                    }
                    return true;
                }
                return false;
            });
        }
        // MarkManager 有自己的锁：在 MARKERS 锁外调用，避免交叉持锁。
        for (String staleId : staleMapIds) {
            MarkManager.removeMapMark(staleId);
        }

        int color = TeamHudRenderer.colorOf(show.memberNumber());
        // 小地图本轮只留接口，不做任何具体地图渲染。
        String mapMarkId = MarkManager.addMapMark(show.x(), show.y(), show.z(), show.type(), color);

        Marker marker = new Marker(show.x(), show.y(), show.z(), show.type(), show.memberNumber(),
                show.initiatorName(), show.initiatorUuid(), now, worldTtl, mapMarkId);
        synchronized (MARKERS) {
            if (MARKERS.size() >= MAX_MARKERS) {
                // 只优先淘汰有期限的标记，避免永久 WAYPOINT 被临时标记挤掉。
                int index = findTemporaryMarker();
                if (index >= 0) {
                    Marker evicted = MARKERS.remove(index);
                    if (evicted.mapMarkId() != null && !evicted.mapMarkId().isEmpty()) {
                        MarkManager.removeMapMark(evicted.mapMarkId());
                    }
                }
            }
            MARKERS.add(marker);
        }
    }

    /** 渲染前取一份快照（已剔除过期世界标记）。 */
    public static List<Marker> snapshot() {
        long now = System.currentTimeMillis();
        synchronized (MARKERS) {
            prune(now);
            return new ArrayList<>(MARKERS);
        }
    }

    /** 断线 / 退出世界：世界标记和预留的小地图标记都不留给下一个世界。 */
    public static void clear() {
        synchronized (MARKERS) {
            MARKERS.clear();
        }
        MarkManager.clear();
    }

    /** 调用方持有 MARKERS 锁时调用。 */
    private static void prune(long now) {
        MARKERS.removeIf(marker -> marker.ttlMs() > 0L
                && now - marker.bornAtMs() >= marker.ttlMs());
    }

    /** 找一条临时标记（ENEMY 或队友 WAYPOINT）用于容量保护。 */
    private static int findTemporaryMarker() {
        for (int i = 0; i < MARKERS.size(); i++) {
            if (MARKERS.get(i).ttlMs() > 0L) {
                return i;
            }
        }
        return -1;
    }
}
