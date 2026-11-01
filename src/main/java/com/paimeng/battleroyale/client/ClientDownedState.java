package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.network.DownedSyncPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端保存服务端同步的击倒数据。 */
public final class ClientDownedState {
    private static final Map<UUID, DownedSyncPayload> STATES = new HashMap<>();

    private ClientDownedState() {
    }

    public static synchronized void apply(DownedSyncPayload payload) {
        if (payload == null) return;
        if (payload.isClear()) {
            clear(payload.playerId());
        } else {
            STATES.put(payload.playerId(), payload);
        }
    }

    public static synchronized void clear(UUID playerId) {
        if (playerId != null) STATES.remove(playerId);
    }

    public static synchronized DownedSyncPayload get(UUID id) {
        return STATES.get(id);
    }

    public static synchronized Map<UUID, DownedSyncPayload> snapshot() {
        return new HashMap<>(STATES);
    }

    public static synchronized void clear() {
        STATES.clear();
    }
}
