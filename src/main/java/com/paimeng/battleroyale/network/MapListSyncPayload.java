package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.LootPoint;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 地图管理 Tab 使用的简要地图列表包。 */
public final class MapListSyncPayload {
    private MapListSyncPayload() {}

    public static void write(FriendlyByteBuf buf, List<GameMap> maps) {
        buf.writeVarInt(Math.min(maps.size(), 512));
        for (GameMap map : maps) {
            buf.writeUtf(map.mapId == null ? "" : map.mapId, 64);
            buf.writeUtf(map.displayName == null ? "地图" : map.displayName, 128);
            buf.writeUtf(map.coverImagePath == null ? "" : map.coverImagePath, 256);
            buf.writeInt(map.minX);
            buf.writeInt(map.minZ);
            buf.writeInt(map.maxX);
            buf.writeInt(map.maxZ);
            // 详情面板需要「源存档路径」和「物资点」，这两项必须来自服务端真实内存对象。
            // 早先这里只发了 4 个字段，客户端拿到的是空壳：GUI 显示「存档：未关联」「物资点（0 个）」，
            // 保存时又把空壳整体写回 JSON，等于把硬盘上的物资点清零。
            buf.writeUtf(map.sourceArchivePath == null ? "" : map.sourceArchivePath, 512);
            List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
            buf.writeVarInt(Math.min(points.size(), 10000));
            for (LootPoint p : points) {
                buf.writeDouble(p.x());
                buf.writeDouble(p.z());
                buf.writeUtf(p.name() == null ? "" : p.name(), 128);
                buf.writeVarInt(p.fontSize());
            }
            // r109（模块一）：毒圈阶段配置随地图列表回推，详情面板显示 / 编辑都读这份。
            buf.writeBoolean(map.zoneCenterSet);
            MapEditorSyncPayload.writeZonePhases(buf, map.zonePhases);
            buf.writeDouble(map.flightSpeed); // [BR-FLIGHT-SPEED] 详情面板回显用
            // [BR-MATCHTEAM] 队伍上限也要进列表包：详情面板 reloadDetailFromList 读的就是这份。
            buf.writeInt(map.maxTeams);
            buf.writeInt(map.maxTeamSize);
        }
    }

    public static List<GameMap> read(FriendlyByteBuf buf) {
        int count = Math.min(Math.max(0, buf.readVarInt()), 512);
        List<GameMap> maps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GameMap map = new GameMap();
            map.mapId = buf.readUtf(64);
            map.displayName = buf.readUtf(128);
            map.coverImagePath = buf.readUtf(256);
            map.minX = buf.readInt();
            map.minZ = buf.readInt();
            map.maxX = buf.readInt();
            map.maxZ = buf.readInt();
            map.sourceArchivePath = buf.readUtf(512);
            map.lootPoints = new ArrayList<>();
            int points = Math.min(buf.readVarInt(), 10000);
            for (int j = 0; j < points; j++) {
                double x = buf.readDouble();
                double z = buf.readDouble();
                String name = buf.readUtf(128);
                int font = buf.readVarInt();
                map.lootPoints.add(new LootPoint(x, z, name, font));
            }
            map.zoneCenterSet = buf.readBoolean();
            map.zonePhases = MapEditorSyncPayload.readZonePhases(buf);
            map.flightSpeed = buf.readDouble(); // [BR-FLIGHT-SPEED] 与 write 严格同序
            map.normalizeFlightSpeed();
            // [BR-MATCHTEAM] 与 write 严格同序。
            map.maxTeams = buf.readInt();
            map.maxTeamSize = buf.readInt();
            map.normalizeMatchLimits();
            maps.add(map);
        }
        return maps;
    }
}
