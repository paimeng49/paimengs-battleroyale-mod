package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.team.PingType;
import com.paimeng.battleroyale.team.Team;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * <b>标点系统的网络通道</b>：两个方向各一条。
 *
 * <ul>
 *   <li>{@code battleroyale:ping}（C2S，客户端 V 键 → 服务端）：
 *       带"准星命中位置 + 标点类型"。标点<b>没有自定义文字</b>——消息文案由服务端
 *       按 {@link PingType} 写死（单击 = "我标记了一处地点"，双击 = "发现敌人"），
 *       所以包里也不传文本，改包塞不进任何东西。坐标是客户端算的，服务端在
 *       {@code team.PingService} 里做冷却和 128 格距离校验后<b>才生效</b>。</li>
 *   <li>{@code battleroyale:ping_show}（S2C，服务端 → 全体在线队友，含发起者本人）：
 *       收到的人在本方世界坐标处画发光标记（见 {@code client.PingRenderer}）。
 *       包里没有存活秒数：<b>生命周期由客户端按"类型 + 是不是自己发的"决定</b>——
 *       ENEMY 世界渲染 10 秒；自己的 WAYPOINT 永久；队友的 WAYPOINT 世界渲染 10 秒
 *       但小地图标记永久（见 {@code client.ClientPingState}）。</li>
 * </ul>
 *
 * <p>包体格式（两边字段顺序必须一模一样）：
 * <pre>
 * ping（C2S）     ：x double | y double | z double | 类型 VarInt（0=WAYPOINT 1=ENEMY）
 * ping_show（S2C）：x double | y double | z double | 类型 VarInt | 发起者队内编号 VarInt
 *                   | 发起者 UUID（两个 long）| 发起者名字 String（上限 16）
 * </pre>
 *
 * <p><b>升级预留</b>：同 {@link TeamSync}，换 1.20.5+ 的 CustomPayload 时只动
 * encode/decode 两族方法。
 */
public final class PingSync {

    /** C2S 通道名：客户端 V 键发起标点（单击 WAYPOINT / 双击 ENEMY）。 */
    public static final ResourceLocation PING_CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "ping");

    /** S2C 通道名：服务端把合法的标点广播给队友。 */
    public static final ResourceLocation SHOW_CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "ping_show");

    /** 玩家名最长 16 字符（原版限制）。 */
    private static final int MAX_NAME_LENGTH = 16;

    private PingSync() {
    }

    /** S2C 包里的一份标点数据（解码结果，不可变）。 */
    public record Show(double x, double y, double z, PingType type,
                       int memberNumber, UUID initiatorUuid, String initiatorName) {
    }

    /** C2S 请求的解析结果。 */
    public record PingRequest(double x, double y, double z, PingType type) {
    }

    // ------------------------------------------------------------------
    // 客户端 → 服务端
    // ------------------------------------------------------------------

    /** 客户端 → 服务端的一条标点请求包（{@code PingClient} 发送用）。 */
    public static FriendlyByteBuf encodePingRequest(double x, double y, double z, PingType type) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(type.id());
        return buf;
    }

    /** 读一条 C2S 标点请求；坐标 NaN / 类型 id 未知都直接抛异常，由调用方拦截丢包。 */
    public static PingRequest decodePingRequest(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        PingType type = PingType.fromId(buf.readVarInt());
        if (type == null) {
            throw new IllegalArgumentException("unknown ping type id");
        }
        return new PingRequest(x, y, z, type);
    }

    // ------------------------------------------------------------------
    // 服务端 → 客户端
    // ------------------------------------------------------------------

    /** 发给单个玩家（没队伍时只发发起者本人的那条路径用）。 */
    public static void sendShowTo(ServerPlayer player, double x, double y, double z,
                                  PingType type, int memberNumber, UUID initiatorUuid, String initiatorName) {
        if (player == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, SHOW_CHANNEL_ID)) {
            ServerPlayNetworking.send(player, SHOW_CHANNEL_ID,
                    encodeShow(x, y, z, type, memberNumber, initiatorUuid, initiatorName));
        }
    }

    /** 发给全队（发起者自己也在队里，所以他也收得到）。 */
    public static void sendShowToTeam(Team team, MinecraftServer server, double x, double y, double z,
                                      PingType type, int memberNumber, UUID initiatorUuid, String initiatorName) {
        if (team == null || server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.contains(player.getUUID()) && ServerPlayNetworking.canSend(player, SHOW_CHANNEL_ID)) {
                // 每个玩家独立一份 ByteBuf，避免复用/引用计数问题。
                ServerPlayNetworking.send(player, SHOW_CHANNEL_ID,
                        encodeShow(x, y, z, type, memberNumber, initiatorUuid, initiatorName));
            }
        }
    }

    /**
     * [BR-MATCHTEAM 模块四] 发给一组成员（标点按本局 MatchTeam 座次表广播时走这里；
     * 大厅 / 未开局由 MatchTeamManager 回退成大厅队成员列表，语义与上面一致）。
     */
    public static void sendShowToMembers(java.util.Collection<UUID> memberIds, MinecraftServer server,
                                         double x, double y, double z,
                                         PingType type, int memberNumber, UUID initiatorUuid, String initiatorName) {
        if (memberIds == null || memberIds.isEmpty() || server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!memberIds.contains(player.getUUID()) || !ServerPlayNetworking.canSend(player, SHOW_CHANNEL_ID)) {
                continue;
            }
            // 每个玩家独立一份 ByteBuf，避免复用/引用计数问题。
            ServerPlayNetworking.send(player, SHOW_CHANNEL_ID,
                    encodeShow(x, y, z, type, memberNumber, initiatorUuid, initiatorName));
        }
    }

    /**
     * S2C 包编码。
     * [UPGRADE] 1.19+ PacketByteBufs 换到 net.minecraft.network 包。
     */
    private static FriendlyByteBuf encodeShow(double x, double y, double z, PingType type,
                                            int memberNumber, UUID initiatorUuid, String initiatorName) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(type.id());
        buf.writeVarInt(memberNumber);
        buf.writeLong(initiatorUuid.getMostSignificantBits());
        buf.writeLong(initiatorUuid.getLeastSignificantBits());
        buf.writeUtf(initiatorName, MAX_NAME_LENGTH);
        return buf;
    }

    /** S2C 包解码（客户端收包线程调用）。脏数据返回 null，调用方跳过即可。 */
    public static Show decodeShow(FriendlyByteBuf buf) {
        try {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            PingType type = PingType.fromId(buf.readVarInt());
            if (type == null) {
                return null;
            }
            int memberNumber = buf.readVarInt();
            UUID initiatorUuid = new UUID(buf.readLong(), buf.readLong());
            String name = buf.readUtf(MAX_NAME_LENGTH);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return null;
            }
            return new Show(x, y, z, type, memberNumber, initiatorUuid, name);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("[{}] 标点同步包解析失败，忽略: {}", BattleRoyaleMod.MOD_ID, e.getMessage());
            return null;
        }
    }
}
