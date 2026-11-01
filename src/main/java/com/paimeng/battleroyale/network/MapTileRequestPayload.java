package com.paimeng.battleroyale.network;

import net.minecraft.network.FriendlyByteBuf;

/** 客户端按需请求一个地图 Tile。 */
public record MapTileRequestPayload(String mapId, int tileX, int tileZ) {
    public static final int MAX_MAP_ID_LENGTH = 64;

    public static void write(FriendlyByteBuf buf, MapTileRequestPayload payload) {
        buf.writeUtf(payload.mapId() == null ? "" : payload.mapId(), MAX_MAP_ID_LENGTH);
        buf.writeVarInt(payload.tileX());
        buf.writeVarInt(payload.tileZ());
    }

    public static MapTileRequestPayload read(FriendlyByteBuf buf) {
        String mapId = buf.readUtf(MAX_MAP_ID_LENGTH);
        int tileX = buf.readVarInt();
        int tileZ = buf.readVarInt();
        return new MapTileRequestPayload(mapId, tileX, tileZ);
    }
}
