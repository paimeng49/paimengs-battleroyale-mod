package com.paimeng.battleroyale.map;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ShortOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.PackedBitStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端小地图底图预生成器。
 *
 * <p><b>三条数据来源，全在选区内</b>：
 * <ol>
 *   <li>已在内存的 FULL 区块：直接读区块的 heightmap + 方块状态；</li>
 *   <li>未加载的区块：<b>从磁盘回读</b>——复用 r72 {@code MapManager.copySelectedChunks} 的
 *       {@link RegionFile} + {@link NbtIo} 读法，打开游戏维度 region 目录里的 .mca，
 *       解出 NBT 里的 Heightmaps（原版 {@link PackedBitStorage} 解码）与 sections 的
 *       block_states palette/data，直接取“最高方块 + MapColor”，
 *       <b>不加载区块、不进世界、不占服务器区块内存</b>；游戏维度 region 里没有时
 *       再试源存档（sourceArchivePath）的 region 目录；</li>
 *   <li>磁盘也没有（从没生成过）：保持 -1，客户端按默认深灰处理。</li>
 * </ol>
 *
 * <p><b>防炸服</b>：内存区块访问仍然只用
 * {@code getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false)}，绝不触发区块生成。
 * 后台线程每处理 {@code batchSize} 个区块检查一次 JVM 已用堆，
 * 超过 {@code memoryLimit}（MB）就 {@code System.gc()} + 短暂 sleep。
 * 磁盘区块缓存按“区块行”分组，只保留最近两行，region 文件句柄随行推进关闭，
 * 缓存不会无限增长。
 *
 * <p><b>进度播报</b>：扫描分两阶段（内存收集 0~30%，像素采样 30~100%），
 * 每 5% 通过 {@code ServerPlayer.displayClientMessage(text, true)} 发一条 ActionBar
 * （主线程投递，后台线程不直接碰玩家对象），形如
 * {@code 小地图生成中: 45% | 内存: 1200MB}。
 */
public final class MapColorGenerator {
    private MapColorGenerator() {}

    /** 基准采样间隔：1 个像素代表 4x4 个方块。 */
    public static final int BLOCKS_PER_PIXEL = 4;
    /** 底图单边最大像素数：选区更大时自动抬高采样间隔，防止数组爆炸。 */
    public static final int MAX_EDGE = 2048;
    /** 磁盘也没有数据的像素的默认颜色（不透明 #1A1A1A）。 */
    public static final int COLOR_MISSING = 0xFF1A1A1A;
    /** MapColor.NONE / 空色的兜底灰。 */
    public static final int COLOR_FALLBACK_GRAY = 0x808080;
    /** 分批检查默认批大小（区块数）。 */
    public static final int DEFAULT_BATCH_SIZE = 500;
    /** JVM 已用堆默认上限告警线（MB），超过就 GC + 暂停。[BR-ARROW-AND-SPEED] 2048 → 4096。 */
    public static final int DEFAULT_MEMORY_LIMIT_MB = 4096;
    /** 每个像素最多 2x2 个子采样点（间隔 step/2），取最高的一列。 */
    private static final int SUB_SAMPLES_PER_AXIS = 2;
    /** 磁盘区块缓存只保留最近 N 个区块行，行内 chunkX 全缓存，防止无限增长。 */
    private static final int DISK_ROW_CACHE = 2;
    /** 内存收集阶段占总进度的百分比，其余给像素采样阶段。 */
    private static final int COLLECT_WEIGHT_PERCENT = 30;
    /** 触发 GC 后给 JVM 的喘息时间。 */
    private static final long GC_PAUSE_MS = 100L;
    /** 当前游戏的区块数据版本号：磁盘区块低于它就先过 DataFixer 升级（与原版加载一致）。 */
    private static final int CURRENT_DATA_VERSION =
            net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battleroyale-map-preview");
        t.setDaemon(true);
        return t;
    });
    /** 正在生成的 mapId 集合，防止同一张地图并发重复生成。 */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    /** 该地图是否正在后台生成底图。 */
    public static boolean isGenerating(String mapId) {
        return mapId != null && RUNNING.contains(mapId);
    }

    /** 兼容旧调用：默认分批与内存阈值、无 ActionBar 观众。 */
    public static String generateMapImage(GameMap map) {
        return generateMapImage(map, null, DEFAULT_BATCH_SIZE, DEFAULT_MEMORY_LIMIT_MB);
    }

    /**
     * 为一张地图发起异步底图生成。
     *
     * @param viewerUuid    接收 ActionBar 进度的玩家（可为 null，比如控制台执行）；
     *                      游戏内会收到 {@code /paimengmods manager map preview} 的进度条。
     * @param batchSize     每处理多少个区块做一次内存检查（&gt;0）。
     * @param memoryLimitMb JVM 已用堆超过多少 MB 触发 GC+暂停（&gt;0）。
     * @return {@code null} 表示任务已提交到后台线程；非 null 是失败原因（给指令回话用）。
     */
    public static String generateMapImage(GameMap map, UUID viewerUuid, int batchSize, int memoryLimitMb) {
        if (map == null || map.mapId == null) {
            return "地图对象为空";
        }
        if (batchSize <= 0 || memoryLimitMb <= 0) {
            return "batchSize 和 memoryLimit 都必须大于 0";
        }
        int minX = Math.min(map.minX, map.maxX);
        int maxX = Math.max(map.minX, map.maxX);
        int minZ = Math.min(map.minZ, map.maxZ);
        int maxZ = Math.max(map.minZ, map.maxZ);
        if (maxX <= minX || maxZ <= minZ) {
            return "地图 " + map.mapId + " 的选区无效（min/max 相同），先用选区工具圈好范围";
        }
        MinecraftServer server = GameWorldManager.server();
        if (server == null) {
            return "服务器尚未启动，无法扫描方块";
        }
        ServerLevel world = server.getLevel(GameWorldManager.worldKey(map.mapId));
        if (world == null) {
            return "地图 " + map.mapId + " 的游戏维度尚未加载，请先 /paimengmods manager game enter " + map.mapId;
        }
        if (!RUNNING.add(map.mapId)) {
            return "地图 " + map.mapId + " 的底图正在生成中，请等 ActionBar 走到 100%";
        }
        int chunkXMin = Math.floorDiv(minX, 16);
        int chunkXMax = Math.floorDiv(maxX, 16);
        int chunkZMin = Math.floorDiv(minZ, 16);
        int chunkZMax = Math.floorDiv(maxZ, 16);
        long totalChunks = (long) (chunkXMax - chunkXMin + 1) * (chunkZMax - chunkZMin + 1);
        BattleRoyaleMod.LOGGER.info("[BR-MINIMAP-FIX] mapId={} 开始异步扫描：选区 X {}~{}，Z {}~{}，"
                        + "区块范围 chunkX {}~{}、chunkZ {}~{}（共 {} 个区块）；"
                        + "batchSize={}，memoryLimit={}MB；内存只查已加载的，未加载的从磁盘回读：{}",
                map.mapId, minX, maxX, minZ, maxZ, chunkXMin, chunkXMax, chunkZMin, chunkZMax, totalChunks,
                batchSize, memoryLimitMb, GameWorldManager.regionDir(server, world.dimension()));
        EXECUTOR.execute(() -> runScan(map, world, viewerUuid, minX, maxX, minZ, maxZ,
                chunkXMin, chunkXMax, chunkZMin, chunkZMax, batchSize, memoryLimitMb));
        return null;
    }

    /** 后台线程主体；任何异常都不能弄丢 RUNNING 标记。 */
    private static void runScan(GameMap map, ServerLevel world, UUID viewerUuid,
                                int minX, int maxX, int minZ, int maxZ,
                                int chunkXMin, int chunkXMax, int chunkZMin, int chunkZMax,
                                int batchSize, int memoryLimitMb) {
        long startMs = System.currentTimeMillis();
        DiskSource disk = null;
        try {
            MinecraftServer server = world.getServer();
            int chunksInSelection = (chunkXMax - chunkXMin + 1) * (chunkZMax - chunkZMin + 1);

            // ---- 第一遍：收集“选区内 + 已在内存 + 已达 FULL”的区块引用（0~30% 进度）----
            Long2ObjectOpenHashMap<LevelChunk> loaded = new Long2ObjectOpenHashMap<>();
            loaded.defaultReturnValue(null);
            int chunksMissing = 0;
            int chunksProcessed = 0;
            int nextPercent = 5;
            for (int cz = chunkZMin; cz <= chunkZMax; cz++) {
                for (int cx = chunkXMin; cx <= chunkXMax; cx++) {
                    // 防炸服核心：最后参数 false，区块没在内存只返回 null，绝不生成。
                    net.minecraft.world.level.chunk.ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false);
                    if (chunk instanceof LevelChunk worldChunk
                            && worldChunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                        loaded.put(ChunkPos.asLong(cx, cz), worldChunk);
                    } else {
                        chunksMissing++;
                    }
                    chunksProcessed++;
                    if (chunksProcessed % batchSize == 0) {
                        memoryGuard(map.mapId, batchSize, memoryLimitMb);
                    }
                }
                int percent = (int) ((long) (cz - chunkZMin + 1) * COLLECT_WEIGHT_PERCENT / (chunkZMax - chunkZMin + 1));
                if (percent >= nextPercent) {
                    BattleRoyaleMod.LOGGER.info("[MapPreview] mapId={} 区块收集进度 {}%（内存 {}，待磁盘回读 {}）",
                            map.mapId, percent, loaded.size(), chunksMissing);
                    sendActionBar(server, viewerUuid, "小地图生成中: " + percent + "% | 内存: " + usedMemoryMb() + "MB");
                    nextPercent += 5;
                }
            }

            // ---- 像素网格：step x step 方块 = 1 像素，边长超过 MAX_EDGE 自动抬间隔 ----
            int blocksX = maxX - minX + 1;
            int blocksZ = maxZ - minZ + 1;
            int step = BLOCKS_PER_PIXEL;
            int maxSide = Math.max(blocksX, blocksZ);
            if ((maxSide + step - 1) / step > MAX_EDGE) {
                step = (maxSide + MAX_EDGE - 1) / MAX_EDGE;
            }
            int width = (blocksX + step - 1) / step;
            int height = (blocksZ + step - 1) / step;
            short[] colorIndex = new short[width * height];
            Arrays.fill(colorIndex, (short) -1);
            short[] heights = new short[width * height];
            // 毒圈弱排水用的水域网格：-1=无数据，0=陆地，1=该列最高方块是水。
            // 与色块同一次后台扫描产出——服务端主线程选圈时永远不再碰方块。
            byte[] water = new byte[width * height];
            Arrays.fill(water, (byte) -1);
            Palette palette = new Palette();

            disk = new DiskSource(server, world, map);
            int sub = Math.max(1, step / SUB_SAMPLES_PER_AXIS);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int worldBottomY = world.getMinBuildHeight();
            int pixelsValid = 0;
            int diskHits = 0;
            nextPercent = COLLECT_WEIGHT_PERCENT + 5;

            for (int pz = 0; pz < height; pz++) {
                int baseZ = minZ + pz * step;
                for (int px = 0; px < width; px++) {
                    int baseX = minX + px * step;
                    int bestY = Integer.MIN_VALUE;
                    int bestColor = 0;
                    boolean bestWater = false;
                    for (int sz = 0; sz < SUB_SAMPLES_PER_AXIS; sz++) {
                        int wz = baseZ + sz * sub;
                        // 双重夹取：既不越出像素自己的 step 区间，也不越出选区边界。
                        if (sz * sub >= step || wz > maxZ) {
                            break;
                        }
                        for (int sx = 0; sx < SUB_SAMPLES_PER_AXIS; sx++) {
                            int wx = baseX + sx * sub;
                            if (sx * sub >= step || wx > maxX) {
                                break;
                            }
                            LevelChunk memChunk = loaded.get(ChunkPos.asLong(wx >> 4, wz >> 4));
                            int topY;
                            BlockState state;
                            BlockGetter view;
                            if (memChunk != null) {
                                Heightmap.Types type = hasHeightmap(memChunk, Heightmap.Types.WORLD_SURFACE)
                                        ? Heightmap.Types.WORLD_SURFACE
                                        : Heightmap.Types.MOTION_BLOCKING;
                                if (!hasHeightmap(memChunk, type)) {
                                    continue;
                                }
                                topY = memChunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(wx & 15, wz & 15) - 1;
                                if (topY < worldBottomY) {
                                    continue; // 整列全空
                                }
                                cursor.set(wx, topY, wz);
                                state = memChunk.getBlockState(cursor);
                                view = memChunk;
                            } else {
                                DiskChunk diskChunk = disk.get(wx >> 4, wz >> 4);
                                if (diskChunk == null) {
                                    continue; // 磁盘也没有：像素保持 -1（默认深灰）
                                }
                                topY = diskChunk.topY(wx & 15, wz & 15);
                                if (topY < worldBottomY) {
                                    continue;
                                }
                                state = diskChunk.stateAt(wx & 15, topY, wz & 15);
                                cursor.set(wx, topY, wz);
                                view = null;
                            }
                            if (topY > bestY) {
                                bestY = topY;
                                bestColor = mapColorRgb(state, view, cursor);
                                // 水柱的最高方块就是 WATER 本身（冰 / 雪按陆地算，正是可站立地形）
                                bestWater = state != null && state.is(Blocks.WATER);
                            }
                        }
                    }
                    if (bestY != Integer.MIN_VALUE) {
                        int p = pz * width + px;
                        colorIndex[p] = palette.index(bestColor);
                        heights[p] = (short) bestY;
                        water[p] = (byte) (bestWater ? 1 : 0);
                        pixelsValid++;
                    }
                }
                if ((long) (pz + 1) * (chunkXMax - chunkXMin + 1) % batchSize == 0) {
                    memoryGuard(map.mapId, batchSize, memoryLimitMb);
                }
                int percent = COLLECT_WEIGHT_PERCENT
                        + (int) ((long) (pz + 1) * (100 - COLLECT_WEIGHT_PERCENT) / height);
                if (percent >= nextPercent) {
                    BattleRoyaleMod.LOGGER.info("[MapPreview] mapId={} 像素采样进度 {}%（有效色块 {}，磁盘区块 {}）",
                            map.mapId, percent, pixelsValid, disk.loadedCount);
                    sendActionBar(server, viewerUuid, "小地图生成中: " + percent + "% | 内存: " + usedMemoryMb() + "MB");
                    nextPercent += 5;
                }
                diskHits = disk.loadedCount;
            }
            disk.close();

            String diskDiag = "region 目录 " + disk.dimensionRegionDir
                    + "：回读成功 " + disk.loadedCount + "（其中经数据修复器升级 " + disk.dataFixedCount + "），"
                    + "文件缺失尝试 " + disk.fileMissingCount + "，区块表未命中 " + disk.chunkMissingCount
                    + "，损坏区块跳过 " + disk.corruptChunkCount + "，直接解析失败 " + disk.parseFailedCount
                    + "，修复后仍失败 " + disk.dataFixFailedCount;
            if (pixelsValid == 0) {
                // 一个有效像素都没有 = 结果必然全黑，不发布；下次进图会自动重试，日志留证据。
                BattleRoyaleMod.LOGGER.warn("[MapPreview] mapId={} 底图生成得到 0 个有效像素，本次结果不发布。诊断：{}；"
                        + "选区内区块 {} 个（内存 {} 个，未加载 {} 个），耗时 {} 毫秒",
                        map.mapId, diskDiag, chunksInSelection, loaded.size(), chunksMissing,
                        System.currentTimeMillis() - startMs);
                sendActionBar(server, viewerUuid, "小地图底图没读到任何区块数据，请查看日志 [MapPreview]");
                return;
            }

            MapBaseImage image = new MapBaseImage(minX, minZ, step, width, height,
                    colorIndex, heights, water, palette.toArray(),
                    chunksInSelection, loaded.size(), chunksMissing, pixelsValid,
                    System.currentTimeMillis());
            map.baseImage = image;
            sendActionBar(server, viewerUuid, "小地图生成完成: 100% | " + width + "x" + height
                    + " 色块（磁盘回读 " + diskHits + " 个区块）| 内存: " + usedMemoryMb() + "MB");
            BattleRoyaleMod.LOGGER.info("[MapPreview] 生成完毕 mapId={}：底图 {}x{} 像素（采样间隔 {}，"
                            + "色块共 {} 个，其中有效 {} 个），选区内区块 {} 个"
                            + "（内存 {} 个，未加载 {} 个），{}，耗时 {} 毫秒",
                    map.mapId, width, height, step, image.pixelCount(), pixelsValid,
                    chunksInSelection, loaded.size(), chunksMissing, diskDiag,
                    System.currentTimeMillis() - startMs);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[MapPreview] mapId={} 底图生成失败", map == null ? "?" : map.mapId, t);
        } finally {
            if (disk != null) {
                disk.close();
            }
            if (map != null && map.mapId != null) {
                RUNNING.remove(map.mapId);
            }
        }
    }

    /** MapColor → 0xRRGGBB；个别方块（讲台等）的取色会读世界，给 null view 时兜底灰。 */
    private static int mapColorRgb(BlockState state, BlockGetter view, BlockPos pos) {
        if (state == null) {
            return COLOR_FALLBACK_GRAY;
        }
        MapColor mapColor;
        try {
            mapColor = state.getMapColor(view, pos);
        } catch (Throwable ignored) {
            return COLOR_FALLBACK_GRAY;
        }
        return (mapColor == null || mapColor == MapColor.NONE || mapColor.calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL) == 0)
                ? COLOR_FALLBACK_GRAY
                : mapColor.calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL) & 0xFFFFFF;
    }

    private static int usedMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        return (int) ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
    }

    /** 分批内存闸：已用堆超过阈值就 GC + 小睡，让后台扫描给 JVM 腾出空间。 */
    private static void memoryGuard(String mapId, int batchSize, int memoryLimitMb) {
        int used = usedMemoryMb();
        if (used <= memoryLimitMb) {
            return;
        }
        BattleRoyaleMod.LOGGER.warn("[BR-MINIMAP-FIX] mapId={} 已用内存 {}MB 超过上限 {}MB（每批 {} 区块检查一次），"
                + "触发 System.gc() 并暂停 {} 毫秒", mapId, used, memoryLimitMb, batchSize, GC_PAUSE_MS);
        System.gc();
        try {
            Thread.sleep(GC_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 后台线程不直接碰玩家：投递到主线程发 ActionBar（sendMessage(text, true)）。 */
    private static void sendActionBar(MinecraftServer server, UUID viewerUuid, String message) {
        if (server == null || viewerUuid == null) {
            return;
        }
        try {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(viewerUuid);
                if (player != null) {
                    player.displayClientMessage(Component.literal(message), true);
                }
            });
        } catch (Throwable ignored) {
            // 服务器正在关服等场景：进度条丢了无所谓，不能影响扫描本身。
        }
    }

    // ==================================================================
    // 磁盘区块回读（复用 r72 copySelectedChunks 的 RegionFile + NbtIo 读法）
    // ==================================================================

    /**
     * 按区块行缓存的只读区块来源。
     *
     * <p>缓存只保留最近 {@link #DISK_ROW_CACHE} 个 chunkZ 行（像素扫描按 Z 递增推进，
     * 上一行的区块之后不会再被访问），行切换时顺带关掉不属于当前行的 region 文件句柄，
     * 所以内存占用与“选区宽度 × 2 行”成正比、与选区高度无关，配合分批内存闸可控。
     */
    private static final class DiskSource implements AutoCloseable {
        private final Path dimensionRegionDir;
        private final Path sourceArchiveRegionDir; // 可能为 null
        private final int worldHeight;
        private final int worldBottomY;
        private final DataFixer dataFixer;
        private final Map<Integer, Map<Long, DiskChunk>> rows = new LinkedHashMap<>();
        private final Map<Long, RegionFile> dimensionFiles = new HashMap<>();
        private final Map<Long, RegionFile> sourceFiles = new HashMap<>();
        /** 区块体检器与 region 句柄同生命周期：先体检再交给原版读，坏区块不会触发原版 ERROR 刷屏。 */
        private final Map<Long, RegionHealthProbe> dimensionProbes = new HashMap<>();
        private final Map<Long, RegionHealthProbe> sourceProbes = new HashMap<>();
        /** 连扇区表都读不出来的 region：整片跳过，只提示一次。 */
        private final Set<Long> unreadableDimensionRegions = new java.util.HashSet<>();
        private final Set<Long> unreadableSourceRegions = new java.util.HashSet<>();
        int loadedCount = 0;
        int fileMissingCount = 0;
        int chunkMissingCount = 0;
        int parseFailedCount = 0;
        int dataFixedCount = 0;
        int dataFixFailedCount = 0;
        /** [BR-MINIMAP-SAFE] 跳过的损坏区块数（日志限频，汇总里给总数）。 */
        int corruptChunkCount = 0;
        private int corruptChunksLogged = 0;

        /** 单条损坏区块提示的最多打印次数，超出后只数不打印。 */
        private static final int CORRUPT_LOG_LIMIT = 20;

        DiskSource(MinecraftServer server, ServerLevel world, GameMap map) {
            this.dimensionRegionDir = GameWorldManager.regionDir(server, world.dimension());
            this.worldHeight = world.getHeight();
            this.worldBottomY = world.getMinBuildHeight();
            this.dataFixer = server.getFixerUpper();
            Path archive = null;
            if (map.sourceArchivePath != null && !map.sourceArchivePath.isBlank()) {
                archive = Path.of(map.sourceArchivePath).resolve("region");
                if (!Files.isDirectory(archive)) {
                    archive = null;
                }
            }
            this.sourceArchiveRegionDir = archive;
        }

        /** 取 (chunkX, chunkZ) 的磁盘区块；没有返回 null（含负缓存，重复访问不再碰盘）。 */
        DiskChunk get(int chunkX, int chunkZ) {
            Map<Long, DiskChunk> row = rows.get(chunkZ);
            long key = ChunkPos.asLong(chunkX, chunkZ);
            if (row != null && row.containsKey(key)) {
                return row.get(key);
            }
            if (row == null) {
                row = new HashMap<>();
                rows.put(chunkZ, row);
                while (rows.size() > DISK_ROW_CACHE) {
                    // 扫描按 Z 递增推进，最老的行之后不会再访问。
                    Iterator<Integer> it = rows.keySet().iterator();
                    it.next();
                    it.remove();
                    pruneOpenFiles();
                }
            }
            DiskChunk chunk = readChunk(dimensionRegionDir, chunkX, chunkZ);
            if (chunk == null && sourceArchiveRegionDir != null) {
                chunk = readChunk(sourceArchiveRegionDir, chunkX, chunkZ);
            }
            if (chunk != null) {
                loadedCount++;
            }
            row.put(key, chunk); // null 也入表 = 负缓存
            return chunk;
        }

        private void pruneOpenFiles() {
            int minZ = Integer.MIN_VALUE;
            int maxZ = Integer.MAX_VALUE;
            for (Integer cz : rows.keySet()) {
                minZ = Math.min(minZ, Math.floorDiv(cz, 32));
                maxZ = Math.max(maxZ, Math.floorDiv(cz, 32));
            }
            pruneCache(dimensionFiles, dimensionProbes, minZ, maxZ);
            pruneCache(sourceFiles, sourceProbes, minZ, maxZ);
        }

        private static void pruneCache(Map<Long, RegionFile> files, Map<Long, RegionHealthProbe> probes,
                                       int minZ, int maxZ) {
            Iterator<Map.Entry<Long, RegionFile>> it = files.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, RegionFile> entry = it.next();
                int rz = (int) (long) entry.getKey();
                if (rz < minZ || rz > maxZ) {
                    it.remove();
                    try {
                        entry.getValue().close();
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
            }
            Iterator<Map.Entry<Long, RegionHealthProbe>> pit = probes.entrySet().iterator();
            while (pit.hasNext()) {
                Map.Entry<Long, RegionHealthProbe> entry = pit.next();
                int rz = (int) (long) entry.getKey();
                if (rz < minZ || rz > maxZ) {
                    pit.remove();
                    entry.getValue().close();
                }
            }
        }

        /**
         * 参考 r72 copySelectedChunks：RegionFile 打开 .mca → hasChunk → getChunkInputStream → NbtIo.read。
         *
         * <p>[BR-MINIMAP-SAFE] 读任何区块数据前先过 {@link RegionHealthProbe} 体检：
         * 扇区越界、region 被截断、外部 {@code .mcc} 缺失的坏区块直接按“未生成”跳过，
         * 既不会让原版 RegionFile 自己刷 {@code stream is truncated} / {@code is not file} 的 ERROR，
         * 也不会中断整轮扫描；同一 region 文件读取彻底失败只提示一次，之后整片跳过。
         */
        private DiskChunk readChunk(Path regionDir, int chunkX, int chunkZ) {
            int rx = Math.floorDiv(chunkX, 32);
            int rz = Math.floorDiv(chunkZ, 32);
            long fileKey = ChunkPos.asLong(rx, rz);
            boolean fromDimension = regionDir.equals(dimensionRegionDir);
            Map<Long, RegionFile> files = fromDimension ? dimensionFiles : sourceFiles;
            Map<Long, RegionHealthProbe> probes = fromDimension ? dimensionProbes : sourceProbes;
            Set<Long> unreadable = fromDimension ? unreadableDimensionRegions : unreadableSourceRegions;
            Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
            try {
                if (unreadable.contains(fileKey)) {
                    return null;
                }
                RegionHealthProbe probe = probes.get(fileKey);
                if (probe == null) {
                    if (!Files.isRegularFile(file)) {
                        fileMissingCount++;
                        return null;
                    }
                    try {
                        probe = RegionHealthProbe.open(file);
                        probes.put(fileKey, probe);
                    } catch (Throwable openError) {
                        unreadable.add(fileKey);
                        BattleRoyaleMod.LOGGER.warn("[BR-MINIMAP-SAFE] region 文件 {} 扇区表读不出来，整片跳过：{}",
                                file.getFileName(), openError.toString());
                        return null;
                    }
                }
                String problem = probe.problem(chunkX, chunkZ, regionDir);
                if (problem != null) {
                    noteCorruptChunk(chunkX, chunkZ, problem);
                    return null;
                }
                RegionFile region = files.get(fileKey);
                if (region == null) {
                    region = new RegionFile(file, regionDir, false);
                    files.put(fileKey, region);
                }
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                if (!region.hasChunk(pos)) {
                    chunkMissingCount++;
                    return null;
                }
                try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                    if (in == null) {
                        chunkMissingCount++;
                        return null;
                    }
                    CompoundTag raw = NbtIo.read(in);
                    int version = raw.contains("DataVersion", 99) ? raw.getInt("DataVersion") : 0;
                    DiskChunk parsed;
                    if (version >= CURRENT_DATA_VERSION) {
                        parsed = DiskChunk.parse(raw, worldHeight, worldBottomY);
                    } else {
                        // 旧格式区块：与原版加载行为一致，先过数据修复器升级到当前格式再解析。
                        try {
                            raw = DataFixTypes.CHUNK.updateToCurrentVersion(dataFixer, raw, version);
                            dataFixedCount++;
                        } catch (Throwable fixError) {
                            dataFixFailedCount++;
                            BattleRoyaleMod.LOGGER.debug("[BR-MINIMAP-FIX] 区块 {} {} 数据修复失败：{}",
                                    chunkX, chunkZ, fixError.toString());
                        }
                        parsed = DiskChunk.parse(raw, worldHeight, worldBottomY);
                    }
                    if (parsed == null) {
                        parseFailedCount++;
                    }
                    return parsed;
                }
            } catch (Throwable t) {
                // 只丢掉出问题的那一个区块（提示限频），坏句柄换掉、体检通道保留，整轮扫描继续。
                noteCorruptChunk(chunkX, chunkZ, t.toString());
                RegionFile failed = files.remove(fileKey);
                if (failed != null) {
                    try {
                        failed.close();
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
                return null;
            }
        }

        /** 坏区块限频提示：最多 {@value #CORRUPT_LOG_LIMIT} 条，之后只累计计数，扫描照常继续。 */
        private void noteCorruptChunk(int chunkX, int chunkZ, String reason) {
            corruptChunkCount++;
            if (corruptChunksLogged < CORRUPT_LOG_LIMIT) {
                corruptChunksLogged++;
                BattleRoyaleMod.LOGGER.warn("[BR-MINIMAP-SAFE] 区块 {},{} 数据损坏，跳过（{}）",
                        chunkX, chunkZ, reason);
                if (corruptChunksLogged == CORRUPT_LOG_LIMIT) {
                    BattleRoyaleMod.LOGGER.warn("[BR-MINIMAP-SAFE] 后续损坏区块不再逐条打印，最后统一汇总条数");
                }
            }
        }

        @Override
        public void close() {
            rows.clear();
            closeFiles(dimensionFiles);
            closeFiles(sourceFiles);
            closeProbes(dimensionProbes);
            closeProbes(sourceProbes);
            unreadableDimensionRegions.clear();
            unreadableSourceRegions.clear();
        }

        private static void closeProbes(Map<Long, RegionHealthProbe> probes) {
            for (RegionHealthProbe probe : probes.values()) {
                probe.close();
            }
            probes.clear();
        }

        private static void closeFiles(Map<Long, RegionFile> files) {
            for (RegionFile region : files.values()) {
                try {
                    region.close();
                } catch (IOException | RuntimeException ignored) {
                }
            }
            files.clear();
        }
    }

    /**
     * 从区块 NBT 直接解出的只读视图：优先 Heightmaps，缺失或坏值时逐 section 扫描最高方块。
     *
     * <p>1.20.1 的 section block_states data 使用不跨 long 的 {@link SimpleBitStorage}；
     * 旧存档原始 NBT 可能使用其他格式或根本没有 Heightmaps，因此解析失败时调用方会先经过
     * {@link DataFixTypes#CHUNK} 升级到当前版本，再重新解析。
     */
    private static final class DiskChunk {
        private final int[] topHeights;
        private final Map<Integer, DiskSection> sections;
        private final int bottomY;

        static DiskChunk parse(CompoundTag root, int worldHeight, int worldBottomY) {
            CompoundTag chunk = root;
            if (!root.contains("sections", 9) && root.contains("Level", 10)) {
                chunk = root.getCompound("Level");
            }
            Map<Integer, DiskSection> parsed = new HashMap<>();
            ListTag sectionList = chunk.getList("sections", 10);
            for (int i = 0; i < sectionList.size(); i++) {
                CompoundTag section = sectionList.getCompound(i);
                if (!section.contains("block_states", 10)) {
                    continue;
                }
                CompoundTag blockStates = section.getCompound("block_states");
                ListTag palette = blockStates.getList("palette", 10);
                if (palette.isEmpty()) {
                    continue;
                }
                int sectionCoord = section.getByte("Y");
                long[] data = blockStates.contains("data", 12) ? blockStates.getLongArray("data") : null;
                try {
                    parsed.put(sectionCoord, new DiskSection(palette, data));
                } catch (Throwable t) {
                    BattleRoyaleMod.LOGGER.warn("[BR-MINIMAP-FIX] section {} 解码失败，跳过：{}",
                            sectionCoord, t.toString());
                }
            }
            if (parsed.isEmpty()) {
                return null;
            }
            int[] top = readHeightmap(chunk, worldHeight, worldBottomY);
            if (top == null) {
                top = scanTopHeights(parsed, worldHeight, worldBottomY);
            }
            return new DiskChunk(top, parsed, worldBottomY);
        }

        private static int[] readHeightmap(CompoundTag chunk, int worldHeight, int worldBottomY) {
            if (!chunk.contains("Heightmaps", 10)) {
                return null;
            }
            CompoundTag heightmaps = chunk.getCompound("Heightmaps");
            String name = heightmaps.contains("WORLD_SURFACE", 12) ? "WORLD_SURFACE" : "MOTION_BLOCKING";
            if (!heightmaps.contains(name, 12)) {
                return null;
            }
            try {
                int bits = Mth.ceillog2(worldHeight + 1);
                PackedBitStorage packed = new PackedBitStorage(256, bits, heightmaps.getLongArray(name));
                int[] top = new int[256];
                boolean hasData = false;
                for (int i = 0; i < top.length; i++) {
                    int stored = packed.get(i);
                    if (stored < 0 || stored > worldHeight) {
                        return null;
                    }
                    top[i] = stored + worldBottomY - 1;
                    hasData |= stored > 0;
                }
                return hasData ? top : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static int[] scanTopHeights(Map<Integer, DiskSection> sections, int worldHeight, int worldBottomY) {
            int[] top = new int[256];
            Arrays.fill(top, worldBottomY - 1);
            int highestSection = Math.floorDiv(worldBottomY + worldHeight - 1, 16);
            for (int column = 0; column < top.length; column++) {
                int localX = column & 15;
                int localZ = column >> 4;
                for (int sectionCoord = highestSection; sectionCoord >= Math.floorDiv(worldBottomY, 16); sectionCoord--) {
                    DiskSection section = sections.get(sectionCoord);
                    if (section == null || section.isAirOnly()) {
                        continue;
                    }
                    for (int localY = 15; localY >= 0; localY--) {
                        BlockState state = section.stateAt(localX, localY, localZ);
                        if (state != null && !state.isAir()) {
                            top[column] = sectionCoord * 16 + localY;
                            break;
                        }
                    }
                    if (top[column] >= worldBottomY) {
                        break;
                    }
                }
            }
            return top;
        }

        private DiskChunk(int[] topHeights, Map<Integer, DiskSection> sections, int bottomY) {
            this.topHeights = topHeights;
            this.sections = sections;
            this.bottomY = bottomY;
        }

        int topY(int localX, int localZ) {
            return topHeights[localZ * 16 + localX];
        }

        BlockState stateAt(int localX, int worldY, int localZ) {
            DiskSection section = sections.get(Math.floorDiv(worldY, 16));
            return section == null ? null : section.stateAt(localX, worldY & 15, localZ);
        }
    }

    private static final class DiskSection {
        private final ListTag palette;
        private final SimpleBitStorage storage;
        private final BlockState[] decoded;

        DiskSection(ListTag palette, long[] rawData) {
            this.palette = palette;
            this.decoded = new BlockState[palette.size()];
            int bits = diskBits(palette.size());
            // 1.20.1 起 SimpleBitStorage 构造顺序变为 (bitsPerEntry, size, values)；
            // 1.18.2 时代是 (size, bitsPerEntry, values)。两个 int 同型，传反了编译不报错，
            // 但运行时会把 4096 当位宽送进 1..32 的校验，抛出
            // IllegalArgumentException: The value 4096 is not in the specified inclusive range of 1 to 32，
            // 导致所有磁盘区块 section 解码失败、底图 0 有效像素（latest-3.log 实锤）。
            this.storage = bits == 0 ? null : new SimpleBitStorage(bits, 4096, rawData);
        }

        private static int diskBits(int paletteSize) {
            int b = Mth.ceillog2(paletteSize);
            if (b == 0) {
                return 0;
            }
            if (b <= 4) {
                return 4;
            }
            if (b <= 8) {
                return b;
            }
            return Mth.ceillog2(sumAllBlockStates());
        }

        boolean isAirOnly() {
            if (storage != null || palette.isEmpty()) {
                return false;
            }
            return stateAt(0, 0, 0) != null && stateAt(0, 0, 0).isAir();
        }

        BlockState stateAt(int localX, int localY, int localZ) {
            int index = (localY << 8) | (localZ << 4) | localX;
            int paletteIndex = storage == null ? 0 : storage.get(index);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                return null;
            }
            BlockState state = decoded[paletteIndex];
            if (state == null) {
                state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(paletteIndex));
                decoded[paletteIndex] = state;
            }
            return state;
        }
    }

    /** MapColor 种类有限，用 short 下标压缩像素；满员后回退到 0 号色，绝不出负溢出。 */
    private static final class Palette {
        private static final int MAX_COLORS = 65534;
        private final Int2ShortOpenHashMap indexMap = new Int2ShortOpenHashMap();
        private int[] colors = new int[512];
        private int count = 0;

        Palette() {
            indexMap.defaultReturnValue((short) -1);
        }

        short index(int rgb) {
            short idx = indexMap.get(rgb);
            if (idx >= 0) {
                return idx;
            }
            if (count >= MAX_COLORS) {
                return 0;
            }
            if (count == colors.length) {
                colors = Arrays.copyOf(colors, count * 2);
            }
            colors[count] = rgb;
            indexMap.put(rgb, (short) count);
            return (short) count++;
        }

        int[] toArray() {
            return Arrays.copyOf(colors, count);
        }
    }

    /** 1.20.1 移植：Yarn 的 chunk.hasHeightmap(type) 判断该高度图是否存在。 */
    private static boolean hasHeightmap(net.minecraft.world.level.chunk.ChunkAccess chunk,
            net.minecraft.world.level.levelgen.Heightmap.Types type) {
        return chunk.getHeightmaps().stream().anyMatch(e -> e.getKey() == type);
    }

    /** 1.20.1 移植：Block.STATE_IDS 不再公开，手动累加全部方块状态数。 */
    private static int sumAllBlockStates() {
        int total = 0;
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            total += block.getStateDefinition().getPossibleStates().size();
        }
        return total;
    }
}
