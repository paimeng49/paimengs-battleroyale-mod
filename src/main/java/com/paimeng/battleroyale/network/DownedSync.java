package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** 击倒状态 S2C 同步。 */
public final class DownedSync {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "downed_sync");

    private DownedSync() {
    }

    public static void broadcast(MinecraftServer server, DownedSyncPayload payload) {
        if (server == null || payload == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendTo(player, payload);
    }

    public static void sendTo(ServerPlayer player, DownedSyncPayload payload) {
        if (player != null && payload != null && ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            ServerPlayNetworking.send(player, CHANNEL_ID, encode(payload));
        }
    }

    private static FriendlyByteBuf encode(DownedSyncPayload p) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUUID(p.playerId());
        buf.writeBoolean(p.downed());
        buf.writeVarInt(Math.max(0, p.remainingTicks()));
        buf.writeVarInt(Math.max(0, p.rescueTicks()));
        buf.writeVarInt(Math.max(0, p.rescueMaxTicks()));
        buf.writeFloat(Math.max(0.0f, p.downedHealth()));
        buf.writeBoolean(p.rescuerUuid() != null);
        if (p.rescuerUuid() != null) buf.writeUUID(p.rescuerUuid());
        buf.writeFloat(Math.max(0.0f, Math.min(1.0f, p.rescueProgress())));
        buf.writeFloat(Math.max(0.0f, p.rescueRemainingSeconds()));
        buf.writeBoolean(p.isClear());
        return buf;
    }

    public static DownedSyncPayload decode(FriendlyByteBuf buf) {
        try {
            UUID id = buf.readUUID();
            boolean downed = buf.readBoolean();
            int remainingTicks = buf.readVarInt();
            int rescueTicks = buf.readVarInt();
            int rescueMaxTicks = buf.readVarInt();
            float downedHealth = buf.readFloat();
            UUID rescuerUuid = buf.readBoolean() ? buf.readUUID() : null;
            float rescueProgress = buf.readFloat();
            float rescueRemainingSeconds = buf.readFloat();
            boolean isClear = buf.readBoolean();
            return new DownedSyncPayload(id, downed, remainingTicks, rescueTicks, rescueMaxTicks,
                    downedHealth, rescuerUuid, rescueProgress, rescueRemainingSeconds, isClear);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
