package com.paimeng.battleroyale.map;

/**
 * 服务端预生成的小地图底图（本轮只存在于服务端内存，不落盘、不下发）。
 *
 * <p>数据是一行 {@link #width} 个像素、共 {@link #height} 行的网格：
 * 像素 (px, pz) 覆盖世界方块区间
 * {@code [originX + px*step, originX + (px+1)*step - 1]} × Z 同理，
 * 记录该区间内「最高非空气方块」的 MapColor 与高度。
 *
 * <p>颜色用调色板压缩：{@link #colorIndex} 每像素一个 short（调色板下标），
 * 而不是 24 位色直接存 int，2048x2048 的底图两个 short 数组共约 16 MB。
 * {@code colorIndex == -1} 表示该像素所在的区块未加载，按默认色处理。
 *
 * <p>本类构造后所有数组不再修改，可被主线程安全读取。
 */
public final class MapBaseImage {
    /** 像素 (0,0) 对应的世界方块 X（= 生成时的选区 minX）。 */
    public final int originX;
    /** 像素 (0,0) 对应的世界方块 Z（= 生成时的选区 minZ）。 */
    public final int originZ;
    /** 每个像素覆盖 step x step 个方块。 */
    public final int step;
    /** 像素列数。 */
    public final int width;
    /** 像素行数。 */
    public final int height;
    /** 每像素的调色板下标；-1 = 未加载 / 无数据。 */
    public final short[] colorIndex;
    /** 每像素的最高非空气方块 y（colorIndex &lt; 0 时无意义）。 */
    public final short[] heights;
    /**
     * 每像素「该列最高方块是不是水」：1=水，0=陆地，-1=无数据。
     * 毒圈弱排水算法（{@code zone.ZonePlanner}）只查这张网格——
     * 数据由 {@link MapColorGenerator} 在后台线程扫底图时顺手记录，
     * 服务端主线程永远不为了选圈去遍历方块。
     */
    public final byte[] water;
    /** 调色板：0xRRGGBB。 */
    public final int[] palette;
    /** 选区换算出的区块总数。 */
    public final int chunksInSelection;
    /** 其中在内存里、被真正扫描的区块数。 */
    public final int chunksLoaded;
    /** 其中未加载、直接跳过（像素填默认色）的区块数。 */
    public final int chunksMissing;
    /** 有有效颜色的像素个数（验收日志里的“色块数量”）。 */
    public final int pixelsValid;
    /** 生成完成时间戳（毫秒）。 */
    public final long generatedAtMs;

    public MapBaseImage(int originX, int originZ, int step, int width, int height,
                        short[] colorIndex, short[] heights, byte[] water, int[] palette,
                        int chunksInSelection, int chunksLoaded, int chunksMissing,
                        int pixelsValid, long generatedAtMs) {
        this.originX = originX;
        this.originZ = originZ;
        this.step = step;
        this.width = width;
        this.height = height;
        this.colorIndex = colorIndex;
        this.heights = heights;
        this.water = water == null ? emptyWater(width, height) : water;
        this.palette = palette;
        this.chunksInSelection = chunksInSelection;
        this.chunksLoaded = chunksLoaded;
        this.chunksMissing = chunksMissing;
        this.pixelsValid = pixelsValid;
        this.generatedAtMs = generatedAtMs;
    }

    /** 像素总数（色块总数）。 */
    public int pixelCount() {
        return width * height;
    }

    /**
     * 像素 (px, pz) 的 ARGB 颜色：有效像素返回 0xFFRRGGBB，
     * 越界或未加载返回不透明 #1A1A1A（与客户端旧逻辑的未加载色一致）。
     */
    public int colorAt(int px, int pz) {
        if (px < 0 || pz < 0 || px >= width || pz >= height) {
            return 0xFF1A1A1A;
        }
        short idx = colorIndex[pz * width + px];
        if (idx < 0 || idx >= palette.length) {
            return 0xFF1A1A1A;
        }
        return 0xFF000000 | (palette[idx] & 0xFFFFFF);
    }

    /** 像素 (px, pz) 记录的最高方块高度；无数据返回 Short.MIN_VALUE。 */
    public int heightAt(int px, int pz) {
        if (px < 0 || pz < 0 || px >= width || pz >= height) {
            return Short.MIN_VALUE;
        }
        int p = pz * width + px;
        if (colorIndex[p] < 0) {
            return Short.MIN_VALUE;
        }
        return heights[p];
    }

    /** 世界方块坐标 (x, z) 对应的像素列；不做夹取，可能越界。 */
    public int pixelXOf(int blockX) {
        return (blockX - originX) / step;
    }

    /** 世界方块坐标 (x, z) 对应的像素行；不做夹取，可能越界。 */
    public int pixelZOf(int blockZ) {
        return (blockZ - originZ) / step;
    }

    /**
     * 世界坐标 (x, z) 所在像素的水域标记：1=水，0=陆地，-1=无数据（越界或未扫描）。
     * 毒圈选圆心按网格调用，纯数组访问，不碰世界方块。
     */
    public byte waterFlagAt(double worldX, double worldZ) {
        int px = (int) Math.floor((worldX - originX) / (double) step);
        int pz = (int) Math.floor((worldZ - originZ) / (double) step);
        if (px < 0 || pz < 0 || px >= width || pz >= height) {
            return -1;
        }
        return water[pz * width + px];
    }

    private static byte[] emptyWater(int width, int height) {
        byte[] empty = new byte[Math.max(0, width * height)];
        java.util.Arrays.fill(empty, (byte) -1);
        return empty;
    }
}
