package com.paimeng.battleroyale;

import com.paimeng.battleroyale.config.GameModeStore;
import com.paimeng.battleroyale.config.QuickChatStore;
import com.paimeng.battleroyale.command.BrCommand;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.GameStateChangedEvent;
import com.paimeng.battleroyale.event.PlayerJoinedTeamEvent;
import com.paimeng.battleroyale.event.PlayerLeftTeamEvent;
import com.paimeng.battleroyale.event.TeamCreatedEvent;
import com.paimeng.battleroyale.event.ZoneStageChangedEvent;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GamePhase;
import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.FlightManager;
import com.paimeng.battleroyale.item.ModItems;
import com.paimeng.battleroyale.game.StatsManager;
import com.paimeng.battleroyale.game.WarmupManager;
import com.paimeng.battleroyale.network.TeamChat;
import com.paimeng.battleroyale.network.ZoneSync;
import com.paimeng.battleroyale.network.FlightSync;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.network.MapEditorSync;
import com.paimeng.battleroyale.network.MapListSync;
import com.paimeng.battleroyale.network.MapTileSync;
import com.paimeng.battleroyale.team.PingService;
import com.paimeng.battleroyale.team.TeamManager;
import com.paimeng.battleroyale.util.BrText;
import com.paimeng.battleroyale.zone.ZoneManager;
import com.paimeng.battleroyale.zone.ZoneStage;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 大逃杀模组入口（服务端 / 通用部分；客户端那半在 {@code client.BattleRoyaleModClient}）。
 *
 * <p>初始化时做六件事：
 * <ol>
 *   <li>往自定义事件总线上挂默认订阅者（状态切换、毒圈阶段变化、队伍变化的日志 + 广播 + 同步）；</li>
 *   <li>在服务端一次性注册 /paimengmods 下的 br（含 team 子树）与 qc 快捷消息指令树；</li>
 *   <li>把状态机和毒圈的 tick 检测挂到 Fabric 的 ServerTickEvents 上；</li>
 *   <li>把毒圈状态每 tick 推给客户端（客户端据此画蓝色能量墙）；</li>
 *   <li>挂玩家断线钩子，让 TeamManager 把离线的队员自动移出队伍；</li>
 *   <li>注册标点系统与快捷消息的两条 C2S 通道接收者。</li>
 * </ol>
 *
 * <p><b>升级到高版本时只需要改这个文件里的两处接线：</b>
 * 1.19+ 起 CommandRegistrationCallback 移到 command.v2 并多一个
 * registryAccess 参数（1.20 起第三个参数改为 RegistrationEnvironment）。
 * 其余类（事件总线、状态机、毒圈、指令树、同步包）均与版本基本无关。
 */
public final class BattleRoyaleMod implements ModInitializer {

    public static final String MOD_ID = "battleroyale";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // [BR-MTR2] C-130 已数据化：不再生成/注册任何实体，位置由 FlightManager
        // 每 tick 计算并经 flight_tick 通道同步，客户端纯函数渲染（见 C130WorldRenderer）。
        // 1) 事件总线：状态切换、毒圈阶段变化的默认消费者。
        //    后续的撤离点、跳伞、GUI 同样在这里 subscribe，
        //    而不是往 GameManager / ZoneManager 里塞代码。
        EventBus bus = EventBus.INSTANCE;
        bus.subscribe(GameStateChangedEvent.class, BattleRoyaleMod::onGameStateChanged);
        // br start 成功后进入 PREPARING；这里先完成野排匹配，预留的出生点传送可在后续流程执行。
        bus.subscribe(GameStateChangedEvent.class, BattleRoyaleMod::onMatchmakingStateChanged);
        bus.subscribe(ZoneStageChangedEvent.class, BattleRoyaleMod::onZoneStageChanged);
        bus.subscribe(TeamCreatedEvent.class, BattleRoyaleMod::onTeamCreated);
        bus.subscribe(PlayerJoinedTeamEvent.class, BattleRoyaleMod::onPlayerJoinedTeam);
        bus.subscribe(PlayerLeftTeamEvent.class, BattleRoyaleMod::onPlayerLeftTeam);
        // 本局统计（剩余存活人数 + 个人击杀数）：进入 RUNNING 时按名单初始化，
        // 离开 RUNNING 时清空并通知客户端收起 HUD。作为独立订阅者挂载，
        // 不改状态机 / 毒圈 / 队伍的现有逻辑。
        bus.subscribe(GameStateChangedEvent.class, BattleRoyaleMod::onStatsStateChanged);
        bus.subscribe(GameStateChangedEvent.class, BattleRoyaleMod::onDownedStateChanged);
        // [BR-FLIGHT] 航线阶段生命周期：进入 FLIGHT 生成航线拉起玩家；中途被掐掉则全部复位。
        bus.subscribe(GameStateChangedEvent.class, BattleRoyaleMod::onFlightStateChanged);

        // 2) 指令注册。
        // [UPGRADE] 1.19+ 改为 net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback，
        //           lambda 参数变成 (dispatcher, registryAccess, environment)
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, dedicated) -> BrCommand.register(dispatcher));

        // 3) 服务端 tick：驱动状态机的自动切换检测 + 毒圈推进与圈外伤害结算（主线程执行）。
        ServerTickEvents.END_SERVER_TICK.register(GameManager.INSTANCE::tick);
        ServerTickEvents.END_SERVER_TICK.register(ZoneManager.INSTANCE::tick);
        ServerTickEvents.END_SERVER_TICK.register(DownedManager.INSTANCE::tick);
        // 开局热身：300 秒倒计时推进（服务端主线程，绝不用主线程死循环 sleep）。
        ServerTickEvents.END_SERVER_TICK.register(WarmupManager.INSTANCE::tick);
        // [BR-FLIGHT] 航线推进：玩家跟随插值、跳伞落地检测（同一主线程驱动）。
        ServerTickEvents.END_SERVER_TICK.register(FlightManager.INSTANCE::tick);

        // 等待期无敌的拦截点在 mixin.LivingEntityMixin（它注入 LivingEntity.damage）：
        // 1.18.2 的 Fabric API 里只有 ServerPlayerEvents（ALLOW_DEATH 等），
        // 没有高版本的 ServerLivingEntityEvents.ALLOW_DAMAGE，所以伤害许可只能这样拦；
        // /kill 在 1.18.2 内部走的也是 LivingEntity.hurt(OUT_OF_WORLD, MAX_VALUE)，同样拦得住。
        ModItems.register();
        // 4) 毒圈状态推给客户端（客户端据此画蓝色能量墙，见 client.ZoneWallRenderer）。
        ServerTickEvents.END_SERVER_TICK.register(ZoneSync::tick);

        // 5) 队伍系统：玩家断线 = 自动离队（服务端不留"幽灵队员"，
        //    离队引发的同步包由 TeamManager 当场发给剩下的队友）；
        //    顺带清掉这个玩家的标点冷却记录。
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    try {
                        DownedManager.INSTANCE.handleDisconnect(handler.player, server);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理击倒状态失败", t);
                    }
                    try {
                        TeamManager.INSTANCE.handleDisconnect(handler.player);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理队伍状态失败", t);
                    }
                    try {
                        PingService.INSTANCE.handleDisconnect(handler.player.getUUID());
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理标点状态失败", t);
                    }
                    try {
                        EditWorldManager.handleDisconnect(handler.player);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理副本维度失败", t);
                    }
                    try {
                        WarmupManager.INSTANCE.handleDisconnect(handler.player, server);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理热身状态失败", t);
                    }
                    // [BR-FLIGHT] 断线：从航线无敌 / 缓降名单摘除，避免留幽灵标记。
                    try {
                        FlightManager.INSTANCE.handleDisconnect(handler.player);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理航线状态失败", t);
                    }
                    try {
                        com.paimeng.battleroyale.match.MapQueue.INSTANCE.leave(handler.player.getUUID());
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理地图等待队列失败", t);
                    }
                    try {
                        MapTileSync.forget(handler.player);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家断线时清理地图 Tile 限速记录失败", t);
                    }
                });

        // 玩家重生后清理旧的击倒/淘汰状态，避免下一次致命伤害被旧状态污染。
        ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> {
                    try {
                        DownedManager.INSTANCE.handleRespawn(newPlayer);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家重生时清理击倒状态失败", t);
                    }
                    try {
                        EditWorldManager.handleRespawn(newPlayer);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家重生时清理副本维度失败", t);
                    }
                });

        // 6) 标点系统 + 快捷消息：两条 C2S 通道（battleroyale:ping、
        //    battleroyale:quick_chat）的服务端接收者。快捷消息的预设列表由
        //    config.QuickChatStore 在注册前加载；network.TeamChat 收到包后
        //    会再按服务器端这份配置校验一次。标点没有文本入参，消息文案由
        //    team.PingService 按 PingType 写死后走 TeamMessageSender 发全队。
        GameModeStore.loadOrCreate();
        MapManager.INSTANCE.loadAll();
        // [BR-FLIGHT] 航线 / 跳伞双通道（C2S parachute 接收者 + S2C flight_route 发送）。
        FlightSync.registerServer();
        MapEditorSync.registerServer();
        MapListSync.registerServer();
        // C2：小地图 Tile 切片的 C2S 接收通道（battleroyale:map_tile_request）。
        MapTileSync.registerServer();
        QuickChatStore.loadOrCreate();
        PingService.INSTANCE.registerServer();
        TeamChat.registerServer();

        // 7) 本局统计：玩家死亡（被淘汰）时更新剩余人数与击杀数，
        //    变化当场广播给所有客户端；中途进服的玩家立刻补发一份当前统计。
        //    只统计本模组名单内的对局玩家，判定都在 StatsManager 内做。
        //    1.18.2 的 Fabric API 里玩家死亡挂在 ServerPlayerEvents.ALLOW_DEATH，
        //    返回 true 表示不拦截原版死亡流程，我们只在旁边记账。
        ServerPlayerEvents.ALLOW_DEATH.register(
                (player, source, amount) -> {
                    return DownedManager.INSTANCE.handleDeath(player, source);
                });
        // 击倒独立血量池的伤害拦截由 mixin 注入 LivingEntity.damage：
        // 击倒玩家不走原版血量，直接扣 downedHealth；非击倒玩家不受影响。

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> StatsManager.INSTANCE.sendTo(handler.player));

        // [BR-ZONE] 模块六：登录瞬间按玩家所在维度补发一次毒圈状态
        // （在他维度就有快照、不在就只有 IDLE——保证主城永远拿干净状态）。
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    try {
                        ZoneSync.sendOnJoin(handler.player);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家登录补发毒圈状态失败", t);
                    }
                });

        // [BR-FLIGHT] 登录兜底：清掉断线前可能残留的零重力标记；
        // 若中途重连回正在飞行的游戏维度（人还在高空），给一张应急缓降伞。
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    try {
                        FlightManager.INSTANCE.ensureCleanJoin(handler.player, server);
                    } catch (Throwable t) {
                        LOGGER.warn("玩家登录时清理航线残留失败", t);
                    }
                });

        // 重连补发：等待队列里的玩家重新登录后，把他所在地图的物资点列表再推一次
        // （客户端断线时已清空缓存，HUD 侧只读本地缓存，不会再每帧找服务端要数据）。
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    try {
                        String mapId = com.paimeng.battleroyale.match.MapQueue.INSTANCE
                                .waitingMapOf(handler.player.getUUID());
                        com.paimeng.battleroyale.map.GameMap map = mapId == null
                                ? null : com.paimeng.battleroyale.map.MapManager.INSTANCE.getMap(mapId);
                        if (map != null) {
                            com.paimeng.battleroyale.network.LootPointsSync.sendTo(handler.player, map);
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("重连补发物资点失败", t);
                    }
                });

        // [BR-HUD-REJOIN] 断线重连回主世界：上一次下线时如果人还在吃鸡游戏维度里，
        // 原版存档会把人原样放回该维度；这里检测到就强制送回主世界大厅并清理残留状态。
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    try {
                        handleGameWorldRejoin(handler.player, server);
                    } catch (Throwable t) {
                        LOGGER.warn("[BR-HUD-REJOIN] 处理游戏维度重连玩家失败", t);
                    }
                });

        LOGGER.info("[{}] 初始化完成：状态机 + 毒圈 + 队伍 + 标点 + 快捷消息通道已挂载，事件订阅者 {} 个",
                MOD_ID, bus.listenerCount());
    }

    /**
     * [BR-HUD-REJOIN] 断线重连回主世界（大厅）。
     *
     * <p>玩家在游戏维度里下线时，存档记录的维度就是 {@code battleroyale_game:*}，
     * 重连瞬间原版会把人原样放回该维度（游戏维度在启动时已预挂载）。这里在 JOIN 时
     * 检测到这种落点，就排进服务端主线程队列（server.execute：JOIN 回调时连接尚未
     * 完全建立，立刻 teleport 会被随后的初始位置包盖掉），下一 tick 把玩家送回主世界。
     *
     * <p>同时按「赛中逃跑」同一套管线做淘汰结算（存活人数 -1、队伍坍缩检查、
     * 清 HUD 包），防止重连回大厅的人仍挂在存活名单里变成"幽灵玩家"。
     * 不改动任何飞行 / 跳伞逻辑本身，只在传送完成后复用既有的
     * {@link FlightManager#ensureCleanJoin} 兜底清掉中途重连场景挂上的应急缓降。
     */
    private static void handleGameWorldRejoin(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        LOGGER.info("[BR-HUD-REJOIN] 玩家 {} 重连落在游戏维度 {}，下一 tick 强制送回大厅",
                player.getName().getString(), player.level().dimension().location());
        server.execute(() -> {
            try {
                sendRejoinerToLobby(player, server);
            } catch (Throwable t) {
                LOGGER.warn("[BR-HUD-REJOIN] 游戏维度重连玩家送回大厅失败", t);
            }
        });
    }

    /** 主线程执行：淘汰 / 清残留状态，然后把玩家传送回主世界并重置重生点。 */
    private static void sendRejoinerToLobby(ServerPlayer player, MinecraftServer server) {
        // 双保险：排队的这一 tick 里玩家可能已被其他流程送出游戏维度。
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        UUID playerId = player.getUUID();
        String name = player.getGameProfile().getName();
        GamePhase phase = GameManager.INSTANCE.getPhase();
        boolean inMatch = phase == GamePhase.PREPARING
                || phase == GamePhase.FLIGHT
                || phase == GamePhase.RUNNING;
        if (inMatch && GameManager.INSTANCE.isJoined(name)) {
            // 对局名单里的人断线重连：与被击杀同一套结算（存活人数 -1、队伍坍缩检查、
            // 淘汰事件、HUD 清除包），杜绝"人回大厅、名字还挂在存活数里"的幽灵玩家。
            DownedManager.INSTANCE.eliminateForDesert(player);
            BrText.broadcast(server, "【大逃杀】" + name + " 断线重连后已送回大厅，判定阵亡！");
        }
        GameManager.INSTANCE.leave(name);
        // [BR-MATCHTEAM] 断线重连按阵亡结算的是"本局参赛资格"，大厅队伍不动：
        // 需求要求游戏结束回主城后原队伍原样恢复，大厅队从始至终没有被本局逻辑修改过。
        com.paimeng.battleroyale.match.MapQueue.INSTANCE.leave(playerId);
        // 热身名单 + 等待期无敌标记一起清；内部顺带做人数不足取消倒计时的检查。
        WarmupManager.INSTANCE.leave(player);
        DownedManager.INSTANCE.clearPlayer(player);
        // 取消仍在排队的"维度就绪后自动传送"，防止刚回大厅又被拽回游戏维度。
        GameWorldManager.cancelPendingEnter(playerId);
        // leave() 内部：优先回进图前的主世界落点，没有记录则回主世界出生点，
        // 两种都是大厅；同时把重生点重置到主世界，之后死亡不会再被送回游戏维度重生。
        String teleportError = GameWorldManager.leave(player);
        if (teleportError != null) {
            LOGGER.warn("[BR-HUD-REJOIN] 玩家 {} 送回大厅传送未完成：{}", name, teleportError);
            return;
        }
        LOGGER.info("[BR-HUD-REJOIN] 玩家 {} 已从游戏维度送回大厅（主世界）", name);
        // 人已在主世界：ensureCleanJoin 走"非游戏维度"分支，清掉航线中途重连时
        // 可能被挂上的应急缓降 / 零重力 / gliding 名单，防止状态跟着回大厅。
        try {
            FlightManager.INSTANCE.ensureCleanJoin(player, server);
        } catch (Throwable t) {
            LOGGER.warn("[BR-HUD-REJOIN] 回大厅后清理航线残留失败", t);
        }
    }

    /**
     * [BR-MATCHTEAM] 开局匹配消费者：仅在 WAITING -> PREPARING 时执行临时队伍分配。
     *
     * <p>大厅队伍（LobbyTeam）只被读取、不被修改；分配出的 MatchTeam 是独立副本。
     * 分配完成后立刻把每位对局玩家送进自己等待地图的游戏维度，落地出生岛中心。
     */
    private static void onMatchmakingStateChanged(GameStateChangedEvent event) {
        if (event.from() == GamePhase.WAITING && event.to() == GamePhase.PREPARING) {
            if (!com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.allocate(event.server())) {
                // [BR-MATCHTEAM 第三步] 有地图重组后超出 maxTeams 上限：整局不开。
                // 分配器已广播报错并记了开局闩（同批人不会反复自动重试）；
                // 这里把状态机退回 WAITING，不传送、不清空等待队列。
                GameManager.INSTANCE.abortToWaiting(event.server());
                return;
            }
            // 必须在 MapQueue 被清空前传送：waitingMapOf 是 MatchTeam 分配和落地的依据。
            com.paimeng.battleroyale.map.GameWorldManager.teleportRosterToGameWorld(event.server());
            // [BR-MATCHTEAM] 开局即释放全部等待名额：等待玩家转为对局参赛者。
            // 不清的话回大厅后旧队列会立刻再次触达独立队伍数门槛，无限重开同一批人。
            com.paimeng.battleroyale.match.MapQueue.INSTANCE.clearAll();
        }
        // [BR-MATCHTEAM] 回到大厅（正常结算 / 强制取消）：销毁本局全部 MatchTeam，
        // 大厅队伍从未被改动，玩家视图立刻恢复成原样。
        if (event.to() == GamePhase.WAITING && event.from() != GamePhase.WAITING) {
            com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.disband(event.server());
        }
    }

    /** 统计生命周期：进入 RUNNING 初始化名单，离开 RUNNING 清空客户端 HUD。 */
    private static void onStatsStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.RUNNING) {
            StatsManager.INSTANCE.start(event.server());
        } else if (event.from() == GamePhase.RUNNING) {
            StatsManager.INSTANCE.clear(event.server());
        }
    }

    private static void onDownedStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.RUNNING || event.from() == GamePhase.RUNNING) {
            DownedManager.INSTANCE.reset(event.server());
        }
    }

    /**
     * [BR-FLIGHT] 航线阶段生命周期消费者。
     *
     * <p>进入 FLIGHT：生成航线并把玩家拉上高空；
     * 从 FLIGHT 被切走且目标不是 RUNNING（说明是 br stop 之类中途掐断）：全部复位。
     * 正常跑完（飞机到终点、全员落地）由 FlightManager 自己调
     * {@code GameManager.beginRunning}，事件回到这里时 to==RUNNING，不做清理。
     */
    private static void onFlightStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.FLIGHT) {
            FlightManager.INSTANCE.begin(event.server());
            // [BR-MATCHTEAM 模块四] 正式进入航线：给每名队员播报「您的队伍编号 + 座次表」。
            MatchTeamManager.INSTANCE.announceFlightTeams(event.server());
        } else if (event.from() == GamePhase.FLIGHT && event.to() != GamePhase.RUNNING) {
            FlightManager.INSTANCE.endMatch(event.server());
        }
    }

    /** 默认订阅者：记录日志、全服广播，并联动毒圈的启停。 */
    private static void onGameStateChanged(GameStateChangedEvent event) {
        LOGGER.info("游戏状态切换: {} -> {} (累计 tick={})",
                event.from().name(), event.to().name(), event.globalTicks());
        BrText.broadcast(event.server(),
                "【大逃杀】状态: " + event.from().displayName() + " -> " + event.to().displayName());

        // 毒圈生命周期跟 RUNNING 走：开局启动，结束/强停即关。
        if (event.to() == GamePhase.RUNNING) {
            ZoneManager.INSTANCE.start(event.server());
        } else if (event.from() == GamePhase.RUNNING) {
            ZoneManager.INSTANCE.stop(event.server());
        }
    }

    /** 毒圈阶段变化的默认消费者：写日志 + 全服播报 + 立刻同步给客户端。GUI 以后另挂订阅者。 */
    private static void onZoneStageChanged(ZoneStageChangedEvent event) {
        String message = switch (event.phase()) {
            // 一局可能多张地图同时在跑（每维度一圈）：播报文案带地图 ID 区分。
            case IDLE -> "【毒圈·" + zoneTag(event) + "】已关闭。";
            case WAITING -> {
                ZoneStage s = stageOf(event);
                // [r130 阶段零] 开局蓝圈罩住全图：这一条等待播报不讲"半径多少米"，
                // 讲清"现在全图安全 + 白圈多大 + 什么时候开始收缩"。
                if (event.phase0()) {
                    yield "【毒圈·" + zoneTag(event) + "】安全区已标定（半径 " + (int) s.targetRadius()
                            + " 米）：当前全图安全，" + s.waitSeconds() + " 秒后毒圈开始收缩，请尽快进圈！";
                }
                yield "【毒圈·" + zoneTag(event) + "】" + s.name() + "：半径 " + (int) event.radius()
                        + " 米，" + s.waitSeconds() + " 秒后开始收缩（圈外每秒 "
                        + (int) s.damagePerSecond() + " 点伤害）。";
            }
            case SHRINKING -> {
                ZoneStage s = stageOf(event);
                yield "【毒圈·" + zoneTag(event) + "】" + s.name() + " 正在收缩！" + s.shrinkSeconds()
                        + " 秒内收到 " + (int) s.targetRadius() + " 米，快进圈！";
            }
            case DONE -> "【毒圈·" + zoneTag(event) + "】所有阶段收缩完毕，最后的决战圈已定型。";
        };
        LOGGER.info("[BR-ZONE] 地图 {} 毒圈变化: phase={} stage={} radius={}",
                event.mapId(), event.phase().name(), event.stageIndex(), event.radius());
        BrText.broadcast(event.server(), message);
        // 阶段一切换立刻补发一次，客户端的能量墙不会等到下一次兜底广播才更新。
        ZoneSync.syncNow(event.server());
    }

    /** 播报文案里的地图标识（多地图并跑时区分是哪一家的圈）。 */
    private static String zoneTag(ZoneStageChangedEvent event) {
        String mapId = event.mapId();
        return mapId == null || mapId.isEmpty() ? "?" : mapId;
    }

    /** 事件里的 stage 允许为 null（防御），播报文案需要一个非空配置兜底。 */
    private static ZoneStage stageOf(ZoneStageChangedEvent event) {
        ZoneStage stage = event.stage();
        if (stage != null) {
            return stage;
        }
        // 理论上到不了这里：WAITING / SHRINKING / DONE 发布时 stageIndex 必然合法。
        return ZoneStage.of("本阶段", 0, 0, event.radius(), 0.0f);
    }

    // ------------------------------------------------------------------
    // 队伍事件的默认消费者：目前只写日志。
    // 指令回话与"数据变化立即同步客户端"都由 TeamManager 当场做完，
    // 这里留着以后接战报 / 统计 / 队伍频道时再加逻辑。
    // ------------------------------------------------------------------

    private static void onTeamCreated(TeamCreatedEvent event) {
        LOGGER.info("队伍创建: {} 号队（队长 {}，ID {}）",
                event.team().getTeamNumber(), event.leaderName(), event.team().getTeamId());
    }

    private static void onPlayerJoinedTeam(PlayerJoinedTeamEvent event) {
        LOGGER.info("玩家入队: {} 加入 {} 号队，队员编号 {} 号",
                event.playerName(), event.team().getTeamNumber(), event.memberNumber());
    }

    private static void onPlayerLeftTeam(PlayerLeftTeamEvent event) {
        LOGGER.info("玩家离队: {} 离开 {} 号队（原编号 {} 号）{}",
                event.playerName(), event.teamNumber(), event.memberNumber(),
                event.disbanded() ? "，队伍已解散" : "");
    }
}
