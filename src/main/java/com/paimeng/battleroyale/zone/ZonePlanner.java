package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.map.MapBaseImage;

import java.util.Random;

/**
 * 服务端安全区生成算法（模块二）：弱排水 + 绝对不出界 + 防卡死兜底。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>绝对不出界</b>：新白圈必须 100% 包在选区里——
 *       {@code centerX - radius >= minX && centerX + radius <= maxX}（Z 同理）。
 *       实现方式是先把圆心的可行域算成「选区内缩 radius」的矩形，
 *       任何候选圆心都夹进这个矩形，天然不可能越界；</li>
 *   <li><b>弱排水</b>：最多 {@value #MAX_SAMPLES} 次随机采样，每个候选圆心按网格
 *       （至少每 {@value #SAMPLE_GRID_BLOCKS} 格一个采样点）统计目标圆盘内的水域占比，
 *       超过 {@value #WATER_LIMIT} 就重摇；20 次都不合格则取水域占比最低的那个；</li>
 *   <li><b>防卡死兜底</b>：半径过大 / 选区过小导致可行域为空时，
 *       强制圆心 = 上一个圈的中心，半径取该点能容下的最大值。</li>
 * </ol>
 *
 * <p><b>性能红线（需求原文：绝不允许在主线程遍历全图方块）</b>：
 * 水域占比只查 {@link MapBaseImage}——那是 {@code MapColorGenerator} 在后台线程
 * 预生成的像素网格（每像素一个「最高方块是否水」标记，见 MapBaseImage.isWaterPixel）。
 * 本类全部是内存数组下标访问：单个候选最多约 64x64 次读，20 个候选也就几千次，
 * 主线程零方块查询、零磁盘 IO。底图还没生成好时水域信息视为未知，
 * 直接接受第一个合法候选（开局圈阶段本来就是全岛，不影响出圈）。
 *
 * <p>纯 Java，不 import 任何 Minecraft 类。
 */
public final class ZonePlanner {

    /** 弱排水重采样上限（需求：最多 20 次）。 */
    public static final int MAX_SAMPLES = 20;

    /** 水域占比红线（需求：超过 30% 重新随机）。 */
    public static final double WATER_LIMIT = 0.30;

    /** 网格采样间隔下限（需求示例：每 10 格采一个点）。 */
    public static final int SAMPLE_GRID_BLOCKS = 10;

    /** 单个候选最多 64x64 个采样点：半径再大也自动变粗网格，开销有硬上界。 */
    private static final int MAX_AXIS_SAMPLES = 64;

    private ZonePlanner() {
    }

    /** 选区边界（已归一化：min &le; max）。 */
    public record Bounds(double minX, double maxX, double minZ, double maxZ) {

        public static Bounds of(int minX, int maxX, int minZ, int maxZ) {
            return new Bounds(Math.min(minX, maxX), Math.max(minX, maxX),
                    Math.min(minZ, maxZ), Math.max(minZ, maxZ));
        }

        /** 硬限制：每一阶段半径最大不能超过选区短边的一半。 */
        public double maxRadius() {
            return Math.max(0.0, Math.min(maxX - minX, maxZ - minZ) / 2.0);
        }

        /** 选区短边长度（阶段零判定"极大半径"的基准之一）。 */
        public double shortSide() {
            return Math.max(0.0, Math.min(maxX - minX, maxZ - minZ));
        }

        /** 从某点到选区四个角的最远平面距离：半径 ≥ 该值即可罩住整个选区。 */
        public double cornerDistance(double x, double z) {
            double dx = Math.max(x - minX, maxX - x);
            double dz = Math.max(z - minZ, maxZ - z);
            return Math.hypot(dx, dz);
        }

        public double centerX() {
            return (minX + maxX) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ) / 2.0;
        }
    }

    /** 生成结果：新圆心 + 实际采用的半径（半径可能被硬限制夹小）。 */
    public record Result(double centerX, double centerZ, double radius) {
    }

    /**
     * 生成下一个白圈。
     *
     * @param prev        当前圈（上一阶段的圆）：新圆心优先在它的偏移域里摇，保证「缩圈」的观感
     * @param wantedRadius GUI 配置里的目标半径；进来前已被夹到 [0, 短边一半]，这里再兜底夹一次
     * @param bounds      选区边界
     * @param terrain     异步预生成的底图（含水域标记）；允许 null（未生成 → 跳过排水判定）
     */
    public static Result next(Result prev, double wantedRadius, Bounds bounds,
                              MapBaseImage terrain, Random random) {
        double maxRadius = bounds.maxRadius();
        double radius = Math.max(0.0, Math.min(wantedRadius, maxRadius));

        // ---- 圆心的可行域 = 选区向内缩 radius；空集说明这个半径怎么放都越界 ----
        double loX = bounds.minX() + radius;
        double hiX = bounds.maxX() - radius;
        double loZ = bounds.minZ() + radius;
        double hiZ = bounds.maxZ() - radius;
        if (loX > hiX || loZ > hiZ) {
            // ---- 防卡死兜底（需求原文）：圆心强制上一圈中心，半径取允许最大值 ----
            return forceFallback(prev, bounds, maxRadius);
        }

        // ---- 弱排水：新旧圈同心圆随机偏移，最多 20 次采样 ----
        // 偏移上限 = 旧圈半径 - 新圈半径（旧圈比新圈大时新圆天然还在旧圆内，观感正确；
        // 旧圈已经贴到选区边界时靠下面的矩形夹取保证不出界）。
        double maxOffset = Math.max(0.0, prev.radius() - radius);
        double bestX = 0, bestZ = 0;
        double bestWater = Double.MAX_VALUE;
        boolean hasBest = false;

        for (int attempt = 0; attempt < MAX_SAMPLES; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            // sqrt 保证在圆盘内均匀分布（而不是向圆心堆积）
            double offset = maxOffset <= 0.0 ? 0.0 : maxOffset * Math.sqrt(random.nextDouble());
            double x = clamp(prev.centerX() + Math.cos(angle) * offset, loX, hiX);
            double z = clamp(prev.centerZ() + Math.sin(angle) * offset, loZ, hiZ);

            double water = waterFraction(terrain, x, z, radius);
            if (water <= WATER_LIMIT) {
                // 无底图数据时 water 返回 -1（视为 0 水域）也走这条：直接接受
                return new Result(x, z, radius);
            }
            if (!hasBest || water < bestWater) {
                hasBest = true;
                bestWater = water;
                bestX = x;
                bestZ = z;
            }
        }
        // 20 次全超线：取水域最少的那个（需求原文）。
        if (hasBest) {
            return new Result(bestX, bestZ, radius);
        }
        // 理论到不了这里（MAX_SAMPLES > 0），同心兜底。
        return new Result(clamp(prev.centerX(), loX, hiX), clamp(prev.centerZ(), loZ, hiZ), radius);
    }

    /** 防卡死兜底：圆心 = 上一个圈的中心，半径 = 该点能容下的最大值（仍不出界）。 */
    private static Result forceFallback(Result prev, Bounds bounds, double maxRadius) {
        double x = clamp(prev.centerX(), bounds.minX(), bounds.maxX());
        double z = clamp(prev.centerZ(), bounds.minZ(), bounds.maxZ());
        double fit = Math.min(Math.min(x - bounds.minX(), bounds.maxX() - x),
                Math.min(z - bounds.minZ(), bounds.maxZ() - z));
        double radius = Math.max(0.0, Math.min(maxRadius, fit));
        return new Result(x, z, radius);
    }

    /**
     * [r130 阶段零] 生成开局目标白圈（第一个阶段的圈）。
     *
     * <p>与 {@link #next} 的区别只有一处：<b>没有可参照的"上一圈"</b>——开局的蓝圈罩住全图，
     * "新圆心在旧圆内随机偏移"的公式会退化成沿可行域边缘堆积。所以这里改为在
     * 「选区内缩 wantedRadius」的可行矩形里<b>均匀采样</b>圆心。
     * 其余三条硬规则与 {@link #next} 完全一致：
     * 绝对不出界（圆心夹进可行矩形）、弱排水（同一套 20 次采样 + 30% 水域红线 +
     * 取最优兜底）、防卡死兜底（可行域为空时圆心落选区中心、半径收缩到容得下的最大值）。
     */
    public static Result firstTarget(double wantedRadius, Bounds bounds,
                                     MapBaseImage terrain, Random random) {
        double maxRadius = bounds.maxRadius();
        double radius = Math.max(0.0, Math.min(wantedRadius, maxRadius));

        double loX = bounds.minX() + radius;
        double hiX = bounds.maxX() - radius;
        double loZ = bounds.minZ() + radius;
        double hiZ = bounds.maxZ() - radius;
        if (loX > hiX || loZ > hiZ) {
            // 可行域为空（半径被夹前已 ≤ 短边一半，理论到不了）：中心兜底。
            return forceFallback(new Result(bounds.centerX(), bounds.centerZ(), 0.0), bounds, maxRadius);
        }

        double bestX = bounds.centerX();
        double bestZ = bounds.centerZ();
        double bestWater = Double.MAX_VALUE;
        boolean hasBest = false;
        double spanX = Math.max(0.0, hiX - loX);
        double spanZ = Math.max(0.0, hiZ - loZ);

        for (int attempt = 0; attempt < MAX_SAMPLES; attempt++) {
            double x = loX + random.nextDouble() * spanX;
            double z = loZ + random.nextDouble() * spanZ;
            double water = waterFraction(terrain, x, z, radius);
            if (water <= WATER_LIMIT) {
                return new Result(x, z, radius);   // 无底图数据时 -1 也走这条：直接接受
            }
            if (!hasBest || water < bestWater) {
                hasBest = true;
                bestWater = water;
                bestX = x;
                bestZ = z;
            }
        }
        // 20 次全超线：取水域占比最低的那个。
        return new Result(bestX, bestZ, radius);
    }

    /**
     * 目标圆盘内的水域占比：按网格查异步预生成的底图像素，绝不触碰世界方块。
     *
     * @return 0.0~1.0；{@code terrain == null} 或圆盘内全是无数据像素时返回 -1（未知）
     */
    public static double waterFraction(MapBaseImage terrain, double centerX, double centerZ, double radius) {
        if (terrain == null || radius <= 0.0) {
            return -1.0;
        }
        // 网格步长（世界格）：至少 10 格一个点；圆盘太大时自动加粗，单轴采样数封顶 64。
        double gridBlocks = Math.max(SAMPLE_GRID_BLOCKS, 2.0 * radius / MAX_AXIS_SAMPLES);
        double stepWorld = gridBlocks;
        double rSq = radius * radius;
        int water = 0;
        int known = 0;
        for (double dz = -radius; dz <= radius; dz += stepWorld) {
            for (double dx = -radius; dx <= radius; dx += stepWorld) {
                if (dx * dx + dz * dz > rSq) {
                    continue;
                }
                byte flag = terrain.waterFlagAt(centerX + dx, centerZ + dz);
                if (flag < 0) {
                    continue;   // 该像素无数据：不计入分母，避免“全黑”被当成陆地/全水
                }
                known++;
                if (flag == 1) {
                    water++;
                }
            }
        }
        if (known == 0) {
            return -1.0;
        }
        return (double) water / known;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
