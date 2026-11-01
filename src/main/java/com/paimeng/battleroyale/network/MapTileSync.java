package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.MapBaseImage;
import com.paimeng.battleroyale.map.MapManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C2：服务端地图底图的网络切片分发。
 *
 * <p>客户端按需发 {@code map_tile_request}（单 Tile 坐标），服务端从
 * {@link GameMap#baseImage}（C1 的 MapColorGenerator 产物，不在主线程死循环发）
 * 切出 64x64 的小块，回 {@code map_tile_response}。底图不存在 / Tile 越界时
 * 回空包（present=false），不抛错。
 *
 * <p>防炸服：单包体积校验（超 {@link MapTileResponsePayload#MAX_SAFE_BYTES} 不发）、
 * 每玩家请求间隔限速（超频请求直接丢弃并打一次警告日志；
 * [BR-ARROW-AND-SPEED] 500ms 放宽到 30ms，见 {@link #MIN_INTERVAL_MS}）。
 */
public final class MapTileSync {
    public static final ResourceLocation REQUEST_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "map_tile_request");
    public static final ResourceLocation RESPONSE_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "map_tile_response");

    /**
     * [BR-ARROW-AND-SPEED] 每玩家请求最小间隔（毫秒），防客户端刷包。
     *
     * <p>原值 500：等于服务端硬性把底图下发限速在 2 块/秒——1024 块的 2048px
     * 全图要 8.5 分钟，客户端把并发开到 16 也没用（超频的包在这里被直接丢弃）。
     * 放宽到 30ms（≈33 块/秒）；客户端按每 tick（≈50ms）1 包的节奏发，
     * 实际 ≈20 块/秒、全图约 51 秒下完，留有余量，超速丢弃只会发生在极端情况。
     * 这里只调了限流时间常数，请求/响应包结构（payload 字段与编解码）完全未动。
     */
    private static final long MIN_INTERVAL_MS = 30;
    /** 超限警告只按玩家去重打印，避免日志刷屏。 */
    private static final Map<UUID, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_WARN_MS = new ConcurrentHashMap<>();

    private MapTileSync() {
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_CHANNEL, (server, player, handler, buf, response) -> {
            MapTileRequestPayload request;
            try {
                request = MapTileRequestPayload.read(buf);
            } catch (RuntimeException e) {
                BattleRoyaleMod.LOGGER.warn("[BR-MAP-C2] 地图 Tile 请求包格式无效，已忽略", e);
                return;
            }
            if (request.mapId() == null || request.mapId().isBlank()
                    || request.mapId().length() > MapTileRequestPayload.MAX_MAP_ID_LENGTH
                    || Math.abs((long) request.tileX()) > 1_000_000
                    || Math.abs((long) request.tileZ()) > 1_000_000) {
                return;
            }
            // 限速：同一玩家 MIN_INTERVAL_MS 内的重复请求直接丢弃。
            long now = System.currentTimeMillis();
            Long last = LAST_REQUEST_MS.get(player.getUUID());
            if (last != null && now - last < MIN_INTERVAL_MS) {
                Long lastWarn = LAST_WARN_MS.get(player.getUUID());
                if (lastWarn == null || now - lastWarn > 5000) {
                    LAST_WARN_MS.put(player.getUUID(), now);
                    BattleRoyaleMod.LOGGER.warn("[BR-MAP-C2] 玩家 {} 请求 Tile 过快，本次丢弃", player.getName().getString());
                }
                return;
            }
            LAST_REQUEST_MS.put(player.getUUID(), now);
            // baseImage 是 volatile + 构造后不可变，MapManager.getMap 本身 synchronized，
            // 切片只是内存拷贝，留在主线程执行最稳妥（也绝不会有死循环）。
            final MapTileRequestPayload safe = request;
            server.execute(() -> sendTile(player, safe));
        });
    }

    /** 服务端断线时清掉该玩家的限速记录。 */
    public static void forget(ServerPlayer player) {
        if (player != null) {
            LAST_REQUEST_MS.remove(player.getUUID());
            LAST_WARN_MS.remove(player.getUUID());
        }
    }

    private static void sendTile(ServerPlayer player, MapTileRequestPayload request) {
        if (player == null || !ServerPlayNetworking.canSend(player, RESPONSE_CHANNEL)) {
            return;
        }
        GameMap map = MapManager.INSTANCE.getMap(request.mapId());
        MapBaseImage image = map == null ? null : map.baseImage;
        MapTileResponsePayload payload = image == null
                ? MapTileResponsePayload.empty(request.mapId(), request.tileX(), request.tileZ())
                : slice(image, request);
        FriendlyByteBuf out = PacketByteBufs.create();
        MapTileResponsePayload.write(out, payload);
        if (out.readableBytes() > MapTileResponsePayload.MAX_SAFE_BYTES) {
            BattleRoyaleMod.LOGGER.warn("[BR-MAP-C2] Tile 包 {} 字节超过安全上限，已改发空包 mapId={} tile=({}, {})",
                    out.readableBytes(), request.mapId(), request.tileX(), request.tileZ());
            out = PacketByteBufs.create();
            MapTileResponsePayload.write(out, MapTileResponsePayload.empty(request.mapId(), request.tileX(), request.tileZ()));
        }
        ServerPlayNetworking.send(player, RESPONSE_CHANNEL, out);
        BattleRoyaleMod.LOGGER.info("[BR-MAP-C2] 收到地图 Tile 请求 mapId={} tile=({}, {})，已发送回客户端（{} 字节，{}）",
                request.mapId(), request.tileX(), request.tileZ(), out.readableBytes(),
                payload.present() ? payload.width() + "x" + payload.height() + " 有数据" : "空包");
    }

    /** 从底图切一块 Tile；越界回空包。 */
    private static MapTileResponsePayload slice(MapBaseImage image, MapTileRequestPayload request) {
        int startX = request.tileX() * MapTileResponsePayload.TILE_SIZE;
        int startZ = request.tileZ() * MapTileResponsePayload.TILE_SIZE;
        if (startX < 0 || startZ < 0 || startX >= image.width || startZ >= image.height) {
            return MapTileResponsePayload.empty(request.mapId(), request.tileX(), request.tileZ());
        }
        int width = Math.min(MapTileResponsePayload.TILE_SIZE, image.width - startX);
        int height = Math.min(MapTileResponsePayload.TILE_SIZE, image.height - startZ);
        int[] colors = new int[width * height];
        short[] heights = new short[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                colors[index] = image.colorAt(startX + x, startZ + z);
                heights[index] = (short) image.heightAt(startX + x, startZ + z);
            }
        }
        return new MapTileResponsePayload(request.mapId(), request.tileX(), request.tileZ(),
                image.originX, image.originZ, image.step, image.width, image.height,
                MapTileResponsePayload.TILE_SIZE, width, height, colors, heights);
    }

    // ==================================================================
    // 客户端侧：发送请求（按需调用，不循环刷包）
    // ==================================================================

    /** 客户端按需请求一个 Tile；由 {@code ClientMapCache} 的按需逻辑调用。 */
    public static void requestTile(String mapId, int tileX, int tileZ) {
        if (mapId == null || mapId.isBlank()) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        MapTileRequestPayload.write(buf, new MapTileRequestPayload(mapId, tileX, tileZ));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(REQUEST_CHANNEL, buf);
    }
}
