package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/** 本局统计的服务端到客户端同步通道。 */
public final class StatsSync {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "stats_sync");

    private StatsSync() {
    }

    public static void broadcast(MinecraftServer server, StatsSyncPayload payload) {
        if (server == null || payload == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, payload);
        }
    }

    public static void sendTo(ServerPlayer player, StatsSyncPayload payload) {
        if (player != null && payload != null && ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            ServerPlayNetworking.send(player, CHANNEL_ID, encode(payload));
        }
    }

    private static FriendlyByteBuf encode(StatsSyncPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(payload.remainingPlayers());
        buf.writeVarInt(payload.remainingTeams()); // [BR-MATCHTEAM] 存活队伍数（-1 = 大厅不画）
        buf.writeVarInt(payload.kills());
        return buf;
    }

    public static StatsSyncPayload decode(FriendlyByteBuf buf) {
        try {
            int players = buf.readVarInt();
            int teams = buf.readVarInt();
            return new StatsSyncPayload(players, teams, Math.max(0, buf.readVarInt()));
        } catch (RuntimeException e) {
            return StatsSyncPayload.empty();
        }
    }
}
