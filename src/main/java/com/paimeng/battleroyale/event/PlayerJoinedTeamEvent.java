package com.paimeng.battleroyale.event;

import com.paimeng.battleroyale.team.Team;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 玩家加入队伍事件：被邀请者执行 /paimengmods br team accept 成功后发布。
 *
 * @param team         加入的队伍
 * @param playerId     加入者 UUID
 * @param playerName   加入者名字
 * @param memberNumber 分配到的队内编号（1-99）
 * @param server       允许为 null（防御），订阅者判空
 */
public record PlayerJoinedTeamEvent(Team team, UUID playerId, String playerName,
                                    int memberNumber, MinecraftServer server) {
}
