package com.paimeng.battleroyale.map;

import com.paimeng.battleroyale.game.GameRules;

/**
 * 毒圈「单个阶段」的持久化配置（模块一）：写进地图 JSON，GUI 可编辑。
 *
 * <p>字段保持 public、纯 Java（Gson 直接读写，不 import Minecraft 类）：
 * <ul>
 *   <li>{@link #waitTime}：倒计时等待秒数（30 ~ 900）；</li>
 *   <li>{@link #shrinkTime}：缩圈推进秒数（30 ~ 900）；</li>
 *   <li>{@link #radius}：本阶段要收缩到的目标半径（格），上限 = 选区短边的一半；</li>
 *   <li>{@link #damage}：圈外每秒伤害。</li>
 * </ul>
 *
 * <p>运行期由 {@code zone.ZoneManager} 转成 {@code zone.ZoneStage}（tick 单位）推进，
 * 本类只负责存储与网络传输。
 */
public class ZonePhaseConfig {

    /** GUI / 服务端校验共用的秒数区间（需求：30~900 秒）。 */
    public static final int MIN_SECONDS = 30;
    public static final int MAX_SECONDS = 900;

    /** 半径硬限制的下限：最小不能小于 0（需求模块一）。 */
    public static final double MIN_RADIUS = 0.0;

    /** 单个地图最多允许的阶段数（防 JSON / 网络包被塞爆）。 */
    public static final int MAX_PHASES = 16;

    public int waitTime = 120;
    public int shrinkTime = 60;
    public double radius = 64.0;
    public double damage = 2.0;

    public ZonePhaseConfig() {
    }

    public ZonePhaseConfig(int waitTime, int shrinkTime, double radius, double damage) {
        this.waitTime = waitTime;
        this.shrinkTime = shrinkTime;
        this.radius = radius;
        this.damage = damage;
    }

    public ZonePhaseConfig copy() {
        return new ZonePhaseConfig(waitTime, shrinkTime, radius, damage);
    }

    /** 秒数夹进 [30, 900]（非法输入回落到默认 60）。 */
    public static int clampSeconds(int value) {
        int v = value < 0 ? MIN_SECONDS : value;
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, v));
    }

    /**
     * 半径硬限制夹取：{@code maxRadius} 由地图选区决定（短边的一半）；
     * 上限 {@code 65536} 是防呆封顶（选区异常大时也不至于让一个阶段的等待 tick 溢出）。
     */
    public static double clampRadius(double value, double maxRadius) {
        double safeMax = Math.max(MIN_RADIUS, Math.min(65536.0, maxRadius));
        if (!(value >= MIN_RADIUS)) {   // 含 NaN：一律回落到上限，绝不留非法值进圈
            return safeMax;
        }
        return Math.min(safeMax, value);
    }

    public static double clampDamage(double value) {
        if (!(value >= 0.0)) {
            return 0.0;
        }
        return Math.min(100.0, value);
    }

    /** 就地夹合法（服务端收到 C2S 包、GUI 保存前都调这里，保证写进 JSON 的一定合法）。 */
    public void clampIntoRange(double maxRadius) {
        waitTime = clampSeconds(waitTime);
        shrinkTime = clampSeconds(shrinkTime);
        radius = clampRadius(radius, maxRadius);
        damage = clampDamage(damage);
    }

    /** 转成运行期阶段（tick 单位 + 展示名）。 */
    public com.paimeng.battleroyale.zone.ZoneStage toStage(int index) {
        return new com.paimeng.battleroyale.zone.ZoneStage(
                "第" + (index + 1) + "阶段",
                waitTime * GameRules.TICKS_PER_SECOND,
                shrinkTime * GameRules.TICKS_PER_SECOND,
                radius,
                (float) damage);
    }
}
