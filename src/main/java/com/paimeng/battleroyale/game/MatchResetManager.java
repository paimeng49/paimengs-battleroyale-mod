package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.GameStateChangedEvent;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 地图重置生命周期：结算展示结束后卸载维度、异步删除旧区块并显示进度。 */
public final class MatchResetManager {

    public static final MatchResetManager INSTANCE = new MatchResetManager();
    public static final int RESET_DELAY_TICKS = 5 * GameRules.TICKS_PER_SECOND;
    private static final int DELETE_BATCH_SIZE = 10;
    private static final int COMPLETE_DISPLAY_TICKS = 3 * GameRules.TICKS_PER_SECOND;

    private int ticksLeft = -1;
    private int completeTicksLeft = -1;
    private boolean resetStarted;
    private ServerBossEvent bossBar;

    private MatchResetManager() {
    }

    public void onGameStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.ENDED) {
            ticksLeft = RESET_DELAY_TICKS;
            resetStarted = false;
        } else if (event.to() == GamePhase.RESETTING) {
            ticksLeft = -1;
            startReset(event.server());
        } else if (event.to() == GamePhase.WAITING) {
            ticksLeft = -1;
            completeTicksLeft = -1;
            resetStarted = false;
            removeBossBar();
        } else if (event.to() == GamePhase.PREPARING || event.to() == GamePhase.FLIGHT) {
            ticksLeft = -1;
        }
    }

    public void tick(MinecraftServer server) {
        if (bossBar != null && GameManager.INSTANCE.getPhase() == GamePhase.RESETTING) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                bossBar.addPlayer(player);
            }
        }
        if (ticksLeft >= 0) {
            if (ticksLeft > 0) {
                ticksLeft--;
            } else {
                ticksLeft = -1;
                if (GameManager.INSTANCE.getPhase() == GamePhase.ENDED) {
                    GameManager.INSTANCE.beginReset(server);
                }
            }
        }
        if (completeTicksLeft >= 0) {
            if (completeTicksLeft > 0) {
                completeTicksLeft--;
            } else {
                completeTicksLeft = -1;
                removeBossBar();
                GameManager.INSTANCE.finishReset(server);
            }
        }
    }

    private void startReset(MinecraftServer server) {
        if (server == null || resetStarted) {
            return;
        }
        resetStarted = true;
        createBossBar(server);
        BattleRoyaleMod.LOGGER.info("[BR-RESET] 开始卸载游戏维度并准备异步删除 .mca 文件");

        try {
            List<ServerPlayer> snapshot = new ArrayList<>(server.getPlayerList().getPlayers());
            for (ServerPlayer player : snapshot) {
                if (GameWorldManager.isGameWorld(player.level().dimension())) {
                    String error = GameWorldManager.leave(player);
                    if (error != null) {
                        BattleRoyaleMod.LOGGER.warn("[BR-RESET] 玩家 {} 送回主世界未完成：{}",
                                player.getGameProfile().getName(), error);
                    }
                }
            }
            InventoryIsolation.INSTANCE.sweepOnlineOutsideGameWorld(server);
            WarmupManager.INSTANCE.cancelCountdown(server);
            WarmupManager.INSTANCE.reset();
            FlightManager.INSTANCE.endMatch(server);
            DownedManager.INSTANCE.reset(server);
            MatchTeamManager.INSTANCE.disband(server);
            GameWorldManager.unloadMatchWorlds(server);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[BR-RESET] 卸载游戏维度失败", t);
            finishResetWithError(server);
            return;
        }

        List<Path> files = GameWorldManager.collectMatchMcaFiles(server);
        if (files.isEmpty()) {
            completeReset(server, 0, 0);
            return;
        }
        int total = files.size();
        AtomicInteger deleted = new AtomicInteger();
        GameWorldManager.runWorldIo(() -> {
            for (int start = 0; start < files.size(); start += DELETE_BATCH_SIZE) {
                int end = Math.min(start + DELETE_BATCH_SIZE, files.size());
                for (int i = start; i < end; i++) {
                    try {
                        Files.deleteIfExists(files.get(i));
                    } catch (IOException e) {
                        BattleRoyaleMod.LOGGER.warn("[BR-RESET] 删除文件失败：{}，{}", files.get(i), e.toString());
                    }
                    deleted.incrementAndGet();
                }
                int count = deleted.get();
                server.execute(() -> updateProgress(server, count, total));
            }
            server.execute(() -> completeReset(server, deleted.get(), total));
        });
    }

    private void createBossBar(MinecraftServer server) {
        bossBar = new ServerBossEvent(Component.literal("正在重置游戏地图... (0%)"),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setProgress(0.0F);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private void updateProgress(MinecraftServer server, int deleted, int total) {
        if (bossBar == null) {
            return;
        }
        int percent = total <= 0 ? 100 : Math.min(100, deleted * 100 / total);
        bossBar.setProgress(Math.max(0.0F, Math.min(1.0F, deleted / (float) Math.max(1, total))));
        bossBar.setName(Component.literal("正在重置游戏地图... (" + percent + "%)"));
    }

    private void completeReset(MinecraftServer server, int deleted, int total) {
        updateProgress(server, total, total);
        if (bossBar != null) {
            bossBar.setProgress(1.0F);
            bossBar.setName(Component.literal("地图重置完成，可以开始下一局！"));
        }
        completeTicksLeft = COMPLETE_DISPLAY_TICKS;
        BattleRoyaleMod.LOGGER.info("[BR-RESET] 地图文件清理完成：删除 {}/{} 个 .mca 文件", deleted, total);
    }

    private void finishResetWithError(MinecraftServer server) {
        if (bossBar != null) {
            bossBar.setName(Component.literal("地图重置失败，请查看服务器日志。"));
        }
        completeTicksLeft = COMPLETE_DISPLAY_TICKS;
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
    }

    /** 关服前调用，立即清理 BossBar 引用。 */
    public void shutdown() {
        removeBossBar();
        ticksLeft = -1;
        completeTicksLeft = -1;
    }
}
