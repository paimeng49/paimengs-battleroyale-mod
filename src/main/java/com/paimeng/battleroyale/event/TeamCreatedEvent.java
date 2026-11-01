package com.paimeng.battleroyale.event;

import com.paimeng.battleroyale.team.Team;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 队伍创建事件：某人执行 /paimengmods br team create 成功后，由 {@code TeamManager}
 * 通过 {@link EventBus#publish(Object)} 发布。
 *
 * <p>和 GameStateChangedEvent 一样：server 允许为 null（防御），订阅者判空。
 * Java 17 record，升级到高版本原样可用。
 */
public record TeamCreatedEvent(Team team, UUID leaderId, String leaderName, MinecraftServer server) {
}
