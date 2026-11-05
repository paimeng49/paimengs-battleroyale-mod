package com.paimeng.battleroyale.map;

import com.google.gson.annotations.SerializedName;
import com.paimeng.battleroyale.game.GameRules;

/**
 * 毒圈「单个阶段」的持久化配置（模块一）：写进地图 JSON，GUI 可编辑。
 *
 * <p>字段保持 public、纯 Java（Gson 直接读写，不 import Minecraft 类）：
 * <ul>
 *   <li>{@link #waitTime}：倒计时等待秒数（30 ~ 900）；</li>
 *   <li>{@link #shrinkTime}：缩圈推进秒数（30 ~ 900）；</li>
 *   <li>{@link #radius}：本阶段要收缩到的目标半径（格），上限 = 选区短边的一半；</li>
 *   <li>{@link #zoneTtkSeconds}：本阶段圈外<b>从满血到被淘汰所需的时间</b>（秒），double。</li>
 * </ul>
 *
 * <p>[r148 TTK 模型] 策划不再配"每秒掉几点"，改配"想在第几阶段让玩家多少秒死"：
 * 运行期按 {@code damagePerSecond = player.getMaxHealth() / zoneTtkSeconds} 反推
 * 每秒伤害（示例：第 1 阶段 20 / 250 = 0.08 点/秒；终局 20 / 8 = 2.5 点/秒）。
 * 取值口径：
 * <ul>
 *   <li>&gt; 0：本阶段用这个秒数（支持小数，如 12.5 秒）；</li>
 *   <li>0：本阶段不单独指定，跟随地图全局
 *       {@link GameMap#zoneTtkSeconds}（默认 250 秒）。</li>
 * </ul>
 * 真正扣血由 {@code zone.ZoneManager.ZoneRuntime} 的伤害累加器结算：
 * 每秒把反推出的伤害加进玩家专属的浮点池，攒满 1 点才扣 1 点，零头滚下一秒。
 *
 * <p><b>旧字段说明</b>：r147 之前这里配的是"每秒伤害"（{@code zoneDamagePerSecond} /
 * {@code damage}）。两种口径数值含义完全不同（旧的 1.0 = 每秒 1 点；现在 1.0 = 一秒毒死），
 * 因此<b>故意不做字段别名</b>：老 JSON 里的旧字段被 Gson 忽略，该阶段回落为跟随全局，
 * 绝不拿旧数字当 TTK 用。
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
    /**
     * [r148] 本阶段圈外满血淘汰时间（秒，double，支持小数）；
     * 0 = 跟随地图全局 {@link GameMap#zoneTtkSeconds}。
     * 旧地图 JSON 的按秒伤害字段（zoneDamagePerSecond / damage）不再读取，见类注释。
     */
    @SerializedName("zoneTtkSeconds")
    public double zoneTtkSeconds = 0.0;

    public ZonePhaseConfig() {
    }

    public ZonePhaseConfig(int waitTime, int shrinkTime, double radius, double zoneTtkSeconds) {
        this.waitTime = waitTime;
        this.shrinkTime = shrinkTime;
        this.radius = radius;
        this.zoneTtkSeconds = zoneTtkSeconds;
    }

    public ZonePhaseConfig copy() {
        return new ZonePhaseConfig(waitTime, shrinkTime, radius, zoneTtkSeconds);
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

    /**
     * [r148] 淘汰时间（秒）夹取：<b>原样保留小数</b>（12.5、250、20.5 都合法），
     * 负数 / NaN / 0 视为「跟随地图全局」→ 0；上限与地图全局共用
     * {@link GameMap#MAX_ZONE_TTK_SECONDS}。
     */
    public static double clampTtk(double value) {
        if (!(value > 0.0)) {          // 含 NaN 与负数：一律回落到 0 = 跟随地图全局值
            return 0.0;
        }
        return Math.min(GameMap.MAX_ZONE_TTK_SECONDS, value);
    }

    /** 就地夹合法（服务端收到 C2S 包、GUI 保存前都调这里，保证写进 JSON 的一定合法）。 */
    public void clampIntoRange(double maxRadius) {
        waitTime = clampSeconds(waitTime);
        shrinkTime = clampSeconds(shrinkTime);
        radius = clampRadius(radius, maxRadius);
        zoneTtkSeconds = clampTtk(zoneTtkSeconds);
    }

    /**
     * [r148] 本阶段最终生效的淘汰时间（秒）：阶段值 &gt; 0 用它，否则用调用方给的
     * 地图全局值（{@link GameMap#zoneTtkSeconds}），并按全局下限兜底。
     */
    public double resolveTtkSeconds(double mapDefault) {
        return zoneTtkSeconds > 0.0 ? zoneTtkSeconds
                : Math.max(GameMap.MIN_ZONE_TTK_SECONDS, mapDefault);
    }

    /** 转成运行期阶段（tick 单位 + 展示名）；淘汰秒数保持浮点，取整在反推伤害时做。 */
    public com.paimeng.battleroyale.zone.ZoneStage toStage(int index, double mapDefaultTtk) {
        return new com.paimeng.battleroyale.zone.ZoneStage(
                "第" + (index + 1) + "阶段",
                waitTime * GameRules.TICKS_PER_SECOND,
                shrinkTime * GameRules.TICKS_PER_SECOND,
                radius,
                resolveTtkSeconds(mapDefaultTtk));
    }
}
