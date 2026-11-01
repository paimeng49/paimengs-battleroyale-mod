package com.paimeng.battleroyale.network;

import java.util.UUID;

/** 服务端同步的击倒、倒计时和救援进度。 */
public record DownedSyncPayload(
        UUID playerId,
        boolean downed,
        int remainingTicks,
        int rescueTicks,
        int rescueMaxTicks,
        float downedHealth,
        UUID rescuerUuid,
        float rescueProgress,
        float rescueRemainingSeconds,
        boolean isClear
) {
    public static DownedSyncPayload empty(UUID playerId) {
        return new DownedSyncPayload(playerId, false, 0, 0, 0, 0.0f, null, 0.0f, 0.0f, true);
    }
}
