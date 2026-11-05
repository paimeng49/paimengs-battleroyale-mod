package com.paimeng.battleroyale.event;

import com.paimeng.battleroyale.team.Team;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** 玩家加入大厅队事件；大厅队不携带任何队内编号。 */
public record PlayerJoinedTeamEvent(Team team, UUID playerId, String playerName,
                                    MinecraftServer server) {
}
