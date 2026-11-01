package com.paimeng.battleroyale.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 极简队伍对象（纯数据 + 编号分配，不直接碰 Minecraft 类）。
 *
 * <p>字段按需求来：
 * <ul>
 *   <li>{@code teamId}：队伍ID（随机 UUID，全服唯一）；</li>
 *   <li>{@code leaderId}：队长UUID（创建队伍的人）；</li>
 *   <li>{@code teamNumber}：队伍编号，1-99，创建时由 {@link TeamManager} 分配最小空闲值；</li>
 *   <li>{@code members}：成员UUID → 队内编号（1-99）。用 LinkedHashMap 保序，
 *       {@link #membersSortedByNumber()} 再按编号排一遍，保证列表稳定。</li>
 * </ul>
 *
 * <p>编号规则：创建队伍时队长固定为 1 号；之后加入的人依次拿"当前最小空闲编号"
 * （有人退队腾出编号后会被复用，永远不会超过 99）。
 *
 * <p>线程约定：只在服务端主线程读写（指令、连接事件都跑在主线程），不加锁。
 * 纯 Java 文件，升级版本时原样可用。
 */
public final class Team {

    /** 队内编号上限：1-99。 */
    public static final int MAX_MEMBERS = 99;

    private final UUID teamId;
    private final int teamNumber;
    private final UUID leaderId;
    private long lastRecruitAtMs;

    /** 成员UUID → 队内编号。LinkedHashMap 保留加入顺序。 */
    private final Map<UUID, Integer> members = new LinkedHashMap<>();

    /** 记录成员加入时的名字，断线前 / 同步包编码时用，省去反复查 PlayerManager。 */
    private final Map<UUID, String> names = new LinkedHashMap<>();

    public Team(UUID teamId, int teamNumber, UUID leaderId) {
        this.teamId = teamId;
        this.teamNumber = teamNumber;
        this.leaderId = leaderId;
    }

    // ------------------------------------------------------------------
    // 成员管理
    // ------------------------------------------------------------------

    /**
     * 把玩家拉进队并分配编号（队长创建时也会走这里，拿到 1 号）。
     *
     * @return 分配到的队内编号（1-99）；-1 = 队伍已满
     */
    public int addMember(UUID playerId, String playerName) {
        Integer existing = members.get(playerId);
        if (existing != null) {
            return existing;                     // 幂等：已在队里就返回现有编号
        }
        int number = firstFreeNumber();
        if (number <= 0) {
            return -1;                           // 满员
        }
        members.put(playerId, number);
        names.put(playerId, playerName);
        return number;
    }

    /**
     * 移出一个成员。
     *
     * @return 该成员原来占的编号；-1 = 他根本不在队里
     */
    public int removeMember(UUID playerId) {
        Integer number = members.remove(playerId);
        names.remove(playerId);
        return number == null ? -1 : number;
    }

    /**
     * 按<b>指定编号</b>放回一个成员。只在"换队长需要重建 Team 对象"时用，
     * 保证剩下的人编号不变；正常入队请走 {@link #addMember}。
     */
    void addMemberWithNumber(UUID playerId, String playerName, int number) {
        if (number < 1 || number > MAX_MEMBERS || members.containsValue(number) || members.containsKey(playerId)) {
            return;                              // 编号非法 / 已被占：静默跳过（重建时不该发生）
        }
        members.put(playerId, number);
        names.put(playerId, playerName);
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

    /** 当前没被占用的最小编号（1-99）；全部占用返回 0。 */
    private int firstFreeNumber() {
        for (int n = 1; n <= MAX_MEMBERS; n++) {
            if (!members.containsValue(n)) {
                return n;
            }
        }
        return 0;
    }

    /** 一条成员记录：UUID + 队内编号 + 名字。 */
    public record Entry(UUID playerId, int number, String name) {
    }

    /** 按队内编号升序返回成员列表（网络包与 /paimengmods br team list 都用这个顺序）。 */
    public List<Entry> membersSortedByNumber() {
        List<Entry> list = new ArrayList<>(members.size());
        members.forEach((uuid, number) -> list.add(new Entry(uuid, number, names.getOrDefault(uuid, "?"))));
        list.sort(Comparator.comparingInt(Entry::number));
        return list;
    }

    public int numberOf(UUID playerId) {
        Integer number = members.get(playerId);
        return number == null ? -1 : number;
    }

    public UUID getTeamId() {
        return teamId;
    }

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
