package com.paimeng.battleroyale.event;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 玩家离开队伍事件：/paimengmods br team leave、队长解散、或断线被移出时发布。
 *
 * @param teamId       离开的队伍ID（队伍可能已解散，别再拿对象）
 * @param teamNumber   离开的队伍编号（供日志/以后战报用）
 * @param playerId     离开者 UUID
 * @param playerName   离开者名字
 * @param memberNumber 离开者原来占的队内编号
 * @param disbanded    true = 他走后队伍空了，已自动解散
 * @param server       允许为 null（防御），订阅者判空
 */
public record PlayerLeftTeamEvent(UUID teamId, int teamNumber, UUID playerId, String playerName,
                                  int memberNumber, boolean disbanded, MinecraftServer server) {
}
