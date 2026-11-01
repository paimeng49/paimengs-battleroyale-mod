package com.paimeng.battleroyale.network;

import java.util.List;
import java.util.UUID;

/**
 * 队伍同步数据包（服务端 → 客户端）：一次发整个队伍的快照，客户端整份替换本地缓存。
 *
 * <p>与 {@code ZoneSnapshot}/{@code ZoneSync} 的分工一致：
 * 本类是<b>纯数据</b>（Java 17 record，不 import 任何 Minecraft 类，升级原样可用），
 * 网络通道、编解码、广播在 {@link TeamSync} 里。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code match}：[BR-MATCHTEAM] true = 这份快照是<b>本局临时队伍（MatchTeam）</b>，
 *       HUD 表头显示 "#N队"；false = 大厅持久队伍（LobbyTeam），显示"队伍 N"；</li>
 *   <li>{@code split}：[BR-MATCHTEAM 第四步] true = 本队成员来自<b>被拆分的预组队</b>
 *       （开局分配器因地图容量上限拆开了开黑队），局内 HUD 显示拆分提示；</li>
 *   <li>{@code teamNumber}：队伍编号（大厅 1-99 / 局内 1~maxTeams），HUD 表头用；</li>
 *   <li>{@code leaderId}：队长UUID，HUD 里给队长名字后面加个 ★；</li>
 *   <li>{@code members}：成员列表，每项 = UUID + 队内编号（match 快照里就是
 *       <b>座号 squadIndex</b>）+ 名字，<b>已按编号升序排好</b>，客户端直接按顺序画即可；</li>
 *   <li>空列表 = "你没有队伍"（离队 / 队伍解散 / 本局销毁恢复时服务端发一份空快照把客户端清干净）。</li>
 * </ul>
 */
public record TeamSyncPayload(UUID teamId, int teamNumber, UUID leaderId,
                              boolean match, boolean split, List<Member> members) {

    /** 一名队员：UUID、队内编号（大厅编号 / 局内座号）、名字、拆分标记（大厅恒 false）。 */
    public record Member(UUID playerId, int number, String name, boolean split) {
    }

    /** "没有队伍"的空快照：离队时服务端单发给本人，客户端收到就清缓存。 */
    public static TeamSyncPayload empty() {
        return new TeamSyncPayload(null, 0, null, false, false, List.of());
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}
