package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.game.GameRules;

import java.util.ArrayList;
import java.util.List;

/**
 * 毒圈默认阶段表（模块三兜底：地图 JSON 里没配 zonePhases 时用这份）。
 *
 * <p>需求升级到 r109：每个地图的阶段配置存在 {@code map.GameMap.zonePhases}
 * （GUI 可编辑、随地图 JSON 持久化），本类只保留两套东西：
 * <ul>
 *   <li>伤害结算间隔常量；</li>
 *   <li>「按选区短边一半等比缩放」的默认阶段生成——半径不再写死 96/64/40 米，
 *       而是 {@link #DEFAULT_FRACTION_RADII} 的百分比：小图自动收小、大图自动放大，
 *       老地图没配毒圈也能玩。</li>
 * </ul>
 */
public final class ZoneStages {

    private ZoneStages() {
    }

    /** 圈外伤害结算间隔：每 20 tick（= 1 秒）一次。 */
    public static final int DAMAGE_INTERVAL_TICKS = GameRules.TICKS_PER_SECOND;

    /** 默认阶段的时间参数（秒）：等待 / 缩圈 / 每秒伤害。 */
    private static final int[] DEFAULT_WAIT_SECONDS = {60, 45, 45, 30, 30, 30};
    private static final int[] DEFAULT_SHRINK_SECONDS = {35, 30, 30, 25, 25, 25};
    private static final double[] DEFAULT_DAMAGE = {2.0, 4.0, 6.0, 8.0, 10.0, 12.0};

    /** 默认目标半径 = 选区短边一半 × 这些百分比（最后收到 0，逼出胜者）。 */
    private static final double[] DEFAULT_FRACTION_RADII = {0.65, 0.45, 0.30, 0.18, 0.08, 0.0};

    /**
     * 按选区生成默认阶段表。
     *
     * @param maxRadius 选区短边的一半（初始蓝圈允许的最大半径）
     */
    public static List<ZoneStage> defaultsFor(double maxRadius) {
        List<ZoneStage> stages = new ArrayList<>(DEFAULT_FRACTION_RADII.length);
        for (int i = 0; i < DEFAULT_FRACTION_RADII.length; i++) {
            stages.add(defaultStageAt(i, maxRadius));
        }
        return stages;
    }

    /**
     * [r131 索引防错位] 按「配置下标」取一条默认阶段：下标越界时循环取默认表的最后一档。
     * 地图 JSON 的 zonePhases 里出现空洞（null）时，ZoneManager 用它**原位补齐**——
     * 绝不跳过整表前移，否则 config[1] 就会冒充 config[0]（白圈比预期小一圈的事故根源）。
     */
    public static ZoneStage defaultStageAt(int index, double maxRadius) {
        // 最后一档半径是 0（决战圈）：越界下标夹到倒数第二档，别用 0 半径补空洞。
        int lastUsable = DEFAULT_FRACTION_RADII.length - 2;
        int i = index >= 0 ? Math.min(index, lastUsable) : 0;
        return new ZoneStage("第" + (index + 1) + "阶段（空洞补默认）",
                DEFAULT_WAIT_SECONDS[i] * GameRules.TICKS_PER_SECOND,
                DEFAULT_SHRINK_SECONDS[i] * GameRules.TICKS_PER_SECOND,
                Math.max(0.0, maxRadius * DEFAULT_FRACTION_RADII[i]),
                (float) DEFAULT_DAMAGE[i]);
    }
}
