package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.network.StatsSyncPayload;

/**
 * 客户端这一侧的本局统计缓存（只读）：剩余存活人数 + 自己的击杀数。
 *
 * <p>数据来源：服务端 {@code StatsManager} 在开局、淘汰、击杀变化时广播的
 * {@link StatsSyncPayload}，由 {@code BattleRoyaleModClient} 收包写入；
 * 消费方：{@link StatsHudRenderer}（屏幕左上角、队友列表上方的状态 HUD）。
 */
public final class ClientStatsState {

    /** remaining < 0 = 本地没有进行中的对局统计（未开局 / 已结算 / 断线），HUD 不画。 */
    private static volatile StatsSyncPayload snapshot = StatsSyncPayload.empty();

    private ClientStatsState() {
    }

    /** 收到服务端快照：整份替换。 */
    public static void apply(StatsSyncPayload payload) {
        snapshot = payload == null ? StatsSyncPayload.empty() : payload;
    }

    /** 断线 / 退出世界：清掉统计，HUD 一行都不画。 */
    public static void clear() {
        snapshot = StatsSyncPayload.empty();
    }

    /** 有没有可显示的统计（服务端进入 RUNNING 后才有）。 */
    public static boolean hasStats() {
        return snapshot.remainingPlayers() >= 0;
    }

    public static int remainingPlayers() {
        return snapshot.remainingPlayers();
    }

    /** [BR-MATCHTEAM] 存活队伍数（-1 = 大厅 / 旧服务端，HUD 不画这一行）。 */
    public static int remainingTeams() {
        return snapshot.remainingTeams();
    }

    public static int kills() {
        return snapshot.kills();
    }
}
