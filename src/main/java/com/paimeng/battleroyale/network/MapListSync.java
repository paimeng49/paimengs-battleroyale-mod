package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.MapManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;

/** 地图管理列表请求、删除和服务端响应。 */
public final class MapListSync {
    public static final ResourceLocation REQUEST_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "map_list_request");
    public static final ResourceLocation DELETE_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "map_list_delete");
    public static final ResourceLocation RESPONSE_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "map_list_response");

    private MapListSync() {}

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_CHANNEL, (server, player, handler, buf, response) -> server.execute(() -> {
            if (player.createCommandSourceStack().hasPermission(2)) send(player);
        }));
        ServerPlayNetworking.registerGlobalReceiver(DELETE_CHANNEL, (server, player, handler, buf, response) -> {
            String id;
            try { id = buf.readUtf(64); } catch (RuntimeException e) { return; }
            final String mapId = id;
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2) || mapId.isBlank()) return;
                MapManager.INSTANCE.deleteMap(mapId);
                send(player);
            });
        });
    }

    public static void send(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, RESPONSE_CHANNEL)) return;
        var buf = PacketByteBufs.create();
        MapListSyncPayload.write(buf, new ArrayList<>(MapManager.INSTANCE.listMaps()));
        ServerPlayNetworking.send(player, RESPONSE_CHANNEL, buf);
    }

    public static void requestList() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(REQUEST_CHANNEL, PacketByteBufs.create());
    }

    public static void requestDelete(String mapId) {
        var buf = PacketByteBufs.create();
        buf.writeUtf(mapId == null ? "" : mapId, 64);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(DELETE_CHANNEL, buf);
    }
}
