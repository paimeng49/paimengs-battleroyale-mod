package com.paimeng.battleroyale.network;

/**
 * 服务端同步给客户端的本局统计。
 *
 * <p>[BR-MATCHTEAM 模块四] {@code remainingTeams} = 本局存活队伍数（基于 MatchTeam，
 * 队里还有未被淘汰的成员就算存活）；-1 = 未开局 / 大厅，HUD 不画这一行。
 */
public record StatsSyncPayload(int remainingPlayers, int remainingTeams, int kills) {
    public static StatsSyncPayload empty() {
        return new StatsSyncPayload(-1, -1, 0);
    }
}
