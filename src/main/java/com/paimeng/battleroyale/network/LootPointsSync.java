package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 物资点列表 S2C 同步。
 *
 * <p>服务端只在玩家进入游戏维度时发一次（{@link com.paimeng.battleroyale.map.GameWorldManager#enter}），
 * 不按帧拉数据。客户端收到后缓存到 {@link com.paimeng.battleroyale.client.ClientLootPointsState}，
 * HUD 和全屏地图每帧只读本地缓存。
 */
public final class LootPointsSync {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "loot_points");

    private LootPointsSync() {
    }

    public static void sendTo(ServerPlayer player, GameMap map) {
        if (player == null || map == null || !ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            return;
        }
        ServerPlayNetworking.send(player, CHANNEL_ID, encode(map));
    }

    /** 物资点在服务端被改动后：把最新列表推给该地图游戏维度里的所有玩家（HUD / 全屏地图即时刷新）。 */
    public static void broadcastToDimension(MinecraftServer server, GameMap map) {
        if (server == null || map == null || map.mapId == null) {
            return;
        }
        try {
            ServerLevel world = server.getLevel(GameWorldManager.worldKey(map.mapId));
            if (world == null) {
                return;
            }
            for (ServerPlayer player : world.getPlayers(p -> true)) {
                sendTo(player, map);
            }
        } catch (Throwable ignored) {
        }
    }

    private static FriendlyByteBuf encode(GameMap map) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(map.mapId == null ? "" : map.mapId, 64);
        List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
        int count = Math.min(points.size(), 512);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            LootPoint point = points.get(i);
            buf.writeDouble(point.x);
            buf.writeDouble(point.z);
            buf.writeUtf(point.name == null ? "物资点" : point.name, 64);
            buf.writeVarInt(Math.max(1, point.fontSize));
        }
        return buf;
    }

    public static Payload decode(FriendlyByteBuf buf) {
        String mapId = buf.readUtf(64);
        int count = Math.min(Math.max(0, buf.readVarInt()), 512);
        List<LootPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = buf.readDouble();
            double z = buf.readDouble();
            String name = buf.readUtf(64);
            int fontSize = Math.max(1, buf.readVarInt());
            points.add(new LootPoint(x, z, name, fontSize));
        }
        return new Payload(mapId, points);
    }

    public record Payload(String mapId, List<LootPoint> points) {
    }
}
