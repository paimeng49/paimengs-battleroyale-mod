package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-MATCHTEAM] 准备（PREPARING）阶段：等待玩家驻守出生岛、无敌保护、
 * 300 秒倒计时与航线阶段切换。
 *
 * <p>开局门槛改造后本类不再按“总人数”自动起倒计时：WAITING 阶段每秒检测一次
 * {@code MatchTeamManager.thresholdReached}（任一等待地图的独立队伍数 ≥ 该图
 * maxTeams），达标即 {@code GameManager.requestStart} 进入 PREPARING；
 * MatchTeam 分配（主类订阅事件）完成后本类才接管倒计时。
 * 管理员 {@code manager start} 可无视门槛强制提前开局。
 */
public final class WarmupManager {
    public static final WarmupManager INSTANCE = new WarmupManager();

    public static final int COUNTDOWN_SECONDS = 300;
    private static final int COUNTDOWN_TICKS = COUNTDOWN_SECONDS * GameRules.TICKS_PER_SECOND;
    /** WAITING 阶段开局门槛的检测节流：每 20 tick（1 秒）跑一次，开销可忽略。 */
    private static final int THRESHOLD_CHECK_TICKS = GameRules.TICKS_PER_SECOND;

    private final Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> invinciblePlayers = ConcurrentHashMap.newKeySet();
    private boolean countdownStarted;
    private int countdownTicks;
    /** 本次倒计时是否由 manager start 强制开启：强制开启时玩家退出不打断倒计时。 */
    private boolean forcedStart;
    private int thresholdCheckTicks;

    private WarmupManager() {
    }

    public boolean isInvincible(UUID playerId) {
        return invinciblePlayers.contains(playerId);
    }

    public boolean isWaiting(UUID playerId) {
        return waitingPlayers.contains(playerId);
    }

    public int waitingCount() {
        return waitingPlayers.size();
    }

    public boolean isCountdownStarted() {
        return countdownStarted;
    }

    public void join(ServerPlayer player) {
        waitingPlayers.add(player.getUUID());
        invinciblePlayers.add(player.getUUID());
        // [BR-MATCHTEAM] 倒计时只在 PREPARING 阶段跑（主类在 MatchTeam 分配后调
        // beginPreparation）；WAITING 阶段加入等待只负责检测开局门槛。
        if (countdownStarted) {
            // 倒计时开始后才加入的玩家也要立即看到同一条预告航线。
            FlightManager.INSTANCE.sendPendingRoute(player);
        } else {
            tryAutoStart(player.getServer());
        }
    }

    public void leave(UUID playerId) {
        waitingPlayers.remove(playerId);
        invinciblePlayers.remove(playerId);
    }

    /**
     * br leave 等「玩家主动退出等待」的入口：先移出名单，再检查是否因人数不足要取消倒计时。
     * 必须在把玩家移出 waitingPlayers <em>之后</em>再取人数，否则会把退出者也算进去。
     */
    public void leave(ServerPlayer player) {
        leave(player.getUUID());
        afterPlayerLeft(player.getServer());
    }

    /**
     * [BR-MATCHTEAM] 管理员强制开局：无视独立队伍数门槛，直接让状态机进入 PREPARING。
     * 真正的 MatchTeam 分配 + 倒计时启动由主类订阅的 WAITING→PREPARING 事件负责，
     * 这里只推动状态机；{@code beginPreparation} 再兜一次底（幂等），
     * 保证即使事件链缺席也能起倒计时。
     */
    public boolean forceStart(MinecraftServer server) {
        if (countdownStarted) {
            return false;
        }
        if (waitingPlayers.isEmpty()) {
            return false;
        }
        if (!GameManager.INSTANCE.requestStart(server, true)) {
            return false;
        }
        beginPreparation(server, true);
        return true;
    }

    /**
     * [BR-MATCHTEAM] WAITING→PREPARING 且 MatchTeam 分配完成后由主类订阅者调用：
     * 起 300 秒倒计时（forced = 是否管理员强制开局）。幂等：已在倒计时则原样返回。
     *
     * <p>[BR-MATCHTEAM 第三步] 分配失败时主类订阅者已把状态机退回 WAITING：
     * 强制开局路径兜底再调到这里时发现已不在 PREPARING，直接不起倒计时。
     */
    public void beginPreparation(MinecraftServer server, boolean forced) {
        if (GameManager.INSTANCE.getPhase() != GamePhase.PREPARING) {
            return;
        }
        startCountdown(server, forced);
    }

    private void startCountdown(MinecraftServer server, boolean forced) {
        if (server == null || countdownStarted) {
            return;
        }
        countdownStarted = true;
        forcedStart = forced;
        countdownTicks = COUNTDOWN_TICKS;
        BattleRoyaleMod.LOGGER.info("[BR-SPAWN] 准备倒计时开始：{} 秒，等待玩家 {} 人{}",
                COUNTDOWN_SECONDS, waitingPlayers.size(), forced ? "（管理员强制）" : "（独立队伍数达标）");
        // 倒计时一开始就生成并同步航线；FLIGHT 阶段只复用这份结果。
        FlightManager.INSTANCE.publishRoutes(server);
        broadcastActionBar(server, COUNTDOWN_SECONDS);
    }

    /** [BR-MATCHTEAM] 独立队伍数达标 → 自动进入 PREPARING（主类订阅事件做分配 + 传送）。 */
    private void tryAutoStart(MinecraftServer server) {
        if (server == null || GameManager.INSTANCE.getPhase() != GamePhase.WAITING) {
            return;
        }
        if (com.paimeng.battleroyale.team.MatchTeamManager.INSTANCE.thresholdReached(server)) {
            // requestStart 内部 transitionTo 会同步触发 WAITING→PREPARING 事件：
            // 主类订阅者完成 MatchTeam 分配 + 开局传送；随后本方法起准备倒计时（幂等兜底）。
            if (GameManager.INSTANCE.requestStart(server, false)) {
                beginPreparation(server, false);
            }
        }
    }

    /** 必须由 ServerTickEvents 驱动，不在指令线程里阻塞。 */
    public void tick(MinecraftServer server) {
        GamePhase phase = GameManager.INSTANCE.getPhase();

        // WAITING：不起倒计时，只做独立队伍数门槛的每秒复检（组队增减会影响达标）。
        if (phase == GamePhase.WAITING) {
            if (++thresholdCheckTicks >= THRESHOLD_CHECK_TICKS) {
                thresholdCheckTicks = 0;
                tryAutoStart(server);
            }
            return;
        }
        thresholdCheckTicks = 0;

        // 非 PREPARING 且倒计时还挂着（异常路径：中途被强制切走）：清航线并复位。
        if (phase != GamePhase.PREPARING) {
            if (countdownStarted) {
                FlightManager.INSTANCE.clearPendingRoutes(server);
                reset();
            }
            return;
        }

        // PREPARING：倒计时未起就什么都不做（由 requestStart 的调用方显式 beginPreparation）。
        if (!countdownStarted) {
            return;
        }
        if (waitingPlayers.isEmpty()) {
            FlightManager.INSTANCE.clearPendingRoutes(server);
            reset();
            return;
        }
        countdownTicks--;
        if (countdownTicks <= 0) {
            finish(server);
            return;
        }
        if (countdownTicks % GameRules.TICKS_PER_SECOND == 0) {
            broadcastActionBar(server, countdownTicks / GameRules.TICKS_PER_SECOND);
        }
    }

    private void finish(MinecraftServer server) {
        Set<UUID> players = Set.copyOf(waitingPlayers);
        for (UUID playerId : players) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            invinciblePlayers.remove(playerId);
            if (player == null) {
                continue;
            }
            teleportToSpawn(player);
            player.displayClientMessage(BrText.of("热身结束，航线阶段开始！"), true);
        }
        waitingPlayers.clear();
        countdownStarted = false;
        forcedStart = false;
        countdownTicks = COUNTDOWN_TICKS;
        // [BR-MATCHTEAM] 准备倒计时结束：PREPARING → FLIGHT（事件自动触发 FlightManager.begin 拉人上机）
        GameManager.INSTANCE.preparingToFlight(server);
        BattleRoyaleMod.LOGGER.info("[BR-SPAWN] 准备倒计时结束：无敌已取消，玩家已拉回出生岛中心，状态切换为航线阶段");
    }

    private void teleportToSpawn(ServerPlayer player) {
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        ServerLevel world = (ServerLevel) player.level();
        GameMap map = GameWorldManager.mapForWorld(world.dimension());
        if (map == null) {
            return;
        }
        BlockPos target = GameWorldManager.matchLanding(map, world);
        player.teleportTo(world, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    private void broadcastActionBar(MinecraftServer server, int seconds) {
        Component text = Component.literal("大逃杀将在 " + seconds + " 秒后开始");
        for (UUID playerId : waitingPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.displayClientMessage(text, true);
            }
        }
    }

    /**
     * 玩家断线（ServerPlayConnectionEvents.DISCONNECT）入口：清名单后同样做人数检查，
     * 与 br leave 走同一个取消判定，保证掉线也不会让倒计时空跑。
     */
    public void handleDisconnect(ServerPlayer player, MinecraftServer server) {
        leave(player.getUUID());
        afterPlayerLeft(server);
    }

    /**
     * 有人退出等待/断线后的统一检查：[BR-MATCHTEAM] 开局门槛改造后倒计时在 PREPARING
     * 阶段跑，"人数不足"不再打断倒计时（逃跑按阵亡结算、比赛照常推进）；
     * 只有等待名单彻底清空且非强制开局才取消本局 —— 回到 WAITING 并销毁 MatchTeam
     * （roster 清空的情况由 GameManager.tick 兜底，这里处理断线竞态）。
     */
    private void afterPlayerLeft(MinecraftServer server) {
        if (!countdownStarted || forcedStart) {
            return;
        }
        if (!waitingPlayers.isEmpty()) {
            return;
        }
        cancelCountdown(server);
        GameManager.INSTANCE.abortToWaiting(server);
    }

    /**
     * [BR-SKIP-COUNTDOWN] 管理员在倒计时期间再次执行 /paimengmods manager start：
     * 跳过剩余倒计时，立刻走与倒计时自然走完完全相同的收尾流程 finish()
     * （传送回出生岛、取消无敌、WAITING → FLIGHT，事件自动触发 FlightManager.begin 拉人上飞机）。
     * 指令和服务端 tick 同在主线程，直接调用是安全的。不重置队伍、不动任何其他状态。
     */
    public boolean skipCountdown(MinecraftServer server) {
        if (!countdownStarted || server == null) {
            return false;
        }
        for (UUID playerId : waitingPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.displayClientMessage(BrText.of("管理员强制跳过倒计时！"), false);
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-SKIP-COUNTDOWN] 管理员强制跳过倒计时：剩余 {} tick，立即结束热身进入航线阶段",
                countdownTicks);
        finish(server);
        return true;
    }

    /**
     * 取消倒计时：停 tick 任务（countdownStarted 置 false 即可，tick 由 ServerTickEvents 驱动）、
     * 剩余时间重置回 300 秒、触发标记复位，并向还在等待的玩家发提示。
     */
    public void cancelCountdown(MinecraftServer server) {
        if (!countdownStarted) {
            return;
        }
        FlightManager.INSTANCE.clearPendingRoutes(server);
        countdownStarted = false;
        countdownTicks = COUNTDOWN_TICKS;
        forcedStart = false;
        if (server == null) {
            return;
        }
        // 立刻清掉 actionbar 上残留的倒计时数字（不再刷新，2 秒后自然消失，主动清更干净）。
        for (UUID playerId : waitingPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.displayClientMessage(Component.literal(""), true);
                player.displayClientMessage(BrText.of("等待名单已清空，倒计时取消，本局取消。"), false);
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-COUNT] 等待名单清空，倒计时取消并复位到 {} 秒", COUNTDOWN_SECONDS);
    }

    public void reset() {
        waitingPlayers.clear();
        invinciblePlayers.clear();
        countdownStarted = false;
        countdownTicks = COUNTDOWN_TICKS;
        forcedStart = false;
    }
}
