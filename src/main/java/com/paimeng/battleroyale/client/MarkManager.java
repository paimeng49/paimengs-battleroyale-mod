package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.team.PingType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 小地图标记接口预留。
 *
 * <p>本轮不接入任何具体小地图，只保存接口数据。WAYPOINT 永久保留，
 * ENEMY 在 10 秒后自动删除；调用方也可以按返回的 markId 主动删除。
 */
@Environment(EnvType.CLIENT)
public final class MarkManager {

    private static final long ENEMY_MARK_TTL_MS = 10_000L;
    private static final int MAX_MARKS = 256;
    private static final List<MapMark> MARKS = new ArrayList<>();

    public record MapMark(String markId, double x, double y, double z,
                          PingType type, int color, long bornAtMs, long ttlMs) {
    }

    private MarkManager() {
    }

    /**
     * 预留给小地图适配器的添加接口。
     *
     * @return 可传给 {@link #removeMapMark(String)} 的标记 id
     */
    public static String addMapMark(double x, double y, double z, PingType type, int color) {
        if (type == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        long ttl = type == PingType.ENEMY ? ENEMY_MARK_TTL_MS : 0L;
        String markId = UUID.randomUUID().toString();
        synchronized (MARKS) {
            prune(now);
            if (MARKS.size() >= MAX_MARKS) {
                MARKS.remove(0);
            }
            MARKS.add(new MapMark(markId, x, y, z, type, color, now, ttl));
        }
        return markId;
    }

    /** 删除一个小地图标记；不存在时返回 false。 */
    public static boolean removeMapMark(String markId) {
        if (markId == null) {
            return false;
        }
        synchronized (MARKS) {
            return MARKS.removeIf(mark -> mark.markId().equals(markId));
        }
    }

    /** 供未来小地图适配器读取的活动标记快照。 */
    public static List<MapMark> activeMarks() {
        long now = System.currentTimeMillis();
        synchronized (MARKS) {
            prune(now);
            return new ArrayList<>(MARKS);
        }
    }

    /** 断线或切换世界时清理当前世界的标记。 */
    public static void clear() {
        synchronized (MARKS) {
            MARKS.clear();
        }
    }

    private static void prune(long now) {
        MARKS.removeIf(mark -> mark.ttlMs() > 0L && now - mark.bornAtMs() >= mark.ttlMs());
    }
}
