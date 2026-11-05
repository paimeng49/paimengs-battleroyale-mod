package com.paimeng.battleroyale.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [BR-MATCHTEAM] 最终版局内队伍分配器 —— 四步流程，纯 Java（不 import Minecraft 类）：
 *
 * <ol>
 *   <li><b>预拆解（Pre-Splitting）</b>：遍历所有预组队（Premade，整支大厅队算一个，
 *       野排散人 = 1 人预组队）。人数 &gt; capacity 的直接拆：capacity=2 时 4 人拆两个 2 人、
 *       3 人拆 1 个 2 人 + 1 个 1 人；原队长永远留在人数最多（最先成块）的那支新队里；
 *       拆出的每一支碎片都打上「来自同一开黑队」标识（split + originName 溯源）；</li>
 *   <li><b>分桶与补人（PLAN 贪心合并）</b>：所有碎片 + 本来就合法的预组队投入 B1..B(capacity-1) 桶，
 *       每个桶内部先随机打乱；再按容量 capacity 的全部整数分划（字典序降序）依次合并 ——
 *       capacity=2: 2 → 1+1；capacity=3: 3 → 2+1 → 1+1+1；capacity=4: 4 → 3+1 → 2+2 → 2+1+1 → 1+1+1+1。
 *       模式跑完后剩下的队伍各自成残队；剩余散人按最多 capacity-1 人一队打包（单数就留残队）；</li>
 *   <li><b>队伍数上限校验</b>：重组后的队伍数 &gt; maxTeams 直接 success=false，
 *       错误信息「重组后队伍数为 X，超出本地图上限 Y」，绝不再偷偷拆队或让人旁观；</li>
 *   <li><b>提示与同步</b>：由调用方（{@link MatchTeamManager}）完成 —— 只要第 1 步拆过队，
 *       生成的每支 MatchTeam 带 split 标识，TeamSync 下发后客户端 HUD 显示拆分提示。</li>
 * </ol>
 *
 * <p>不变式：返回的每一支 {@link TeamDraft} 人数 ≤ capacity，且 members 的第一位就是队长
 * （{@code leaderId} 与 {@code members.get(0)} 指向同一人）。
 */
public final class MatchTeamAllocator {

    private MatchTeamAllocator() {
    }

    // ------------------------------------------------------------------
    // 输入 / 输出数据结构（第 19 条确认过的签名口径）
    // ------------------------------------------------------------------

    /**
     * 预组队里的一名成员：joinOrder = 大厅加入顺序下标（开局分配瞬间现算的排序键，
     * <b>不是大厅持有的编号</b>；散人 0），leader = 是否大厅队长，
     * fromSplit = [BR-MATCHTEAM 第四步] 该成员是否经历过第一步预拆解（HUD 拆分提示按人显示）。
     */
    public record Member(UUID playerId, String name, int joinOrder, boolean leader, boolean fromSplit) {

        /** 分配输入用的便捷构造（还没拆，fromSplit=false）。 */
        public Member(UUID playerId, String name, int joinOrder, boolean leader) {
            this(playerId, name, joinOrder, leader, false);
        }
    }

    /** 预组队：分配前就已成形的一组人。整支大厅队 = 一个；每个野排散人 = 1 人预组队。 */
    public record Premade(UUID lobbyTeamId, UUID leaderId, int lobbyTeamNumber,
                          String displayName, List<Member> members) {
        public int size() {
            return members.size();
        }
    }

    /** 分配过程中的一支草稿队（拆分碎片、合并成品、残队共用此形态）。 */
    public record TeamDraft(UUID lobbyTeamId, int lobbyTeamNumber, UUID leaderId,
                            String originName, boolean split, List<Member> members,
                            int draftOrder) {
        public int size() {
            return members.size();
        }
    }

    /** 分配结果：开局流程只看 success；失败读 error，成功读队伍列表和统计。 */
    public record AllocationResult(boolean success, String error, List<TeamDraft> teams,
                                   int fullTeams, int remnantTeams, int freeSlots,
                                   boolean splitHappened) {

        static AllocationResult fail(String error) {
            return new AllocationResult(false, error, List.of(), 0, 0, 0, false);
        }

        static AllocationResult ok(List<TeamDraft> teams, int fullTeams, int remnantTeams,
                                   int freeSlots, boolean splitHappened) {
            return new AllocationResult(true, null, List.copyOf(teams),
                    fullTeams, remnantTeams, freeSlots, splitHappened);
        }
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    /**
     * @param all      本地图全部预组队（大厅队 + 野排散人，散人也包成 1 人 Premade）
     * @param capacity 该地图 maxTeamSize（1~4，配置层已 clamp；这里只防御非法值）
     * @param maxTeams 该地图 maxTeams（第三步校验用）
     */
    public static AllocationResult allocate(List<Premade> all, int capacity, int maxTeams) {
        capacity = Math.max(1, capacity);
        int draftCounter = 0;

        // ---- 第一步：预拆解 ----
        List<TeamDraft> fragments = new ArrayList<>();
        boolean splitHappened = false;
        for (Premade premade : all) {
            List<Member> sorted = sortedMembers(premade.members());
            if (sorted.isEmpty()) {
                continue;
            }
            if (sorted.size() <= capacity) {
                fragments.add(normalDraft(premade, sorted, draftCounter++));
                continue;
            }
            splitHappened = true;
            int index = 0;
            while (index < sorted.size()) {
                int take = Math.min(capacity, sorted.size() - index);
                List<Member> chunk = new ArrayList<>(take);
                for (Member member : sorted.subList(index, index + take)) {
                    // 打过「来自同一开黑队」标识：本块每名成员都算被拆过。
                    chunk.add(new Member(member.playerId(), member.name(), member.joinOrder(),
                            member.leader(), true));
                }
                index += take;
                // 原队长在第一位（sorted 保证），所以第一个满容量块必然带队长；
                // 后面的块由本块座次最高者接任队长。
                UUID chunkLeader = chunk.get(0).playerId();
                if (premade.leaderId() != null) {
                    for (Member member : chunk) {
                        if (member.playerId().equals(premade.leaderId())) {
                            chunkLeader = member.playerId();
                            break;
                        }
                    }
                }
                fragments.add(new TeamDraft(premade.lobbyTeamId(), premade.lobbyTeamNumber(),
                        chunkLeader, premade.displayName(), true, chunk, draftCounter++));
            }
        }

        // ---- 第二步：分桶 + 随机打乱 + PLAN 贪心合并 ----
        List<List<TeamDraft>> buckets = new ArrayList<>();
        for (int i = 0; i <= capacity; i++) {
            buckets.add(new ArrayList<>());
        }
        for (TeamDraft draft : fragments) {
            buckets.get(draft.size()).add(draft);
        }
        for (List<TeamDraft> bucket : buckets) {
            Collections.shuffle(bucket);
        }

        List<TeamDraft> teams = new ArrayList<>();
        for (List<Integer> pattern : partitions(capacity)) {
            Map<Integer, Integer> need = new LinkedHashMap<>();
            for (int part : pattern) {
                need.merge(part, 1, Integer::sum);
            }
            while (hasEnough(buckets, need)) {
                List<TeamDraft> picked = new ArrayList<>();
                for (Map.Entry<Integer, Integer> entry : need.entrySet()) {
                    List<TeamDraft> bucket = buckets.get(entry.getKey());
                    for (int k = 0; k < entry.getValue(); k++) {
                        picked.add(bucket.remove(0));
                    }
                }
                teams.add(combine(picked, draftCounter++));
            }
        }
        // 残队收尾：先处理 B2..B(capacity-1) 的整支残队 —— 按第 19 条口径，
        // capacity=4 时"3 人队各自、2+1、2 单独"：残队可以捡散人补，但只补到 capacity-1
        // （补满 capacity 的组合在上面的 PLAN 里已经穷尽了）。然后把剩下的散人
        // 按最多 capacity-1 人一队打包（capacity=2 时落单散人单独成残队）。
        List<TeamDraft> singles = buckets.get(1);
        int cursor = 0;
        for (int size = capacity - 1; size >= 2; size--) {
            for (TeamDraft draft : buckets.get(size)) {
                TeamDraft current = draft;
                while (current.size() < capacity - 1 && cursor < singles.size()) {
                    current = combine(List.of(current, singles.get(cursor++)), draftCounter++);
                }
                teams.add(current);
            }
        }
        int singleChunk = Math.max(1, capacity - 1);
        while (cursor < singles.size()) {
            int take = Math.min(singleChunk, singles.size() - cursor);
            teams.add(combine(new ArrayList<>(singles.subList(cursor, cursor + take)), draftCounter++));
            cursor += take;
        }

        // ---- 第三步：队伍数上限校验 ----
        if (teams.size() > maxTeams) {
            return AllocationResult.fail("重组后队伍数为 " + teams.size() + "，超出本地图上限 " + maxTeams + "。");
        }

        int fullTeams = 0;
        int remnantTeams = 0;
        int freeSlots = 0;
        for (TeamDraft draft : teams) {
            if (draft.size() >= capacity) {
                fullTeams++;
            } else {
                remnantTeams++;
            }
            freeSlots += capacity - draft.size();
        }
        return AllocationResult.ok(teams, fullTeams, remnantTeams, freeSlots, splitHappened);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 队长恒排最前，其余按加入顺序升序（散人 joinOrder=0 不影响，本身就单人）。 */
    private static List<Member> sortedMembers(List<Member> members) {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparingInt((Member m) -> m.leader() ? 0 : 1)
                .thenComparingInt(m -> m.joinOrder() < 0 ? 999 : m.joinOrder()));
        return sorted;
    }

    /** 没超容量的预组队直接变草稿：队长取预组队队长（没在线则座次最高者接任）。 */
    private static TeamDraft normalDraft(Premade premade, List<Member> sorted, int draftOrder) {
        UUID leaderId = sorted.get(0).playerId();
        if (premade.leaderId() != null) {
            for (Member member : sorted) {
                if (member.playerId().equals(premade.leaderId())) {
                    leaderId = member.playerId();
                    break;
                }
            }
        }
        // 保证不变式：leader 排第一位（大厅队长不在大厅座次 1 时也要提到最前）。
        List<Member> ordered = new ArrayList<>(sorted.size());
        for (Member member : sorted) {
            if (member.playerId().equals(leaderId)) {
                ordered.add(member);
                break;
            }
        }
        for (Member member : sorted) {
            if (!member.playerId().equals(leaderId)) {
                ordered.add(member);
            }
        }
        return new TeamDraft(premade.lobbyTeamId(), premade.lobbyTeamNumber(), leaderId,
                premade.displayName(), false, ordered, draftOrder);
    }

    private static boolean hasEnough(List<List<TeamDraft>> buckets, Map<Integer, Integer> need) {
        for (Map.Entry<Integer, Integer> entry : need.entrySet()) {
            if (buckets.get(entry.getKey()).size() < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 合并若干支草稿：人数最多的那支配队长（同人数比大厅编号、再比创建序），
     * 成员顺序 = 队长队在前，其余按同样的优先级顺延；只要有一支来自拆分，成品就带 split 标识。
     */
    private static TeamDraft combine(List<TeamDraft> sources, int draftOrder) {
        if (sources.size() == 1) {
            return sources.get(0);
        }
        List<TeamDraft> byPriority = new ArrayList<>(sources);
        byPriority.sort(Comparator.comparingInt(TeamDraft::size).reversed()
                .thenComparingInt(TeamDraft::lobbyTeamNumber)
                .thenComparingInt(TeamDraft::draftOrder));
        List<Member> members = new ArrayList<>();
        boolean split = false;
        for (TeamDraft source : byPriority) {
            members.addAll(source.members());
            split |= source.split();
        }
        TeamDraft head = byPriority.get(0);
        return new TeamDraft(head.lobbyTeamId(), head.lobbyTeamNumber(), head.leaderId(),
                head.originName(), split, members, draftOrder);
    }

    /**
     * capacity 的全部整数分划，字典序降序（＝需求里说的模式常量表 PLAN）：
     * 4 → [4], [3,1], [2,2], [2,1,1], [1,1,1,1]；3 → [3], [2,1], [1,1,1]；2 → [2], [1,1]。
     */
    private static List<List<Integer>> partitions(int n) {
        List<List<Integer>> result = new ArrayList<>();
        buildPartitions(n, n, new ArrayList<>(), result);
        return result;
    }

    private static void buildPartitions(int remaining, int maxPart,
                                        List<Integer> current, List<List<Integer>> out) {
        if (remaining == 0) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int part = Math.min(maxPart, remaining); part >= 1; part--) {
            current.add(part);
            buildPartitions(remaining - part, part, current, out);
            current.remove(current.size() - 1);
        }
    }
}
