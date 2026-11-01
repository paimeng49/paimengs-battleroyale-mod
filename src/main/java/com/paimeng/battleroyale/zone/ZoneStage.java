package com.paimeng.battleroyale.zone;

import com.paimeng.battleroyale.game.GameRules;

/**
 * 毒圈单个阶段的配置：等待多久、用多久收缩、收缩到多大、圈外每秒掉多少血。
 *
 * <p>record 是纯 Java 17 语法，不依赖 Minecraft，升级到高版本时原样可用。
 * 时间统一换算成 tick 存储（{@link #of} 按秒填写，可读性更好）。
 *
 * <p>约定：收缩是<b>线性插值</b>——从阶段开始时的半径，用 shrinkTicks
 * 均匀收到 targetRadius；等待期间半径保持不变但圈外伤害照常结算。
 */
public record ZoneStage(String name, int waitTicks, int shrinkTicks,
                        double targetRadius, float damagePerSecond) {

    /** 按“秒”书写的工厂，内部换算成 tick，配置表更直观。 */
    public static ZoneStage of(String name, int waitSeconds, int shrinkSeconds,
                               double targetRadius, float damagePerSecond) {
        return new ZoneStage(name,
                waitSeconds * GameRules.TICKS_PER_SECOND,
                shrinkSeconds * GameRules.TICKS_PER_SECOND,
                targetRadius, damagePerSecond);
    }

    public int waitSeconds() {
        return waitTicks / GameRules.TICKS_PER_SECOND;
    }

    public int shrinkSeconds() {
        return shrinkTicks / GameRules.TICKS_PER_SECOND;
    }
}
