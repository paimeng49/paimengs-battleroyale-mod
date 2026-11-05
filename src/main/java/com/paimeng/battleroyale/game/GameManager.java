package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.GameStateChangedEvent;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 大逃杀状态机核心：唯一的“状态权威”，所有状态切换都必须走
 * {@link #transitionTo(GamePhase, MinecraftServer)}，由它向事件总线发布
 * {@link GameStateChangedEvent}。
 *
 * <p>自动切换（PREPARING→RUNNING、RUNNING→ENDED、ENDED→WAITING）全部在
 * {@link #tick(MinecraftServer)} 里检测，tick 由主类挂到 Fabric 的
 * ServerTickEvents 上，保证逻辑始终运行在服务端主线程。
 *
 * <p>升级预留：除 MinecraftServer 形参外，本类不使用任何易变的 MC API；
 * 高版本迁移时这个文件基本不用动。
 */
public final class GameManager {

    public static final GameManager INSTANCE = new GameManager();

    /** 当前阶段。只在服务端线程读写。 */
    private GamePhase phase = GamePhase.WAITING;

    /** 进入当前阶段后已经过的 tick 数。 */
    private int phaseTicks = 0;

    /** 状态机累计 tick（随服务端 tick 递增，事件里带上方便调试）。 */
    private int totalTicks = 0;

    /** 本局报名名单（玩家名，保持加入顺序）。 */
    private final Set<String> roster = new LinkedHashSet<>();

    private GameManager() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public GamePhase getPhase() {
        return phase;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public boolean isJoined(String playerName) {
        return roster.contains(playerName);
    }

    public int getPlayerCount() {
        return roster.size();
    }

    public Set<String> getRoster() {
        return Collections.unmodifiableSet(roster);
    }

    // ------------------------------------------------------------------
    // 由指令触作的动作（返回 false 表示当前状态不允许，指令层负责报错）
    // ------------------------------------------------------------------

    /** 加入大厅名单。对局进行中不允许加入。 */
    public boolean join(String playerName) {
        if (!phase.allowsRosterChange()) {
            return false;
        }
        return roster.add(playerName);
    }

    /** 退出名单。任何阶段都可以退出。 */
    public boolean leave(String playerName) {
        return roster.remove(playerName);
    }

    /** 开局：WAITING → PREPARING（旧入口，非强制）。 */
    public boolean requestStart(MinecraftServer server) {
        return requestStart(server, false);
    }

    /**
     * [BR-MATCHTEAM 模块一] 开局判定：<b>不再看总人数</b>，看的是独立队伍数量——
     * 任意等待地图的独立队伍数达到该地图配置的 maxTeams（{@code forced=true} 时为
     * 管理员强制开局，无视门槛），才允许进入 PREPARING。
     */
    public boolean requestStart(MinecraftServer server, boolean forced) {
        if (phase != GamePhase.WAITING || roster.isEmpty()) {
            return false;
        }
        if (!forced && !com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.thresholdReached(server)) {
            return false;
        }
        transitionTo(GamePhase.PREPARING, server);
        return true;
    }

    /**
     * [BR-MATCHTEAM] 准备（PREPARING）倒计时结束：PREPARING → FLIGHT。
     * 由 {@link WarmupManager} 在倒计时走完 / 管理员跳过时调用；
     * 进入 FLIGHT 后主类订阅者会拉起航线并发送队伍播报。
     */
    public boolean preparingToFlight(MinecraftServer server) {
        if (phase != GamePhase.PREPARING) {
            return false;
        }
        transitionTo(GamePhase.FLIGHT, server);
        return true;
    }

    /** 热身倒计时结束：WAITING → FLIGHT（航线阶段）。 */
    public boolean startFlight(MinecraftServer server) {
        if (phase != GamePhase.WAITING && phase != GamePhase.PREPARING) {
            return false;
        }
        transitionTo(GamePhase.FLIGHT, server);
        return true;
    }

    /**
     * [BR-MATCHTEAM] 取消本局回大厅：仅允许从 PREPARING 调用
     * （航线 / 比赛一旦开始就不能"取消"，只能 stop 走 ENDED）。
     * transitionTo 同步触发 PREPARING→WAITING 事件，主类订阅者据此销毁
     * MatchTeam、恢复大厅队伍视图。
     */
    public boolean abortToWaiting(MinecraftServer server) {
        if (phase != GamePhase.PREPARING) {
            return false;
        }
        transitionTo(GamePhase.WAITING, server);
        return true;
    }

    /**
     * [BR-FLIGHT] 航线阶段收尾：FLIGHT → RUNNING。
     * 由 {@link FlightManager} 在飞机到终点且全员落地（或超时）后调用；
     * 毒圈启动、统计初始化等由 BattleRoyaleMod 里既有的 RUNNING 订阅自动接棒。
     */
    public boolean beginRunning(MinecraftServer server) {
        if (phase != GamePhase.FLIGHT) {
            return false;
        }
        transitionTo(GamePhase.RUNNING, server);
        return true;
    }

    /** 结算展示结束后进入地图重置阶段。 */
    public boolean beginReset(MinecraftServer server) {
        if (phase != GamePhase.ENDED) {
            return false;
        }
        transitionTo(GamePhase.RESETTING, server);
        return true;
    }

    /** 重置完成后回到大厅，并清空上一局报名名单。 */
    public boolean finishReset(MinecraftServer server) {
        if (phase != GamePhase.RESETTING) {
            return false;
        }
        roster.clear();
        transitionTo(GamePhase.WAITING, server);
        return true;
    }

    /** /paimengmods br stop：强制结束本局（进入 ENDED，稍后开始地图重置）。 */
    public boolean requestStop(MinecraftServer server) {
        if (!phase.isActiveMatch()) {
            return false;
        }
        transitionTo(GamePhase.ENDED, server);
        return true;
    }

    // ------------------------------------------------------------------
    // 每 tick 检测：由 ServerTickEvents.END_SERVER_TICK 驱动
    // ------------------------------------------------------------------

    /**
     * 服务端主循环结束时调用。这里做两件事：
     * 1. 计数递增；
     * 2. 根据时间/人数检测是否该切换状态。
     */
    public void tick(MinecraftServer server) {
        totalTicks++;
        phaseTicks++;

        switch (phase) {
            case PREPARING -> {
                // [BR-MATCHTEAM] 准备阶段的推进改由 WarmupManager 的倒计时驱动
                // （PREPARING → FLIGHT）；这里只兜底"人跑光了取消开局"。
                if (roster.isEmpty()) {
                    transitionTo(GamePhase.WAITING, server);
                }
            }
            case FLIGHT -> {
                // 航线阶段由 FlightManager 驱动 FLIGHT → RUNNING；人跑光同样取消。
                if (roster.isEmpty()) {
                    transitionTo(GamePhase.WAITING, server);
                }
            }
            case RUNNING -> {
                // [BR-MATCHTEAM 模块四] 胜负判定基于 MatchTeam：存活队伍数只剩 1 支（或 0）即分出冠军；
                // 名单清空 / 超时两个兜底条件保留。
                // [BR-WINGUARD] 三个触发条件分别判断并写日志，杜绝静默 ENDED。
                boolean everyoneLeft = roster.isEmpty();
                boolean championCrown =
                        com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.isMatchDecided();
                boolean timedOut = phaseTicks >= GameRules.MATCH_MAX_TICKS;
                if (everyoneLeft || championCrown || timedOut) {
                    com.paimeng.battleroyale.BattleRoyaleMod.LOGGER.info(
                            "[BR-GAME] RUNNING -> ENDED 触发: 名单清空={}, 分出冠军(存活队伍={}, 开局队伍={}), 超时(第{}tick)",
                            everyoneLeft,
                            com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.aliveTeamCount(),
                            com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.startedTeamCount(),
                            phaseTicks);
                    transitionTo(GamePhase.ENDED, server);
                }
            }
            case ENDED -> {
                if (phaseTicks >= GameRules.ENDED_RESET_TICKS) {
                    transitionTo(GamePhase.RESETTING, server);
                }
            }
            case RESETTING -> {
                // 地图删除与模板重载由 MatchResetManager 的异步流程驱动。
            }
            default -> {
                // WAITING：自动开局由 WarmupManager 检测独立队伍数后调 requestStart
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态切换的唯一出口
    // ------------------------------------------------------------------

    private void transitionTo(GamePhase to, MinecraftServer server) {
        GamePhase from = this.phase;
        if (from == to) {
            return;
        }
        this.phase = to;
        this.phaseTicks = 0;
        EventBus.INSTANCE.publish(new GameStateChangedEvent(from, to, totalTicks, server));
    }
}
