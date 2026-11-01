package com.paimeng.battleroyale.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [BR-MATCHTEAM] 局内临时队伍（MatchTeam）——一局比赛专用，与大厅持久队伍
 * {@link Team}（LobbyTeam）完全隔离：
 *
 * <ul>
 *   <li>大厅的创建 / 邀请 / 审批 / 队长权限一概不受影响，本对象只在
 *       WAITING → PREPARING 那一刻由 {@link MatchTeamManager} 依据大厅现状
 *       重新“编译”出来；</li>
 *   <li>{@code matchTeamId} 是新生成的唯一 ID，和 lobbyTeamId 无关
 *       （{@code lobbyTeamId} 只作溯源记录，便于调试 / 以后做“原队标记”）；</li>
 *   <li>{@code teamNumber} 是本局内的队伍编号（1 队 ~ maxTeams 队），由
 *       {@link MatchTeamManager} 分配完毕后一次性写定；</li>
 *   <li>{@code squadIndex} 是队内座号（1 号位 ~ 4 号位），队长永远是 1 号位。</li>
 * </ul>
 *
 * <p>对象创建后成员不可变（本局内有人逃跑 / 淘汰只改淘汰名单，不改队伍编制），
 * 所以所有查询都返回拷贝 / 只读视图，不需要考虑并发修改。
 * 纯 Java 文件，不 import 任何 Minecraft 类，升级版本原样可用。
 */
public final class MatchTeam {

    private final UUID matchTeamId;
    /** 溯源：这些成员来自哪个大厅队（可空 = 纯野拼出来的队）。仅记录，不参与任何判定。 */
    private final UUID lobbyTeamId;
    private final String mapId;
    private final int teamNumber;
    private final UUID leaderId;
    /** [BR-MATCHTEAM] 该队所属地图的容量（maxTeamSize），UI 可显示 "2/3" 这类人数。 */
    private final int capacity;
    /** [BR-MATCHTEAM 第四步] 本队是否由被拆分的预组队成员组成（HUD 显示拆分提示）。 */
    private final boolean splitFromPremade;

    /** 成员 UUID → 座号记录；LinkedHashMap 保留“队长 → 原队成员 → 补位散人”的插入顺序。 */
    private final Map<UUID, Member> members = new LinkedHashMap<>();

    /** 一名局内队员：playerId + 座号（1 号位起）+ 入局时昵称快照 + 是否经历过预组队拆分。 */
    public record Member(UUID playerId, int squadIndex, String name, boolean splitOrigin) {
    }

    public MatchTeam(UUID matchTeamId, UUID lobbyTeamId, String mapId, int teamNumber, UUID leaderId,
                     int capacity, boolean splitFromPremade) {
        this.matchTeamId = matchTeamId;
        this.lobbyTeamId = lobbyTeamId;
        this.mapId = mapId;
        this.teamNumber = teamNumber;
        this.leaderId = leaderId;
        this.capacity = capacity;
        this.splitFromPremade = splitFromPremade;
    }

    /**
     * 追加一名成员。squadIndex 由调用方（分配算法）按插入顺序给出，
     * 对象建好后不再调用。
     */
    void addMember(UUID playerId, int squadIndex, String name, boolean splitOrigin) {
        members.put(playerId, new Member(playerId, squadIndex, name, splitOrigin));
    }

    public UUID getMatchTeamId() {
        return matchTeamId;
    }

    public UUID getLobbyTeamId() {
        return lobbyTeamId;
    }

    public String getMapId() {
        return mapId;
    }

    public int getTeamNumber() {
        return teamNumber;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    /** 本队容量（地图 maxTeamSize 快照）。 */
    public int getCapacity() {
        return capacity;
    }

    /** 本队成员是否来自被拆分的预组队（第四步：客户端 HUD 拆分提示的依据）。 */
    public boolean isSplitFromPremade() {
        return splitFromPremade;
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID playerId) {
        return members.containsKey(playerId);
    }

    public Member member(UUID playerId) {
        return members.get(playerId);
    }

    /** 该成员的座号（1 号位起）；不在本队返回 -1。 */
    public int squadIndexOf(UUID playerId) {
        Member member = members.get(playerId);
        return member == null ? -1 : member.squadIndex();
    }

    /** 全部成员 UUID（含自己），按座号升序。 */
    public List<UUID> memberIds() {
        List<UUID> ids = new ArrayList<>(members.keySet());
        ids.sort(Comparator.comparingInt(id -> members.get(id).squadIndex()));
        return ids;
    }

    /** 按座号升序的成员记录拷贝。 */
    public List<Member> membersSorted() {
        List<Member> list = new ArrayList<>(members.values());
        list.sort(Comparator.comparingInt(Member::squadIndex));
        return list;
    }

    public boolean isLeader(UUID playerId) {
        return leaderId.equals(playerId);
    }
}
