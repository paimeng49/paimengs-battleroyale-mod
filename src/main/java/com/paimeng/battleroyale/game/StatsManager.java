package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.network.StatsSync;
import com.paimeng.battleroyale.network.StatsSyncPayload;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端权威维护本局存活人数和击杀数。 */
public final class StatsManager {
    public static final StatsManager INSTANCE = new StatsManager();

    private final Set<UUID> alivePlayers = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();

    private StatsManager() {
    }

    public void start(MinecraftServer server) {
        alivePlayers.clear();
        kills.clear();
        // [BR-MATCHTEAM] 存活名单以本局 MatchTeam 参赛者为准；无 MatchTeam（旧流程 / 兜底）
        // 时退回报名名单，保证开局统计不会因为分配缺失而漏人。
        java.util.Set<java.util.UUID> assigned = MatchTeamManager.INSTANCE.assignedPlayerIds();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean participant = !assigned.isEmpty()
                    ? assigned.contains(player.getUUID())
                    : GameManager.INSTANCE.isJoined(player.getGameProfile().getName());
            if (participant) {
                alivePlayers.add(player.getUUID());
                kills.put(player.getUUID(), 0);
            }
        }
        sync(server);
    }

    public void clear(MinecraftServer server) {
        alivePlayers.clear();
        kills.clear();
        sync(server);
    }

    public void sendTo(ServerPlayer player) {
        StatsSync.sendTo(player, new StatsSyncPayload(
                alivePlayers.size(), MatchTeamManager.INSTANCE.aliveTeamCount(),
                kills.getOrDefault(player.getUUID(), 0)));
    }

    /** 兼容旧入口：正式淘汰统一由 DownedManager 调用。 */
    public void die(ServerPlayer dead, DamageSource source) {
        onEliminated(dead, source);
    }

    public void onEliminated(ServerPlayer dead, DamageSource source) {
        if (!alivePlayers.remove(dead.getUUID())) {
            return;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer killer && alivePlayers.contains(killer.getUUID())) {
            kills.merge(killer.getUUID(), 1, Integer::sum);
        }
        sync(dead.server);
    }

    private void sync(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // [BR-MATCHTEAM 模块四] 存活队伍数基于 MatchTeam 编制 + 淘汰名单实时统计。
        int remainingTeams = MatchTeamManager.INSTANCE.aliveTeamCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            StatsSync.sendTo(player, new StatsSyncPayload(
                    alivePlayers.size(), remainingTeams, kills.getOrDefault(player.getUUID(), 0)));
        }
    }
}
