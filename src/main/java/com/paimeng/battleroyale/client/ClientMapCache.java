package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.MapTileResponsePayload;
import com.paimeng.battleroyale.network.MapTileSync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C2/C3：客户端小地图 Tile 缓存 + [BR-CRASH-AND-LOAD] 全图下载队列。
 *
 * <p>收 S2C Tile 包（每个包的头部自带底图元数据 origin / step / 像素尺寸）。
 * 上一版只按玩家位置请求 3x3 邻域、每秒最多 1 包——玩家站着不动时远处的图
 * 永远不下载，表现为「走到哪刷到哪」。
 *
 * <p>现在改为**全图排队、平滑流水、不插队**：
 * <ul>
 *   <li>收到任何一个带数据的响应包（首次拿到元数据）时，立刻算出全图所有
 *       Tile 坐标，按离玩家的距离从近到远排好，整体压入 {@link #QUEUE}；
 *       脚下永远在队首附近，无需任何插队动作；</li>
 *   <li>{@code END_CLIENT_TICK} 从队列取 Tile 发请求：**同时在途（已发出没回包）
 *       的请求最多 {@link #MAX_IN_FLIGHT} 个**，但发送受
 *       {@link #SEND_INTERVAL_MS} 节奏控制（与服务端限速合拍，超速包会被
 *       服务端直接丢弃，所以并发窗口要开大、发出去的节奏要卡准）；</li>
 *   <li>防重复：已缓存、已在队列（{@link #QUEUED} 集合）、已在途
 *       （{@link #PENDING}）的 Tile 都不会被再次排队或发送；</li>
 *   <li>回包丢失 / 被服务端限速丢弃的 Tile 超时（{@link #PENDING_TTL_MS}）后
 *       自动回到队首重试；空包（服务端还没数据）也回队尾稍后再试；</li>
 *   <li>[BR-ARROW-AND-SPEED] 取消了旧版「玩家每跨一个 Tile 就把 3x3 邻域插队」
 *       的做法：移动时插队会把远处的 Tile 永远挤到队尾，表现为「永远只加载
 *       脚下、远处不下」。全图队列本身按距玩家距离排序，脚下先亮已经成立。</li>
 * </ul>
 *
 * <p>缓存按 mapId 隔离，切图 / 断线时连队列一起清空。渲染只读 {@link #tileAt}。
 */
@Environment(EnvType.CLIENT)
public final class ClientMapCache {
    /** 缓存 Tile 上限（含排队中）；2048x2048 像素底图 = 32x32 = 1024 块。 */
    private static final int MAX_TILES = 4096;
    /**
     * [BR-ARROW-AND-SPEED] 同时在途请求上限：2 → 16。
     * 旧值 2 等于「发 2 包等回包」，单程延迟直接把吞吐砍到 2 块/秒；
     * 16 个名额让流水线在等回包时也一直有活在跑。
     */
    private static final int MAX_IN_FLIGHT = 16;
    /**
     * [BR-ARROW-AND-SPEED] 相邻两次发出的最小间隔（毫秒）。
     * 服务端对同一玩家有请求限速，超速的包会被**直接丢弃**；所以并发窗口开大
     * 的同时，发送节奏必须卡在限速之下，否则 16 包里 15 包白扔 + 超时重排队。
     */
    private static final long SEND_INTERVAL_MS = 40;
    /** [BR-ARROW-AND-SPEED] 重新排序队列的最小位移（世界方块）：不到 16 格不动队列。 */
    private static final int RESORT_MOVE_BLOCKS = 16;
    /** [BR-ARROW-AND-SPEED] 队列积压低于这个数就不值得折腾排序。 */
    private static final int RESORT_BACKLOG = 32;
    /** 在途请求超时（毫秒）：超时没回包（比如被服务端限速丢弃）就重回队列。 */
    private static final long PENDING_TTL_MS = 5000;
    /** 元数据未知时，探测性请求 (0,0) 块的最小间隔（毫秒），避免刷空包。 */
    private static final long BOOTSTRAP_INTERVAL_MS = 1000;

    /** key = mapId#tileX#tileZ。 */
    private static final Map<String, Tile> TILES = new ConcurrentHashMap<>();
    /** 在途请求 Tile → 发出时间。 */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();
    /** 在途请求 Tile → 编码坐标，超时可精确放回队列。 */
    private static final Map<String, Long> PENDING_CODES = new ConcurrentHashMap<>();
    /** 全图下载队列（先进先出，队首 = 离玩家最近 / 最紧急）。 */
    private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
    /** 队列 + 在途去重集合：只要一块 Tile 还没进 TILES，它的 key 就在这里。 */
    private static final Set<String> QUEUED = new HashSet<>();
    /** 当前缓存归属的地图。 */
    private static volatile String cachedMapId = "";
    /** 每个地图的底图元数据（来自任意一个带数据的响应包头）。 */
    private static final Map<String, Meta> META = new ConcurrentHashMap<>();
    /** 上次按之排队的全图元数据；换图 / 底图重建（尺寸变了）时重新排队。 */
    private static Meta queuedForMeta;

    private static long lastBootstrapMs;
    /** 上一次真正发出请求的时刻（毫秒）：发送节奏用，见 {@link #SEND_INTERVAL_MS}。 */
    private static long lastSendMs;
    /** 上次重排队列时玩家的块坐标（用位移阈值触发，见 {@link #RESORT_MOVE_BLOCKS}）。 */
    private static double resortAnchorX = Double.NaN;
    private static double resortAnchorZ = Double.NaN;

    private ClientMapCache() {
    }

    /** 由 {@code BattleRoyaleModClient} 在 onInitializeClient 里调用一次。 */
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(MapTileSync.RESPONSE_CHANNEL,
                (client, handler, buf, sender) -> {
                    MapTileResponsePayload payload;
                    try {
                        payload = MapTileResponsePayload.read(buf);
                    } catch (RuntimeException e) {
                        BattleRoyaleMod.LOGGER.warn("[BR-MAP-C2] Tile 响应包解码失败，已忽略", e);
                        return;
                    }
                    client.execute(() -> apply(payload));
                });
        ClientTickEvents.END_CLIENT_TICK.register(ClientMapCache::onClientTick);
    }

    /** 主线程收包入缓存 / 维护队列。 */
    private static void apply(MapTileResponsePayload payload) {
        if (payload == null) {
            return;
        }
        String mapId = payload.mapId() == null ? "" : payload.mapId();
        // 换图了：旧图缓存和队列整体作废（一次对局只在一张图上跑）。
        if (!mapId.equals(cachedMapId)) {
            clear();
            cachedMapId = mapId;
        }
        String key = key(mapId, payload.tileX(), payload.tileZ());
        PENDING.remove(key);
        Long code = PENDING_CODES.remove(key);
        if (!payload.present()) {
            // 空包 = 服务端还没有这块数据（底图生成中）：回队尾，下轮再试。
            if (code != null) {
                QUEUE.addLast(code);
            }
            return;
        }
        if (payload.step() > 0 && payload.imageWidth() > 0) {
            Meta meta = new Meta(payload.originX(), payload.originZ(), payload.step(),
                    payload.imageWidth(), payload.imageHeight(), payload.tileSize());
            META.put(mapId, meta);
            // [BR-CRASH-AND-LOAD] 核心：首次知道全图尺寸（或底图重建导致尺寸变化）
            // 时，立刻把全图所有 Tile 排进下载队列。
            if (!meta.equals(queuedForMeta)) {
                buildFullMapQueue(mapId, meta);
            }
        }
        int[][] colors = new int[payload.height()][payload.width()];
        int[][] heights = new int[payload.height()][payload.width()];
        for (int z = 0; z < payload.height(); z++) {
            for (int x = 0; x < payload.width(); x++) {
                int index = z * payload.width() + x;
                colors[z][x] = payload.colors()[index];
                heights[z][x] = payload.heights()[index];
            }
        }
        TILES.put(key, new Tile(mapId, payload.tileX(), payload.tileZ(), colors, heights));
        // 同步写入整图 DynamicTexture；渲染阶段只需一次 GuiGraphics.blit。
        MapBaseTexture.onTileCached(mapId, META.get(mapId), payload.tileX(), payload.tileZ(), colors);
        // 这块彻底到手：从去重集合销账，之后的重复响应包不会再入队。
        QUEUED.remove(key);
        if (QUEUE.isEmpty() && PENDING.isEmpty()) {
            // [BR-ARROW-AND-SPEED] 验收点：全图队列顺利清空，日志明确打出来。
            BattleRoyaleMod.LOGGER.info("[BR-MAP-C3] 全图底图下载完成：队列已清空，共缓存 {} 块（mapId={}）",
                    TILES.size(), mapId);
        }
        BattleRoyaleMod.LOGGER.info("[BR-MAP-C2] 收到 Tile 数据 mapId={} tile=({}, {}) 尺寸={}x{}，"
                        + "缓存 {} 块，队列剩 {} 块",
                mapId, payload.tileX(), payload.tileZ(), payload.width(), payload.height(),
                TILES.size(), QUEUE.size());
    }

    /** 客户端 tick：超时重试 → 移动够远就重排队列 → 按节奏从全图队列取件发请求。 */
    private static void onClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        String mapId = ClientLootPointsState.mapId();
        if (mapId == null || mapId.isEmpty()
                || !GameWorldManager.isGameWorld(client.level.dimension())) {
            return;
        }
        if (!mapId.equals(cachedMapId)) {
            clear();
            cachedMapId = mapId;
        }
        long now = System.currentTimeMillis();
        // 超时的在途请求（被服务端限速丢弃 / 丢包）放回队首重试。
        requeueExpired(mapId, now);

        Meta meta = META.get(mapId);
        if (meta == null) {
            // 还不知道底图元数据：先探测性地请求左上角第 (0,0) 块拿到 origin/step。
            // 这一步保留 1 秒节流：底图还没生成完时服务端一直回空包，不能刷它。
            String bootstrapKey = key(mapId, 0, 0);
            if (!PENDING.containsKey(bootstrapKey) && now - lastBootstrapMs >= BOOTSTRAP_INTERVAL_MS) {
                lastBootstrapMs = now;
                PENDING.put(bootstrapKey, now);
                PENDING_CODES.put(bootstrapKey, encode(0, 0));
                MapTileSync.requestTile(mapId, 0, 0);
            }
            return;
        }

        LocalPlayer player = client.player;
        // [BR-ARROW-AND-SPEED] 不再每跨 Tile 插队：那会把远处永远挤到队尾，
        // 表现为「永远只加载脚下、远处不下」。改为移动超过 16 格且队列确实
        // 积压时，对**剩余队列整体按离玩家的距离重新排序**——只调顺序、
        // 不丢任何 Tile，远处的块依然全部会下完，只是近处的先亮。
        int centerTileX = Math.floorDiv(Math.max(0, meta.pixelXOf((int) player.getX())), meta.tileSize());
        int centerTileZ = Math.floorDiv(Math.max(0, meta.pixelZOf((int) player.getZ())), meta.tileSize());
        if (needResort(player.getX(), player.getZ()) && QUEUE.size() > RESORT_BACKLOG) {
            resortQueueAround(centerTileX, centerTileZ);
            resortAnchorX = player.getX();
            resortAnchorZ = player.getZ();
        }

        // 流水发送：在途不足 MAX_IN_FLIGHT 且距上次发送已超过 SEND_INTERVAL_MS
        // 时，从队列取件补发。客户端 tick 约 50ms 一拍、节奏 40ms，一拍补 1 包；
        // 总速率 ≈ 20 包/秒，卡在服务端 33 包/秒限速之内，一发一个准，没有白扔的包。
        // 2048px 全图（1024 块）约 51 秒下完。
        while (PENDING.size() < MAX_IN_FLIGHT) {
            long sendNow = System.currentTimeMillis();
            if (sendNow - lastSendMs < SEND_INTERVAL_MS) {
                break;
            }
            Long code = pollSendable(mapId);
            if (code == null) {
                break;
            }
            lastSendMs = sendNow;
            int tx = decodeX(code);
            int tz = decodeZ(code);
            String key = key(mapId, tx, tz);
            PENDING.put(key, sendNow);
            PENDING_CODES.put(key, code);
            MapTileSync.requestTile(mapId, tx, tz);
        }
    }

    /**
     * 收到元数据时排队全图：所有合法 Tile 坐标按「离玩家所在 Tile 的距离」
     * 从近到远入队（离中心同距的按行序），保证玩家先看到身边的图亮起来。
     */
    private static void buildFullMapQueue(String mapId, Meta meta) {
        QUEUE.clear();
        QUEUED.clear();
        queuedForMeta = meta;
        Minecraft client = Minecraft.getInstance();
        int centerTileX = 0;
        int centerTileZ = 0;
        if (client.player != null) {
            centerTileX = Math.floorDiv(Math.max(0, meta.pixelXOf((int) client.player.getX())), meta.tileSize());
            centerTileZ = Math.floorDiv(Math.max(0, meta.pixelZOf((int) client.player.getZ())), meta.tileSize());
        }
        int maxTileX = (meta.imageWidth() - 1) / meta.tileSize();
        int maxTileZ = (meta.imageHeight() - 1) / meta.tileSize();
        final int cx = centerTileX;
        final int cz = centerTileZ;
        List<Long> all = new ArrayList<>((maxTileX + 1) * (maxTileZ + 1));
        outer:
        for (int tz = 0; tz <= maxTileZ; tz++) {
            for (int tx = 0; tx <= maxTileX; tx++) {
                // 内存护栏：极端大图（理论上限 16384x16384 像素）也不许把队列撑爆，
                // 近处优先排满 MAX_TILES 即可，远处留黑不影响玩法。
                if (TILES.size() + QUEUED.size() >= MAX_TILES) {
                    break outer;
                }
                String key = key(mapId, tx, tz);
                if (TILES.containsKey(key)) {
                    continue; // 已缓存的不排队
                }
                all.add(encode(tx, tz));
                QUEUED.add(key);
            }
        }
        all.sort(Comparator
                .comparingLong((Long code) -> dist2(decodeX(code) - cx, decodeZ(code) - cz))
                .thenComparingLong(code -> (long) decodeZ(code) * 65536L + decodeX(code)));
        QUEUE.addAll(all);
        // 队列刚按玩家当前位置排好：把重排锚点也定在这里，移动满 16 格才会再排。
        if (client.player != null) {
            resortAnchorX = client.player.getX();
            resortAnchorZ = client.player.getZ();
        } else {
            resortAnchorX = Double.NaN;
            resortAnchorZ = Double.NaN;
        }
        BattleRoyaleMod.LOGGER.info("[BR-MAP-C3] 全图下载队列已建立：mapId={} 底图 {}x{} 像素，"
                        + "Tile 共 {}x{}，待下载 {} 块（中心 {}#{})",
                mapId, meta.imageWidth(), meta.imageHeight(), maxTileX + 1, maxTileZ + 1,
                QUEUE.size(), cx, cz);
    }

    /** 从队列取下一个真正需要发的 Tile；途中把已缓存 / 已在途的销掉。 */
    private static Long pollSendable(String mapId) {
        while (!QUEUE.isEmpty()) {
            Long code = QUEUE.pollFirst();
            int tx = decodeX(code);
            int tz = decodeZ(code);
            String key = key(mapId, tx, tz);
            if (TILES.containsKey(key)) {
                QUEUED.remove(key);
                continue;
            }
            if (PENDING.containsKey(key)) {
                continue; // 在途：不重发，QUEUED 保留，超时后会由 requeueExpired 回队
            }
            return code;
        }
        return null;
    }

    /** 在途超时：从 PENDING 移除并放回队首，稍后重试。 */
    private static void requeueExpired(String mapId, long now) {
        Iterator<Map.Entry<String, Long>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() <= PENDING_TTL_MS) {
                continue;
            }
            it.remove();
            Long code = PENDING_CODES.remove(entry.getKey());
            if (code != null && entry.getKey().startsWith(mapId + '#')) {
                QUEUE.addFirst(code);
            }
        }
    }

    /** [BR-ARROW-AND-SPEED] 玩家离上次重排锚点超过阈值才值得再排一次。 */
    private static boolean needResort(double x, double z) {
        if (Double.isNaN(resortAnchorX)) {
            return true;
        }
        double dx = x - resortAnchorX;
        double dz = z - resortAnchorZ;
        return dx * dx + dz * dz >= (double) RESORT_MOVE_BLOCKS * RESORT_MOVE_BLOCKS;
    }

    /**
     * [BR-ARROW-AND-SPEED] 把剩余队列整体按「离玩家当前 Tile 的距离」重排。
     *
     * <p>与旧 {@code boostAround} 插队的本质区别：插队只把脚下 3x3 提到队首，
     * 玩家持续移动时等于每 64px 就把整条远端队伍往后推一格——远处永远排不到、
     * 永远不下。整体重排只是调整还没发出的 Tile 的顺序，一个都不丢：
     * 玩家停下来的瞬间，身边的先亮完，然后由近及远继续铺满全图。
     */
    private static void resortQueueAround(int centerTileX, int centerTileZ) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Long> rest = new ArrayList<>(QUEUE);
        QUEUE.clear();
        final int cx = centerTileX;
        final int cz = centerTileZ;
        rest.sort(Comparator
                .comparingLong((Long code) -> dist2(decodeX(code) - cx, decodeZ(code) - cz))
                .thenComparingLong(code -> (long) decodeZ(code) * 65536L + decodeX(code)));
        QUEUE.addAll(rest);
    }

    private static long dist2(int dx, int dz) {
        return (long) dx * dx + (long) dz * dz;
    }

    /** Tile 坐标编码进 long：高 32 位 X，低 32 位 Z。 */
    private static long encode(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    private static int decodeX(long code) {
        return (int) (code >> 32);
    }

    private static int decodeZ(long code) {
        return (int) code;
    }

    /** 读取一个已缓存的 Tile（C3 渲染接入用）。 */
    public static Tile tileAt(String mapId, int tileX, int tileZ) {
        return TILES.get(key(mapId, tileX, tileZ));
    }

    /** 当前缓存的 Tile 数量。 */
    public static int size() {
        return TILES.size();
    }

    /** 最近收到 Tile 包的地图 id；它比物资点通道的 id 更适合作为底图兜底。 */
    public static String cachedMapId() {
        return cachedMapId;
    }

    /** 全图队列剩余待下载数量（含在途），下载完为 0。 */
    public static int pendingDownloads() {
        return QUEUE.size();
    }

    /** 某张图的底图元数据；未知（还没收到任何带数据的包）时返回 null。 */
    public static Meta meta(String mapId) {
        return META.get(mapId);
    }

    /** 断线 / 换图时清空（缓存、队列、在途一起作废）。 */
    public static void clear() {
        MapBaseTexture.reset();
        TILES.clear();
        PENDING.clear();
        PENDING_CODES.clear();
        QUEUE.clear();
        QUEUED.clear();
        META.clear();
        queuedForMeta = null;
        cachedMapId = "";
        lastBootstrapMs = 0;
        lastSendMs = 0;
        resortAnchorX = Double.NaN;
        resortAnchorZ = Double.NaN;
    }

    private static String key(String mapId, int tileX, int tileZ) {
        return (mapId == null ? "" : mapId) + '#' + tileX + '#' + tileZ;
    }

    /** 一块 64x64（边缘可能更小）的像素数据。 */
    public record Tile(String mapId, int tileX, int tileZ, int[][] colors, int[][] heights) {
    }

    /** 底图元数据：像素 (0,0) 对应的世界方块与采样步长。 */
    public record Meta(int originX, int originZ, int step, int imageWidth, int imageHeight, int tileSize) {
        public int pixelXOf(int blockX) {
            return Math.floorDiv(blockX - originX, step);
        }

        public int pixelZOf(int blockZ) {
            return Math.floorDiv(blockZ - originZ, step);
        }
    }
}
