package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.game.GameManager;
import com.paimeng.battleroyale.game.GamePhase;
import com.paimeng.battleroyale.team.MatchTeam;
import com.paimeng.battleroyale.team.MatchTeamManager;
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
 * [BR-MINIMAP] 队友坐标 S2C 通道（battleroyale:teammate_pos）。
 *
 * <p>挂在 {@code ServerTickEvents.END_SERVER_TICK} 上的 {@link #tick} 每 5 tick
 * （0.25 秒）给<b>每支 MatchTeam 的每名在线成员</b>定向发一份本队全员快照
 * （含观战中的已淘汰成员，需求：观战时小地图继续显示存活队友图标）。
 * 只在 FLIGHT / RUNNING 阶段发送；阶段离开后补发一次空包把客户端清干净，
 * 之后静默零开销（未开局时不遍历不发包）。
 *
 * <p>量级：≤4 人/队 × ~40B/人 ≈ 每队 160B，每 0.25 秒一份，只发给本队——
 * 全场 25 队也不过每秒 ~16KB，可忽略。客户端按 250ms 线性插值平滑移动。
 *
 * <p>包体格式（两边字段顺序必须一模一样）：
 * <pre>
 * count VarInt + count × (uuid UUID | seat VarInt | x double | z double | status VarInt)
 * </pre>
 */
public final class TeammatePosSync {

    /** 通道名：battleroyale:teammate_pos。 */
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "teammate_pos");

    /** 发送间隔（tick）：0.25 秒，客户端侧线性插值补足平滑。 */
    private static final int SEND_INTERVAL_TICKS = 5;

    /** 心跳日志间隔（tick）：每 5 秒确认一次“包还在发”（实机排障用，[r143]）。 */
    private static final int HEARTBEAT_LOG_TICKS = 100;

    /** 服务端 tick 计数（主线程独占，无需同步）。 */
    private static int tickCounter = 0;

    /** 上一 tick 是否处于“该发坐标”的阶段：离开时补发一次空包收尾。 */
    private static boolean wasSending = false;

    // [r143] 排障计数：本轮发送期内累计发出的包数 / 条目数（阶段切换时归零）。
    private static long sentPackets = 0;
    private static long sentEntries = 0;

    private TeammatePosSync() {
    }

    /** 由主类挂到 {@code ServerTickEvents.END_SERVER_TICK}。 */
    public static void tick(MinecraftServer server) {
        if (server == null || !MatchTeamManager.INSTANCE.isActive()) {
            if (wasSending) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP] 队友坐标同步停止（队伍组已解散），"
                        + "本轮共发 {} 包 / {} 条坐标", sentPackets, sentEntries);
            }
            wasSending = false;
            return;
        }
        GamePhase phase = GameManager.INSTANCE.getPhase();
        boolean sending = phase == GamePhase.FLIGHT || phase == GamePhase.RUNNING;
        if (!sending) {
            if (wasSending) {
                BattleRoyaleMod.LOGGER.info("[BR-MINIMAP] 队友坐标同步停止（阶段={}），"
                        + "本轮共发 {} 包 / {} 条坐标；已补发清屏空包",
                        phase, sentPackets, sentEntries);
                broadcastClear(server);
            }
            wasSending = false;
            return;
        }
        if (!wasSending) {
            wasSending = true;
            sentPackets = 0;
            sentEntries = 0;
            int teamCount = MatchTeamManager.INSTANCE.teams().size();
            int playerCount = MatchTeamManager.INSTANCE.assignedPlayerIds().size();
            BattleRoyaleMod.LOGGER.info("[BR-MINIMAP] 队友坐标同步开始：阶段={}，队伍 {} 支 / 参赛 {} 人，"
                    + "每 {} tick 给每队全员定向发本队坐标快照",
                    phase, teamCount, playerCount, SEND_INTERVAL_TICKS);
        }
        tickCounter++;
        if (tickCounter % SEND_INTERVAL_TICKS != 0) {
            return;
        }
        for (MatchTeam team : MatchTeamManager.INSTANCE.teams()) {
            sendToTeam(team, server);
        }
        // [r143] 心跳：每 5 秒一行，实机看日志就能确认“服务端到底有没有在发”。
        if (tickCounter % HEARTBEAT_LOG_TICKS == 0) {
            BattleRoyaleMod.LOGGER.info("[BR-MINIMAP] 发包心跳：阶段={}，累计 {} 包 / {} 条坐标，"
                    + "队伍 {} 支", phase, sentPackets, sentEntries,
                    MatchTeamManager.INSTANCE.teams().size());
        }
    }

    /** 给一支队伍的全部在线成员（含观战者）发本队坐标快照。 */
    private static void sendToTeam(MatchTeam team, MinecraftServer server) {
        List<TeammatePosPayload.Entry> entries = new ArrayList<>(team.size());
        for (MatchTeam.Member member : team.membersSorted()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member.playerId());
            if (player == null) {
                continue; // 掉线成员不占图标（重连即回大厅，不会再进本局）
            }
            // [r-LEAVE] 维度硬过滤：br leave 判阵亡 / 断线补判后人已回主世界，
            // 但还挂在 MatchTeam 编制里——不过滤就会把大厅坐标当"幽灵队友"发给全队。
            // 局内正常淘汰的旁观者仍在游戏维度，照常进包（客户端只画存活/倒地图标）。
            if (!com.paimeng.battleroyale.map.GameWorldManager.isGameWorld(player.level().dimension())) {
                continue;
            }
            entries.add(new TeammatePosPayload.Entry(member.playerId(), member.squadIndex(),
                    player.getX(), player.getZ(),
                    MatchTeamManager.INSTANCE.matchStatusOf(member.playerId())));
        }
        sentPackets++;
        sentEntries += entries.size();
        TeammatePosPayload payload = new TeammatePosPayload(entries);
        for (MatchTeam.Member member : team.membersSorted()) {
            sendTo(server.getPlayerList().getPlayer(member.playerId()), payload);
        }
    }

    /** 空包 = 清掉客户端的坐标缓存（大厅不需要队友图标）。 */
    public static void sendTo(ServerPlayer player, TeammatePosPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            ServerPlayNetworking.send(player, CHANNEL_ID, encode(payload));
        }
    }

    /** 本局销毁 / 阶段离开时定向清屏（发给全部本队参赛者，含已回大厅的原成员）。 */
    public static void broadcastClear(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, TeammatePosPayload.empty());
        }
    }

    private static FriendlyByteBuf encode(TeammatePosPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(payload.entries().size());
        for (TeammatePosPayload.Entry entry : payload.entries()) {
            buf.writeUUID(entry.playerId());
            buf.writeVarInt(entry.seat());
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.z());
            buf.writeVarInt(entry.status());
        }
        return buf;
    }

    /** 网络包 → 快照（客户端调用）。坏数据一律当空包（清缓存），不抛异常。 */
    public static TeammatePosPayload decode(FriendlyByteBuf buf) {
        try {
            int count = buf.readVarInt();
            if (count < 0 || count > 16) {
                return TeammatePosPayload.empty();
            }
            List<TeammatePosPayload.Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID playerId = buf.readUUID();
                int seat = buf.readVarInt();
                double x = buf.readDouble();
                double z = buf.readDouble();
                int status = buf.readVarInt();
                if (status < TeamSyncPayload.STATUS_ALIVE || status > TeamSyncPayload.STATUS_ELIMINATED) {
                    status = TeamSyncPayload.STATUS_ALIVE;
                }
                entries.add(new TeammatePosPayload.Entry(playerId, seat, x, z, status));
            }
            return new TeammatePosPayload(entries);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("[{}] 队友坐标包解析失败，按空包处理: {}",
                    BattleRoyaleMod.MOD_ID, e.getMessage());
            return TeammatePosPayload.empty();
        }
    }
}
