package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;

/**
 * 世界缩略图生成器（仅生成像素缓存，不含任何 HUD / 小地图渲染）。
 *
 * <p>数据来源：{@code Minecraft.world}，只读取已加载区块。
 * 所有区块访问都走 {@code ClientChunkManager.getChunk(x, z, ChunkStatus.FULL, false)}，
 * 第四个参数 required=false 时区块不存在直接返回 null，绝不会触发区块加载。
 *
 * <p>最高方块获取：{@code LevelChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE)}，
 * 1.18.2 Yarn 下该图顶部 y = heightmap.get(lx, lz) - 1（Heightmap 没有 getBlockY 方法）。
 *
 * <p>线程模型：
 * <ol>
 *   <li>客户端线程：读取并复制每列的顶部高度 + 方块颜色到普通数组；</li>
 *   <li>后台线程：在纯数组上应用高度阴影，生成最终 ARGB 像素数组；</li>
 *   <li>完成后经 {@code Minecraft.execute} 回客户端线程更新缓存。</li>
 * </ol>
 */
public final class MapThumbnailGenerator {
    private MapThumbnailGenerator() {
    }

    /** 缩略图最大边长（像素）。 */
    public static final int MAX_EDGE = 512;
    /** 未加载区块的像素颜色（不透明 #1A1A1A）。 */
    public static final int COLOR_UNLOADED = 0xFF1A1A1A;
    /** MapColor.NONE / 未命中硬编码表时的兜底灰（不透明 #808080）。 */
    public static final int COLOR_FALLBACK_GRAY = 0xFF808080;

    // ---- 硬编码颜色表（需求第 4 条） ----
    private static final int COLOR_STONE = 0x777777;
    private static final int COLOR_GRASS = 0x6A9A3C;
    private static final int COLOR_WATER = 0x3F76E4;
    private static final int COLOR_LAVA = 0xD45B12;
    private static final int COLOR_SAND = 0xD8C078;
    private static final int COLOR_DIRT = 0x8B6B4A;
    private static final int COLOR_LEAVES = 0x4F8A3A;
    private static final int COLOR_WOOD = 0x8B6A3E;
    private static final int COLOR_SNOW = 0xF2F2F2;
    private static final int COLOR_ICE = 0xDDEAF2;

    // ---- 方块类别（采样间隔 >1 时按类别投票） ----
    private static final int CAT_STONE = 0;
    private static final int CAT_GRASS = 1;
    private static final int CAT_WATER = 2;
    private static final int CAT_LAVA = 3;
    private static final int CAT_SAND = 4;
    private static final int CAT_DIRT = 5;
    private static final int CAT_LEAVES = 6;
    private static final int CAT_WOOD = 7;
    private static final int CAT_SNOW = 8;
    private static final int CAT_OTHER = 9;
    private static final int CAT_ICE = 10;
    private static final int NUM_CATS = 11;

    // ---- 像素有效性 ----
    private static final byte KIND_UNLOADED = 0; // 未加载 → #1A1A1A
    private static final byte KIND_AIR = 1;      // 空气列 → 全透明
    private static final byte KIND_BLOCK = 2;    // 有效方块 → 颜色 + 高度阴影

    /**
     * 从高度图顶部向下查找第一个有效方块时，最多再向下探几格。
     * 只在顶部是树叶/玻璃/零碎方块（或高度图给到了空气层）时才会真的探到底；
     * 常见的实心顶部一格就返回。窗口放宽到 12，保证树冠下方、岸边凹陷里的水面也能被找到。
     */
    private static final int MAX_DOWN_SCAN = 12;
    /** 向上冗余探测的格数：只为兜住「高度图顶部恰好没记到液面」的区块，正常情况多读几格空气。 */
    private static final int FLUID_UP_PROBE = 3;
    /** 探测已加载区块范围时，以玩家区块为中心向四周各探多少个区块（只是判空，不加载）。 */
    private static final int CHUNK_PROBE_RADIUS = 24;

    // ==================== 对外缓存（只允许客户端线程读写发布后的引用） ====================
    private static volatile int[] pixels;          // ARGB，行优先，宽 pxWidth
    private static volatile int pxWidth;
    private static volatile int pxHeight;
    private static volatile int originBlockX;      // 像素 (0,0) 对应的世界方块 X
    private static volatile int originBlockZ;      // 像素 (0,0) 对应的世界方块 Z
    private static volatile int sampleStep;        // 每像素覆盖的世界方块数
    private static volatile long lastGeneratedMs;  // 最近一次发布缓存的时间戳
    private static volatile ClientLevel capturedWorld;
    private static volatile String capturedDimension;
    /** 本次缓存生成时玩家所在的世界坐标，用于判断是否需要重建覆盖范围。 */
    private static volatile double generationCenterX;
    private static volatile double generationCenterZ;

    /** 后台线程用的原始采样数据（客户端线程复制出来，之后不再触碰世界对象）。 */
    private static final class Capture {
        int width;
        int height;
        int step;
        int originX;
        int originZ;
        byte[] kind;   // KIND_* 每像素一个
        byte[] cat;    // CAT_* 每像素一个（投票胜出的类别），液体统计靠它
        int[] color;   // 仅 kind==KIND_BLOCK 时有效（0xRRGGBB）
        short[] topY;  // 仅 kind==KIND_BLOCK 时有效（选中方块的 y）
        int chunksProcessed;
        long captureMillis;
        // 最终像素里的液体统计（buildPixels 阶段统计，生成完成时打印一次）
        int liquidWaterPixels;
        int liquidLavaPixels;
        int liquidIcePixels;
        /** 采样阶段命中的液体/冰方块总数，用于对比“采样到但像素里没有”的异常。 */
        int sampledLiquidBlocks;
    }

    // ==================== 对外 API ====================

    /** 缓存是否可用（ARGB 像素数组）。 */
    public static boolean isReady() {
        return pixels != null && pxWidth > 0 && pxHeight > 0;
    }

    /** 最近一次生成的 ARGB 像素数组（行优先，宽 {@link #getWidth()}）。客户端线程读取。 */
    public static int[] getPixels() {
        return pixels;
    }

    public static int getWidth() {
        return pxWidth;
    }

    public static int getHeight() {
        return pxHeight;
    }

    /** 每像素覆盖的世界方块数（采样间隔）。 */
    public static int getSampleStep() {
        return sampleStep;
    }

    /** 像素 (0,0) 覆盖区域的世界方块 X 坐标起点。 */
    public static int getOriginBlockX() {
        return originBlockX;
    }

    /** 像素 (0,0) 覆盖区域的世界方块 Z 坐标起点。 */
    public static int getOriginBlockZ() {
        return originBlockZ;
    }

    /** 缓存年龄（毫秒）；无缓存返回 Long.MAX_VALUE。 */
    public static long getAgeMillis() {
        long t = lastGeneratedMs;
        return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /** 清空缓存（退出世界等场景可用）。 */
    public static void clear() {
        pixels = null;
        pxWidth = 0;
        pxHeight = 0;
        capturedWorld = null;
        capturedDimension = null;
        generationCenterX = 0.0;
        generationCenterZ = 0.0;
        lastGeneratedMs = 0;
        lastFailedAttemptMs = 0;
    }

    /** 当前缓存对应的维度标识，供 HUD 日志和维度切换判断使用。 */
    public static String getCapturedDimension() {
        return capturedDimension;
    }

    /**
     * 游戏开始时预加载一次；运行阶段不按时间自动刷新。
     * 换世界后缓存会被清空并在下一次客户端 tick 重新生成。
     */
    private static ClientLevel lastTickWorld;
    private static String lastTickDimension;

    public static void onClientTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            lastTickWorld = null;
            lastTickDimension = null;
            return;
        }
        String dimension = client.level.dimension().location().toString();
        String rebuildReason = null;
        if (lastTickWorld != null && (lastTickWorld != client.level || !dimension.equals(lastTickDimension))) {
            rebuildReason = "维度切换";
            clear();
        }
        lastTickWorld = client.level;
        lastTickDimension = dimension;
        if (capturedWorld != null && capturedWorld != client.level) {
            rebuildReason = "维度切换";
            clear();
        }
        if (!generating && shouldRegenerate(client.player.getX(), client.player.getZ(), client.level)) {
            if (rebuildReason == null) {
                rebuildReason = pixels == null ? "首次进入" : "玩家移出 1/4 范围";
            }
            requestGenerate(rebuildReason);
        }
    }

    // ==================== 生成流程 ====================

    private static volatile boolean generating = false;
    /** 最近一次失败的重建尝试时间，用于冷却，防止采样失败时每个 tick 重试并刷日志。 */
    private static volatile long lastFailedAttemptMs;
    /** 失败后的重试冷却：5 秒。成功重建本身由 1/4 覆盖范围阈值控制频率。 */
    private static final long RETRY_COOLDOWN_MS = 5000L;

    /** 只有移动超过缓存覆盖范围四分之一，才重新采样已加载区块。 */
    private static boolean shouldRegenerate(double playerX, double playerZ, ClientLevel world) {
        long now = System.currentTimeMillis();
        if (lastFailedAttemptMs != 0 && now - lastFailedAttemptMs < RETRY_COOLDOWN_MS) {
            return false;
        }
        if (pixels == null || capturedWorld != world || pxWidth <= 0 || pxHeight <= 0 || sampleStep <= 0) {
            return true;
        }
        double coverageX = pxWidth * (double) sampleStep;
        double coverageZ = pxHeight * (double) sampleStep;
        return Math.abs(playerX - generationCenterX) > coverageX / 4.0
                || Math.abs(playerZ - generationCenterZ) > coverageZ / 4.0;
    }

    /**
     * 发起一次生成。必须在客户端线程调用（内部完成数据复制后再起后台线程）。
     * 正在生成时重复调用会被忽略。
     */
    public static void requestGenerate() {
        requestGenerate("手动请求");
    }

    private static void requestGenerate(String reason) {
        if (generating) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            return;
        }
        generating = true;
        BattleRoyaleMod.LOGGER.info("缩略图缓存重建：原因={}", reason);
        long requestStart = System.currentTimeMillis();
        Capture capture;
        try {
            capture = captureOnClientThread(world, client);
        } catch (Throwable t) {
            generating = false;
            lastFailedAttemptMs = System.currentTimeMillis();
            BattleRoyaleMod.LOGGER.warn("缩略图采样阶段异常", t);
            return;
        }
        if (capture == null) {
            generating = false;
            lastFailedAttemptMs = System.currentTimeMillis();
            return;
        }
        final ClientLevel worldRef = world;
        final double requestCenterX = client.player.getX();
        final double requestCenterZ = client.player.getZ();
        Thread worker = new Thread(() -> {
            try {
                int[] argb = buildPixels(capture);
                long doneAt = System.currentTimeMillis();
                client.execute(() -> {
                    // 维度切换后，旧线程的结果不能覆盖新维度缓存。
                    if (client.level != worldRef) {
                        generating = false;
                        return;
                    }
                    pixels = argb;
                    pxWidth = capture.width;
                    pxHeight = capture.height;
                    originBlockX = capture.originX;
                    originBlockZ = capture.originZ;
                    sampleStep = capture.step;
                    capturedWorld = worldRef;
                    capturedDimension = worldRef.dimension().location().toString();
                    generationCenterX = requestCenterX;
                    generationCenterZ = requestCenterZ;
                    lastGeneratedMs = doneAt;
                    generating = false;
                    lastFailedAttemptMs = 0;
                    BattleRoyaleMod.LOGGER.info("缩略图生成完成：像素 {}x{}，采样间隔 {}，已处理区块 {} 个，总耗时 {} 毫秒",
                            capture.width, capture.height, capture.step, capture.chunksProcessed,
                            doneAt - requestStart);
                    BattleRoyaleMod.LOGGER.info("HUD 缩略图缓存已就绪，维度={}，覆盖范围 X{}~{}, Z{}~{}，像素 {}x{}",
                            capturedDimension, capture.originX,
                            capture.originX + capture.width * capture.step - 1,
                            capture.originZ, capture.originZ + capture.height * capture.step - 1,
                            capture.width, capture.height);
                    // 验证液体是否真的进了像素：N/M/K 全为 0 时说明周围没有液体或仍未生效。
                    BattleRoyaleMod.LOGGER.info("[MapThumb] 液体像素统计：水 {}，岩浆 {}，冰 {}",
                            capture.liquidWaterPixels, capture.liquidLavaPixels, capture.liquidIcePixels);
                    if (capture.liquidWaterPixels == 0 && capture.liquidLavaPixels == 0
                            && capture.liquidIcePixels == 0 && capture.sampledLiquidBlocks > 0) {
                        BattleRoyaleMod.LOGGER.warn("[MapThumb] 采样到 {} 个液体方块，但最终像素里没有液体，请检查投票",
                                capture.sampledLiquidBlocks);
                    }
                });
            } catch (Throwable t) {
                generating = false;
                lastFailedAttemptMs = System.currentTimeMillis();
                BattleRoyaleMod.LOGGER.warn("缩略图像素生成阶段异常", t);
            }
        }, "battleroyale-thumbnail-gen");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 客户端线程：只读取已加载区块，把每列的顶部高度 + 方块类别 + 颜色复制进普通数组。
     * 全程不触碰任何会触发区块加载的方法。
     */
    private static Capture captureOnClientThread(ClientLevel world, Minecraft client) {
        long t0 = System.nanoTime();
        ChunkPos center = new ChunkPos(client.player.blockPosition());
        int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;
        Long2ObjectOpenHashMap<LevelChunk> loaded = new Long2ObjectOpenHashMap<>();
        for (int dx = -CHUNK_PROBE_RADIUS; dx <= CHUNK_PROBE_RADIUS; dx++) {
            for (int dz = -CHUNK_PROBE_RADIUS; dz <= CHUNK_PROBE_RADIUS; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                LevelChunk chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                loaded.put(ChunkPos.asLong(cx, cz), chunk);
                if (cx < minCX) minCX = cx;
                if (cx > maxCX) maxCX = cx;
                if (cz < minCZ) minCZ = cz;
                if (cz > maxCZ) maxCZ = cz;
            }
        }
        if (loaded.isEmpty()) {
            BattleRoyaleMod.LOGGER.info("缩略图：未找到任何已加载区块，跳过本次生成");
            return null;
        }
        int areaBlocksX = (maxCX - minCX + 1) * 16;
        int areaBlocksZ = (maxCZ - minCZ + 1) * 16;
        // 采样间隔 = ceil(最大区域边长 / 512)
        int step = Math.max(1, (Math.max(areaBlocksX, areaBlocksZ) + MAX_EDGE - 1) / MAX_EDGE);
        int width = (areaBlocksX + step - 1) / step;
        int height = (areaBlocksZ + step - 1) / step;
        int originX = minCX * 16;
        int originZ = minCZ * 16;
        int bottomY = world.getMinBuildHeight();
        int topY = world.getMaxBuildHeight();

        Capture cap = new Capture();
        cap.width = width;
        cap.height = height;
        cap.step = step;
        cap.originX = originX;
        cap.originZ = originZ;
        cap.kind = new byte[width * height];
        cap.cat = new byte[width * height];
        cap.color = new int[width * height];
        cap.topY = new short[width * height];
        cap.chunksProcessed = loaded.size();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[] counts = new int[NUM_CATS];
        int[] sumR = new int[NUM_CATS];
        int[] sumG = new int[NUM_CATS];
        int[] sumB = new int[NUM_CATS];
        int[] catOut = new int[1];
        // 采样阶段的液体方块数（诊断用：和最终像素统计对比，能看出是不是被投票吃掉了）
        int sampleWater = 0;
        int sampleLava = 0;
        int sampleIce = 0;

        for (int py = 0; py < height; py++) {
            int zLo = originZ + py * step;
            int zHi = Math.min(originZ + (py + 1) * step - 1, originZ + areaBlocksZ - 1);
            for (int px = 0; px < width; px++) {
                int xLo = originX + px * step;
                int xHi = Math.min(originX + (px + 1) * step - 1, originX + areaBlocksX - 1);
                java.util.Arrays.fill(counts, 0);
                java.util.Arrays.fill(sumR, 0);
                java.util.Arrays.fill(sumG, 0);
                java.util.Arrays.fill(sumB, 0);
                int heightSum = 0;
                int heightCount = 0;
                boolean anyLoaded = false;

                for (int wz = zLo; wz <= zHi; wz++) {
                    for (int wx = xLo; wx <= xHi; wx++) {
                        LevelChunk chunk = loaded.get(ChunkPos.asLong(wx >> 4, wz >> 4));
                        if (chunk == null) {
                            continue; // 未加载区块：不计入投票，整像素都未加载时输出 #1A1A1A
                        }
                        anyLoaded = true;
                        // 高度图顶部（1.18.2 Yarn：get(lx, lz) 返回顶部方块 y+1；
                        // 主用 WORLD_SURFACE，个别区块缺失时退到 MOTION_BLOCKING）
                        Heightmap.Types type = hasHeightmap(chunk, Heightmap.Types.WORLD_SURFACE)
                                ? Heightmap.Types.WORLD_SURFACE
                                : Heightmap.Types.MOTION_BLOCKING;
                        if (!hasHeightmap(chunk, type)) {
                            continue;
                        }
                        int topBlockY = chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(wx & 15, wz & 15) - 1;
                        int idx = sampleColumn(chunk, cursor, catOut, wx, wz, topBlockY, bottomY, topY);
                        if (idx < 0) {
                            continue; // 空气列
                        }
                        cursor.set(wx, idx, wz);
                        int color = colorForState(catOut[0], chunk, cursor);
                        int cat = catOut[0];
                        if (cat == CAT_WATER) sampleWater++;
                        if (cat == CAT_LAVA) sampleLava++;
                        if (cat == CAT_ICE) sampleIce++;
                        counts[cat]++;
                        sumR[cat] += (color >> 16) & 0xFF;
                        sumG[cat] += (color >> 8) & 0xFF;
                        sumB[cat] += color & 0xFF;
                        heightSum += idx;
                        heightCount++;
                    }
                }

                int p = py * width + px;
                if (!anyLoaded) {
                    cap.kind[p] = KIND_UNLOADED;
                    cap.cat[p] = (byte) CAT_OTHER;
                    cap.color[p] = COLOR_UNLOADED & 0xFFFFFF;
                    cap.topY[p] = 0;
                    continue;
                }
                int bestCat = -1;
                int bestCount = 0;
                for (int c = 0; c < NUM_CATS; c++) {
                    if (counts[c] > bestCount) { // 间隔为 1 时天然等价于“取该列顶部方块”
                        bestCount = counts[c];
                        bestCat = c;
                    }
                }
                // 打平时偏向液体/冰：水域边缘的像素不会被岸上的草、沙抢走
                if (bestCount > 0) {
                    if (counts[CAT_WATER] == bestCount && counts[CAT_WATER] > 0) bestCat = CAT_WATER;
                    if (counts[CAT_LAVA] == bestCount && counts[CAT_LAVA] > 0) bestCat = CAT_LAVA;
                    if (counts[CAT_ICE] == bestCount && counts[CAT_ICE] > 0) bestCat = CAT_ICE;
                }
                if (bestCat < 0 || heightCount == 0) {
                    cap.kind[p] = KIND_AIR;
                    cap.cat[p] = (byte) CAT_OTHER;
                    continue;
                }
                cap.kind[p] = KIND_BLOCK;
                cap.cat[p] = (byte) bestCat;
                cap.color[p] = ((sumR[bestCat] / bestCount) << 16)
                        | ((sumG[bestCat] / bestCount) << 8)
                        | (sumB[bestCat] / bestCount);
                cap.topY[p] = (short) (heightSum / heightCount);
            }
        }
        cap.captureMillis = (System.nanoTime() - t0) / 1_000_000;
        cap.sampledLiquidBlocks = sampleWater + sampleLava + sampleIce;
        BattleRoyaleMod.LOGGER.info("缩略图采样完成：已处理区块 {} 个，区域 {}x{} 方块，采样间隔 {}，客户端采样耗时 {} 毫秒",
                cap.chunksProcessed, areaBlocksX, areaBlocksZ, step, cap.captureMillis);
        BattleRoyaleMod.LOGGER.info("[MapThumb] 采样阶段液体方块数：水 {}，岩浆 {}，冰 {}",
                sampleWater, sampleLava, sampleIce);
        return cap;
    }

    /**
     * 从高度图顶部向下找这一列要显示的方块。
     *
     * <p><b>液体能显示出来的关键规则</b>：从上往下看，遇到的<b>第一个</b>「实心方块」或「液体」
     * 就是这一列的顶部，立刻采用并停止向下扫描。旧写法把「实心(3) > 液体(2)」当成全局优先级，
     * 于是在 8 格窗口内一直挖到水下的池底泥土/沙（isOpaque=true 优先级 3），把水面反超掉——
     * 这就是水深不足 8 格时水域一直画成泥土色的根因。
     *
     * <p>需求里的优先级 实心 → 液体 → 树叶/玻璃 → 空气 在这里的含义是：
     * 同一层里实心最优先；树叶/玻璃只作为后备（继续往下找更值钱的方块），
     * 但绝不越过已经遇到的水面去挖池底。液体用 {@code FluidState.isEmpty()} 判断，
     * 静止水和流动水都算，不用 isStill()/isSource()/level 过滤。
     *
     * @return 选中方块的 y；-1 表示扫描窗口内全是空气
     */
    private static int sampleColumn(LevelChunk chunk, BlockPos.MutableBlockPos cursor, int[] catOut,
                                    int wx, int wz, int topBlockY, int bottomY, int topY) {
        int fallbackY = -1;
        int fallbackCat = CAT_OTHER;
        // 从高度图顶部再往上探几格：万一个别区块的 WORLD_SURFACE 没把水记进顶部，
        // 液面在顶部方块之上时也能被捞出来（需求第 5 条：液体上方的空气层不能跳过整列）。
        int yHi = Math.min(topBlockY + FLUID_UP_PROBE, topY - 1);
        int yLo = Math.max(topBlockY - (MAX_DOWN_SCAN - 1), bottomY);
        for (int y = yHi; y >= yLo; y--) {
            cursor.set(wx, y, wz);
            BlockState state = chunk.getBlockState(cursor);
            FluidState fluid = chunk.getFluidState(cursor);
            if (fluid.isEmpty()) {
                // 个别区块的流体子表没填全时，用方块状态自带的流体兜底
                fluid = state.getFluidState();
            }
            if (state.isAir() && fluid.isEmpty()) {
                continue; // 空气层：继续往下，不因为这列上方有空气就跳过整列
            }
            int cat = classify(state, fluid);
            if (cat == CAT_WATER || cat == CAT_LAVA) {
                catOut[0] = cat; // 液面就是顶部，绝不继续往下挖到池底
                return y;
            }
            if (cat == CAT_ICE || state.isSolid()) {
                catOut[0] = cat; // 实心方块（含冰）：最高优先级，直接采用
                return y;
            }
            if (fallbackY < 0) {
                // 树叶 / 玻璃 / 零碎方块：先记住最靠上的一个，再往下找实心或液体
                fallbackY = y;
                fallbackCat = cat;
            }
        }
        if (fallbackY < 0) {
            return -1;
        }
        catOut[0] = fallbackCat;
        return fallbackY;
    }

    /**
     * 方块类别（硬编码表），供投票与颜色兜底判断使用。
     * 液体判断只看 {@code !fluid.isEmpty()}：水源和流动水都算液体。
     */
    private static int classify(BlockState state, FluidState fluid) {
        if (!fluid.isEmpty()) {
            if (fluid.is(FluidTags.WATER)) return CAT_WATER;
            if (fluid.is(FluidTags.LAVA)) return CAT_LAVA;
            return CAT_OTHER; // 其他模组流体：走地图色兜底
        }
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)) {
            return CAT_ICE;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DEEPSLATE)) {
            return CAT_STONE;
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            return CAT_GRASS;
        }
        if (state.is(BlockTags.LEAVES)) {
            return CAT_LEAVES;
        }
        if (state.is(BlockTags.LOGS)) {
            return CAT_WOOD;
        }
        if (state.is(Blocks.SAND)) {
            return CAT_SAND;
        }
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
            return CAT_SNOW;
        }
        if (state.is(BlockTags.DIRT)) {
            return CAT_DIRT;
        }
        return CAT_OTHER;
    }

    /** 硬编码颜色 → BlockState.getMapColor() 兜底 → CLEAR 用 #808080。 */
    private static int colorForState(int cat, LevelChunk chunk, BlockPos pos) {
        switch (cat) {
            case CAT_STONE: return COLOR_STONE;
            case CAT_GRASS: return COLOR_GRASS;
            case CAT_WATER: return COLOR_WATER;
            case CAT_LAVA: return COLOR_LAVA;
            case CAT_SAND: return COLOR_SAND;
            case CAT_DIRT: return COLOR_DIRT;
            case CAT_LEAVES: return COLOR_LEAVES;
            case CAT_WOOD: return COLOR_WOOD;
            case CAT_ICE: return COLOR_ICE;   // 冰 → #DDEAF2
            case CAT_SNOW: return COLOR_SNOW;
            default: {
                BlockState state = chunk.getBlockState(pos);
                net.minecraft.world.level.material.MapColor mapColor = state.getMapColor(chunk, pos);
                if (mapColor == null || mapColor == net.minecraft.world.level.material.MapColor.NONE || mapColor.calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL) == 0) {
                    return COLOR_FALLBACK_GRAY;
                }
                return mapColor.calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL) & 0xFFFFFF;
            }
        }
    }

    /**
     * 后台线程：在纯数组上应用高度阴影并输出最终 ARGB。
     * 坡度 = 0.5×(h−hL) + 0.5×(h−hU)，单项高度差裁剪到 -16~16；
     * 亮度系数 = clamp(1.0 + 坡度×0.04, 0.65, 1.25)。
     */
    private static int[] buildPixels(Capture cap) {
        int w = cap.width;
        int h = cap.height;
        int[] out = new int[w * h];
        int water = 0;
        int lava = 0;
        int ice = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int p = py * w + px;
                byte kind = cap.kind[p];
                if (kind == KIND_UNLOADED) {
                    out[p] = COLOR_UNLOADED;
                    continue;
                }
                if (kind == KIND_AIR) {
                    out[p] = 0x00000000; // 空气列：透明像素
                    continue;
                }
                // 液体统计：按最终胜出的像素类别数，和屏幕上真正看到的像素一致
                byte cat = cap.cat[p];
                if (cat == CAT_WATER) water++;
                else if (cat == CAT_LAVA) lava++;
                else if (cat == CAT_ICE) ice++;
                int hh = cap.topY[p];
                int dhL = px > 0 ? clampDiff(hh - cap.topY[p - 1]) : 0;
                int dhU = py > 0 ? clampDiff(hh - cap.topY[p - w]) : 0;
                double slope = 0.5 * dhL + 0.5 * dhU;
                double factor = Math.max(0.65, Math.min(1.25, 1.0 + slope * 0.04));
                int r = (int) Math.min(255, ((cap.color[p] >> 16) & 0xFF) * factor);
                int g = (int) Math.min(255, ((cap.color[p] >> 8) & 0xFF) * factor);
                int b = (int) Math.min(255, (cap.color[p] & 0xFF) * factor);
                out[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        cap.liquidWaterPixels = water;
        cap.liquidLavaPixels = lava;
        cap.liquidIcePixels = ice;
        return out;
    }

    private static int clampDiff(int d) {
        return d < -16 ? -16 : Math.min(d, 16);
    }

    /** 1.20.1 移植：Yarn 的 chunk.hasHeightmap(type) 判断该高度图是否存在。 */
    private static boolean hasHeightmap(net.minecraft.world.level.chunk.ChunkAccess chunk,
            net.minecraft.world.level.levelgen.Heightmap.Types type) {
        return chunk.getHeightmaps().stream().anyMatch(e -> e.getKey() == type);
    }
}
