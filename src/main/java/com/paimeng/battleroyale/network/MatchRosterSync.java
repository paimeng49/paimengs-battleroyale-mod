package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
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
 * [BR-NAMETAG] 本局花名册 S2C 通道（battleroyale:match_roster）。
 *
 * <p>发送时机：开局分配完成（PREPARING 切入瞬间）广播一次；比赛期间每 200 tick
 * 重播一份兜底（覆盖中途重连进服的客户端，最多 10 秒内补齐）；
 * 本局销毁（disband）广播空包把所有人清干净。
 *
 * <p>包体格式（两边字段顺序必须一模一样）：
 * <pre>
 * [active boolean]
 *   └─ true：count VarInt + count × (uuid UUID | seat VarInt)
 * </pre>
 *
 * <p>只发装了本模组的客户端（{@link ServerPlayNetworking#canSend}）。
 * 量级估算：100 人 × 20 字节 ≈ 2KB/包，10 秒一份，可以忽略不计。
 */
public final class MatchRosterSync {

    /** 通道名：battleroyale:match_roster。客户端用同一个名字注册接收者。 */
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "match_roster");

    /** 花名册条目上限（含全部参赛队）：防坏数据把客户端撑爆。 */
    private static final int MAX_ENTRIES = 512;

    private MatchRosterSync() {
    }

    /** 广播给所有在线的模组装客户端（原版客户端不发包、不受影响）。 */
    public static void broadcast(MinecraftServer server, MatchRosterPayload payload) {
        if (server == null || payload == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, payload);
        }
    }

    /** 单发（重连补包场景留口，目前周期重播已覆盖）。 */
    public static void sendTo(ServerPlayer player, MatchRosterPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            ServerPlayNetworking.send(player, CHANNEL_ID, encode(payload));
        }
    }

    private static FriendlyByteBuf encode(MatchRosterPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(payload.active());
        if (!payload.active()) {
            return buf;
        }
        buf.writeVarInt(payload.entries().size());
        for (MatchRosterPayload.Entry entry : payload.entries()) {
            buf.writeUUID(entry.playerId());
            buf.writeVarInt(entry.seat());
        }
        return buf;
    }

    /** 网络包 → 快照（客户端调用）。坏数据一律当「没有花名册」，不抛异常。 */
    public static MatchRosterPayload decode(FriendlyByteBuf buf) {
        try {
            if (!buf.readBoolean()) {
                return MatchRosterPayload.empty();
            }
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) {
                return MatchRosterPayload.empty();
            }
            List<MatchRosterPayload.Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID playerId = buf.readUUID();
                int seat = buf.readVarInt();
                if (seat > 0) {
                    entries.add(new MatchRosterPayload.Entry(playerId, seat));
                }
            }
            return new MatchRosterPayload(!entries.isEmpty(), entries);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("[{}] 花名册同步包解析失败，按无花名册处理: {}",
                    BattleRoyaleMod.MOD_ID, e.getMessage());
            return MatchRosterPayload.empty();
        }
    }
}
