package com.paimeng.battleroyale.team;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.MatchmakingCompleteEvent;
import com.paimeng.battleroyale.event.PlayerEliminatedEvent;
import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.match.MapQueue;
import com.paimeng.battleroyale.network.MatchRosterPayload;
import com.paimeng.battleroyale.network.MatchRosterSync;
import com.paimeng.battleroyale.network.TeamSync;
import com.paimeng.battleroyale.network.TeamSyncPayload;
import com.paimeng.battleroyale.network.TeammatePosSync;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [BR-MATCHTEAM] 局内临时队伍（MatchTeam）权威管理器 —— PUBG 标准匹配：
 * 大厅自由组队的 {@link Team}（LobbyTeam）在这里只被<b>读取</b>，绝不修改；
 * WAITING → PREPARING 的瞬间按地图的 maxTeams / maxTeamSize 重新“编译”出一套
 * 只在本局生效的临时队伍，游戏结束（回 WAITING）整套销毁，大厅队伍原样恢复。
 *
 * <p>分配算法（模块二，最终四步版，实现在 {@link MatchTeamAllocator}）：
 * <ol>
 *   <li><b>预拆解</b>：人数 &gt; capacity（地图 maxTeamSize）的预组队直接拆成
 *       若干满员块 + 一个残块，原队长留在第一块，拆出的每一支带「来自同一开黑队」标识；</li>
 *   <li><b>分桶与补人</b>：碎片 + 合法预组队（含野排散人）进 B1..B(capacity-1) 桶，
 *       桶内随机打乱，按 PLAN（容量的整数分划，如 4 → 3+1 → 2+2 → 2+1+1 → 1+1+1+1）贪心合并，
 *       合不掉的各自成残队；</li>
 *   <li><b>队伍数上限校验</b>：重组后队伍数 &gt; maxTeams → 整局不开，
 *       给等待玩家报错「重组后队伍数为 X，超出本地图上限 Y」；</li>
 *   <li><b>提示与同步</b>：编号 + 座号 + MatchTeam 落盘 + TeamSync（含 split 字段），
 *       被拆分的预组队成员在局内 HUD 看到拆分提示。</li>
 * </ol>
 *
 * <p>线程约定：全部只在服务端主线程调用（状态事件、指令、tick 都在主线程）。
 */
public final class MatchTeamManager {

    public static final MatchTeamManager INSTANCE = new MatchTeamManager();

    /** 本局是否已经分配过 MatchTeam（PREPARING 起生效，回 WAITING 销毁）。 */
    private boolean active;

    private final List<MatchTeam> teams = new ArrayList<>();
    private final Map<UUID, MatchTeam> playerToTeam = new LinkedHashMap<>();

    /**
     * [BR-TEAMHUD] 本局淘汰名册：被分到 MatchTeam 后在本局内被淘汰（含逃跑判定）的 UUID。
     * 与 DownedManager 的即时状态区别开：那边在淘汰收尾的 clearPlayer 里会抹掉
     * eliminated 标记（复活/回大厅场景复用同一出口），这份名册只随本局走
     * （开局重建、销毁清空），是队伍 HUD 的 ELIMINATED 显示与掉线重连判定的权威依据。
     */
    private final Set<UUID> eliminatedThisMatch = new HashSet<>();

    /** [BR-MATCHTEAM 第三步] 开局失败闩：mapId → 失败当时等待队列的签名。 */
    private final Map<String, Long> startBlockedSignature = new HashMap<>();

    /** [BR-NAMETAG] 花名册周期重播计数（服务端主线程独占）。 */
    private int rosterTickCounter;

    /** [BR-NAMETAG] 花名册重播间隔（tick）：10 秒一份，兜底晚进服 / 丢包，流量可忽略。 */
    private static final int ROSTER_REBROADCAST_TICKS = 200;

    /**
     * [BR-WINGUARD] 本局开局时分发出去的 MatchTeam 总数。
     * 胜负判定必须要求开局 >= 2 支队伍：单人测试只有 1 支队时，
     * 「存活队伍数 <= 1」从第 1 tick 就恒成立，会在 RUNNING 刚切入的
     * 下一个 tick 就把状态机静默推到 ENDED。
     */
    private int startedTeamCount;

    private MatchTeamManager() {
    }

    // ------------------------------------------------------------------
    // 开局门槛（模块一）：WAITING 阶段看的是独立队伍数，不是总人数
    // ------------------------------------------------------------------

    /**
     * 任意一张等待中的地图，其“独立队伍数”达到该地图配置的 maxTeams 即可自动开局。
     * 独立队伍 = 大厅队（不管几人，整队算 1 支）；没建队的散人一人算一支。
     *
     * <p>第三步校验失败过的地图，只要等待队列还是那批人（签名没变）就跳过：
     * 否则每秒都会“达标 → 分配失败 → 回大厅”死循环刷屏；有人进出队列即自动放行重试。
     */
    public boolean thresholdReached(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        for (String mapId : MapQueue.INSTANCE.waitingMapIds()) {
            GameMap map = MapManager.INSTANCE.getMap(mapId);
            if (map == null) {
                continue;
            }
            Long blocked = startBlockedSignature.get(mapId);
            if (blocked != null && blocked == waitingSignature(mapId)) {
                continue;
            }
            if (independentTeamCount(server, mapId) >= effectiveMaxTeams(map)) {
                return true;
            }
        }
        return false;
    }

    /** 某张地图等待队列里的独立队伍数（离线报名者不计）。 */
    public int independentTeamCount(MinecraftServer server, String mapId) {
        Set<UUID> lobbySeen = new HashSet<>();
        int count = 0;
        for (UUID playerId : MapQueue.INSTANCE.snapshot(mapId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                continue;
            }
            Team lobby = TeamManager.INSTANCE.teamOf(playerId);
            if (lobby == null) {
                count++;                     // 散人：一人一支独立队伍
            } else if (lobbySeen.add(lobby.getTeamId())) {
                count++;                     // 大厅队：整队一支（含单人建的队）
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 开局分配（模块二核心）：WAITING -> PREPARING 事件里调用
    // ------------------------------------------------------------------

    /**
     * 依据大厅现状为本局所有等待地图生成 MatchTeam。
     *
     * @return true = 全部分配成功（调用方继续开局传送）；
     *         false = 有地图第三步校验失败或无可分配玩家，调用方必须把局退回 WAITING。
     */
    public boolean allocate(MinecraftServer server) {
        clearInternal();
        if (server == null) {
            return false;
        }

        // 先算后落盘：任何一张地图“重组后爆位”都不生成半套队伍，整局不开。
        LinkedHashMap<GameMap, MatchTeamAllocator.AllocationResult> results = new LinkedHashMap<>();
        for (String mapId : MapQueue.INSTANCE.waitingMapIds()) {
            GameMap map = MapManager.INSTANCE.getMap(mapId);
            if (map == null) {
                continue;
            }
            results.put(map, MatchTeamAllocator.allocate(collectPremades(server, map),
                    effectiveMaxTeamSize(map), effectiveMaxTeams(map)));
        }

        boolean failed = false;
        for (Map.Entry<GameMap, MatchTeamAllocator.AllocationResult> entry : results.entrySet()) {
            if (!entry.getValue().success()) {
                notifyStartFailure(server, entry.getKey(), entry.getValue().error());
                failed = true;
            }
        }
        if (failed) {
            return false;
        }

        boolean assignedAny = false;
        for (Map.Entry<GameMap, MatchTeamAllocator.AllocationResult> entry : results.entrySet()) {
            assignedAny |= commit(server, entry.getKey(), entry.getValue());
        }

        List<ServerPlayer> unqueued = new ArrayList<>();
        for (String name : GameManager.INSTANCE.getRoster()) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) {
                continue;                    // 离线报名者：留在名单，重连时按逃跑处理
            }
            if (MapQueue.INSTANCE.waitingMapOf(player.getUUID()) == null && !GameWorldManager.isGameWorld(player.level().dimension())) {
                unqueued.add(player);
            }
        }
        for (ServerPlayer player : unqueued) {
            player.displayClientMessage(BrText.of("【大逃杀】你不在任何地图的等待队列里，本局旁观。"), false);
        }

        active = assignedAny;
        if (assignedAny) {
            startedTeamCount = teams.size();
            // [BR-NAMETAG] 开局当场广播本局花名册（uuid→座号）：
            // 客户端给头顶 NameTag 拼 [N号] 前缀必须拿得到全部队伍的座号表。
            broadcastRoster(server);
            int players = playerToTeam.size();
            EventBus.INSTANCE.publish(new MatchmakingCompleteEvent(
                    teams.size(), players, teams.isEmpty() ? 1 : teams.get(0).size(),
                    "matchteam", server));
            BattleRoyaleMod.LOGGER.info("[BR-MATCHTEAM] 分配完成：{} 支 MatchTeam，{} 名参赛玩家（大厅队伍未改动）",
                    teams.size(), players);
        } else {
            BattleRoyaleMod.LOGGER.warn("[BR-MATCHTEAM] 没有任何可分配的在线玩家，本局没有 MatchTeam");
        }
        return assignedAny;
    }

    /** 收集一张地图的全部预组队：整支大厅队一个，野排散人一人一个。 */
    private List<MatchTeamAllocator.Premade> collectPremades(MinecraftServer server, GameMap map) {
        List<MatchTeamAllocator.Premade> premades = new ArrayList<>();
        LinkedHashMap<UUID, Team> lobbySeen = new LinkedHashMap<>();
        LinkedHashMap<UUID, List<MatchTeamAllocator.Member>> lobbyMembers = new LinkedHashMap<>();
        for (UUID playerId : MapQueue.INSTANCE.snapshot(map.mapId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                continue;
            }
            Team lobby = TeamManager.INSTANCE.teamOf(playerId);
            MatchTeamAllocator.Member member = new MatchTeamAllocator.Member(playerId,
                    player.getGameProfile().getName(), lobbyOrderOf(lobby, playerId), isLobbyLeader(playerId));
            if (lobby == null) {
                // 野排散人：1 人预组队，和大厅队走完全同一套桶逻辑。
                premades.add(new MatchTeamAllocator.Premade(null, playerId, 0, member.name(), List.of(member)));
            } else {
                lobbySeen.putIfAbsent(lobby.getTeamId(), lobby);
                lobbyMembers.computeIfAbsent(lobby.getTeamId(), id -> new ArrayList<>()).add(member);
            }
        }
        for (Map.Entry<UUID, Team> entry : lobbySeen.entrySet()) {
            Team lobby = entry.getValue();
            premades.add(new MatchTeamAllocator.Premade(lobby.getTeamId(), lobby.getLeaderId(),
                    lobby.getTeamNumber(), "队伍" + lobby.getTeamNumber(),
                    lobbyMembers.get(entry.getKey())));
        }
        return premades;
    }

    /** 第三步失败的报错：广播 + 记闩，防止门槛每秒“达标→失败→回大厅”死循环刷屏。 */
    private void notifyStartFailure(MinecraftServer server, GameMap map, String error) {
        startBlockedSignature.put(map.mapId, waitingSignature(map.mapId));
        String message = "【大逃杀】地图 " + map.mapId + " 开局失败：" + error
                + "请减少预组队数量重新等待，或让管理员调大该地图的队伍上限（maxTeams）。";
        if (server != null) {
            BrText.broadcast(server, message);
        }
        BattleRoyaleMod.LOGGER.warn("[BR-MATCHTEAM] {}", message);
    }

    /** 某地图等待队列的签名：进出人即变化，签名一变就放行门槛重试。 */
    private static long waitingSignature(String mapId) {
        List<UUID> ids = new ArrayList<>(MapQueue.INSTANCE.snapshot(mapId));
        java.util.Collections.sort(ids);
        long hash = 1469598103934665603L;
        for (UUID id : ids) {
            hash = (hash ^ id.getMostSignificantBits()) * 1099511628211L;
            hash = (hash ^ id.getLeastSignificantBits()) * 1099511628211L;
        }
        return hash;
    }

    /** 第四步落盘：编号 + 座号 + MatchTeam 构建（含容量与拆分标识）+ TeamSync 下发。 */
    private boolean commit(MinecraftServer server, GameMap map, MatchTeamAllocator.AllocationResult result) {
        int capacity = effectiveMaxTeamSize(map);
        List<MatchTeamAllocator.TeamDraft> ordered = new ArrayList<>(result.teams());
        ordered.sort(Comparator.comparingInt((MatchTeamAllocator.TeamDraft d) -> d.size()).reversed()
                .thenComparingInt(MatchTeamAllocator.TeamDraft::lobbyTeamNumber)
                .thenComparingInt(MatchTeamAllocator.TeamDraft::draftOrder));
        int teamNumber = 0;
        int seatTotal = 0;
        for (MatchTeamAllocator.TeamDraft draft : ordered) {
            if (draft.members().isEmpty()) {
                continue;
            }
            teamNumber++;
            seatTotal += draft.size();
            UUID leaderId = draft.leaderId() != null
                    ? draft.leaderId() : draft.members().get(0).playerId();
            MatchTeam team = new MatchTeam(UUID.randomUUID(), draft.lobbyTeamId(), map.mapId,
                    teamNumber, leaderId, capacity, draft.split());
            MatchTeamAllocator.Member leaderMember = memberOf(draft, leaderId);
            int seat = 0;
            team.addMember(leaderId, ++seat, leaderMember.name(), leaderMember.fromSplit());
            for (MatchTeamAllocator.Member member : draft.members()) {
                if (member.playerId().equals(leaderId)) {
                    continue;                // 队长恒 1 号位
                }
                team.addMember(member.playerId(), ++seat, member.name(), member.fromSplit());
            }
            teams.add(team);
            for (MatchTeam.Member member : team.membersSorted()) {
                playerToTeam.put(member.playerId(), team);
            }
            TeamSync.syncMatchTeam(team, server);
        }
        if (result.splitHappened()) {
            BattleRoyaleMod.LOGGER.warn("[BR-MATCHTEAM] 地图 {}：有预组队因容量 {} 上限被预拆解，相关队员局内 HUD 将显示拆分提示",
                    map.mapId, capacity);
        }
        BattleRoyaleMod.LOGGER.info("[BR-MATCHTEAM] 地图 {}：{} 支临时队伍 / {} 名选手（满编 {} 支，残队 {} 支，空位 {}，上限 {} 队 x {} 人）",
                map.mapId, teamNumber, seatTotal, result.fullTeams(), result.remnantTeams(),
                result.freeSlots(), effectiveMaxTeams(map), capacity);
        return teamNumber > 0;
    }

    // ------------------------------------------------------------------
    // 局内判定统一入口（模块四）：比赛进行中一律用 MatchTeam
    // ------------------------------------------------------------------

    public boolean isActive() {
        return active;
    }

    /** 该玩家是否已被分进本局的某支 MatchTeam。 */
    public boolean isAssigned(UUID playerId) {
        return active && playerId != null && playerToTeam.containsKey(playerId);
    }

    /**
     * [BR-MATCHTEAM] 统一"有没有队"的判定：局内看是否被分到某支 MatchTeam，
     * 未开局 / 大厅看是否有大厅队。快捷消息 / 标点的"无队只发自己"兜底都读它。
     */
    public boolean hasTeamView(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (active && playerToTeam.containsKey(playerId)) {
            return true;
        }
        if (active) {
            // 局内但没被分到 MatchTeam（例如中途加入的旁观）：不再认大厅队，避免半局串队。
            return false;
        }
        return TeamManager.INSTANCE.teamOf(playerId) != null;
    }

    /** 该玩家的 MatchTeam；没开局 / 不在局内返回 null。 */
    public MatchTeam teamOf(UUID playerId) {
        return active ? playerToTeam.get(playerId) : null;
    }

    /** 本局全部 MatchTeam（只读视图）。 */
    public List<MatchTeam> teams() {
        return List.copyOf(teams);
    }

    /** [BR-MATCHTEAM] 本局全部参赛玩家 UUID。 */
    public Set<UUID> assignedPlayerIds() {
        return Set.copyOf(playerToTeam.keySet());
    }

    /**
     * [BR-TEAMHUD] 队友存活状态联动：该玩家所在 MatchTeam 的状态快照重发给全队。
     * 由 DownedManager 在击倒 / 救起等瞬时状态切换、以及本局淘汰名册变化时调用；
     * 未开局或该玩家不在局内队伍里则静默跳过（大厅快照不需要状态字段）。
     */
    public void resyncStatusFor(UUID playerId, MinecraftServer server) {
        if (!active || playerId == null || server == null) {
            return;
        }
        MatchTeam team = playerToTeam.get(playerId);
        if (team != null) {
            TeamSync.syncMatchTeam(team, server);
        }
    }

    // ------------------------------------------------------------------
    // [BR-NAMETAG] 本局花名册（uuid → 座号）：客户端给任何一名玩家
    // 拼头顶 [N号] 前缀都要查这张表，所以必须全量下发，而不只自己那支队。
    // ------------------------------------------------------------------

    /** 组装并广播本局花名册；未开局 / 无队伍时静默跳过。 */
    public void broadcastRoster(MinecraftServer server) {
        if (!active || server == null || playerToTeam.isEmpty()) {
            return;
        }
        List<MatchRosterPayload.Entry> entries = new ArrayList<>(playerToTeam.size());
        for (Map.Entry<UUID, MatchTeam> entry : playerToTeam.entrySet()) {
            entries.add(new MatchRosterPayload.Entry(entry.getKey(),
                    entry.getValue().squadIndexOf(entry.getKey())));
        }
        MatchRosterSync.broadcast(server, new MatchRosterPayload(true, entries));
    }

    /** 广播空花名册：客户端清空座号表，头顶名字恢复原样（大厅阶段要求）。 */
    public void broadcastEmptyRoster(MinecraftServer server) {
        if (server == null) {
            return;
        }
        MatchRosterSync.broadcast(server, MatchRosterPayload.empty());
    }

    /**
     * 挂到 {@code ServerTickEvents.END_SERVER_TICK}：每 {@value #ROSTER_REBROADCAST_TICKS}
     * tick 重播一份花名册，兜底中途重连进服 / 丢包的客户端。
     */
    public void tick(MinecraftServer server) {
        if (!active || server == null) {
            rosterTickCounter = 0;
            return;
        }
        rosterTickCounter++;
        if (rosterTickCounter % ROSTER_REBROADCAST_TICKS == 0) {
            broadcastRoster(server);
        }
    }

    /** [BR-TEAMHUD] 该玩家是否在本局内已被淘汰（含逃跑判定；DownedManager 的瞬时标记也算）。 */
    public boolean wasEliminatedThisMatch(UUID playerId) {
        return playerId != null
                && (eliminatedThisMatch.contains(playerId) || DownedManager.INSTANCE.isEliminated(playerId));
    }

    /**
     * [BR-TEAMHUD] PlayerEliminatedEvent 消费者：进本局淘汰名册并立刻重发该队快照，
     * 全队 HUD 当场把这名成员画成灰色删除线（ELIMINATED）。
     * 只记被分到 MatchTeam 的参赛者；未开局（分配失败回退 / 大厅死亡）不进名册。
     */
    public void onPlayerEliminated(PlayerEliminatedEvent event) {
        if (!active || event == null || event.player() == null) {
            return;
        }
        UUID playerId = event.player().getUUID();
        if (!playerToTeam.containsKey(playerId)) {
            return;
        }
        if (eliminatedThisMatch.add(playerId)) {
            resyncStatusFor(playerId, event.server());
        }
    }

    /**
     * [BR-TEAMHUD] 成员在队伍 HUD 上的实时状态码：
     * 0 = ALIVE、1 = DOWNED（倒地待救）、2 = ELIMINATED（本局淘汰，优先于倒地）。
     * 数据权威取自 DownedManager（倒地）+ 本局淘汰名册（淘汰）。
     */
    public int matchStatusOf(UUID playerId) {
        if (playerId == null) {
            return com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_ALIVE;
        }
        if (wasEliminatedThisMatch(playerId)) {
            return com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_ELIMINATED;
        }
        if (DownedManager.INSTANCE.isDowned(playerId)) {
            return com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_DOWNED;
        }
        return com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_ALIVE;
    }

    /**
     * 队友判定：比赛进行中且双方都在局内时按 MatchTeam；
     * 其余情况（大厅、观战、旧调用）回退大厅队伍 —— 大厅语义保持不变。
     */
    public boolean isTeammate(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        if (active) {
            MatchTeam teamA = playerToTeam.get(a);
            MatchTeam teamB = playerToTeam.get(b);
            if (teamA != null || teamB != null) {
                // 只要任何一方在局内，就只认 MatchTeam（队友免伤 / 播报染色 / 救援都走这条）
                return teamA != null && teamA == teamB;
            }
        }
        Team lobbyA = TeamManager.INSTANCE.teamOf(a);
        return lobbyA != null && lobbyA.contains(b);
    }

    /**
     * “同队成员（含自己）”列表：局内按 MatchTeam，大厅按 LobbyTeam，
     * 都没有时返回只含自己的单元素列表。
     */
    public List<UUID> memberIdsIncludingSelf(UUID playerId) {
        List<UUID> result = new ArrayList<>();
        if (playerId == null) {
            return result;
        }
        MatchTeam team = active ? playerToTeam.get(playerId) : null;
        if (team != null) {
            result.addAll(team.memberIds());
            return result;
        }
        Team lobby = TeamManager.INSTANCE.teamOf(playerId);
        if (lobby != null) {
            for (Team.Entry entry : lobby.membersInJoinOrder()) {
                result.add(entry.playerId());
            }
            return result;
        }
        result.add(playerId);
        return result;
    }

    /**
     * 只查询本局 MatchTeam 的座号；大厅阶段明确返回 -1。
     * [BR-NOLOBBYNUMBER] 大厅队没有 squadIndex，避免快捷消息/标点误用大厅成员顺序上色。
     */
    public int squadIndexOf(UUID playerId) {
        MatchTeam team = active ? playerToTeam.get(playerId) : null;
        return team == null ? -1 : team.squadIndexOf(playerId);
    }

    /** 存活队伍数（队伍里还有未被本局淘汰的成员就算存活）；未开局返回 -1。 */
    public int aliveTeamCount() {
        if (!active) {
            return -1;
        }
        int alive = 0;
        for (MatchTeam team : teams) {
            // [BR-TEAMHUD] 淘汰判定统一走本局名册（含 DownedManager 瞬时标记兜底），
            // 与队伍 HUD 的 ELIMINATED 状态同一份真相。
            for (UUID memberId : team.memberIds()) {
                if (!wasEliminatedThisMatch(memberId)) {
                    alive++;
                    break;
                }
            }
        }
        return alive;
    }

    /**
     * 本局是否已分出胜负（只剩 0~1 支存活队伍）；未开局返回 false。
     *
     * <p>[BR-WINGUARD] 必须开局就分发过 >= 2 支队伍才可能判胜负：
     * 单人/单队测试局存活队伍数恒等于 1，不加这道门槛会导致
     * RUNNING 后第 1 个 tick 就误判"冠军产生"直接 ENDED。
     */
    public boolean isMatchDecided() {
        // 单队测试局也应在 RUNNING 首个 tick 进入结算；无队伍则不判定。
        return active && startedTeamCount >= 1 && aliveTeamCount() <= 1;
    }

    /** [BR-WINGUARD] 开局分发出去的 MatchTeam 总数（未开局为 0），供结算/日志用。 */
    public int startedTeamCount() {
        return startedTeamCount;
    }

    // ------------------------------------------------------------------
    // FLIGHT 播报（模块四）：上飞机时把队伍编号 + 座次表发给每名队员
    // ------------------------------------------------------------------

    public void announceFlightTeams(MinecraftServer server) {
        if (!active || server == null) {
            return;
        }
        for (MatchTeam team : teams) {
            for (MatchTeam.Member member : team.membersSorted()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member.playerId());
                if (player == null) {
                    continue;
                }
                MutableComponent head = BrText.mutable("【大逃杀】您的队伍编号：#" + team.getTeamNumber() + "队")
                        .withStyle(ChatFormatting.YELLOW);
                player.displayClientMessage(head, false);
                for (MatchTeam.Member seat : team.membersSorted()) {
                    String line = seat.squadIndex() + "号位：" + seat.name()
                            + (team.isLeader(seat.playerId()) ? "（队长）" : "")
                            + (seat.playerId().equals(member.playerId()) ? "（你）" : "");
                    player.displayClientMessage(BrText.of(line), false);
                }
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-MATCHTEAM] FLIGHT 队伍播报已发送：{} 支队伍", teams.size());
    }

    // ------------------------------------------------------------------
    // 结束销毁：回 WAITING 时整套 MatchTeam 消失，大厅队伍原样恢复
    // ------------------------------------------------------------------

    /** 销毁本局全部 MatchTeam，并把大厅队伍快照补发给每名原参赛者。 */
    public void disband(MinecraftServer server) {
        if (!active && teams.isEmpty() && playerToTeam.isEmpty()) {
            return;
        }
        List<UUID> affected = new ArrayList<>(playerToTeam.keySet());
        clearInternal();
        if (server != null) {
            for (UUID playerId : affected) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    continue;
                }
                Team lobby = TeamManager.INSTANCE.teamOf(playerId);
                // clearInternal() 已把 active 置 false，isAssigned 返回 false，
                // 这里的大厅快照不会再被 sendTo 里的“局内覆盖保护”拦掉。
                TeamSync.sendTo(player, lobby == null ? TeamSyncPayload.empty() : TeamSync.of(lobby));
            }
            // [BR-NAMETAG] / [BR-MINIMAP] 本局结束：花名册与队友坐标一起清干净，
            // 头顶名字恢复原样、小地图队友图标消失（大厅阶段不留局内视觉残留）。
            broadcastEmptyRoster(server);
            TeammatePosSync.broadcastClear(server);
        }
        BattleRoyaleMod.LOGGER.info("[BR-MATCHTEAM] 本局 MatchTeam 已销毁，{} 名玩家的队伍视图恢复为大厅状态",
                affected.size());
    }

    /** 服务器关停等场景：只清内存，不发任何同步包（人都不在线了）。 */
    public void clearSilently() {
        clearInternal();
    }

    private void clearInternal() {
        active = false;
        startedTeamCount = 0;
        teams.clear();
        playerToTeam.clear();
        eliminatedThisMatch.clear();
        startBlockedSignature.clear();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int effectiveMaxTeams(GameMap map) {
        map.normalizeMatchLimits();
        return map.maxTeams;
    }

    private static int effectiveMaxTeamSize(GameMap map) {
        map.normalizeMatchLimits();
        return map.maxTeamSize;
    }

    /** 开局分配瞬间把大厅加入顺序转换成排序键；这个键不属于大厅数据，也不下发客户端。 */
    private static int lobbyOrderOf(Team lobby, UUID playerId) {
        if (lobby == null) {
            return 0;
        }
        int order = lobby.joinOrderOf(playerId);
        return order < 0 ? 999 : order;
    }

    private static boolean isLobbyLeader(UUID playerId) {
        Team lobby = TeamManager.INSTANCE.teamOf(playerId);
        return lobby != null && lobby.isLeader(playerId);
    }

    /** 草稿成员查找（队长 1 号位落盘时用）；理论上必找到，兜底返回占位。 */
    private static MatchTeamAllocator.Member memberOf(MatchTeamAllocator.TeamDraft draft, UUID playerId) {
        for (MatchTeamAllocator.Member member : draft.members()) {
            if (member.playerId().equals(playerId)) {
                return member;
            }
        }
        return new MatchTeamAllocator.Member(playerId, "?", 0, false);
    }
}
