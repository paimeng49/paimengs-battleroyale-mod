package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.GameStateChangedEvent;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.match.MapQueue;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-GAMEMODE] 模块一：游戏模式状态机（纯服务端，主线程）。
 *
 * <p>需求（三条全覆盖）：
 * <ol>
 *   <li><b>记录与恢复</b>：参赛者进入 {@code battleroyale_game} 维度时记下他当前的
 *       游戏模式；离开（回大厅 / 主世界，含结算与强停的传送）时原样恢复。
 *       管理员 / 地图编辑者不是参赛者，模式完全不动；</li>
 *   <li><b>局内切换</b>：等待区（WAITING/PREPARING）→ 冒险；航线（FLIGHT）与
 *       正式局（RUNNING）→ 生存；被淘汰（ELIMINATED）→ 旁观；</li>
 *   <li><b>掉线兜底</b>：重连落回游戏维度时——对局进行中且本局已淘汰 → 保持/恢复
 *       旁观；旁观状态掉线的重连后依然是旁观；存活参赛者按当前阶段重置模式。</li>
 * </ol>
 *
 * <p>旁观者保护：模式已经是旁观的玩家（被淘汰、手动旁观）在任何阶段切换里都
 * 不会被拽回生存/冒险——旁观状态一直保留到离开维度。
 */
public final class GameModeManager {

    public static final GameModeManager INSTANCE = new GameModeManager();

    /** UUID → 进入游戏维度前的游戏模式。只记第一次（putIfAbsent），恢复后删除。 */
    private final Map<UUID, GameType> originalModes = new ConcurrentHashMap<>();

    private GameModeManager() {
    }

    /**
     * GameWorldManager 成功把玩家传送进游戏维度后调用：
     * 记录原模式（仅参赛者）并按当前阶段设置局内模式。
     */
    public void onEnterGameWorld(ServerPlayer player) {
        if (!isParticipant(player)) {
            return; // 管理员 / 观战者：不记也不改
        }
        remember(player);
        applyPhaseMode(player);
    }

    /** GameWorldManager.leave / 关服送出时调用：恢复进入维度前的模式。 */
    public void onLeaveGameWorld(ServerPlayer player) {
        GameType origin = originalModes.remove(player.getUUID());
        if (origin != null) {
            setMode(player, origin);
        } else if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            // 记录丢失（如服务器重启）时旁观状态绝不能带出游戏维度，兜底生存。
            setMode(player, GameType.SURVIVAL);
        }
    }

    /**
     * 阶段切换：游戏维度里的所有参赛者按新阶段换模式。
     * ENDED（结算/强停）不动模式——等 5 秒安全重置统一恢复；
     * WAITING 时名单已清空，participant 判定自然为 false，不会误伤。
     */
    public void onPhaseChanged(GameStateChangedEvent event) {
        MinecraftServer server = event.server();
        if (server == null || event.to() == GamePhase.ENDED) {
            return;
        }
        int scanned = 0;
        int applied = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!GameWorldManager.isGameWorld(player.level().dimension()) || !isParticipant(player)) {
                continue;
            }
            scanned++;
            // 只有名册明确标记为 ELIMINATED 才能保持旁观；手动切旁观不能绕过开局模式下发。
            if (MatchTeamManager.INSTANCE.wasEliminatedThisMatch(player.getUUID())) {
                continue;
            }
            try {
                applyPhaseMode(player);
                applied++;
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("[BR-GAMEMODE] 玩家 {} 阶段切换改模式失败：{}",
                        player.getGameProfile().getName(), t.toString());
            }
        }
        // [BR-GAMEMODE-DEBUG] 排查用：确认每次阶段切换到底遍历了几个参赛者、成功执行了几个。
        BattleRoyaleMod.LOGGER.info("[BR-GAMEMODE] 阶段切换 {} -> {}：游戏维度参赛者遍历 {} 人，模式已下发 {} 人",
                event.from().name(), event.to().name(), scanned, applied);
    }

    /** 被淘汰 → 旁观（DownedManager.eliminate 在收尾前调用）。 */
    public void onEliminated(ServerPlayer victim) {
        remember(victim);
        setMode(victim, GameType.SPECTATOR);
    }

    /**
     * [BR-RESPAWN-SPEC] 原版死亡重生后强制保持旁观；不重新 remember，避免把已经
     * 切成 SPECTATOR 的当前模式误记成“进入前模式”，导致结算回大厅后恢复失败。
     */
    public void forceSpectatorAfterRespawn(ServerPlayer player) {
        if (player != null) {
            setMode(player, GameType.SPECTATOR);
        }
    }

    /**
     * 掉线重连落回游戏维度时调用（handleGameWorldRejoin 的两条分支都用它）：
     * 本局已淘汰 → 保持/恢复旁观；本来就在旁观（手动观战掉线）→ 依然是旁观；
     * 存活参赛者 → 按当前阶段重置模式。
     */
    public void onReconnect(ServerPlayer player) {
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        if (MatchTeamManager.INSTANCE.wasEliminatedThisMatch(player.getUUID())) {
            setMode(player, GameType.SPECTATOR);
            return;
        }
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return; // 旁观掉线重连：不动
        }
        if (!isParticipant(player)) {
            return;
        }
        remember(player);
        applyPhaseMode(player);
    }

    /**
     * 重连（服务器仍活、玩家曾离线）兜底：离线期间本局已结束、安全重置没能带上他
     * （离线玩家不在在线列表里），磁盘上的模式可能停在旁观 / 生存——
     * 只要落点已经不在游戏维度且还有未消费的记录，当场恢复。
     * 落在游戏维度里的（对局仍进行中）不在这里处理：由 onReconnect / 送回大厅路径消费记录。
     */
    public void restoreIfStale(ServerPlayer player) {
        if (GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        GameType origin = originalModes.remove(player.getUUID());
        if (origin != null) {
            setMode(player, origin);
        } else if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            setMode(player, GameType.SURVIVAL);
        }
    }

    /** 兜底清理（关服 / 本局彻底结束后可选调用，防止 Map 残留）。 */
    public void forget(UUID playerId) {
        originalModes.remove(playerId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void applyPhaseMode(ServerPlayer player) {
        GamePhase phase = GameManager.INSTANCE.getPhase();
        switch (phase) {
            case WAITING, PREPARING -> setMode(player, GameType.ADVENTURE);
            case FLIGHT, RUNNING -> setMode(player, GameType.SURVIVAL);
            default -> { /* ENDED：保持现状，等安全重置统一恢复 */ }
        }
    }

    /**
     * 切换玩家游戏模式的唯一正确姿势（原版 {@code /gamemode} 命令同款路径）：
     * <ol>
     *   <li>{@link ServerPlayer#setGameMode(GameType)}：改服务端字段的<b>同时</b>向该玩家
     *       客户端发 {@code CHANGE_GAME_MODE} 事件包 + abilities 包 + tab 列表更新，
     *       客户端真正切换操作权限（对应 Yarn 的 {@code ServerPlayerEntity#setGameMode}）；</li>
     *   <li>服务端字段已是目标但客户端可能仍是旧模式（历史版本误用
     *       {@code gameMode.changeGameModeForPlayer}——它<b>从不发送</b>本地
     *       CHANGE_GAME_MODE 包，是"服务端冒险、客户端创造"的根因）：
     *       无条件补发一次事件包，强制客户端收敛。</li>
     * </ol>
     */
    private void setMode(ServerPlayer player, GameType target) {
        GameType current = player.gameMode.getGameModeForPlayer();
        if (current == target) {
            player.connection.send(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.CHANGE_GAME_MODE, (float) target.getId()));
            BattleRoyaleMod.LOGGER.debug("[BR-GAMEMODE] {}：服务端已是 {}，向客户端补发 CHANGE_GAME_MODE 强制收敛",
                    player.getGameProfile().getName(), target.getName());
            return;
        }
        boolean synced = player.setGameMode(target);
        BattleRoyaleMod.LOGGER.debug("[BR-GAMEMODE] {}：{} -> {}（ServerPlayer#setGameMode 返回 {}）",
                player.getGameProfile().getName(), current.getName(), target.getName(), synced);
    }

    /** 进维度改模式前记下"改之前的模式"——第一次记录，之后重复进维度不覆盖。 */
    private void remember(ServerPlayer player) {
        originalModes.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
    }

    /** 参赛者 = 本局名单 / 本局已分配队伍 / 等待队列，三者其一。管理员不算。 */
    private boolean isParticipant(ServerPlayer player) {
        return MatchTeamManager.INSTANCE.isAssigned(player.getUUID())
                || GameManager.INSTANCE.isJoined(player.getGameProfile().getName())
                || MapQueue.INSTANCE.waitingMapOf(player.getUUID()) != null;
    }
}
