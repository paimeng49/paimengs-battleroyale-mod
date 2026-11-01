package com.paimeng.battleroyale.zone;

/**
 * 毒圈自身的小阶段（和游戏的 {@code GamePhase} 是两回事）。
 * 一局 RUNNING 期间，毒圈按 IDLE → WAITING ⇄ SHRINKING …… → DONE 推进：
 *
 * <pre>
 * WAITING(等 waitTicks 秒倒计时) → SHRINKING(用 shrinkTicks 收到目标圆) → 下一阶段 WAITING ... → 全部收完 DONE
 * </pre>
 *
 * <p>状态机对外只有两个「活」状态（需求模块三）：
 * <ul>
 *   <li>{@link #WAITING}：倒计时中——蓝圈停在原地，HUD 显示 mm:ss 倒计时；</li>
 *   <li>{@link #SHRINKING}：缩圈中——倒计时锁 0，客户端按总进度百分比 lerp 实时半径。</li>
 * </ul>
 * {@link #IDLE}（未开圈）与 {@link #DONE}（收完全部阶段）是两端边界态。
 *
 * <p>纯 Java 枚举，不依赖 Minecraft，升级到高版本时原样可用。
 */
public enum ZonePhase {

    /** 未激活：对局不在 RUNNING 时毒圈停在这里，tick 里直接跳过。 */
    IDLE("未激活"),

    /** 倒计时中：蓝圈半径不动，圈外玩家已经在按秒掉血。 */
    WAITING("倒计时中"),

    /** 缩圈中：蓝圈从收缩起点线性插值到下个白圈。 */
    SHRINKING("缩圈中"),

    /** 全部阶段收完：保持最终半径和伤害，直到本局结束。 */
    DONE("已收到底");

    private final String displayName;

    ZonePhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 毒圈是否正在生效（会持续对圈外玩家造成伤害）。 */
    public boolean isActive() {
        return this != IDLE;
    }
}
