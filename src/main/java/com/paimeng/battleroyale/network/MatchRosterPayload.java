package com.paimeng.battleroyale.network;

import java.util.List;
import java.util.UUID;

/**
 * [BR-NAMETAG] 本局花名册数据包（服务端 → 全体客户端）。
 *
 * <p>一次把<b>本局所有参赛者</b>的「UUID → 队内座号（1~4 号位）」发下去：
 * 头顶 NameTag 要给视野里<b>任何一名玩家</b>拼出 [N号] 前缀并按座号上色，
 * 客户端必须拿得到<b>全部队伍</b>的座号表，而不是只有自己那支队的快照。
 *
 * <p>{@code active=false}（entries 为空）= 本局没有 MatchTeam（未开局 / 已销毁），
 * 客户端收到就清空本地花名册，大厅阶段头顶维持原样。
 *
 * <p>与 {@link TeamSyncPayload} 的分工：本类是纯数据 record，
 * 不 import 任何 Minecraft 类；编解码与广播在 {@link MatchRosterSync} 里。
 */
public record MatchRosterPayload(boolean active, List<Entry> entries) {

    /** 一名参赛者：UUID + 局内座号（1 号位起，队长恒 1）。 */
    public record Entry(UUID playerId, int seat) {
    }

    /** 「没有花名册」：断线清屏 / 本局销毁时服务端广播这一份。 */
    public static MatchRosterPayload empty() {
        return new MatchRosterPayload(false, List.of());
    }
}
