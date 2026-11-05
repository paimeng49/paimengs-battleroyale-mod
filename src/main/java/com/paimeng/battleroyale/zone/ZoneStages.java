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
 *
 * <p>[r148 TTK 模型] 默认表自带逐阶段<b>淘汰时间梯度</b>
 * （{@link #DEFAULT_TTK_SECONDS}，示例配置：250 → 200 → … → 8 秒）：
 * 圈外满血玩家第 1 阶段要 250 秒才毒死，终局只要 8 秒。运行期反推
 * {@code damagePerSecond = maxHealth / 本阶段秒数}。
 * 阶段 index 超过梯度长度时一律用最后一个元素（终局高压），
 * 地图 JSON 自己配了 zonePhases 的逐阶段值则覆盖这份默认。
 */
public final class ZoneStages {

    private ZoneStages() {
    }

    /** 圈外伤害结算间隔：每 20 tick（= 1 秒）一次。 */
    public static final int DAMAGE_INTERVAL_TICKS = GameRules.TICKS_PER_SECOND;

    /** 默认阶段的时间参数（秒）：等待 / 缩圈。 */
    private static final int[] DEFAULT_WAIT_SECONDS = {60, 45, 45, 30, 30, 30};
    private static final int[] DEFAULT_SHRINK_SECONDS = {35, 30, 30, 25, 25, 25};

    /** 默认目标半径 = 选区短边一半 × 这些百分比（最后收到 0，逼出胜者）。 */
    private static final double[] DEFAULT_FRACTION_RADII = {0.65, 0.45, 0.30, 0.18, 0.08, 0.0};

    /**
     * [r148] 默认淘汰时间梯度（秒）：第 1~12 阶段「不打包、圈外满血到死」的用时。
     * 阶段下标超过梯度长度用最后一个元素（终局 8 秒高压）。
     * GUI 新增阶段、默认表补空洞都从这里取值。
     */
    public static final double[] DEFAULT_TTK_SECONDS =
            {250, 200, 180, 150, 120, 100, 30, 25, 20, 15, 10, 8};

    /**
     * 第 index 阶段（0 基）的默认淘汰秒数：越界取梯度最后一个元素。
     * [r148 边界] 需求三.1「当前阶段超过配置数组长度时用最后一个值」就落在这里。
     */
    public static double defaultTtkAt(int index) {
        if (index < 0) {
            return DEFAULT_TTK_SECONDS[0];
        }
        return DEFAULT_TTK_SECONDS[Math.min(index, DEFAULT_TTK_SECONDS.length - 1)];
    }

    /**
     * 按选区生成默认阶段表：半径走百分比缩放，淘汰秒数走 {@link #DEFAULT_TTK_SECONDS} 梯度。
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
     * 淘汰秒数用该下标在 {@link #DEFAULT_TTK_SECONDS} 梯度上的值（越界取最后）。
     */
    public static ZoneStage defaultStageAt(int index, double maxRadius) {
        // 最后一档半径是 0（决战圈）：越界下标夹到倒数第二档，别用 0 半径补空洞。
        int lastUsable = DEFAULT_FRACTION_RADII.length - 2;
        int i = index >= 0 ? Math.min(index, lastUsable) : 0;
        return new ZoneStage("第" + (index + 1) + "阶段（空洞补默认）",
                DEFAULT_WAIT_SECONDS[i] * GameRules.TICKS_PER_SECOND,
                DEFAULT_SHRINK_SECONDS[i] * GameRules.TICKS_PER_SECOND,
                Math.max(0.0, maxRadius * DEFAULT_FRACTION_RADII[i]),
                defaultTtkAt(index));
    }
}
