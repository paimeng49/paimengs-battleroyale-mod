package com.paimeng.battleroyale.event;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** 玩家离开大厅队事件；大厅队不携带任何队内编号。 */
public record PlayerLeftTeamEvent(UUID teamId, int teamNumber, UUID playerId, String playerName,
                                  boolean disbanded, MinecraftServer server) {
}
