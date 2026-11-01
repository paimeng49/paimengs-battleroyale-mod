package com.paimeng.battleroyale.match;

import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.MapManager;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每张地图一份等待队列。
 *
 * <p>当前人数 = 该 mapId 对应 Set 的大小；加入时 add、退出 / 开始时 remove。
 * 一个玩家同时只能等一张地图：换图会先从旧队列移除。
 */
public final class MapQueue {
    public static final MapQueue INSTANCE = new MapQueue();

    private final Map<String, Set<UUID>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerToMap = new ConcurrentHashMap<>();

    private MapQueue() {
    }

    public int waitingCount(String mapId) {
        Set<UUID> set = waiting.get(mapId);
        return set == null ? 0 : set.size();
    }

    public boolean isWaiting(UUID playerId, String mapId) {
        return mapId.equals(playerToMap.get(playerId));
    }

    public String waitingMapOf(UUID playerId) {
        return playerToMap.get(playerId);
    }

    /** 把玩家加入 mapId 的等待队列；失败返回错误文案，成功返回 null。 */
    public String join(UUID playerId, String mapId) {
        GameMap map = MapManager.INSTANCE.getMap(mapId);
        if (map == null) {
            return "地图不存在";
        }
        String current = playerToMap.get(playerId);
        if (mapId.equals(current)) {
            return "你已在该地图等待中";
        }
        int max = Math.max(1, map.maxPlayers);
        Set<UUID> set = waiting.computeIfAbsent(mapId, ignored -> ConcurrentHashMap.newKeySet());
        if (set.size() >= max) {
            return "该地图已满";
        }
        if (current != null) {
            leave(playerId);
        }
        set.add(playerId);
        playerToMap.put(playerId, mapId);
        return null;
    }

    public void leave(UUID playerId) {
        String mapId = playerToMap.remove(playerId);
        if (mapId != null) {
            Set<UUID> set = waiting.get(mapId);
            if (set != null) {
                set.remove(playerId);
            }
        }
    }

    /** 开局时把该地图等待队列清空（玩家进入对局后不再占用等待名额）。 */
    public void clearMap(String mapId) {
        Set<UUID> set = waiting.remove(mapId);
        if (set != null) {
            for (UUID id : set) {
                playerToMap.remove(id);
            }
        }
    }

    /** br start 成功后统一清空：等待名额转为对局玩家。 */
    public void clearAll() {
        waiting.clear();
        playerToMap.clear();
    }

    public Set<UUID> snapshot(String mapId) {
        Set<UUID> set = waiting.get(mapId);
        return set == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    /** [BR-MATCHTEAM] 当前有等待玩家的所有地图 ID（开局门槛与 MatchTeam 分配按图分组用）。 */
    public Set<String> waitingMapIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, Set<UUID>> entry : waiting.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }
}
