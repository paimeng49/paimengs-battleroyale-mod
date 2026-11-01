package com.paimeng.battleroyale.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端返回的单个地图 Tile（C2 阶段：网络切片分发）。
 *
 * <p>Tile 按底图像素网格切分：Tile (tileX, tileZ) 覆盖像素区间
 * {@code [tileX*TILE_SIZE, +TILE_SIZE)} × Z 同理，右下角不足时按实际宽高发。
 *
 * <p>每包都带底图元数据（origin / step / 像素总尺寸），客户端据此能把
 * 玩家世界坐标换算成 Tile 坐标，不需要额外的“地图信息”包。
 *
 * <p>数据量：64x64 Tile = 4096 像素 ×（ARGB 4 字节 + 高度 2 字节）≈ 24 KB，
 * 远低于 1.18.2 CustomPayload 的 32767 字节 C2S / 1048576 字节 S2C 上限。
 * Tile 不存在或底图未生成时返回 present=false 的空包，不报错。
 */
public record MapTileResponsePayload(
        String mapId, int tileX, int tileZ,
        int originX, int originZ, int step, int imageWidth, int imageHeight, int tileSize,
        int width, int height, int[] colors, short[] heights) {

    /** 单 Tile 边长（像素）。 */
    public static final int TILE_SIZE = 64;
    /** 单包安全上限：超过直接不发，防炸服（原版 S2C 自定义包硬上限 1048576 字节）。 */
    public static final int MAX_SAFE_BYTES = 4096 * 6 + 256;

    /** 无数据空包（地图不存在 / 底图未生成 / Tile 越界）。 */
    public static MapTileResponsePayload empty(String mapId, int tileX, int tileZ) {
        return new MapTileResponsePayload(mapId, tileX, tileZ, 0, 0, 0, 0, 0, TILE_SIZE, 0, 0, new int[0], new short[0]);
    }

    /** 是否有有效像素数据。 */
    public boolean present() {
        return width > 0 && height > 0 && colors.length == width * height && heights.length == colors.length;
    }

    public static void write(FriendlyByteBuf buf, MapTileResponsePayload payload) {
        buf.writeUtf(payload.mapId == null ? "" : payload.mapId, MapTileRequestPayload.MAX_MAP_ID_LENGTH);
        buf.writeVarInt(payload.tileX);
        buf.writeVarInt(payload.tileZ);
        buf.writeVarInt(payload.originX);
        buf.writeVarInt(payload.originZ);
        buf.writeVarInt(payload.step);
        buf.writeVarInt(payload.imageWidth);
        buf.writeVarInt(payload.imageHeight);
        buf.writeVarInt(payload.tileSize);
        buf.writeVarInt(payload.width);
        buf.writeVarInt(payload.height);
        for (int color : payload.colors) {
            buf.writeInt(color);
        }
        for (short h : payload.heights) {
            buf.writeShort(h);
        }
    }

    /** 客户端解码：所有字段都限死上限，坏包按空包处理，不吃进内存。 */
    public static MapTileResponsePayload read(FriendlyByteBuf buf) {
        String mapId = buf.readUtf(MapTileRequestPayload.MAX_MAP_ID_LENGTH);
        int tileX = buf.readVarInt();
        int tileZ = buf.readVarInt();
        int originX = buf.readVarInt();
        int originZ = buf.readVarInt();
        int step = Math.max(0, buf.readVarInt());
        int imageWidth = clamp(buf.readVarInt(), 0, 16384);
        int imageHeight = clamp(buf.readVarInt(), 0, 16384);
        int tileSize = clamp(buf.readVarInt(), 1, TILE_SIZE);
        int width = clamp(buf.readVarInt(), 0, tileSize);
        int height = clamp(buf.readVarInt(), 0, tileSize);
        if (width == 0 || height == 0) {
            return empty(mapId, tileX, tileZ);
        }
        int count = width * height;
        if (buf.readableBytes() < count * 6) {
            return empty(mapId, tileX, tileZ);
        }
        int[] colors = new int[count];
        short[] heights = new short[count];
        for (int i = 0; i < count; i++) {
            colors[i] = buf.readInt();
        }
        for (int i = 0; i < count; i++) {
            heights[i] = buf.readShort();
        }
        return new MapTileResponsePayload(mapId, tileX, tileZ, originX, originZ, step,
                imageWidth, imageHeight, tileSize, width, height, colors, heights);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
