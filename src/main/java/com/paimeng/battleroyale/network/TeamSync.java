package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.team.MatchTeam;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.team.Team;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 队伍数据的 S2C 通道（跟着 {@link ZoneSync} 的套路走，只是触发方式不同）：
 * 毒圈是每 tick 变化的连续数据，要靠定时广播；队伍只在<b>创建 / 加入 / 离开</b>
 * 的瞬间变一次，所以这里没有任何 tick 逻辑，全部由 {@code TeamManager} 在
 * 状态变化的当场调用 {@link #syncTeam} / {@link #sendTo}（需求里的"立即同步一次"）。
 *
 * <p>包体格式（两边字段顺序必须一模一样）：
 * <pre>
 * [有无队伍 boolean]
 *   ├─ false：后面没有字段（空快照，客户端清缓存）
 *   └─ true ：teamId UUID | teamNumber VarInt | leaderId UUID | match Boolean | split Boolean
 *             + count VarInt
 *             + count × (uuid UUID | number VarInt | name String | split Boolean | status VarInt)
 *             （status：0=ALIVE / 1=DOWNED / 2=ELIMINATED，局内快照取自 DownedManager）
 * </pre>
 *
 * <p>只发装了本模组的客户端（{@link ServerPlayNetworking#canSend}），原版客户端不会被踢线。
 *
 * <p><b>升级预留</b>：只有 {@link #encode} / {@link #decode} 两个方法贴 1.18.2 的
 * FriendlyByteBuf API。1.20.5+ 改 CustomPayload 时，把本类拆成 payload 记录 +
 * streamCodec，收发调用点换成 ServerPlayNetworking.send(player, payload) 即可。
 */
public final class TeamSync {

    /** 通道名：battleroyale:team_sync。客户端用同一个名字注册接收者。 */
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "team_sync");

    /** 玩家名最长 16 字符（原版限制），读写都按这个上限防坏数据。 */
    private static final int MAX_NAME_LENGTH = 16;

    private TeamSync() {
    }

    // ------------------------------------------------------------------
    // 服务端调用
    // ------------------------------------------------------------------

    /** 给单个玩家发一份快照（数据变化时立即同步、离队者清屏都走这里）。 */
    public static void sendTo(ServerPlayer player, TeamSyncPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        // [BR-MATCHTEAM] 大厅快照永远压不掉局内快照：本局已分到 MatchTeam 的玩家，
        // 比赛期间只认 MatchTeamManager 发出去的 match 包（回主城后由 disband 补发大厅快照）。
        if (!payload.match() && MatchTeamManager.INSTANCE.isAssigned(player.getUUID())) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            ServerPlayNetworking.send(player, CHANNEL_ID, encode(payload));
        }
    }

    /** 把整个队伍的当前快照发给所有在线队员。 */
    public static void syncTeam(Team team, MinecraftServer server) {
        if (team == null || server == null) {
            return;
        }
        TeamSyncPayload payload = of(team);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.contains(player.getUUID())) {
                sendTo(player, payload);
            }
        }
    }

    /** Team（服务端大厅队对象）→ 网络快照；成员按加入顺序，编号恒 0（[BR-NOLOBBYNUMBER] 大厅无编号）。 */
    public static TeamSyncPayload of(Team team) {
        List<TeamSyncPayload.Member> members = new ArrayList<>(team.size());
        for (Team.Entry entry : team.membersInJoinOrder()) {
            // 大厅快照不携带队内编号；0 只表示“当前不是 MatchTeam 座号”。
            members.add(new TeamSyncPayload.Member(entry.playerId(), 0, entry.name(),
                    false, TeamSyncPayload.STATUS_ALIVE));
        }
        return new TeamSyncPayload(team.getTeamId(), team.getTeamNumber(), team.getLeaderId(),
                false, false, members);
    }

    /** [BR-MATCHTEAM] MatchTeam（本局临时队伍）→ 网络快照；成员编号即座号（1 号位起）。 */
    public static TeamSyncPayload ofMatch(MatchTeam team) {
        List<TeamSyncPayload.Member> members = new ArrayList<>(team.size());
        for (MatchTeam.Member member : team.membersSorted()) {
            members.add(new TeamSyncPayload.Member(member.playerId(), member.squadIndex(),
                    member.name(), member.splitOrigin(), statusOf(member.playerId())));
        }
        return new TeamSyncPayload(team.getMatchTeamId(), team.getTeamNumber(), team.getLeaderId(),
                true, team.isSplitFromPremade(), members);
    }

    /**
     * [BR-TEAMHUD] 成员的实时存活状态：统一走 MatchTeamManager（倒地取自 DownedManager，
     * 淘汰取自本局淘汰名册——DownedManager 的瞬时标记会在淘汰收尾被清掉，名册才是权威）。
     */
    private static int statusOf(UUID playerId) {
        return MatchTeamManager.INSTANCE.matchStatusOf(playerId);
    }

    /** [BR-MATCHTEAM] 把一支 MatchTeam 的快照发给全部在线队员（开局分配 / 重建时调用）。 */
    public static void syncMatchTeam(MatchTeam team, MinecraftServer server) {
        if (team == null || server == null) {
            return;
        }
        TeamSyncPayload payload = ofMatch(team);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.contains(player.getUUID())) {
                sendTo(player, payload);
            }
        }
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    /**
     * 快照 → 网络包。
     * [UPGRADE] 1.19+ 把 PacketByteBufs 换成 net.minecraft.network.PacketByteBufs。
     */
    private static FriendlyByteBuf encode(TeamSyncPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        if (payload.isEmpty()) {
            buf.writeBoolean(false);
            return buf;
        }
        buf.writeBoolean(true);
        buf.writeUUID(payload.teamId());
        buf.writeVarInt(payload.teamNumber());
        buf.writeUUID(payload.leaderId());
        buf.writeBoolean(payload.match()); // [BR-MATCHTEAM] 快照来源标记：true = 局内 MatchTeam
        buf.writeBoolean(payload.split()); // [BR-MATCHTEAM 第四步] true = 预组队被拆分过
        buf.writeVarInt(payload.members().size());
        for (TeamSyncPayload.Member member : payload.members()) {
            buf.writeUUID(member.playerId());
            buf.writeVarInt(member.number());
            buf.writeUtf(member.name(), MAX_NAME_LENGTH);
            buf.writeBoolean(member.split()); // [BR-MATCHTEAM 第四步] 该队员来自被拆分的预组队
            buf.writeVarInt(member.status()); // [BR-TEAMHUD] 存活状态：0 正常 / 1 倒地 / 2 淘汰
        }
        return buf;
    }

    /** 网络包 → 快照（客户端调用）。坏数据一律当"没有队伍"，不抛异常。 */
    public static TeamSyncPayload decode(FriendlyByteBuf buf) {
        try {
            if (!buf.readBoolean()) {
                return TeamSyncPayload.empty();
            }
            UUID teamId = buf.readUUID();
            int teamNumber = buf.readVarInt();
            UUID leaderId = buf.readUUID();
            boolean match = buf.readBoolean(); // [BR-MATCHTEAM] 与 encode 严格同序
            boolean split = buf.readBoolean(); // [BR-MATCHTEAM 第四步] 预组队拆分标记
            int count = buf.readVarInt();
            if (count < 0 || count > Team.MAX_MEMBERS) {
                return TeamSyncPayload.empty();
            }
            List<TeamSyncPayload.Member> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID playerId = buf.readUUID();
                int number = buf.readVarInt();
                String name = buf.readUtf(MAX_NAME_LENGTH);
                boolean memberSplit = buf.readBoolean(); // [BR-MATCHTEAM] 与 encode 严格同序
                int status = buf.readVarInt();            // [BR-TEAMHUD] 与 encode 严格同序
                if (status < TeamSyncPayload.STATUS_ALIVE || status > TeamSyncPayload.STATUS_ELIMINATED) {
                    status = TeamSyncPayload.STATUS_ALIVE; // 坏数据按正常显示，不炸 HUD
                }
                members.add(new TeamSyncPayload.Member(playerId, number, name, memberSplit, status));
            }
            return new TeamSyncPayload(teamId, teamNumber, leaderId, match, split, members);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("[{}] 队伍同步包解析失败，按无队伍处理: {}",
                    BattleRoyaleMod.MOD_ID, e.getMessage());
            return TeamSyncPayload.empty();
        }
    }
}
