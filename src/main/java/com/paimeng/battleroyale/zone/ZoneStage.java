package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.game.GameRules;

/**
 * 毒圈单个阶段的配置：等待多久、用多久收缩、收缩到多大、圈外多少秒淘汰满血玩家。
 *
 * <p>record 是纯 Java 17 语法，不依赖 Minecraft，升级到高版本时原样可用。
 * 时间统一换算成 tick 存储（{@link #of} 按秒填写，可读性更好）。
 *
 * <p>[r148 TTK 模型] 阶段携带的是<b>淘汰时间</b>（zoneTtkSeconds，秒，double）而不是
 * 每秒伤害：运行期按 {@code damagePerSecond = player.getMaxHealth() / zoneTtkSeconds}
 * 反推每秒伤害（20 血 / 250 秒 = 0.08 点/秒；20 血 / 8 秒 = 2.5 点/秒）。
 * 反推出的小数伤害不会直接 {@code hurt()}——那会被原版受击无敌帧整段吞掉——由
 * {@link ZoneManager.ZoneRuntime} 每秒加进玩家专属的浮点池，攒满 1 点扣 1 点。
 *
 * <p>约定：收缩是<b>线性插值</b>——从阶段开始时的半径，用 shrinkTicks
 * 均匀收到 targetRadius；等待期间半径保持不变但圈外伤害照常结算。
 */
public record ZoneStage(String name, int waitTicks, int shrinkTicks,
                        double targetRadius, double zoneTtkSeconds) {

    /** 按“秒”书写的工厂，内部换算成 tick，配置表更直观。 */
    public static ZoneStage of(String name, int waitSeconds, int shrinkSeconds,
                               double targetRadius, double zoneTtkSeconds) {
        return new ZoneStage(name,
                waitSeconds * GameRules.TICKS_PER_SECOND,
                shrinkSeconds * GameRules.TICKS_PER_SECOND,
                targetRadius, zoneTtkSeconds);
    }

    public int waitSeconds() {
        return waitTicks / GameRules.TICKS_PER_SECOND;
    }

    public int shrinkSeconds() {
        return shrinkTicks / GameRules.TICKS_PER_SECOND;
    }
}
