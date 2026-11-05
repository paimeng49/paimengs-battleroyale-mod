package com.paimeng.battleroyale.game;

import com.mojang.datafixers.DataFixer;
import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [BR-LOOT-SCAN 方案一] 合法刷新点库扫描器（后台线程直读磁盘 .mca，绝不占服务端主线程）。
 *
 * <p>游戏维度 region 文件开局前刚从源存档复制、还没有任何箱子，磁盘快照即完整地图，
 * 因此不需要加载区块、不需要碰已加载世界——与 {@code MapColorGenerator} 同一套
 * {@link RegionFile} + {@link NbtIo} 直读回退方案。
 *
 * <p>算法（大型建筑友好）：逐区块解码为空气/实体网格，直接记录满足“当前空气、头顶一格空气、
 * 下方实体、上方 15 格内有实体顶盖”的坐标。刷新点不再经过房间大小 Flood Fill 否决，机场、
 * 商场等超过 2000 格的大平层也能完整进入刷新点库；{@link #FLOOD_CAP} 仅保留给兼容诊断。
 * 扫描出的点进入 {@link ConcurrentLinkedQueue}，主线程定期取回形成刷新点库。
 *
 * <p>生命周期：{@link LootSpawner#start} 进入 FLIGHT 时对每个游戏维度调用
 * {@link #begin}（重入自动作废旧任务）；{@link #cancelAll} 在整局结束
 * （{@link LootSpawner#stop}）时统一取消。扫描结果只在本局有效——
 * 下一局 region 文件重新复制，必须重扫。
 *
 * <p>线程模型：扫描线程只读磁盘、只写自己的任务状态与 pending 队列；
 * 主线程只通过 {@link #drainInto} / {@link #isComplete} / 统计快照读取结果。
 */
public final class LootPointScanner {

    public static final LootPointScanner INSTANCE = new LootPointScanner();

    /** Flood Fill 扩散上限（格）：保留给兼容诊断，刷新点库不再用它否决大房间。 */
    public static final int FLOOD_CAP = 2000;
    /** 天花板判定：候选点上方 1~15 格内有实体方块。 */
    public static final int CEILING_RANGE = 15;
    /** 候选点 Y 随机扫描区间（与撒点兜底共用同一高度口径）。 */
    public static final int SCAN_MIN_Y = 60;
    public static final int SCAN_MAX_Y = 150;

    /** 网格单元编码。 */
    private static final byte CELL_AIR = 0;
    private static final byte CELL_SOLID = 1;
    private static final byte CELL_BLOCKED = 2;

    /** 区块网格 LRU 上限（个）：约 16 x 16 x 384 字节 x 12 = 不到 1.5 MB。 */
    private static final int GRID_CACHE = 12;
    /** region 文件句柄 LRU 上限。 */
    private static final int REGION_CACHE = 6;
    /** 选区单边宽度上限：坐标编码位宽 21 bit，超过即放弃扫描（不可能出现的地图尺寸）。 */
    private static final int MAX_SPAN = 1 << 21;

    private static final int CURRENT_DATA_VERSION =
            SharedConstants.getCurrentVersion().getDataVersion().getVersion();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battleroyale-loot-scan");
        t.setDaemon(true);
        return t;
    });

    /** 每个游戏维度一份扫描任务（后台线程创建、主线程查询）。 */
    private final Map<ResourceKey<Level>, DimScan> scans = new ConcurrentHashMap<>();

    private LootPointScanner() {
    }

    // ==================================================================
    // 对外接口（主线程调用）
    // ==================================================================

    /** 开始扫描某张地图的游戏维度选区；同一维度已有任务时先作废旧任务。必须在服务端主线程调用。 */
    public void begin(ServerLevel world, GameMap map) {
        if (world == null || map == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        ResourceKey<Level> key = world.dimension();
        cancel(key);
        int minX = Math.min(map.minX, map.maxX);
        int maxX = Math.max(map.minX, map.maxX);
        int minZ = Math.min(map.minZ, map.maxZ);
        int maxZ = Math.max(map.minZ, map.maxZ);
        if (maxX - minX >= MAX_SPAN || maxZ - minZ >= MAX_SPAN) {
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT-SCAN] 地图 {} 选区超过坐标编码上限，跳过刷新点库扫描",
                    map.mapId);
            return;
        }
        DimScan scan = new DimScan(key, map.mapId,
                GameWorldManager.regionDir(server, key), server.getFixerUpper(),
                minX, maxX, minZ, maxZ,
                world.getMinBuildHeight(), world.getHeight());
        scans.put(key, scan);
        EXECUTOR.execute(() -> run(scan));
        BattleRoyaleMod.LOGGER.info("[BR-LOOT-SCAN] 地图 {} 刷新点库扫描开始：选区 X {}~{} Z {}~{}，"
                        + "Y 候选 {}~{}，Flood Fill 上限 {} 格，天花板窗口 {} 格（后台线程直读磁盘，不加载区块）",
                map.mapId, minX, maxX, minZ, maxZ, SCAN_MIN_Y, SCAN_MAX_Y, FLOOD_CAP, CEILING_RANGE);
    }

    /** 主线程取回扫描出的坐标（有上限，防单 tick 抖动）。返回本次取回的数量。 */
    public int drainInto(ResourceKey<Level> key, List<BlockPos> target, int limit) {
        DimScan scan = key == null ? null : scans.get(key);
        if (scan == null) {
            return 0;
        }
        int moved = 0;
        BlockPos pos;
        while (moved < limit && (pos = scan.pending.poll()) != null) {
            target.add(pos);
            moved++;
        }
        return moved;
    }

    /** 该维度扫描是否已取回点数>0（不要求扫描完成——扫到一点就能用一点）。 */
    public boolean hasScan(ResourceKey<Level> key) {
        return key != null && scans.containsKey(key);
    }

    /** 扫描是否已结束（完成 / 出错 / 取消都算结束）。 */
    public boolean isComplete(ResourceKey<Level> key) {
        DimScan scan = key == null ? null : scans.get(key);
        return scan == null || scan.finished();
    }

    /** 已产出点数 + 待取回点数：诊断日志用。 */
    public long totalPoints(ResourceKey<Level> key) {
        DimScan scan = key == null ? null : scans.get(key);
        return scan == null ? 0 : scan.produced.get();
    }

    /** 取消指定维度的扫描（旧任务作废）。 */
    public void cancel(ResourceKey<Level> key) {
        DimScan scan = key == null ? null : scans.remove(key);
        if (scan != null) {
            scan.cancelled.set(true);
        }
    }

    /** 整局结束：全部取消。 */
    public void cancelAll() {
        for (DimScan scan : scans.values()) {
            scan.cancelled.set(true);
        }
        scans.clear();
    }

    // ==================================================================
    // 扫描线程
    // ==================================================================

    private void run(DimScan scan) {
        long t0 = System.currentTimeMillis();
        try {
            scanAllChunks(scan);
        } catch (Throwable t) {
            scan.error = t.toString();
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT-SCAN] 地图 {} 刷新点库扫描异常：{}", scan.mapId, t.toString());
        }
        scan.done = true;
        scan.ms = System.currentTimeMillis() - t0;
        if (!scan.cancelled.get()) {
            BattleRoyaleMod.LOGGER.info("[BR-LOOT-SCAN] 地图 {} 刷新点库扫描完成：区块 {} 个（磁盘缺失 {}），"
                            + "封闭室内 {} 间，开放空间 {} 处，合法刷新点 {} 个，耗时 {} 秒",
                    scan.mapId, scan.chunksScanned, scan.chunksMissing,
                    scan.roomsIndoor, scan.roomsOpen, scan.produced.get(), scan.ms / 1000.0);
        }
    }

    /** 逐区块扫描选区：区块内部只做候选点预检，Flood Fill 经网格缓存跨区块扩展。 */
    private void scanAllChunks(DimScan scan) {
        int minCX = Math.floorDiv(scan.minX, 16);
        int maxCX = Math.floorDiv(scan.maxX, 16);
        int minCZ = Math.floorDiv(scan.minZ, 16);
        int maxCZ = Math.floorDiv(scan.maxZ, 16);
        int scanMinY = Math.max(SCAN_MIN_Y, scan.minY + 1);
        int scanMaxY = Math.min(SCAN_MAX_Y, scan.minY + scan.height - 3);
        // 大型建筑不再经过 Flood Fill 房间上限判定，所有满足基础室内条件的坐标直接入库。
        for (int cz = minCZ; cz <= maxCZ && !scan.cancelled.get(); cz++) {
            for (int cx = minCX; cx <= maxCX && !scan.cancelled.get(); cx++) {
                byte[] grid = scan.gridAt(cx, cz);
                scan.chunksScanned++;
                if (grid == null) {
                    scan.chunksMissing++;
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    int x = cx * 16 + lx;
                    if (x < scan.minX || x > scan.maxX) {
                        continue;
                    }
                    for (int lz = 0; lz < 16; lz++) {
                        int z = cz * 16 + lz;
                        if (z < scan.minZ || z > scan.maxZ) {
                            continue;
                        }
                        for (int y = scanMinY; y <= scanMaxY; y++) {
                            // 只要求箱子位置和头顶一格为空气、下方实体、15格内有顶棚。
                            if (scan.cell(x, y, z) != CELL_AIR
                                    || scan.cell(x, y + 1, z) != CELL_AIR
                                    || scan.cell(x, y - 1, z) != CELL_SOLID
                                    || y > scan.surfaceY(x, z) + 5
                                    || !scan.hasCeiling(x, y, z)) {
                                continue;
                            }
                            scan.pending.add(new BlockPos(x, y, z));
                            scan.produced.incrementAndGet();
                        }
                    }
                }
            }
        }
    }

    /**
     * 从 seed 出发上限 {@value #FLOOD_CAP} 格的 BFS 扩散，只穿过空气格。
     * 全部扩散格进 visited（开放域也不重复扩展）。封闭且拿到房间格后，
     * 把房间内所有满足箱子条件的坐标（脚下实体、头顶留一格、带顶盖）记入 pending。
     */
    private void floodRoom(DimScan scan, long seed, long[] room) {
        int head = 0;
        int tail = 0;
        room[tail++] = seed;
        scan.visited.add(seed);
        while (head < tail) {
            long k = room[head++];
            int y = scan.unpackY(k);
            if (y <= scan.minY || y >= scan.minY + scan.height - 1) {
                // 碰到世界高度边界 = 上下直通虚空，判开放。
                scan.roomsOpen++;
                return;
            }
            for (int d = 0; d < 6; d++) {
                int nx = scan.unpackX(k) + DX[d];
                int ny = y + DY[d];
                int nz = scan.unpackZ(k) + DZ[d];
                if (scan.cell(nx, ny, nz) != CELL_AIR) {
                    continue; // 越界格 cell() 按实体返回，天然封边
                }
                long nk = scan.pack(nx, ny, nz);
                if (scan.visited.add(nk)) {
                    // 扩散格数撑满上限仍未被墙壁封住 = 开放空间。
                    if (tail >= room.length) {
                        scan.roomsOpen++;
                        return;
                    }
                    room[tail++] = nk;
                }
            }
        }
        // 扩散在封顶前自然结束：封闭室内。记录房间内全部合格站立点。
        scan.roomsIndoor++;
        int minY = Math.max(SCAN_MIN_Y, scan.minY + 1);
        int maxY = Math.min(SCAN_MAX_Y, scan.minY + scan.height - 3);
        for (int i = 0; i < tail; i++) {
            long k = room[i];
            int x = scan.unpackX(k);
            int y = scan.unpackY(k);
            int z = scan.unpackZ(k);
            if (y < minY || y > maxY || x < scan.minX || x > scan.maxX || z < scan.minZ || z > scan.maxZ) {
                continue;
            }
            if (scan.cell(x, y + 1, z) == CELL_AIR
                    && scan.cell(x, y - 1, z) == CELL_SOLID
                    && y <= scan.surfaceY(x, z) + 5
                    && scan.hasCeiling(x, y, z)) {
                scan.pending.add(new BlockPos(x, y, z));
                scan.produced.incrementAndGet();
            }
        }
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    // ==================================================================
    // 每个扫描任务的磁盘视图（只在扫描线程访问，非线程安全）
    // ==================================================================

    /** 一个维度的扫描运行时：任务参数快照 + 网格缓存 + visited + 产出队列 + 统计。 */
    private static final class DimScan {
        final ResourceKey<Level> key;
        final String mapId;
        final Path regionDir;
        final DataFixer fixer;
        final int minX, maxX, minZ, maxZ;
        final int minY, height;

        /** 已解码区块网格 LRU：key = ChunkPos.asLong；value = 全高度 byte 网格，null = 全空气。 */
        final LinkedHashMap<Long, byte[]> grids = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > GRID_CACHE;
            }
        };
        /** region 文件句柄 LRU（淘汰即关）。 */
        final LinkedHashMap<Long, RegionFile> regions = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, RegionFile> eldest) {
                if (size() > REGION_CACHE) {
                    try {
                        eldest.getValue().close();
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                return false;
            }
        };
        /** 全局已扩散格（防同一开放空间被不同候选点反复撑满上限）。 */
        final LongOpenHashSet visited = new LongOpenHashSet(1 << 16);
        final ConcurrentLinkedQueue<BlockPos> pending = new ConcurrentLinkedQueue<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicLong produced = new java.util.concurrent.atomic.AtomicLong();
        volatile boolean done;
        volatile String error;
        volatile long ms;
        volatile int chunksScanned;
        volatile int chunksMissing;
        volatile int roomsIndoor;
        volatile int roomsOpen;

        DimScan(ResourceKey<Level> key, String mapId, Path regionDir, DataFixer fixer,
                int minX, int maxX, int minZ, int maxZ, int minY, int height) {
            this.key = key;
            this.mapId = mapId;
            this.regionDir = regionDir;
            this.fixer = fixer;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.height = height;
        }

        boolean finished() {
            return done || cancelled.get();
        }

        // ---------------- 坐标打包（原版 BlockPos.asLong 同口径：26+26+12 位有符号） ----------------

        long pack(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38
                    | ((long) z & 0x3FFFFFFL) << 12
                    | ((long) y & 0xFFFL);
        }

        int unpackX(long k) {
            return (int) (k >> 38);
        }

        int unpackY(long k) {
            return (int) (k << 52 >> 52);
        }

        int unpackZ(long k) {
            return (int) (k << 26 >> 38);
        }

        // ---------------- 单元查询 ----------------

        /** 世界坐标单元编码：网格缺失（磁盘无区块 / 尚未写盘）一律按空气处理。 */
        byte cell(int x, int y, int z) {
            if (y < minY || y >= minY + height) {
                return CELL_BLOCKED;
            }
            byte[] grid = gridAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (grid == null) {
                return CELL_AIR;
            }
            int localX = x - Math.floorDiv(x, 16) * 16;
            int localZ = z - Math.floorDiv(z, 16) * 16;
            return grid[((y - minY) << 8) | (localZ << 4) | localX];
        }

        /** 上方 1~10 格内有实体方块（天花板/楼板）。 */
        int surfaceY(int x, int z) {
            for (int y = minY + height - 1; y >= minY; y--) {
                if (cell(x, y, z) == CELL_SOLID) {
                    return y + 1;
                }
            }
            return minY;
        }

        boolean hasCeiling(int x, int y, int z) {
            for (int d = 1; d <= CEILING_RANGE; d++) {
                if (cell(x, y + d, z) == CELL_SOLID) {
                    return true;
                }
            }
            return false;
        }

        // ---------------- 网格加载与解码 ----------------

        byte[] gridAt(int chunkX, int chunkZ) {
            long gk = ChunkPos.asLong(chunkX, chunkZ);
            byte[] cached = grids.get(gk);
            if (cached != null) {
                return cached;
            }
            if (grids.containsKey(gk)) {
                return null; // 负缓存：确认磁盘没有这个区块
            }
            byte[] grid = loadGrid(chunkX, chunkZ);
            grids.put(gk, grid);
            return grid;
        }

        /** 从 .mca 直读区块并解码三色网格；磁盘没有返回 null（按全空气处理）。 */
        private byte[] loadGrid(int chunkX, int chunkZ) {
            int rx = Math.floorDiv(chunkX, 32);
            int rz = Math.floorDiv(chunkZ, 32);
            long fk = ChunkPos.asLong(rx, rz);
            try {
                RegionFile region = regions.get(fk);
                if (region == null) {
                    Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                    if (!Files.isRegularFile(file)) {
                        regions.remove(fk);
                        return null;
                    }
                    region = new RegionFile(file, regionDir, false);
                    regions.put(fk, region);
                }
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                if (!region.hasChunk(pos)) {
                    return null;
                }
                CompoundTag root;
                try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                    if (in == null) {
                        return null;
                    }
                    root = NbtIo.read(in);
                }
                if (root == null) {
                    return null;
                }
                int version = root.contains("DataVersion", 99) ? root.getInt("DataVersion") : 0;
                if (version < CURRENT_DATA_VERSION) {
                    try {
                        root = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, root, version);
                    } catch (Throwable t) {
                        return null; // 修不动就当没有，整块按空气
                    }
                }
                return decodeGrid(root);
            } catch (Throwable t) {
                // 单区块读失败不影响整图：按空气跳过（下局复制完成后自然修正）。
                return null;
            }
        }

        /** 1.20.1 区块 NBT：sections[].block_states.{palette,data}，SimpleBitStorage 不跨 long。 */
        private byte[] decodeGrid(CompoundTag root) {
            CompoundTag chunk = root;
            if (!root.contains("sections", 9) && root.contains("Level", 10)) {
                chunk = root.getCompound("Level");
            }
            byte[] grid = new byte[height << 8];
            ListTag sections = chunk.getList("sections", 10);
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag section = sections.getCompound(i);
                if (!section.contains("block_states", 10)) {
                    continue;
                }
                int yBase = section.getByte("Y") * 16;
                int localBase = yBase - minY;
                if (localBase >= height || localBase + 16 <= 0) {
                    continue; // 世界高度之外的 section
                }
                CompoundTag blockStates = section.getCompound("block_states");
                ListTag palette = blockStates.getList("palette", 10);
                if (palette.isEmpty()) {
                    continue;
                }
                byte[] codes = new byte[palette.size()];
                for (int p = 0; p < palette.size(); p++) {
                    BlockState state = NbtUtils.readBlockState(
                            BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(p));
                    codes[p] = classify(state);
                }
                long[] data = blockStates.contains("data", 12) ? blockStates.getLongArray("data") : null;
                int bits = storageBits(palette.size());
                if (bits == 0) {
                    byte code = codes[0];
                    if (code != CELL_AIR) {
                        fillSection(grid, localBase, code);
                    }
                    continue;
                }
                SimpleBitStorage storage = new SimpleBitStorage(bits, 4096, data);
                for (int idx = 0; idx < 4096; idx++) {
                    int pi = storage.get(idx);
                    if (pi < 0 || pi >= codes.length || codes[pi] == CELL_AIR) {
                        continue;
                    }
                    int localX = idx & 15;
                    int localZ = (idx >>> 4) & 15;
                    int localY = idx >>> 8;
                    int row = localBase + localY;
                    if (row < 0 || row >= height) {
                        continue;
                    }
                    grid[(row << 8) | (localZ << 4) | localX] = codes[pi];
                }
            }
            return grid;
        }

        private static void fillSection(byte[] grid, int localBase, byte code) {
            for (int localY = 0; localY < 16; localY++) {
                int row = localBase + localY;
                if (row < 0 || row >= grid.length >> 8) {
                    continue;
                }
                int start = row << 8;
                for (int i = 0; i < 256; i++) {
                    grid[start + i] = code;
                }
            }
        }

        /** palette 位宽：与 MapColorGenerator 磁盘解码同口径（1.20.1 SimpleBitStorage 不跨 long）。 */
        private static int storageBits(int paletteSize) {
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
            int total = 0;
            for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
                total += block.getStateDefinition().getPossibleStates().size();
            }
            return Mth.ceillog2(total);
        }

        private static byte classify(BlockState state) {
            try {
                if (state.isAir()) {
                    return CELL_AIR;
                }
                return state.isSolid() ? CELL_SOLID : CELL_BLOCKED;
            } catch (Throwable t) {
                return CELL_BLOCKED;
            }
        }
    }

    /** 关服兜底：停掉扫描线程池（守护线程，正常不显式关闭也不会挡住 JVM 退出）。 */
    public static void shutdown() {
        Iterator<DimScan> it = INSTANCE.scans.values().iterator();
        while (it.hasNext()) {
            it.next().cancelled.set(true);
            it.remove();
        }
    }
}
