package com.paimeng.battleroyale.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * [BR-KILLFEED] 玩家被击倒（进入倒地状态）时发布。
 *
 * <p>与 {@link PlayerEliminatedEvent} 成对：击倒播报监听这个，淘汰播报监听那个。
 * killer 为 null 表示环境致死（毒圈 / 摔落 / 无人救援等）。
 */
public record PlayerDownedEvent(ServerPlayer player, DamageSource source, ServerPlayer killer, MinecraftServer server) {
}
