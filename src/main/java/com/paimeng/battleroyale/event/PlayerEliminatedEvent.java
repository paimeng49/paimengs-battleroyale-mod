package com.paimeng.battleroyale.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 玩家被正式淘汰后发布的事件。 */
public record PlayerEliminatedEvent(ServerPlayer player, DamageSource source, MinecraftServer server) {
}
