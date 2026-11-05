package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.client.ClientParachuteState;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** 服务端权威广播玩家是否已经打开降落伞。 */
public final class ParachuteSync {
    public static final ResourceLocation CHANNEL =
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "parachute_state");

    private ParachuteSync() {
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, sender) -> {
            UUID id = buf.readUUID();
            boolean active = buf.readBoolean();
            client.execute(() -> {
                boolean prev = ClientParachuteState.isParachuting(id);
                ClientParachuteState.set(id, active);
                if (prev != active) {
                    // [BR-PARA-DEBUG] 状态包到达证据：有这行 = 包收到了；没有 = 网络层问题。
                    BattleRoyaleMod.LOGGER.info("[BR-PARA] 客户端收到开伞状态包 uuid={} active={}", id, active);
                }
            });
        });
    }

    public static void broadcast(ServerLevel world, UUID id, boolean active) {
        if (world == null || id == null) {
            return;
        }
        for (ServerPlayer viewer : world.getPlayers(p -> true)) {
            send(viewer, id, active);
        }
    }

    public static void send(ServerPlayer viewer, UUID id, boolean active) {
        if (viewer == null || id == null || !ServerPlayNetworking.canSend(viewer, CHANNEL)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUUID(id);
        buf.writeBoolean(active);
        ServerPlayNetworking.send(viewer, CHANNEL, buf);
    }
}
