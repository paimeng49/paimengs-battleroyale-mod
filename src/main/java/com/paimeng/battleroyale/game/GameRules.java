package com.paimeng.battleroyale.game;

/**
 * 一局游戏的可调参数全部集中在这里。
 * 毒圈相关的参数（初始半径、阶段表、伤害间隔）按内聚原则单独放在
 * {@code zone.ZoneStages}；以后接撤离窗口等参数时再往这个类里加
 * （或换成读配置文件），不要散落在各功能代码里。
 */
public final class GameRules {

    private GameRules() {
    }

    /** Minecraft 每秒 20 tick。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 最少几人才能开局。 */
    public static final int MIN_PLAYERS_TO_START = 2;

    /** 准备阶段倒计时：10 秒。 */
    public static final int PREPARING_SECONDS = 10;
    public static final int PREPARING_TICKS = PREPARING_SECONDS * TICKS_PER_SECOND;

    /** 一局最长时长：20 分钟（以后由毒圈逼出胜者，这里只是兜底）。 */
    public static final int MATCH_MAX_SECONDS = 20 * 60;
    public static final int MATCH_MAX_TICKS = MATCH_MAX_SECONDS * TICKS_PER_SECOND;

    /** 结束后的结算展示时长：15 秒，之后自动回到大厅。 */
    public static final int ENDED_RESET_SECONDS = 15;
    public static final int ENDED_RESET_TICKS = ENDED_RESET_SECONDS * TICKS_PER_SECOND;
}
