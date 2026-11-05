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
 *       HUD 表头显示 "#N队"；false = 大厅持久队伍（LobbyTeam），HUD 只显示
 *       "我的队伍"，成员列表只有队长标识和名字；</li>
 *   <li>{@code split}：[BR-MATCHTEAM 第四步] true = 本队成员来自<b>被拆分的预组队</b>
 *       （开局分配器因地图容量上限拆开了开黑队），局内 HUD 显示拆分提示；</li>
 *   <li>{@code teamNumber}：match 快照 = 局内 "#N队" 编号（1~maxTeams）；
 *       大厅快照 = 大厅队伍门牌（1-99，仅 /team join 定位用，HUD 大厅模式不显示它）；</li>
 *   <li>{@code leaderId}：队长UUID，HUD 里给队长名字标注"（队长）★"；</li>
 *   <li>{@code members}：成员列表，每项 = UUID + 编号 + 名字。
 *       [BR-NOLOBBYNUMBER] <b>编号只有局内才存在</b>：match 快照里是座号 squadIndex
 *       （1 号位起），大厅快照里恒为 0，客户端大厅模式一律不画它；
 *       列表已按加入顺序（局内按座号）排好，客户端直接顺序画即可；</li>
 *   <li>空列表 = "你没有队伍"（离队 / 队伍解散 / 本局销毁恢复时服务端发一份空快照把客户端清干净）。</li>
 * </ul>
 */
public record TeamSyncPayload(UUID teamId, int teamNumber, UUID leaderId,
                              boolean match, boolean split, List<Member> members) {

    /** [BR-TEAMHUD] 成员存活状态：正常（HUD 常规配色）。 */
    public static final int STATUS_ALIVE = 0;
    /** [BR-TEAMHUD] 成员存活状态：倒地（HUD 深红 + "倒地" 标识，取自 DownedManager）。 */
    public static final int STATUS_DOWNED = 1;
    /** [BR-TEAMHUD] 成员存活状态：已淘汰（HUD 灰色 + 删除线，取自 DownedManager）。 */
    public static final int STATUS_ELIMINATED = 2;

    /**
     * 一名队员：UUID、编号（[BR-NOLOBBYNUMBER] 局内=座号 squadIndex，大厅恒 0）、
     * 名字、拆分标记（大厅恒 false）、
     * 存活状态（{@link #STATUS_ALIVE} / {@link #STATUS_DOWNED} / {@link #STATUS_ELIMINATED}，
     * 局内快照实时取自 DownedManager，大厅快照恒 ALIVE）。
     */
    public record Member(UUID playerId, int number, String name, boolean split, int status) {
    }

    /** "没有队伍"的空快照：离队时服务端单发给本人，客户端收到就清缓存。 */
    public static TeamSyncPayload empty() {
        return new TeamSyncPayload(null, 0, null, false, false, List.of());
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}
