package com.paimeng.battleroyale.team;

import com.paimeng.battleroyale.config.GameModeStore;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GamePhase;
import com.paimeng.battleroyale.event.PlayerJoinedTeamEvent;
import com.paimeng.battleroyale.event.PlayerLeftTeamEvent;
import com.paimeng.battleroyale.event.TeamCreatedEvent;
import com.paimeng.battleroyale.network.TeamSync;
import com.paimeng.battleroyale.network.TeamSyncPayload;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 极简队伍系统（服务端唯一权威，内存数据，不存盘——重启服务器队伍清零）。
 *
 * <p>两张核心映射按需求来：
 * <ul>
 *   <li>{@code playerToTeam}：玩家UUID → 队伍ID；</li>
 *   <li>{@code teams}：队伍ID → {@link Team} 对象。</li>
 * </ul>
 * 外加一张 {@code pendingInvites}：被邀请人UUID → 发邀请的队伍ID（一人同时只留最新一条）。
 *
 * <p>每个动作的固定收尾：先在 EventBus 上发事件（TeamCreatedEvent /
 * PlayerJoinedTeamEvent / PlayerLeftTeamEvent），再当场 {@link TeamSync#syncTeam}
 * 把最新队伍快照推给所有在线队员——这就是需求说的"数据变化时立即同步一次"，
 * 所以本类不挂任何 tick。
 *
 * <p>队长退队 / 断线时：若还有活人，队伍列表顺位第一（编号最小）的成员自动接任队长
 * （{@link Team} 的 leaderId 是不可变的，换队长 = 用同一个 teamId 重建对象）；
 * 只剩他一人则直接解散。普通队员断线 = 自动离队。队长也可以用
 * {@code /paimengmods br team transfer <玩家名>} 手动移交（{@link #transfer}）。
 *
 * <p><b>[BR-MATCHTEAM] 与局内队伍的边界</b>：本类只管大厅队（DraftGroup / LobbyTeam），
 * 写入的"队伍编号 1-99、队员编号 1-99"都是<b>大厅座次</b>，只用于大厅 HUD 排序，
 * 绝不是局内 1~4 号位；局内座号（squadIndex）与战术颜色只由 {@link MatchTeamManager}
 * 在 WAITING → PREPARING 的分配瞬间写进 MatchTeam。create / invite / accept / join
 * 等命令成功后只做两件事：改大厅队数据 + 发 {@link TeamSync} 大厅快照（match=false）
 * 刷新大厅 HUD。旧 Matchmaker 在指令路径上直接改局内编队的时代已结束，死代码已删。
 *
 * <p><b>[BR-LOBBY-GATE] 状态机拦截</b>：一切大厅队伍管理（建队 / 邀请 / 接受 / 拒绝 /
 * 加入 / 审批 / 踢人 / 退队 / 移交 / 招募）只允许在 {@code WAITING}（大厅）阶段执行；
 * 进入 PREPARING / FLIGHT（投放跳伞）/ RUNNING / ENDED 后一律回
 * 「游戏已开始，局内无法进行大厅队伍管理。」。断线清理不受此限制——那是被动维护。
 *
 * <p>线程约定：所有方法只在服务端主线程调用（指令执行、连接事件都在这条线程）。
 *
 * <p><b>升级预留</b>：本类只用到 ServerPlayer#getUuid()、
 * getGameProfile().getName()、{@code player.server} 字段和 sendMessage(Component, boolean)，
 * 全部跨版本稳定；1.20.5+ 只有 sendMessage 换名，改 {@link #whisper} 一处即可。
 */
public final class TeamManager {

    public static final TeamManager INSTANCE = new TeamManager();

    /** 玩家UUID → 所在队伍ID。 */
    private final Map<UUID, UUID> playerToTeam = new HashMap<>();

    /** 队伍ID → 队伍对象。 */
    private final Map<UUID, Team> teams = new HashMap<>();

    /** 待处理的邀请：被邀请人UUID → 发出邀请的队伍ID（后来者覆盖前者）。 */
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();
    /** 队伍ID → 待审批的入队申请。 */
    private final Map<UUID, Map<UUID, JoinRequest>> pendingJoins = new HashMap<>();

    private record JoinRequest(UUID applicantId, String applicantName, long createdAtMs) {}

    private TeamManager() {
    }

    /** 指令层的统一返回：成功/失败 + 一句要回显给玩家的话。 */
    public record Result(boolean success, String message) {
        static Result ok(String message) {
            return new Result(true, message);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    /**
     * [BR-LOBBY-GATE] 大厅队伍管理的状态机闸门：只有 WAITING（大厅）阶段放行。
     *
     * <p>一旦进入 PREPARING（MatchTeam 已依据大厅现状分配完成）以及随后的
     * FLIGHT（投放跳伞）/ RUNNING / ENDED，大厅队就是"上一局的存档"，
     * 局内改它会和 MatchTeam 打架，一律拒绝。
     *
     * @return null = 允许执行；非 null = 直接把这个失败 Result 回给玩家
     */
    private static Result lobbyManagementGate() {
        return GameManager.INSTANCE.getPhase() == GamePhase.WAITING
                ? null
                : Result.fail("游戏已开始，局内无法进行大厅队伍管理");
    }

    // ------------------------------------------------------------------
    // 查询（给指令层用）
    // ------------------------------------------------------------------

    /** 该玩家所在队伍；没有队伍返回 null。 */
    public Team teamOf(UUID playerId) {
        UUID teamId = playerToTeam.get(playerId);
        return teamId == null ? null : teams.get(teamId);
    }

    /** 按玩家 UUID 查询队伍编号；没有队伍返回 0。 */
    public int teamNumberOf(UUID playerId) {
        Team team = teamOf(playerId);
        return team == null ? 0 : team.getTeamNumber();
    }

    /** 队伍统一颜色定义，客户端 HUD 与服务端队伍编号共用这组值。 */
    public static int colorForTeamNumber(int teamNumber) {
        return switch (teamNumber) {
            case 1 -> 0xFFFFFF55; // 黄色
            case 2 -> 0xFFFFAA33; // 橙色
            case 3 -> 0xFF5599FF; // 蓝色
            case 4 -> 0xFF55DD55; // 绿色
            default -> 0xFFFFFFFF; // 无队伍或未定义队伍
        };
    }

    // ------------------------------------------------------------------
    // /paimengmods br team create
    // ------------------------------------------------------------------

    public Result create(ServerPlayer player) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        UUID playerId = player.getUUID();
        if (teamOf(playerId) != null) {
            return Result.fail("你已经在队伍里了，先用 /paimengmods br team leave 退出。");
        }
        int teamNumber = firstFreeTeamNumber();
        if (teamNumber <= 0) {
            return Result.fail("队伍编号 1-99 已用满，暂时创建不了。");
        }
        String name = nameOf(player);
        Team team = new Team(UUID.randomUUID(), teamNumber, playerId);
        team.addMember(playerId, name);                     // 队长 = 1 号
        teams.put(team.getTeamId(), team);
        playerToTeam.put(playerId, team.getTeamId());
        pendingInvites.remove(playerId);

        EventBus.INSTANCE.publish(new TeamCreatedEvent(team, playerId, name, player.server));
        TeamSync.syncTeam(team, player.server);
        return Result.ok("已创建 " + teamNumber + " 号队伍，你是队长（队员 1 号）。"
                + "用 /paimengmods br team invite <玩家名> 拉人。");
    }

    // ------------------------------------------------------------------
    // /paimengmods br team invite <玩家名>
    // ------------------------------------------------------------------

    public Result invite(ServerPlayer from, ServerPlayer target) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(from.getUUID());
        if (team == null) {
            return Result.fail("你还没有队伍，先用 /paimengmods br team create 创建。");
        }
        if (from.getUUID().equals(target.getUUID())) {
            return Result.fail("不能邀请自己。");
        }
        if (teamOf(target.getUUID()) != null) {
            return Result.fail(nameOf(target) + " 已经在队伍里了。");
        }
        if (team.isFull()) {
            return Result.fail(team.getTeamNumber() + " 号队伍已满（99 人）。");
        }
        pendingInvites.put(target.getUUID(), team.getTeamId());
        sendClickableInvite(target, nameOf(from));
        return Result.ok("已向 " + nameOf(target) + " 发出邀请（TA 输入 /paimengmods br team accept 接受）。");
    }

    /**
     * 被邀请者收到的可点击邀请消息：
     * [队伍邀请]（金色） 名字邀请你加入队伍（白色） [接受]（绿+下划线，点击执行 accept）
     * [拒绝]（红+下划线，点击执行 deny）。
     */
    private void sendClickableInvite(ServerPlayer target, String fromName) {
        MutableComponent out = colored("[队伍邀请] ", 0xFFAA00);
        out.append(colored(fromName + "邀请你加入队伍 ", ChatFormatting.WHITE));
        out.append(clickable("[接受]", 0x55FF55, "/paimengmods br team accept", "点击接受邀请"));
        out.append(colored("  ", ChatFormatting.WHITE));
        out.append(clickable("[拒绝]", 0xFF5555, "/paimengmods br team deny", "点击拒绝邀请"));
        target.displayClientMessage(out, false);
    }

    // ------------------------------------------------------------------
    // /paimengmods br team deny（拒绝待处理邀请，并通知队长）
    // ------------------------------------------------------------------

    public Result deny(ServerPlayer player) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        UUID teamId = pendingInvites.remove(player.getUUID());
        if (teamId == null) {
            return Result.fail("没有待处理的队伍邀请。");
        }
        Team team = teams.get(teamId);
        if (team != null) {
            ServerPlayer leader = player.server.getPlayerList().getPlayer(team.getLeaderId());
            whisper(leader, "【大逃杀】" + nameOf(player) + " 拒绝了你的邀请。");
        }
        return Result.ok("已拒绝队伍邀请。");
    }

    // ------------------------------------------------------------------
    // /paimengmods br team hh <消息>（队长向全服广播招募，含可点击加入段）
    // ------------------------------------------------------------------

    /**
     * 只有队长能发招募。全服（含发送者自己）收到同一条富文本：
     * [队伍招募]（金色） 名字发送了队伍招募信息：（白色） 自定义消息（黄色）
     * 目前队伍人数为（当前/上限）（灰色，上限取 match.json 的 teamLimit，默认 4）
     * 点击这里加入（绿色下划线，点击执行 accept）；满员时替换为灰色"（队伍已满）"且不可点击。
     */
    public Result recruit(ServerPlayer leader, String message) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(leader.getUUID());
        if (team == null || !team.isLeader(leader.getUUID())) {
            return Result.fail("只有队长能发送招募消息");
        }
        int limit = Math.max(1, GameModeStore.recruitTeamLimit());
        int size = team.size();
        String leaderName = nameOf(leader);
        team.setLastRecruitAtMs(System.currentTimeMillis());

        MutableComponent out = colored("[队伍招募] ", 0xFFAA00);
        out.append(colored(leaderName + "发送了队伍招募信息：", ChatFormatting.WHITE));
        out.append(colored(message, 0xFFFF55));
        out.append(colored(" 目前队伍人数为（" + size + "/" + limit + "） ", ChatFormatting.GRAY));
        if (size < limit) {
            out.append(clickable("点击这里加入", 0x55FF55,
                    "/paimengmods br team join " + team.getTeamId(), "点击加入 " + leaderName + " 的队伍"));
        } else {
            out.append(colored("（队伍已满）", ChatFormatting.GRAY));
        }
        for (ServerPlayer receiver : leader.server.getPlayerList().getPlayers()) {
            receiver.displayClientMessage(out, false);
        }
        return Result.ok("已向全服发送队伍招募。");
    }

    /** 一段带 RGB 颜色的普通文本。 */
    private static MutableComponent colored(String raw, int color) {
        MutableComponent text = BrText.mutable(raw);
        text.setStyle(Style.EMPTY.withColor(color));
        return text;
    }

    /** 一段使用标准染色格式的普通文本。 */
    private static MutableComponent colored(String raw, ChatFormatting format) {
        MutableComponent text = BrText.mutable(raw);
        text.setStyle(Style.EMPTY.withColor(format));
        return text;
    }

    /** 一段绿色/红色 + 下划线的可点击文字：点击执行指令，悬停显示提示。 */
    private static MutableComponent clickable(String raw, int color, String command, String hover) {
        MutableComponent text = colored(raw, color);
        text.setStyle(text.getStyle().withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, BrText.of(hover))));
        return text;
    }

    // ------------------------------------------------------------------
    // /paimengmods br team join / approve / reject / kick
    // ------------------------------------------------------------------

    public Result join(ServerPlayer player, String rawTeamId) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = findTeam(rawTeamId);
        if (team == null) return Result.fail("队伍不存在");
        if (teamOf(player.getUUID()) != null) return Result.fail("你已在队伍中，请先退出");
        int limit = Math.max(1, GameModeStore.recruitTeamLimit());
        if (team.size() >= limit) return Result.fail("该队伍已满");
        long now = System.currentTimeMillis();
        if (team.getLastRecruitAtMs() > 0 && now - team.getLastRecruitAtMs() <= GameModeStore.recruitValidSeconds() * 1000L) {
            return addMemberAndNotify(player, team, nameOf(player) + " 通过招募加入了队伍");
        }
        pendingJoins.computeIfAbsent(team.getTeamId(), ignored -> new HashMap<>())
                .put(player.getUUID(), new JoinRequest(player.getUUID(), nameOf(player), now));
        ServerPlayer leader = player.server.getPlayerList().getPlayer(team.getLeaderId());
        if (leader != null) sendJoinRequest(leader, player);
        String leaderName = leader == null ? "队长" : nameOf(leader);
        return Result.ok("已向 " + leaderName + " 的队伍发送入队申请，请等待队长审批");
    }

    public Result approve(ServerPlayer leader, String applicantArg) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(leader.getUUID());
        if (team == null || !team.isLeader(leader.getUUID())) return Result.fail("只有队长能审批入队申请");
        UUID applicantId = findPendingApplicant(team, applicantArg);
        if (applicantId == null) return Result.fail("没有找到该玩家的待处理入队申请");
        ServerPlayer applicant = leader.server.getPlayerList().getPlayer(applicantId);
        if (applicant == null) return Result.fail("该玩家已离线");
        pendingJoins.getOrDefault(team.getTeamId(), new HashMap<>()).remove(applicantId);
        return addMemberAndNotify(applicant, team, nameOf(applicant) + " 加入了队伍");
    }

    public Result reject(ServerPlayer leader, String applicantArg) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(leader.getUUID());
        if (team == null || !team.isLeader(leader.getUUID())) return Result.fail("只有队长能处理入队申请");
        UUID applicantId = findPendingApplicant(team, applicantArg);
        if (applicantId == null) return Result.fail("没有找到该玩家的待处理入队申请");
        Map<UUID, JoinRequest> requests = pendingJoins.get(team.getTeamId());
        requests.remove(applicantId);
        ServerPlayer applicant = leader.server.getPlayerList().getPlayer(applicantId);
        if (applicant != null) whisper(applicant, "你的入队申请被拒绝");
        return Result.ok("已拒绝入队申请");
    }

    public Result kick(ServerPlayer leader, ServerPlayer target) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(leader.getUUID());
        if (team == null || !team.isLeader(leader.getUUID())) return Result.fail("只有队长能执行踢人");
        if (leader.getUUID().equals(target.getUUID())) return Result.fail("队长不能踢自己，请使用 /paimengmods br team leave");
        if (!team.contains(target.getUUID())) return Result.fail("目标不在你的队伍中");
        String targetName = nameOf(target);
        removeFromTeam(target, leader.server);
        broadcastToTeam(team, leader.server, targetName + " 被队长踢出了队伍");
        whisper(target, "你已被踢出队伍");
        return Result.ok("已将 " + targetName + " 踢出队伍");
    }

    private Result addMemberAndNotify(ServerPlayer player, Team team, String notice) {
        if (team.size() >= Math.max(1, GameModeStore.recruitTeamLimit())) return Result.fail("该队伍已满");
        int number = team.addMember(player.getUUID(), nameOf(player));
        if (number < 0) return Result.fail("该队伍已满");
        playerToTeam.put(player.getUUID(), team.getTeamId());
        pendingInvites.remove(player.getUUID());
        Map<UUID, JoinRequest> requests = pendingJoins.get(team.getTeamId());
        if (requests != null) requests.remove(player.getUUID());
        EventBus.INSTANCE.publish(new PlayerJoinedTeamEvent(team, player.getUUID(), nameOf(player), number, player.server));
        TeamSync.syncTeam(team, player.server);
        broadcastToTeam(team, player.server, notice);
        return Result.ok("已加入 " + team.getTeamNumber() + " 号队伍，你的队员编号是 " + number + " 号");
    }

    private void sendJoinRequest(ServerPlayer leader, ServerPlayer applicant) {
        MutableComponent out = colored("[入队申请] ", 0xFFAA00)
                .append(colored(nameOf(applicant) + " 想加入你的队伍  ", ChatFormatting.WHITE));
        out.append(clickable("[同意]", 0x55FF55,
                "/paimengmods br team approve " + applicant.getUUID(), "同意入队"));
        out.append(colored("  ", ChatFormatting.WHITE));
        out.append(clickable("[拒绝]", 0xFF5555,
                "/paimengmods br team reject " + applicant.getUUID(), "拒绝入队"));
        leader.displayClientMessage(out, false);
    }

    private UUID findPendingApplicant(Team team, String raw) {
        Map<UUID, JoinRequest> requests = pendingJoins.get(team.getTeamId());
        if (requests == null) return null;
        try {
            UUID id = UUID.fromString(raw);
            return requests.containsKey(id) ? id : null;
        } catch (IllegalArgumentException ignored) {
            for (JoinRequest request : requests.values()) {
                if (request.applicantName().equalsIgnoreCase(raw)) return request.applicantId();
            }
            return null;
        }
    }

    private Team findTeam(String raw) {
        try {
            Team team = teams.get(UUID.fromString(raw));
            if (team != null) return team;
        } catch (IllegalArgumentException ignored) {
        }
        try {
            int number = Integer.parseInt(raw);
            return teams.values().stream().filter(team -> team.getTeamNumber() == number).findFirst().orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void broadcastToTeam(Team team, MinecraftServer server, String message) {
        if (server == null) return;
        for (ServerPlayer member : server.getPlayerList().getPlayers()) {
            if (team.contains(member.getUUID())) member.displayClientMessage(BrText.of(message), false);
        }
    }

    // ------------------------------------------------------------------
    // /paimengmods br team accept
    // ------------------------------------------------------------------

    public Result accept(ServerPlayer player) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        UUID playerId = player.getUUID();
        if (teamOf(playerId) != null) {
            pendingInvites.remove(playerId);
            return Result.fail("你已经有队伍了，先用 /paimengmods br team leave 退出。");
        }
        UUID teamId = pendingInvites.remove(playerId);
        if (teamId == null) {
            return Result.fail("没有收到任何入队邀请。");
        }
        Team team = teams.get(teamId);
        if (team == null) {
            return Result.fail("邀请你的队伍已经解散了。");
        }
        String name = nameOf(player);
        int number = team.addMember(playerId, name);
        if (number < 0) {
            return Result.fail("邀请你的队伍已满。");
        }
        playerToTeam.put(playerId, teamId);

        EventBus.INSTANCE.publish(new PlayerJoinedTeamEvent(team, playerId, name, number, player.server));
        TeamSync.syncTeam(team, player.server);
        return Result.ok("已加入 " + team.getTeamNumber() + " 号队伍，你的队员编号是 " + number + " 号。");
    }

    // ------------------------------------------------------------------
    // /paimengmods br team leave（断线清理复用同一套逻辑）
    // /paimengmods br team transfer <玩家名>（队长手动移交）
    // ------------------------------------------------------------------

    public Result leave(ServerPlayer player) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        if (teamOf(player.getUUID()) == null) {
            return Result.fail("你没有队伍。");
        }
        return Result.ok(removeFromTeam(player, player.server));
    }

    /**
     * /paimengmods br team transfer &lt;玩家名&gt;：队长把队长职位移交给队内指定玩家。
     *
     * <p>只动大厅队数据：leaderId 不可变，沿用"同一个 teamId 重建对象"的既有模式，
     * 全员座次编号原样保留（移交不重排）；收尾同样只发大厅 {@link TeamSync} 快照，
     * 不碰局内 MatchTeam 座号 / 颜色。移交后原队长变成普通队员，权限判断
     * 全部以重建后对象里的 {@code leaderId} 为准。
     */
    public Result transfer(ServerPlayer leader, ServerPlayer target) {
        Result gate = lobbyManagementGate();
        if (gate != null) {
            return gate;
        }
        Team team = teamOf(leader.getUUID());
        if (team == null || !team.isLeader(leader.getUUID())) {
            return Result.fail("只有队长能移交队长职位");
        }
        UUID targetId = target.getUUID();
        if (!team.contains(targetId)) {
            return Result.fail(nameOf(target) + " 不在你的队伍里");
        }
        if (team.isLeader(targetId)) {
            return Result.fail("TA 已经是队长了");
        }
        // 同一个 teamId / teamNumber 重建，全员座次编号原样搬过去，只换 leaderId。
        Team current = new Team(team.getTeamId(), team.getTeamNumber(), targetId);
        for (Team.Entry entry : team.membersSortedByNumber()) {
            current.addMemberWithNumber(entry.playerId(), entry.name(), entry.number());
        }
        current.setLastRecruitAtMs(team.getLastRecruitAtMs());
        teams.put(team.getTeamId(), current);
        // playerToTeam 记的是 teamId，未变；无需逐成员重挂。
        TeamSync.syncTeam(current, leader.server);

        String targetName = nameOf(target);
        whisper(leader, "【大逃杀】你已把队长移交给 " + targetName + "，你现在是普通队员。");
        whisper(target, "【大逃杀】" + nameOf(leader) + " 把队长移交给了你，你现在是 "
                + current.getTeamNumber() + " 号队伍的队长。");
        broadcastToTeam(current, leader.server, "【队伍】" + nameOf(leader)
                + " 已将队长移交给 " + targetName + "。");
        return Result.ok("已把队长移交给 " + targetName + "。");
    }

    /** 玩家断线：自动离队（不给他发聊天，只维护服务端状态并同步队友）。 */
    public void handleDisconnect(ServerPlayer player) {
        UUID playerId = player.getUUID();
        pendingInvites.remove(playerId);
        if (teamOf(playerId) == null) {
            return;
        }
        removeFromTeam(player, player.server);
    }

    /**
     * 把玩家从队伍拿掉：改两张映射、必要时换队长/解散、发事件、同步。
     *
     * @return 给执行者看的一句话
     */
    private String removeFromTeam(ServerPlayer player, MinecraftServer server) {
        UUID playerId = player.getUUID();
        UUID teamId = playerToTeam.remove(playerId);
        Team team = teamId == null ? null : teams.get(teamId);
        if (team == null) {
            return "已离开队伍。";
        }
        String name = nameOf(player);
        int number = team.removeMember(playerId);
        boolean disbanded = team.size() == 0;
        Team current = team;
        if (disbanded) {
            teams.remove(teamId);
        } else {
            if (team.isLeader(playerId)) {
                // 队长退队：编号最小的活人接任（极简规则，不做选举）。
                // leaderId 不可变，所以用同一个 teamId 重建 Team 对象再放回映射。
                current = new Team(team.getTeamId(), team.getTeamNumber(),
                        team.membersSortedByNumber().get(0).playerId());
                for (Team.Entry entry : team.membersSortedByNumber()) {
                    // 指定编号放回，剩下的人编号保持不变
                    current.addMemberWithNumber(entry.playerId(), entry.name(), entry.number());
                }
                current.setLastRecruitAtMs(team.getLastRecruitAtMs());
                teams.put(teamId, current);
                playerToTeam.put(current.getLeaderId(), teamId);
                ServerPlayer heir = server == null ? null
                        : server.getPlayerList().getPlayer(current.getLeaderId());
                whisper(heir, "【大逃杀】前任队长离开了，你现在是 "
                        + current.getTeamNumber() + " 号队伍的队长。");
            }
            // 剩下的队员立刻刷新（快照取的是 teams 里的最新对象）
            TeamSync.syncTeam(current, server);
        }

        EventBus.INSTANCE.publish(new PlayerLeftTeamEvent(
                teamId, team.getTeamNumber(), playerId, name, Math.max(number, 0), disbanded, server));
        // 空快照单发给离队的本人，让 TA 的客户端清掉队友列表
        TeamSync.sendTo(server.getPlayerList().getPlayer(playerId), TeamSyncPayload.empty());
        return disbanded
                ? "你离开了 " + team.getTeamNumber() + " 号队伍，队伍已解散。"
                : "你离开了 " + team.getTeamNumber() + " 号队伍。";
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 最小空闲队伍编号（1-99）；99 个队全开满返回 0。 */
    private int firstFreeTeamNumber() {
        for (int n = 1; n <= Team.MAX_MEMBERS; n++) {
            final int candidate = n;
            boolean used = teams.values().stream().anyMatch(t -> t.getTeamNumber() == candidate);
            if (!used) {
                return n;
            }
        }
        return 0;
    }

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    /** 给单个玩家发一条系统聊天（target 允许 null：断线时他可能已不在列表里）。 */
    private static void whisper(ServerPlayer target, String raw) {
        if (target != null) {
            // [UPGRADE] 1.20.5+ sendMessage(Component, boolean) 改名 displayClientMessage
            target.displayClientMessage(BrText.of(raw), false);
        }
    }
}
