package com.paimeng.battleroyale.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 大厅队（DraftGroup / LobbyTeam）对象——纯数据，不碰 Minecraft 类。
 *
 * <p><b>[BR-NOLOBBYNUMBER] 大厅阶段不分配任何编号。</b>
 * 本类只记录成员和他们的<b>加入顺序</b>（LinkedHashMap 保序）：
 * <ul>
 *   <li>没有"队内编号"字段，也没有 firstFreeNumber 这类编号分配逻辑；</li>
 *   <li>1 黄 2 橙 3 蓝 4 绿那套局内座号配色与 {@code MatchTeam} 绑定，
 *       大厅队成员从头到尾不接触；</li>
 *   <li>开局分配（{@link MatchTeamAllocator}）需要"谁先加入"来决定座次顺序时，
 *       用 {@link #joinOrderOf(UUID)} 在<b>分配瞬间</b>换算下标——那是局内座号的
 *       输入，不是大厅持有的编号。</li>
 * </ul>
 *
 * <p>字段：
 * <ul>
 *   <li>{@code teamId}：队伍ID（随机 UUID，全服唯一）；</li>
 *   <li>{@code leaderId}：队长UUID（创建队伍的人）；</li>
 *   <li>{@code teamNumber}：大厅队伍ID 1-99（{@code /team join <编号>} 和招募消息用它
 *       定位队伍，是"队伍门牌"，不是局内 "#N队" 编号）；</li>
 *   <li>{@code members}：成员UUID → 名字，按加入顺序排列。</li>
 * </ul>
 *
 * <p>线程约定：只在服务端主线程读写（指令、连接事件都跑在主线程），不加锁。
 * 纯 Java 文件，升级版本时原样可用。
 */
public final class Team {

    /** 大厅队人数上限。 */
    public static final int MAX_MEMBERS = 99;

    private final UUID teamId;
    private final int teamNumber;
    private final UUID leaderId;
    private long lastRecruitAtMs;

    /** 成员UUID → 名字。LinkedHashMap 保留加入顺序（列表展示 / 开局座次都按它）。 */
    private final Map<UUID, String> members = new LinkedHashMap<>();

    public Team(UUID teamId, int teamNumber, UUID leaderId) {
        this.teamId = teamId;
        this.teamNumber = teamNumber;
        this.leaderId = leaderId;
    }

    // ------------------------------------------------------------------
    // 成员管理
    // ------------------------------------------------------------------

    /**
     * 把玩家拉进队。只记录人和加入顺序，<b>不分配任何编号</b>。
     *
     * @return true = 已在队里（含刚加入）；false = 队伍已满
     */
    public boolean addMember(UUID playerId, String playerName) {
        if (members.containsKey(playerId)) {
            return true;                         // 幂等：已在队里
        }
        if (members.size() >= MAX_MEMBERS) {
            return false;                        // 满员
        }
        members.put(playerId, playerName);
        return true;
    }

    /** 移出一个成员。@return true = 他原本在队里 */
    public boolean removeMember(UUID playerId) {
        return members.remove(playerId) != null;
    }

    public boolean contains(UUID playerId) {
        return members.containsKey(playerId);
    }

    public boolean isFull() {
        return members.size() >= MAX_MEMBERS;
    }

    public int size() {
        return members.size();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 一条成员记录：UUID + 名字（按加入顺序返回，没有编号字段）。 */
    public record Entry(UUID playerId, String name) {
    }

    /** 按加入顺序返回成员列表（网络包、/team list、开局座次输入都用这个顺序）。 */
    public List<Entry> membersInJoinOrder() {
        List<Entry> list = new ArrayList<>(members.size());
        members.forEach((uuid, name) -> list.add(new Entry(uuid, name)));
        return list;
    }

    /**
     * 加入顺序下标（0 起）；不在队里返回 -1。
     * <b>[BR-NOLOBBYNUMBER]</b> 仅供开局分配器在分配瞬间排座次用，
     * 绝不下发给客户端、绝不进聊天文案。
     */
    public int joinOrderOf(UUID playerId) {
        int index = 0;
        for (UUID id : members.keySet()) {
            if (id.equals(playerId)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    public UUID getTeamId() {
        return teamId;
    }

    /** 大厅队伍门牌号（1-99）：/team join 定位用，与局内 "#N队" 编号无关。 */
    public int getTeamNumber() {
        return teamNumber;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public long getLastRecruitAtMs() {
        return lastRecruitAtMs;
    }

    public void setLastRecruitAtMs(long lastRecruitAtMs) {
        this.lastRecruitAtMs = lastRecruitAtMs;
    }

    public boolean isLeader(UUID playerId) {
        return leaderId.equals(playerId);
    }
}
