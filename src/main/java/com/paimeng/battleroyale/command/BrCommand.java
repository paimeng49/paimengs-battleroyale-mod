package com.paimeng.battleroyale.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.config.GameModeStore;
import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GamePhase;
import com.paimeng.battleroyale.game.LootSpawner;
import com.paimeng.battleroyale.game.WarmupManager;
import com.paimeng.battleroyale.item.SelectionToolItem;
import com.paimeng.battleroyale.item.ModItems;
import net.minecraft.world.item.ItemStack;
import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.MapEditSession;
import com.paimeng.battleroyale.map.MapColorGenerator;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.map.SelectionData;
import com.paimeng.battleroyale.match.MapQueue;
import com.paimeng.battleroyale.network.LootPointsSync;
import com.paimeng.battleroyale.network.MapEditorSync;
import com.paimeng.battleroyale.network.MapListSync;
import com.paimeng.battleroyale.network.TeamSync;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.team.Team;
import com.paimeng.battleroyale.team.TeamManager;
import com.paimeng.battleroyale.util.BrText;
import com.paimeng.battleroyale.zone.ZoneManager;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * /paimengmods br join | leave | stop | setcenter | team ... 指令。
 *（开局不再由玩家侧 br start 触发：br join 进入等待后自动倒计时，
 * 管理员可用 /paimengmods manager start 强制开始。）
 *
 * <p>本类负责 {@code /paimengmods} 根指令下的 <b>br</b>（大逃杀 + 队伍）指令，
 * 并在服务端一次性挂载，避免客户端命令树合并冲突。
 *
 * <p>本类只负责“校验 + 给指令执行者回话”，真正的状态变更全部委托给
 * {@link GameManager} / {@link ZoneManager} / {@link TeamManager}——
 * 以后接入 GUI / 控制台时也能复用同一套入口。
 *
 * <p>升级预留：CommandManager / CommandSourceStack / EntityArgument 的包路径在
 * yarn 1.18 ~ 1.21 系列里保持稳定，本类升级时基本不用动。
 */
public final class BrCommand {

    private static final int SUCCESS = 1;
    private static final int FAILURE = 0;
    /** OP 权限等级，2 = 管理员。 */
    private static final int REQUIRED_OP_LEVEL = 2;

    private BrCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(CommandRoots.ROOT)
                .then(Commands.literal(CommandRoots.BR)
                        .then(Commands.literal("join")
                                .executes(ctx -> join(ctx.getSource()))
                                .then(Commands.argument("mapId", StringArgumentType.word())
                                        .executes(ctx -> joinMap(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mapId")))))
                        .then(Commands.literal("leave")
                                .executes(ctx -> leave(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(Commands.literal("loot_tool")
                                 .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                 .executes(ctx -> lootTool(ctx.getSource())))
                        .then(Commands.literal("chest_tool")
                                 .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                 .executes(ctx -> chestTool(ctx.getSource())))
                         .then(Commands.literal("debug")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .then(Commands.literal("loot")
                                        .executes(ctx -> debugLoot(ctx.getSource())))
                                 .then(Commands.literal("tploot")
                                         .executes(ctx -> debugTeleportLoot(ctx.getSource()))))
                        .then(Commands.literal("setcenter")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> setCenter(ctx.getSource())))
                        .then(Commands.literal("team")
                                .then(Commands.literal("create")
                                        .executes(ctx -> teamCreate(ctx.getSource())))
                                .then(Commands.literal("invite")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> teamInvite(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player")))))
                                .then(Commands.literal("accept")
                                        .executes(ctx -> teamAccept(ctx.getSource())))
                                .then(Commands.literal("deny")
                                        .executes(ctx -> teamDeny(ctx.getSource())))
                                .then(Commands.literal("hh")
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(ctx -> teamRecruit(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "message")))))
                                 .then(Commands.literal("join")
                                         .then(Commands.argument("teamId", StringArgumentType.word())
                                                 .executes(ctx -> teamJoin(ctx.getSource(), StringArgumentType.getString(ctx, "teamId")))))
                                 .then(Commands.literal("approve")
                                         .then(Commands.argument("player", StringArgumentType.word())
                                                 .executes(ctx -> teamApprove(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                                 .then(Commands.literal("reject")
                                         .then(Commands.argument("player", StringArgumentType.word())
                                                 .executes(ctx -> teamReject(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                                 .then(Commands.literal("kick")
                                         .then(Commands.argument("player", EntityArgument.player())
                                                 .executes(ctx -> teamKick(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                                 .then(Commands.literal("transfer")
                                         .then(Commands.argument("player", EntityArgument.player())
                                                 .executes(ctx -> teamTransfer(ctx.getSource(),
                                                         EntityArgument.getPlayer(ctx, "player")))))
                                .then(Commands.literal("leave")
                                        .executes(ctx -> teamLeave(ctx.getSource())))
                                .then(Commands.literal("list")
                                         .executes(ctx -> teamList(ctx.getSource())))))
                .then(Commands.literal("manager")
                        .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                        .executes(ctx -> managerHelp(ctx.getSource()))
                        .then(Commands.literal("gui")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> openMapEditor(ctx.getSource())))
                        .then(Commands.literal("start")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> managerStart(ctx.getSource())))
                        .then(Commands.literal("enter")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .then(Commands.argument("archiveName", StringArgumentType.word())
                                        .executes(ctx -> enterArchive(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "archiveName")))))
                        .then(Commands.literal("leave")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> leaveArchive(ctx.getSource())))
                        .then(Commands.literal("add_map")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .then(Commands.argument("mapId", StringArgumentType.word())
                                        .then(Commands.argument("displayName", StringArgumentType.greedyString())
                                                .executes(ctx -> addMap(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mapId"),
                                                        StringArgumentType.getString(ctx, "displayName"))))))
                        .then(Commands.literal("select")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .then(Commands.literal("pos1")
                                        .executes(ctx -> selectPos(ctx.getSource(), 1)))
                                .then(Commands.literal("pos2")
                                        .executes(ctx -> selectPos(ctx.getSource(), 2)))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> selectClear(ctx.getSource()))))
                        .then(Commands.literal("loot")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> lootAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("spawn")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> spawnHelp(ctx.getSource()))
                                .then(Commands.literal("set")
                                        .executes(ctx -> managerSpawnSet(ctx.getSource())))
                                .then(Commands.literal("get")
                                        .executes(ctx -> managerSpawnGet(ctx.getSource()))))
                        .then(Commands.literal("game")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> gameHelp(ctx.getSource()))
                                .then(Commands.literal("enter")
                                        .then(mapIdArgument()
                                                .executes(ctx -> gameEnter(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mapId")))))
                                .then(Commands.literal("leave")
                                        .executes(ctx -> gameLeave(ctx.getSource())))
                                .then(Commands.literal("spawn")
                                        .then(Commands.literal("set")
                                                .executes(ctx -> gameSpawnSet(ctx.getSource())))
                                        .then(Commands.literal("get")
                                                .executes(ctx -> gameSpawnGet(ctx.getSource()))))
                                .then(Commands.literal("rebuild")
                                        .then(mapIdArgument()
                                                .executes(ctx -> gameRebuild(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mapId"))))))
                        .then(Commands.literal("map")
                                .requires(src -> src.hasPermission(REQUIRED_OP_LEVEL))
                                .executes(ctx -> managerMapHelp(ctx.getSource()))
                                .then(Commands.literal("preview")
                                        .executes(ctx -> managerMapPreview(ctx.getSource(), null,
                                                MapColorGenerator.DEFAULT_BATCH_SIZE,
                                                MapColorGenerator.DEFAULT_MEMORY_LIMIT_MB))
                                        .then(mapIdArgument()
                                                .executes(ctx -> managerMapPreview(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mapId"),
                                                        MapColorGenerator.DEFAULT_BATCH_SIZE,
                                                        MapColorGenerator.DEFAULT_MEMORY_LIMIT_MB))
                                                .then(Commands.argument("batchSize", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("memoryLimit", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> managerMapPreview(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "mapId"),
                                                                        IntegerArgumentType.getInteger(ctx, "batchSize"),
                                                                        IntegerArgumentType.getInteger(ctx, "memoryLimit")))))))))
                );
    }

    // ------------------------------------------------------------------

    /**
     * /paimengmods br join（不带参数）：不再直接加入，
     * 改为列出所有已保存地图，每行带 clickEvent，点击执行 /paimengmods br join &lt;mapId&gt;。
     */
    private static int join(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        // [r144][BR-LOBBYTEAM] 绝对权限拦截：属于大厅队的非队长成员，连地图列表都不给开。
        // 选图/进图权 100% 归属队长；晚进服的队员由队长重新点一次地图带进来。
        if (isLobbyMemberWithoutLeaderRight(source, player)) {
            return FAILURE;
        }
        if (!GameManager.INSTANCE.getPhase().allowsRosterChange()) {
            source.sendFailure(BrText.of("对局已经开始或正在结算，不能加入。"));
            return FAILURE;
        }
        // [BR-MATCHTEAM 模块一] 大厅自由组队：带着大厅队一起排队完全合法，
        // 局内的补位 / 合并由 MatchTeamManager 在开局瞬间自动完成，这里不再拦截。
        Collection<GameMap> maps = MapManager.INSTANCE.listMaps();
        if (maps.isEmpty()) {
            source.sendFailure(BrText.of("没有可用的地图，请联系管理员创建"));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("[大逃杀] 点击选择地图："), false);
        for (GameMap map : maps) {
            if (map.mapId == null || !map.mapId.matches("[A-Za-z0-9_-]+")) {
                continue;
            }
            source.sendSuccess(() -> mapOptionLine(map), false);
        }
        return SUCCESS;
    }

    /** 一行可点击的地图选项：&gt;&gt; 显示名（当前等待人数/动态上限 人），点击执行 join &lt;mapId&gt;。 */
    private static MutableComponent mapOptionLine(GameMap map) {
        String displayName = map.displayName == null || map.displayName.isBlank() ? map.mapId : map.displayName;
        // [r149 DYNCAP 模块一] 上限 = maxTeams × maxTeamSize（动态派生，不再是写死的 100）。
        int max = map.getMaxPlayers();
        int waiting = Math.min(MapQueue.INSTANCE.waitingCount(map.mapId), max);
        MutableComponent line = BrText.mutable(">> " + displayName + "（" + waiting + "/" + max + " 人）")
                .withStyle(ChatFormatting.GREEN);
        line.setStyle(line.getStyle()
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/paimengmods br join " + map.mapId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        BrText.of("点击加入 " + displayName + " 的等待队列")))
                .withUnderlined(Boolean.TRUE));
        return line;
    }

    /**
     * [r144][BR-LOBBYTEAM] 大厅队加入地图的绝对权限判定（join 列地图 与 join &lt;mapId&gt; 选图 共用）：
     * 玩家属于某个大厅队伍（DraftGroup）且不是队长 → 红字提示并返回 true（拦截）；
     * 散人（无大厅队伍）返回 false，行为完全不受影响。
     */
    private static boolean isLobbyMemberWithoutLeaderRight(CommandSourceStack source, ServerPlayer player) {
        Team team = TeamManager.INSTANCE.teamOf(player.getUUID());
        if (team != null && !team.isLeader(player.getUUID())) {
            source.sendFailure(BrText.of("只有队长才能选择地图加入，请等待队长选择地图！"));
            return true;
        }
        return false;
    }

    /** [r143][BR-LOBBYTEAM] 加入地图指令（br join <mapId> 分支）：队长权限 + 全队批量进入。 */
    private static int joinMap(CommandSourceStack source, String mapId) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        // [r144][BR-LOBBYTEAM] 绝对权限拦截（方法最开头，先于一切状态判断）：
        // 大厅队里非队长成员敲 join / 点地图选项，一律红字拦截——
        // 上一版「队长已在该图等待时允许成员跟随进入」的后门已彻底移除。
        // 晚进服的队员：队长重新点击一次地图，走下面的全队遍历即可把新人带进来。
        if (isLobbyMemberWithoutLeaderRight(source, player)) {
            return FAILURE;
        }
        GameManager game = GameManager.INSTANCE;
        if (game.getPhase() == GamePhase.RESETTING) {
            source.sendFailure(BrText.of("服务器正在重置地图，请稍后。"));
            return FAILURE;
        }
        if (!game.getPhase().allowsRosterChange()) {
            source.sendFailure(BrText.of("对局已经开始或正在结算，不能加入。"));
            return FAILURE;
        }
        GameMap map = MapManager.INSTANCE.getMap(mapId);
        if (map == null) {
            source.sendFailure(BrText.of("地图不存在"));
            return FAILURE;
        }
        String displayName = map.displayName == null || map.displayName.isBlank() ? map.mapId : map.displayName;
        Team team = TeamManager.INSTANCE.teamOf(player.getUUID());
        if (team == null) {
            // —— 无大厅队的散人：单人进入（原有行为不变）——
            String error = enterWaiting(player, map);
            if (error != null) {
                source.sendFailure(BrText.of(error));
                return FAILURE;
            }
            source.sendSuccess(() -> BrText.of("已加入 " + displayName + "，正在传送到出生岛；等待期间处于无敌状态，可自由行动"), false);
            return SUCCESS;
        }
        // —— 走到这里必然是队长：带全队进图，按加入顺序遍历全部在线成员批量进场 ——
        // （成员后上线/漏带的场景：队长再点一次同一地图，enterWaiting 对已在等待区的
        // 成员幂等跳过传送，只对齐状态，晚进服的人会被这轮遍历拉进来。）
        // [r149 DYNCAP 模块一] 整队容量预检：当前等待人数 + 即将新增占位人数 > 动态上限
        // （maxTeams × maxTeamSize）时整队拒绝进入并提示换图——已在该图等位的成员不重复计数，
        // 队长重跑带队不会被自己家的老名额挡路。
        int incoming = 0;
        for (Team.Entry entry : team.membersInJoinOrder()) {
            ServerPlayer member = source.getServer().getPlayerList().getPlayer(entry.playerId());
            if (member != null && !map.mapId.equals(MapQueue.INSTANCE.waitingMapOf(member.getUUID()))) {
                incoming++;
            }
        }
        String capacityError = MapQueue.INSTANCE.capacityError(map, incoming);
        if (capacityError != null) {
            source.sendFailure(BrText.of(capacityError));
            return FAILURE;
        }
        String leaderName = player.getGameProfile().getName();
        int entered = 0;
        List<String> offline = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Team.Entry entry : team.membersInJoinOrder()) {
            ServerPlayer member = source.getServer().getPlayerList().getPlayer(entry.playerId());
            if (member == null) {
                offline.add(entry.name()); // 离线成员跳过：上线后大厅队还在，跟队长再进即可
                continue;
            }
            String error = enterWaiting(member, map);
            if (error != null) {
                failed.add(entry.name() + "（" + error + "）");
                member.displayClientMessage(BrText.of("随队长 " + leaderName + " 进图失败：" + error), false);
                continue;
            }
            entered++;
            if (!member.getUUID().equals(player.getUUID())) {
                member.displayClientMessage(BrText.of("队长 " + leaderName + " 已选择 " + displayName
                        + "，全队已带入出生岛等待；等待期间处于无敌状态，可自由行动"), false);
            }
        }
        // 状态同步：全员进图后立刻向全队补发大厅队伍同步包（TeamSync 大厅快照），
        // 左上角 HUD 立即显示队长与全部成员——哪怕他们刚从别的维度被拽过来。
        TeamSync.syncTeam(team, source.getServer());
        final int enteredFinal = entered;
        source.sendSuccess(() -> BrText.of("[大逃杀] 队长带队进入 " + displayName + " 出生岛等待："
                + enteredFinal + " 人已就位（含你）"
                + (offline.isEmpty() ? "" : "；离线未带入：" + String.join("、", offline))
                + (failed.isEmpty() ? "" : "；失败：" + String.join("、", failed))), false);
        return SUCCESS;
    }

    /**
     * [r143] 单人「进入该地图等待区」的完整流程（散人 / 队长带队两种路径共用同一套逻辑，
     * 绝不分叉；[r144] 成员跟随后门已移除）：
     * 写地图等待队列 → 进全局对局名单 → 传送出生岛（维度未生成时后台排队，
     * 就绪自动传送）→ 登记无敌与等待状态 → 一次性推送该图物资点列表。
     *
     * <p>已在该图等待（重复执行 / 队长带队重跑）视为幂等：不重复占队列，
     * 后续步骤照常重新对齐。传送失败则整体回滚（退队列 + 退名单），
     * 返回错误文案；成功返回 null。
     */
    private static String enterWaiting(ServerPlayer player, GameMap map) {
        UUID playerId = player.getUUID();
        String name = player.getGameProfile().getName();
        GameManager game = GameManager.INSTANCE;
        String queueError = MapQueue.INSTANCE.join(playerId, map.mapId);
        if (queueError != null && !"你已在该地图等待中".equals(queueError)) {
            return queueError;
        }
        // [r144] 幂等：已在该图等待区站稳（队列 + 热身无敌登记都在）的玩家
        // （队长带队重跑/晚进服补带的场景）不重复传送，只补发一次物资点同步对齐状态。
        if (queueError != null && WarmupManager.INSTANCE.isWaiting(playerId)) {
            LootPointsSync.sendTo(player, map);
            return null;
        }
        game.join(name);
        String teleportError = GameWorldManager.enterAsWaitingPlayer(player, map.mapId);
        if (teleportError != null) {
            MapQueue.INSTANCE.leave(playerId);
            game.leave(name);
            return "传送到出生岛失败：" + teleportError;
        }
        // 等待状态：可自由行动但无敌（伤害在 mixin.LivingEntityMixin 里按无敌标记拒绝）。
        WarmupManager.INSTANCE.join(player);
        LootPointsSync.sendTo(player, map);
        return null;
    }

    /**
     * /paimengmods br leave：按状态机当前阶段（GameManager.getPhase()）分流退出。
     *
     * <p>赛前等待（WAITING，热身的 300 秒倒计时也在这一阶段里跑；含结算期 ENDED 的兜底清理）：
     * 正常退出——退出地图等待队列与对局名单、清掉无敌/热身临时数据、取消还在排队的进场传送、
     * 回主世界大厅，并向本人提示「你已退出比赛等待。」。等待期的 300 秒倒计时如果因这次退出
     * 人数不足 minPlayersToStart，由 WarmupManager.leave(player) 当场取消并复位（强制倒计时除外）。
     *
     * <p>[r-LEAVE] 赛中进行（PREPARING / FLIGHT 航线 / RUNNING 毒圈与跳伞）按存活状态分两条路：
     * <ol>
     *   <li><b>存活（ALIVE）/ 倒地（DOWNED）主动退出 = 判定阵亡</b>：绝对不能直接传送——
     *       先把与被击杀完全同一套淘汰结算走完
     *       （{@link DownedManager#eliminateForDesert}：原地骨灰盒、击倒/淘汰播报、
     *       全队覆灭播报与排名结算、Buff 全清、收降落伞、存活与击杀结算；
     *       击杀者取最近玩家伤害来源，否则记「环境/主动退出」），
     *       结算全部完成后才传送回大厅并恢复原游戏模式，
     *       提示「你已主动退出对局，判定为阵亡。」；</li>
     *   <li><b>已淘汰（ELIMINATED）旁观中途退出</b>：直接传送回大厅、恢复原模式，
     *       提示「你已退出本局观战，正在返回大厅。」——绝不重复死亡结算与骨灰盒。</li>
     * </ol>
     * 两条路最后都做同一套数据清理：对局名单、等待队列、击倒/HUD 残留、待传送排队；
     * 队友坐标包同步会跳过不在游戏维度的成员（见 TeammatePosSync），杜绝小地图幽灵队友。
     */
    private static int leave(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        UUID playerId = player.getUUID();
        String name = player.getGameProfile().getName();
        GamePhase phase = GameManager.INSTANCE.getPhase();
        boolean queued = MapQueue.INSTANCE.waitingMapOf(playerId) != null;
        boolean waiting = WarmupManager.INSTANCE.isWaiting(playerId);
        boolean joined = GameManager.INSTANCE.isJoined(name);
        boolean inMatch = phase == GamePhase.PREPARING
                || phase == GamePhase.FLIGHT
                || phase == GamePhase.RUNNING;
        if (!queued && !waiting && !joined) {
            source.sendFailure(BrText.of("你不在当前大逃杀的名单里。"));
            return FAILURE;
        }
        // [r-LEAVE] 旁观判定权威 = 本局淘汰名册（DownedManager 的瞬时标记在淘汰收尾
        // 会被清掉，旁观者此刻已不持有它；名册随本局走，disband 才清空）。
        boolean alreadyEliminated = MatchTeamManager.INSTANCE.wasEliminatedThisMatch(playerId);
        String resultText;
        if (inMatch && joined) {
            if (alreadyEliminated) {
                // —— 二、观战中途退出：只传送，不再碰任何死亡结算 / 骨灰盒 ——
                resultText = "你已退出本局观战，正在返回大厅。";
            } else {
                // —— 一、存活 / 倒地主动退出：先走完一整套"阵亡"结算，再传送 ——
                DownedManager.INSTANCE.eliminateForDesert(player, true);
                resultText = "你已主动退出对局，判定为阵亡。";
            }
            // 退出对局名单（存活人数与重连判定的权威依据）；大厅队伍编制保留。
            GameManager.INSTANCE.leave(name);
        } else {
            // —— 赛前等待：正常退出 ——
            // [BR-MATCHTEAM] 只退出排队与名单，大厅队伍原样保留（换图再排队不用重新组队）。
            GameManager.INSTANCE.leave(name);
            resultText = "你已退出比赛等待。";
        }
        MapQueue.INSTANCE.leave(playerId);
        // 无敌标记与热身名单一起清除；顺带把击倒/淘汰残留也立清，避免带着 HUD 回大厅。
        // leave(player) 内部会做人数检查：退出后等待人数不足 minPlayersToStart 时立刻取消倒计时。
        WarmupManager.INSTANCE.leave(player);
        DownedManager.INSTANCE.clearPlayer(player);
        // 取消仍在排队的"维度就绪后自动传送"，防止回大厅后又被拽回游戏维度。
        GameWorldManager.cancelPendingEnter(playerId);
        String teleportError = GameWorldManager.leave(player);
        // leave 返回「当前不在游戏维度里」不算失败：等待玩家可能还站在主世界等维度生成。
        if (teleportError != null && !teleportError.contains("不在游戏维度")) {
            source.sendFailure(BrText.of("传送回大厅失败：" + teleportError));
        }
        final String successText = resultText;
        source.sendSuccess(() -> BrText.of(successText), false);
        return SUCCESS;
    }

    /** /paimengmods br loot_tool：发放旧的区域地标工具（写 lootPoints，用于地图显示/航线）。 */
    private static int lootTool(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        ItemStack stack = new ItemStack(ModItems.LOOT_TOOL);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        source.sendSuccess(() -> BrText.of("已获得物资点工具（区域地标）：右键添加，左键移除"), false);
        return SUCCESS;
    }

    /** /paimengmods br chest_tool：发放物资箱生成工具（写 manualLootSpawns，开局强制出箱子）。 */
    private static int chestTool(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        ItemStack stack = new ItemStack(ModItems.CHEST_SPAWN_TOOL);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        source.sendSuccess(() -> BrText.of("已获得物资箱生成工具：右键记录箱子坐标，左键移除"), false);
        return SUCCESS;
    }

    /** /paimengmods br debug loot：在准星方块上方或脚下强制生成普通物资箱。 */
    private static int debugLoot(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        net.minecraft.world.phys.HitResult result = player.pick(6.0D, 1.0F, false);
        net.minecraft.core.BlockPos pos = result instanceof net.minecraft.world.phys.BlockHitResult
                ? ((net.minecraft.world.phys.BlockHitResult) result).getBlockPos().above()
                : player.blockPosition();
        if (LootSpawner.INSTANCE.forceSpawnDebug(player, pos)) {
            source.sendSuccess(() -> BrText.of("已在 " + pos.toShortString() + " 强制生成并填充物资箱"), false);
            return SUCCESS;
        }
        source.sendFailure(BrText.of("调试物资箱生成失败：请确认你在游戏维度，目标位置是空气，且地图已加载"));
        return FAILURE;
    }

    /** /paimengmods br debug tploot：显示最近 5 个箱子，并传送到最近箱子上方。 */
    private static int debugTeleportLoot(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        List<BlockPos> nearest = LootSpawner.INSTANCE.latestPlacedChests(player.level().dimension(), 5);
        if (nearest.isEmpty()) {
            source.sendFailure(BrText.of("当前维度没有已记录的物资箱。"));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("最近的物资箱坐标："), false);
        for (int i = 0; i < nearest.size(); i++) {
            BlockPos pos = nearest.get(i);
            final int index = i + 1;
            source.sendSuccess(() -> BrText.of("#" + index + " " + pos.toShortString()), false);
        }
        BlockPos target = nearest.get(0);
        player.teleportTo((ServerLevel) player.level(), target.getX() + 0.5D,
                target.getY() + 2.0D, target.getZ() + 0.5D, player.getYRot(), player.getXRot());
        source.sendSuccess(() -> BrText.of("已传送到第一个物资箱上方。"), false);
        return SUCCESS;
    }

    private static int stop(CommandSourceStack source) {
        if (!GameManager.INSTANCE.requestStop(source.getServer())) {
            source.sendFailure(BrText.of("当前没有进行中的大逃杀对局。"));
            return FAILURE;
        }
        MapQueue.INSTANCE.clearAll();
        source.sendSuccess(() -> BrText.of("已强制结束本局大逃杀。"), false);
        return SUCCESS;
    }

    /**
     * /paimengmods br setcenter：把毒圈圆心设为执行者当前所在位置（X/Z 平面）。
     *
     * <p>[BR-ZONE] r109 起毒圈按维度独立：
     * <ol>
     *   <li>执行者所在维度正在跑圈 → 立刻挪那一圈（半径不变，超界自动夹回）；</li>
     *   <li>同时把圆心写进该地图的 JSON（zoneCenterSet/zoneCenterX/Z），
     *       下一局开圈直接以这里为初始蓝圈中心。</li>
     * </ol>
     */
    private static int setCenter(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
                player.level().dimension();
        double x = player.getX();
        double z = player.getZ();
        StringBuilder hint = new StringBuilder();

        // 1) 正在跑的圈（按维度路由）。
        if (ZoneManager.INSTANCE.setCenterAt(key, x, z)) {
            hint.append("（对局进行中，本维度蓝圈圆心已即时移动）");
        }

        // 2) 写进地图配置：只有游戏维度能反查到地图。
        GameMap real = GameWorldManager.mapForWorld(key);
        if (real != null) {
            real.zoneCenterX = x;
            real.zoneCenterZ = z;
            real.zoneCenterSet = true;
            MapManager.INSTANCE.saveMap(real);
            hint.append("（已写入地图 ").append(real.mapId)
                    .append(" 配置，下一局初始蓝圈以此为中心）");
        } else if (hint.length() == 0) {
            source.sendFailure(BrText.of("请在游戏维度里执行：主城 / 编辑副本没有可绑定的毒圈。"));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of(String.format("毒圈圆心已设为 (%.1f, %.1f)%s",
                x, z, hint)), false);
        return SUCCESS;
    }

    /** 取执行者玩家；控制台执行时回错误并返回 null。 */
    private static ServerPlayer asPlayer(CommandSourceStack source) {
        ServerPlayer executor0 = source.getPlayer();
        if (executor0 == null) {
            source.sendFailure(BrText.of("这条指令只能由玩家执行。"));
        }
        return executor0;
    }

    private static int openMapEditor(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) return FAILURE;
        GameMap map = resolveEditorMap(player).copy();
        if (map.mapId == null || map.mapId.isBlank()) map.mapId = "new_map";
        if (map.displayName == null || map.displayName.isBlank()) map.displayName = "新地图";
        if (map.lootPoints == null) map.lootPoints = new java.util.ArrayList<>();
        MapEditorSync.sendOpen(player, map);
        
         MapEditorSync.sendArchives(player, MapManager.INSTANCE.scanImports());
        
        source.sendSuccess(() -> BrText.of("正在打开地图编辑器"), false);
        return SUCCESS;
    }

    /**
     * 解析「此刻该给这名玩家看哪张地图」，并把它写回该玩家的编辑草稿（DRAFTS）：
     * <ul>
     *   <li>玩家在游戏维度里 → 按维度键剥前缀解析 mapId，取 MapManager 里的真实对象
     *       （上一版这里装载的是空白草稿，指令明明写进了真实地图，GUI 却显示 0 个物资点）；</li>
     *   <li>玩家在编辑副本里 → 走原有 prepareDraftForArchive 流程；</li>
     *   <li>其他维度 → 保留现有草稿；草稿带着 mapId 且服务端有这张图时，
     *       用真实对象刷新草稿，避免 GUI 拿旧空壳覆盖真数据。</li>
     * </ul>
     */
    public static GameMap resolveEditorMap(ServerPlayer player) {
        if (GameWorldManager.isGameWorld(player.level().dimension())) {
            GameMap real = GameWorldManager.mapForWorld(player.level().dimension());
            if (real != null) {
                GameMap copy = real.copy();
                MapEditSession.DRAFTS.put(player.getUUID(), copy.copy());
                BattleRoyaleMod.LOGGER.info("[Editor] 游戏维度装载真实地图 {}：物资点 {} 个",
                        real.mapId, real.lootPoints == null ? 0 : real.lootPoints.size());
                return copy;
            }
        }
        String archivePath = MapEditSession.selectionOf(player.getUUID()).archivePath;
        GameMap draft = MapEditSession.prepareDraftForArchive(player.getUUID(), archivePath);
        if (draft.mapId != null && !draft.mapId.isBlank() && !"default".equals(draft.mapId)
                && (archivePath == null || archivePath.isBlank())) {
            GameMap real = MapManager.INSTANCE.getMap(draft.mapId);
            if (real != null) {
                GameMap copy = real.copy();
                MapEditSession.DRAFTS.put(player.getUUID(), copy.copy());
                BattleRoyaleMod.LOGGER.info("[Editor] 按草稿 mapId={} 装载真实地图：物资点 {} 个",
                        real.mapId, real.lootPoints == null ? 0 : real.lootPoints.size());
                return copy;
            }
        }
        return draft;
    }

    private static int scanMapImports(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) return FAILURE;
        var entries = MapManager.INSTANCE.scanImports();
        MapEditorSync.sendArchives(player, entries);
        source.sendSuccess(() -> BrText.of("已扫描导入目录，可用存档：" + entries.size()), false);
        return SUCCESS;
    }

    private static int enterArchive(CommandSourceStack source, String archiveName) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = EditWorldManager.enter(player, archiveName);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("已进入 " + archiveName + " 编辑模式，用选区工具圈定区域"), false);
        return SUCCESS;
    }

    private static int leaveArchive(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = EditWorldManager.leave(player);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("已离开编辑副本，区块已卸载"), false);
        return SUCCESS;
    }

    private static int addMap(CommandSourceStack source, String mapId, String displayName) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        if (mapId == null || !mapId.matches("[A-Za-z0-9_-]+")) {
            source.sendFailure(BrText.of("mapId 只能包含字母、数字、下划线和短横线"));
            return FAILURE;
        }
        SelectionData selection = MapEditSession.selectionOf(player.getUUID());
        if (!selection.isComplete()) {
            source.sendFailure(BrText.of("请先用选区工具圈定区域"));
            return FAILURE;
        }
        if (selection.spanX() > SelectionToolItem.MAX_SPAN || selection.spanZ() > SelectionToolItem.MAX_SPAN) {
            source.sendFailure(BrText.of("选区跨度不能超过 " + SelectionToolItem.MAX_SPAN + " 格"));
            return FAILURE;
        }
        // 同 ID 已存在时：在真实内存对象上原地更新选区，绝不新建空壳替换
        //（旧写法 createMap 会把这张图已有的物资点直接丢掉）。
        GameMap existing = MapManager.INSTANCE.getMap(mapId);
        GameMap map;
        if (existing != null) {
            map = existing;
            map.minX = Math.min(selection.minX(), selection.maxX());
            map.maxX = Math.max(selection.minX(), selection.maxX());
            map.minZ = Math.min(selection.minZ(), selection.maxZ());
            map.maxZ = Math.max(selection.minZ(), selection.maxZ());
            map.displayName = displayName;
        } else {
            map = MapManager.INSTANCE.createMap(mapId, displayName,
                    selection.minX(), selection.minZ(), selection.maxX(), selection.maxZ());
        }
        GameMap draft = MapEditSession.draftOf(player.getUUID());
        if (draft.lootPoints != null && !draft.lootPoints.isEmpty()) {
            map.lootPoints = new java.util.ArrayList<>(draft.lootPoints);
        } else if (map.lootPoints == null) {
            map.lootPoints = new java.util.ArrayList<>();
        }
        if (draft.manualLootSpawns != null && !draft.manualLootSpawns.isEmpty()) {
            map.manualLootSpawns = new java.util.ArrayList<>();
            for (com.paimeng.battleroyale.map.ManualLootSpawn spawn : draft.manualLootSpawns) {
                if (spawn != null) {
                    map.manualLootSpawns.add(new com.paimeng.battleroyale.map.ManualLootSpawn(spawn.x, spawn.y, spawn.z));
                }
            }
        } else if (map.manualLootSpawns == null) {
            map.manualLootSpawns = new java.util.ArrayList<>();
        }
        if (selection.archivePath != null && !selection.archivePath.isBlank()) {
            map.sourceArchivePath = selection.archivePath;
        }
        MapManager.INSTANCE.saveMap(map);
        source.sendSuccess(() -> BrText.of((existing != null ? "地图 " + mapId + " 选区已更新，保留原有 "
                + (map.lootPoints == null ? 0 : map.lootPoints.size()) + " 个物资点" : "地图 " + mapId + " 创建成功")), false);
        return SUCCESS;
    }

    // ------------------------------------------------------------------
    // /paimengmods manager game enter | leave | spawn set | spawn get | rebuild
    // 全部继承 manager 的 OP2 权限，挂在 manager game 子组下
    // ------------------------------------------------------------------

    /** 带地图 ID 联想的 mapId 参数（输入 enter / rebuild 后按 Tab 直接选地图）。 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> mapIdArgument() {
        return Commands.argument("mapId", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (GameMap map : MapManager.INSTANCE.listMaps()) {
                        if (map.mapId != null && map.mapId.matches("[A-Za-z0-9_-]+")) {
                            builder.suggest(map.mapId);
                        }
                    }
                    return builder.buildFuture();
                });
    }

    /**
     * /paimengmods manager start：管理员强制开始热身倒计时（无视人数限制）。
     * [BR-SKIP-COUNTDOWN] 倒计时已在跑且仍处于 WAITING 阶段时再次执行，
     * 直接跳过剩余倒计时、立刻结束热身进入航线阶段（生成飞机并拉人上机）。
     */
    private static int managerStart(CommandSourceStack source) {
        WarmupManager warmup = WarmupManager.INSTANCE;
        if (warmup.isCountdownStarted()) {
            // [BR-MATCHTEAM] 倒计时现在在 PREPARING 阶段跑：再次执行 = 跳过剩余倒计时直接上飞机。
            if (GameManager.INSTANCE.getPhase() == GamePhase.PREPARING
                    && warmup.skipCountdown(source.getServer())) {
                source.sendSuccess(() -> BrText.of("检测到倒计时进行中：已强制跳过剩余倒计时，所有人立即进入航线阶段。"), false);
                return SUCCESS;
            }
            source.sendFailure(BrText.of("倒计时已经在进行中，不能重复开始。"));
            return FAILURE;
        }
        if (warmup.waitingCount() == 0) {
            source.sendFailure(BrText.of("当前没有等待中的玩家，无法开始倒计时。"));
            return FAILURE;
        }
        // [BR-MATCHTEAM 模块一] 管理员强制开局：无视独立队伍数门槛，直接 WAITING → PREPARING
        // （事件链同步完成 MatchTeam 分配 + 进场传送 + 起准备倒计时）。
        if (!warmup.forceStart(source.getServer())) {
            source.sendFailure(BrText.of("强制开始失败，状态已变化，请重试。"));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("已强制开局：临时队伍分配完成，" + WarmupManager.COUNTDOWN_SECONDS
                + " 秒准备倒计时开始（无视队伍数门槛），玩家仍处于无敌状态；强制期间有人退出不打断。"), false);
        return SUCCESS;
    }

    /** /paimengmods manager：直接执行时列出子命令，避免只报「未知或不完整的命令」。 */
    private static int managerHelp(CommandSourceStack source) {
        source.sendSuccess(() -> BrText.of("【地图管理】子命令："), false);
        source.sendSuccess(() -> BrText.of("  gui —— 打开地图编辑器"), false);
        source.sendSuccess(() -> BrText.of("  start —— 强制开始热身倒计时；倒计时中再次执行则立即跳过剩余倒计时进入航线"), false);
        source.sendSuccess(() -> BrText.of("  enter <存档名> / leave —— 进入 / 离开编辑副本"), false);
        source.sendSuccess(() -> BrText.of("  add_map <mapId> <显示名> —— 把当前选区保存为地图"), false);
        source.sendSuccess(() -> BrText.of("  select pos1 / pos2 / clear —— 圈选区（编辑副本=地图范围，游戏维度=出生岛范围）"), false);
        source.sendSuccess(() -> BrText.of("  loot add <名字> —— 在当前位置添加物资点（编辑副本或游戏维度）"), false);
        source.sendSuccess(() -> BrText.of("  spawn set / get —— 保存 / 查看出生岛区域（游戏维度）"), false);
        source.sendSuccess(() -> BrText.of("  game —— 游戏维度（enter / leave / spawn / rebuild）"), false);
        source.sendSuccess(() -> BrText.of("  map preview [mapId] [batchSize] [memoryLimit] —— 服务端后台预生成小地图底图（只存内存）"), false);
        return SUCCESS;
    }

    /** /paimengmods manager map：直接执行时列出子命令。 */
    private static int managerMapHelp(CommandSourceStack source) {
        source.sendSuccess(() -> BrText.of("【底图】子命令："), false);
        source.sendSuccess(() -> BrText.of("  preview [mapId] [batchSize] [memoryLimit] —— 异步生成底图并显示 ActionBar 进度"), false);
        source.sendSuccess(() -> BrText.of("（在游戏维度里可省略 mapId，自动取当前维度对应的地图）"), false);
        return SUCCESS;
    }

    /**
     * /paimengmods manager map preview [mapId] [batchSize] [memoryLimit]：提交异步底图生成任务。
     * 内存里的区块直接采样，未加载的从游戏维度 region 磁盘回读（不加载区块、不发网络包）；
     * 结果存进 GameMap.baseImage（内存），执行玩家会收到 ActionBar 进度。
     */
    private static int managerMapPreview(CommandSourceStack source, String mapId, int batchSize, int memoryLimitMb) {
        String resolvedId = mapId;
        if (resolvedId == null || resolvedId.isBlank()) {
            resolvedId = GameWorldManager.resolveMapId(source.getLevel().dimension());
            if (resolvedId == null) {
                source.sendFailure(BrText.of("请带上地图 ID：/paimengmods manager map preview <mapId>"
                        + "（站在游戏维度里可以直接省略）"));
                return FAILURE;
            }
        }
        GameMap map = MapManager.INSTANCE.getMap(resolvedId);
        if (map == null) {
            source.sendFailure(BrText.of("找不到地图 " + resolvedId));
            return FAILURE;
        }
        ServerPlayer viewer = asPlayer(source);
        String error = MapColorGenerator.generateMapImage(map,
                viewer == null ? null : viewer.getUUID(), batchSize, memoryLimitMb);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("底图生成已在后台线程开始：选区 X" + map.minX + "~" + map.maxX
                + "、Z" + map.minZ + "~" + map.maxZ + "（每 " + batchSize + " 区块检查一次内存，上限 "
                + memoryLimitMb + "MB；未加载区块从磁盘回读），进度看屏幕上方的 ActionBar"), false);
        return SUCCESS;
    }

    /** /paimengmods manager game：直接执行时列出子命令 + 可用地图，方便确认 game 分支已注册。 */
    private static int gameHelp(CommandSourceStack source) {
        source.sendSuccess(() -> BrText.of("【游戏维度】子命令："), false);
        source.sendSuccess(() -> BrText.of("  enter <mapId> —— 进入该地图的游戏维度（不存在则生成）"), false);
        source.sendSuccess(() -> BrText.of("  leave —— 回到进入游戏维度前的位置"), false);
        source.sendSuccess(() -> BrText.of("  spawn set / spawn get —— 设置 / 查看出生点"), false);
        source.sendSuccess(() -> BrText.of("  rebuild <mapId> —— 删除旧维度数据并重新复制区块"), false);
        Collection<GameMap> maps = MapManager.INSTANCE.listMaps();
        if (maps.isEmpty()) {
            source.sendSuccess(() -> BrText.of("当前没有任何已保存地图。"), false);
        } else {
            StringBuilder sb = new StringBuilder("可用地图：");
            for (GameMap map : maps) {
                if (map.mapId != null && map.mapId.matches("[A-Za-z0-9_-]+")) {
                    sb.append(map.mapId).append("  ");
                }
            }
            source.sendSuccess(() -> BrText.of(sb.toString()), false);
        }
        return SUCCESS;
    }

    /** /paimengmods manager game enter <mapId>：管理员进入游戏维度（未创建则后台生成，就绪后自动传送）。 */
    private static int gameEnter(CommandSourceStack source, String mapId) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = GameWorldManager.enter(player, mapId);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("正在进入游戏维度 " + mapId
                + "（首次进入会在后台复制区块，不卡游戏；边界由服务端每 tick 软约束，超出会被静默推回）"), false);
        return SUCCESS;
    }

    /** /paimengmods manager game rebuild <mapId>：后台删旧数据、重新复制区块，完成后自动进入。 */
    private static int gameRebuild(CommandSourceStack source, String mapId) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = GameWorldManager.rebuild(player, mapId);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("游戏维度 " + mapId
                + " 重建已在后台开始（删除旧数据 + 重新复制区块），完成后会自动传送你进入"), false);
        return SUCCESS;
    }

    /** /paimengmods manager game leave：返回进入游戏维度前的世界。 */
    private static int gameLeave(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = GameWorldManager.leave(player);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        source.sendSuccess(() -> BrText.of("已离开游戏维度"), false);
        return SUCCESS;
    }

    /** /paimengmods manager game spawn set：在游戏维度里把当前位置设为出生点并写回 JSON。 */
    private static int gameSpawnSet(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        String error = GameWorldManager.setSpawnHere(player);
        if (error != null) {
            source.sendFailure(BrText.of(error));
            return FAILURE;
        }
        GameMap map = GameWorldManager.mapForWorld(player.level().dimension());
        source.sendSuccess(() -> BrText.of(String.format("出生点已设为 (%.1f, %.1f, %.1f)，已写入地图 %s",
                map == null ? 0 : map.spawnX, map == null ? 0 : map.spawnY, map == null ? 0 : map.spawnZ,
                map == null ? "?" : map.mapId)), false);
        return SUCCESS;
    }

    /**
     * /paimengmods manager select pos1 | pos2：编辑副本和游戏维度都能执行。
     *
     * <p>编辑副本里圈的是地图区域（选区工具同一份 {@link SelectionData}）；
     * 游戏维度里圈的是出生岛区域（{@link MapEditSession#SPAWN_SELECTIONS}，
     * 与 spawn_tool 的角点共用一份数据，圈完两点再执行 {@code manager spawn set} 落盘）。
     * 底层圈点逻辑完全复用，不改选区工具本身。
     */
    private static int selectPos(CommandSourceStack source, int which) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        boolean editWorld = EditWorldManager.isEditWorld(player.level().dimension());
        if (!editWorld && !GameWorldManager.isGameWorld(player.level().dimension())) {
            source.sendFailure(BrText.of("请先用 /paimengmods manager enter <存档名> 进入编辑副本"
                    + "，或用 /paimengmods manager game enter <mapId> 进入游戏维度"));
            return FAILURE;
        }
        // 坐标取玩家当前所在方块（Y 也带上，方便核对脚下高度）。
        int x = player.blockPosition().getX();
        int y = player.blockPosition().getY();
        int z = player.blockPosition().getZ();
        SelectionData data = editWorld
                ? MapEditSession.selectionOf(player.getUUID())
                : MapEditSession.spawnSelectionOf(player.getUUID());
        if (which == 1) {
            data.x1 = x;
            data.z1 = z;
            source.sendSuccess(() -> BrText.of("第一选取点已设置为：(" + x + ", " + y + ", " + z + ")"), false);
        } else {
            data.x2 = x;
            data.z2 = z;
            source.sendSuccess(() -> BrText.of("第二选取点已设置为：(" + x + ", " + y + ", " + z + ")"), false);
        }
        if (editWorld) {
            if (data.isComplete()) {
                if (data.spanX() > SelectionToolItem.MAX_SPAN || data.spanZ() > SelectionToolItem.MAX_SPAN) {
                    source.sendSuccess(() -> BrText.of("选区跨度不能超过 " + SelectionToolItem.MAX_SPAN + " 格，请重新圈选"), false);
                } else {
                    source.sendSuccess(() -> BrText.of("当前选区：X " + data.minX() + "~" + data.maxX()
                            + "，Z " + data.minZ() + "~" + data.maxZ()), false);
                }
            }
        } else {
            // 游戏维度：这两点是出生岛角点，圈齐后用 manager spawn set 落盘。
            if (data.isComplete()) {
                source.sendSuccess(() -> BrText.of("出生岛角点已圈齐：X " + data.minX() + "~" + data.maxX()
                        + "，Z " + data.minZ() + "~" + data.maxZ()
                        + "，执行 /paimengmods manager spawn set 保存"), false);
            } else {
                source.sendSuccess(() -> BrText.of("（游戏维度：这两点是出生岛范围，再点另一个角后执行"
                        + " /paimengmods manager spawn set）"), false);
            }
        }
        return SUCCESS;
    }

    /** /paimengmods manager select clear：编辑副本清地图选区，游戏维度清出生岛角点。 */
    private static int selectClear(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        if (EditWorldManager.isEditWorld(player.level().dimension())) {
            SelectionData data = MapEditSession.selectionOf(player.getUUID());
            data.x1 = data.z1 = data.x2 = data.z2 = null;
            source.sendSuccess(() -> BrText.of("已清空当前选区"), false);
            return SUCCESS;
        }
        if (GameWorldManager.isGameWorld(player.level().dimension())) {
            SelectionData data = MapEditSession.spawnSelectionOf(player.getUUID());
            data.x1 = data.z1 = data.x2 = data.z2 = null;
            source.sendSuccess(() -> BrText.of("已清空出生岛角点"), false);
            return SUCCESS;
        }
        source.sendFailure(BrText.of("请在编辑副本或游戏维度中使用该指令"));
        return FAILURE;
    }

    /**
     * /paimengmods manager loot add &lt;名字&gt;：允许在编辑维度和游戏维度使用。
     *
     * <p>游戏维度分支严格按数据链路走：
     * 维度键剥离前缀 → mapId → {@code MapManager.INSTANCE.getMap(mapId)} 拿真实内存对象
     * → 加进 {@code map.lootPoints} → {@code saveMap} 落盘 → 回推 GUI 数据包，
     * 保证指令执行完马上按快捷键打开 GUI 就能看到新物资点。
     * 每一步都打 [LootCmd] 日志，方便核对是哪一环断了。
     */
    private static int lootAdd(CommandSourceStack source, String name) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        if (name == null || name.isBlank()) {
            source.sendFailure(BrText.of("物资点名字不能为空"));
            return FAILURE;
        }
        GameMap map;
        boolean draft;
        if (EditWorldManager.isEditWorld(player.level().dimension())) {
            map = MapEditSession.draftOf(player.getUUID());
            draft = true;
        } else if (GameWorldManager.isGameWorld(player.level().dimension())) {
            // 1) 解析 mapId：从当前维度注册键里剥离 battleroyale_game: 前缀。
            String dimKey = player.level().dimension().location().toString();
            String rawId = dimKey.startsWith(GameWorldManager.DIMENSION_NAMESPACE + ":")
                    ? dimKey.substring(GameWorldManager.DIMENSION_NAMESPACE.length() + 1)
                    : dimKey;
            BattleRoyaleMod.LOGGER.info("[LootCmd] 解析到 mapId: {}", rawId);
            // 2) 维度路径是 safeId 小写形式，先松散匹配回地图表里记录的真实 mapId，再取对象。
            String canonicalId = GameWorldManager.resolveMapId(player.level().dimension());
            map = canonicalId == null ? null : MapManager.INSTANCE.getMap(canonicalId);
            if (map == null) {
                BattleRoyaleMod.LOGGER.error("[LootCmd] getMap({}) 返回 null：维度路径 {} 在地图表里找不到对应 mapId",
                        canonicalId, rawId);
                source.sendFailure(BrText.of("当前游戏维度没有关联的地图配置（mapId=" + rawId + "）"));
                return FAILURE;
            }
            BattleRoyaleMod.LOGGER.info("[LootCmd] 获取到真实 GameMap，原物资点数量：{}",
                    map.lootPoints == null ? 0 : map.lootPoints.size());
            draft = false;
        } else {
            source.sendFailure(BrText.of("物资点只能在编辑副本或游戏维度中添加"));
            return FAILURE;
        }
        if (map.lootPoints == null) {
            map.lootPoints = new java.util.ArrayList<>();
        }
        double x = player.getX();
        double z = player.getZ();
        for (LootPoint existing : map.lootPoints) {
            double dx = existing.x() - x;
            double dz = existing.z() - z;
            if (dx * dx + dz * dz <= 1.0) {
                source.sendFailure(BrText.of("该位置附近已有物资点 " + existing.name()));
                return FAILURE;
            }
        }
        // 3) 添加数据：写在 MapManager 内存里的真实对象上（不是副本）。
        map.lootPoints.add(new LootPoint(x, z, name.trim(), 12));
        if (draft) {
            MapEditorSync.sendOpen(player, map);
        } else {
            // 4) 落盘：saveMap 重写 config/battleroyale/maps/<mapId>.json。
            MapManager.INSTANCE.saveMap(map);
            BattleRoyaleMod.LOGGER.info("[LootCmd] 添加物资点后，现数量：{}，已调用 saveMap 落盘（mapId={}）",
                    map.lootPoints.size(), map.mapId);
            // 5) 同步 GUI：刷新该玩家的编辑草稿 + 详情面板数据 + HUD 物资点缓存，
            //    这样指令执行完马上打开 GUI 就能看到。
            MapEditSession.DRAFTS.put(player.getUUID(), map.copy());
            MapEditorSync.sendOpen(player, map);
            MapListSync.send(player);
            LootPointsSync.sendTo(player, map);
        }
        source.sendSuccess(() -> BrText.of("已添加物资点 " + name.trim() + "：("
                + (int) x + ", " + (int) z + ")" + (draft ? "（草稿，保存地图后才写入 JSON）" : "")), false);
        return SUCCESS;
    }

    /**
     * /paimengmods manager spawn：直接执行时列出子命令。
     * 与 {@code manager game spawn} 是同一套实现，只是多给一条更短的路径。
     */
    private static int spawnHelp(CommandSourceStack source) {
        source.sendSuccess(() -> BrText.of("【出生岛】子命令："), false);
        source.sendSuccess(() -> BrText.of("  set —— 把圈好的范围写回地图（需先在出生岛 select pos1 / pos2）"), false);
        source.sendSuccess(() -> BrText.of("  get —— 查看当前出生点与出生岛范围"), false);
        return SUCCESS;
    }

    /**
     * /paimengmods manager spawn set：在游戏维度里把出生岛区域写回地图 JSON。
     *
     * <p>范围来自 {@code manager select pos1 / pos2}（与 spawn_tool 共用同一份角点暂存）；
     * 两点没圈齐时退一步——只把脚下位置记为出生点，并明确提示还差哪一步。
     * 所有写入都落在 {@code MapManager} 的真实内存对象上，保存后才落盘。
     */
    private static int managerSpawnSet(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        GameMap map = GameWorldManager.mapForWorld(player.level().dimension());
        if (map == null) {
            source.sendFailure(BrText.of("只能在游戏维度里设置出生岛，先执行 /paimengmods manager game enter <mapId>"));
            return FAILURE;
        }
        SelectionData data = MapEditSession.spawnSelectionOf(player.getUUID());
        if (!data.isComplete()) {
            String error = GameWorldManager.setSpawnHere(player);
            if (error != null) {
                source.sendFailure(BrText.of(error));
                return FAILURE;
            }
            source.sendSuccess(() -> BrText.of(String.format(
                    "出生岛范围还没圈齐（用 /paimengmods manager select pos1 和 pos2 各点一次），"
                            + "已先把出生点设为 (%.1f, %.1f, %.1f) 并写入地图 %s",
                    map.spawnX, map.spawnY, map.spawnZ, map.mapId)), false);
            return SUCCESS;
        }
        map.spawnMinX = data.minX();
        map.spawnMaxX = data.maxX();
        map.spawnMinZ = data.minZ();
        map.spawnMaxZ = data.maxZ();
        map.spawnAreaSet = true;
        map.spawnX = (map.spawnMinX + map.spawnMaxX) / 2.0 + 0.5;
        map.spawnZ = (map.spawnMinZ + map.spawnMaxZ) / 2.0 + 0.5;
        map.spawnY = player.getY();
        map.spawnSet = true;
        MapManager.INSTANCE.saveMap(map);
        MapEditSession.SPAWN_SELECTIONS.remove(player.getUUID());
        try {
            ((net.minecraft.server.level.ServerLevel) player.level()).setDefaultSpawnPos(player.blockPosition(), player.getYRot());
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.debug("设置维度出生点失败（不影响地图 JSON 保存）: {}", t.toString());
        }
        BattleRoyaleMod.LOGGER.info("地图 {} 出生岛区域已保存：X {}~{}，Z {}~{}",
                map.mapId, map.spawnMinX, map.spawnMaxX, map.spawnMinZ, map.spawnMaxZ);
        source.sendSuccess(() -> BrText.of("出生岛区域已设置，范围 X " + map.spawnMinX + "~" + map.spawnMaxX
                + "，Z " + map.spawnMinZ + "~" + map.spawnMaxZ + "（地图 " + map.mapId
                + "，中心 (" + (int) map.spawnX + ", " + (int) map.spawnY + ", " + (int) map.spawnZ
                + ")），已写入 JSON；开局时全部玩家传送到该区域"), false);
        return SUCCESS;
    }

    /** /paimengmods manager spawn get：显示当前游戏维度地图的出生点与出生岛范围。 */
    private static int managerSpawnGet(CommandSourceStack source) {
        GameMap map = GameWorldManager.mapForWorld(source.getLevel() == null
                ? null : source.getLevel().dimension());
        if (map == null) {
            source.sendFailure(BrText.of("请先在游戏维度里执行（/paimengmods manager game enter <mapId>）"));
            return FAILURE;
        }
        if (!map.spawnSet) {
            source.sendSuccess(() -> BrText.of("地图 " + map.mapId + " 尚未设置出生点，进入时将传送到选区中心"), false);
        } else {
            source.sendSuccess(() -> BrText.of(String.format("地图 %s 出生点：(%.1f, %.1f, %.1f)",
                    map.mapId, map.spawnX, map.spawnY, map.spawnZ)), false);
        }
        if (map.spawnAreaSet) {
            source.sendSuccess(() -> BrText.of("出生岛范围：X " + map.spawnMinX + "~" + map.spawnMaxX
                    + "，Z " + map.spawnMinZ + "~" + map.spawnMaxZ), false);
        } else {
            source.sendSuccess(() -> BrText.of("出生岛范围：未设置（select pos1 / pos2 圈好后执行 /paimengmods manager spawn set）"), false);
        }
        ServerPlayer executor = null;
        executor = source.getPlayer();
        if (executor != null) {
            SelectionData data = MapEditSession.spawnSelectionOf(executor.getUUID());
            if (data.isComplete()) {
                source.sendSuccess(() -> BrText.of("待保存的角点：X " + data.minX() + "~" + data.maxX()
                        + "，Z " + data.minZ() + "~" + data.maxZ() + "（执行 /paimengmods manager spawn set 写入）"), false);
            }
        }
        return SUCCESS;
    }

    /** /paimengmods manager game spawn get：显示当前所在游戏维度地图的出生点。 */
    private static int gameSpawnGet(CommandSourceStack source) {
        GameMap map = GameWorldManager.mapForWorld(source.getLevel() == null
                ? null : source.getLevel().dimension());
        if (map == null) {
            source.sendFailure(BrText.of("请先在游戏维度里执行（/paimengmods manager game enter <mapId>）"));
            return FAILURE;
        }
        if (!map.spawnSet) {
            source.sendSuccess(() -> BrText.of("地图 " + map.mapId + " 尚未设置出生点，进入时将传送到选区中心"), false);
            return SUCCESS;
        }
        source.sendSuccess(() -> BrText.of(String.format("地图 %s 出生点：(%.1f, %.1f, %.1f)",
                map.mapId, map.spawnX, map.spawnY, map.spawnZ)), false);
        return SUCCESS;
    }

    // ------------------------------------------------------------------
    // /paimengmods br team create | invite | accept | deny | hh | join | approve
    //                | reject | kick | transfer | leave | list
    // 一律委托 TeamManager，这里只把 Result 翻成聊天回话。
    // 大厅队管理只在 WAITING 阶段可用（闸门在 TeamManager 内部），
    // 这些命令永远不会分配局内 1~4 号位或战术颜色 —— 那是 MatchTeamManager 的事。
    // ------------------------------------------------------------------

    private static int teamCreate(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.create(player));
    }

    private static int teamInvite(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.invite(player, target));
    }

    private static int teamAccept(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.accept(player));
    }

    /** /paimengmods br team deny：拒绝当前待处理的队伍邀请（并通知队长）。 */
    private static int teamDeny(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.deny(player));
    }

    private static int teamJoin(CommandSourceStack source, String teamId) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE : report(source, TeamManager.INSTANCE.join(player, teamId));
    }

    private static int teamApprove(CommandSourceStack source, String applicant) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE : report(source, TeamManager.INSTANCE.approve(player, applicant));
    }

    private static int teamReject(CommandSourceStack source, String applicant) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE : report(source, TeamManager.INSTANCE.reject(player, applicant));
    }

    private static int teamKick(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE : report(source, TeamManager.INSTANCE.kick(player, target));
    }

    /** /paimengmods br team transfer &lt;玩家名&gt;：队长手动把队长职位移交给队内指定玩家。 */
    private static int teamTransfer(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE : report(source, TeamManager.INSTANCE.transfer(player, target));
    }

    /** /paimengmods br team hh <消息>：队长向全服广播队伍招募（消息可点击加入）。 */
    private static int teamRecruit(CommandSourceStack source, String message) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.recruit(player, message));
    }

    private static int teamLeave(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        return player == null ? FAILURE
                : report(source, TeamManager.INSTANCE.leave(player));
    }

    /**
     * /paimengmods br team list：富文本座次表 —— 标题金色带「(当前/上限)」人数表，
     * 队长名字前加黄色 [队长] 标识，自己名字青色并标「(你)」。
     * 对局中（已被分到 MatchTeam）展示本局临时队伍座次，大厅展示大厅队（DraftGroup）。
     */
    private static int teamList(CommandSourceStack source) {
        ServerPlayer player = asPlayer(source);
        if (player == null) {
            return FAILURE;
        }
        // [BR-MATCHTEAM 模块三] 对局中展示的是 MatchTeam 座号（1~capacity 号位）；
        // 大厅 / 未开局展示大厅队 —— 数据源不同，互不覆盖。
        com.paimeng.battleroyale.team.MatchTeam matchTeam =
                com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.teamOf(player.getUUID());
        if (matchTeam != null) {
            final com.paimeng.battleroyale.team.MatchTeam teamRef = matchTeam;
            source.sendSuccess(() -> BrText.mutable("【本局 #" + teamRef.getTeamNumber() + " 队】")
                    .withStyle(ChatFormatting.GOLD)
                    .append(gauge(teamRef.size(), teamRef.getCapacity()))
                    .append(BrText.mutable("  临时队伍，大厅队未改动").withStyle(ChatFormatting.GRAY)), false);
            for (com.paimeng.battleroyale.team.MatchTeam.Member seat : matchTeam.membersSorted()) {
                boolean leader = seat.playerId().equals(matchTeam.getLeaderId());
                boolean self = seat.playerId().equals(player.getUUID());
                String label = seat.squadIndex() + "号位";
                source.sendSuccess(() -> teamRow(label, seat.name(), leader, self), false);
            }
            return SUCCESS;
        }
        Team team = TeamManager.INSTANCE.teamOf(player.getUUID());
        if (team == null) {
            source.sendFailure(BrText.mutable("你没有队伍。创建：")
                    .withStyle(ChatFormatting.RED)
                    .append(BrText.mutable("/paimengmods br team create")
                            .withStyle(ChatFormatting.YELLOW)));
            return FAILURE;
        }
        final Team teamRef = team;
        // 大厅队人数上限 = match.json 的 teamLimit（默认 4），与加入/招募校验同源
        final int limit = Math.max(1, GameModeStore.recruitTeamLimit());
        source.sendSuccess(() -> BrText.mutable("【队伍 " + teamRef.getTeamNumber() + " 号】")
                .withStyle(ChatFormatting.GOLD)
                .append(gauge(teamRef.size(), limit)), false);
        for (Team.Entry entry : team.membersInJoinOrder()) {
            boolean leader = entry.playerId().equals(team.getLeaderId());
            boolean self = entry.playerId().equals(player.getUUID());
            // 大厅只显示队长标记和成员名字，不显示任何队内编号。
            source.sendSuccess(() -> teamRow("", entry.name(), leader, self), false);
        }
        return SUCCESS;
    }

    /** 「(2/4)」人数表：未满绿色，满员红色。 */
    private static MutableComponent gauge(int size, int limit) {
        return BrText.mutable("（" + size + "/" + limit + "）")
                .withStyle(size >= limit ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    /** 一行成员：座次(灰) + [队长](黄·粗) + 名字(自己= aqua，他人=白) + (你)。 */
    private static MutableComponent teamRow(String seatLabel, String name, boolean leader, boolean self) {
        MutableComponent row = BrText.mutable("  " + seatLabel + " ").withStyle(ChatFormatting.GRAY);
        if (leader) {
            row.append(BrText.mutable("[队长] ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
        row.append(BrText.mutable(name).withStyle(self ? ChatFormatting.AQUA : ChatFormatting.WHITE));
        if (self) {
            row.append(BrText.mutable(" (你)").withStyle(ChatFormatting.GRAY));
        }
        return row;
    }

    /** 把 TeamManager.Result 发到聊天：成功走 feedback，失败走 error。 */
    private static int report(CommandSourceStack source, TeamManager.Result result) {
        if (result.success()) {
            source.sendSuccess(() -> BrText.of(result.message()), false);
            return SUCCESS;
        }
        source.sendFailure(BrText.of(result.message()));
        return FAILURE;
    }
}
