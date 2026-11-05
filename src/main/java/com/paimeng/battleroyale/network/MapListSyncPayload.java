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
            // 字符串统一夹长度：writeUtf 超限是抛异常而不是截断，一个字段坏掉会让整张列表包发不出去。
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.mapId, 64), 64);
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.displayName, 128), 128);
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.coverImagePath, 256), 256);
            buf.writeInt(map.minX);
            buf.writeInt(map.minZ);
            buf.writeInt(map.maxX);
            buf.writeInt(map.maxZ);
            // 详情面板需要「源存档路径」和「物资点」，这两项必须来自服务端真实内存对象。
            // 早先这里只发了 4 个字段，客户端拿到的是空壳：GUI 显示「存档：未关联」「物资点（0 个）」，
            // 保存时又把空壳整体写回 JSON，等于把硬盘上的物资点清零。
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.sourceArchivePath, 512), 512);
            List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
            buf.writeVarInt(Math.min(points.size(), 10000));
            for (LootPoint p : points) {
                buf.writeDouble(p.x());
                buf.writeInt(p.y());
                buf.writeDouble(p.z());
                buf.writeUtf(MapEditorSyncPayload.clipForWire(p.name(), 128), 128);
                buf.writeVarInt(p.fontSize());
            }
            // r109（模块一）：毒圈阶段配置随地图列表回推，详情面板显示 / 编辑都读这份。
            buf.writeBoolean(map.zoneCenterSet);
            MapEditorSyncPayload.writeZonePhases(buf, map.zonePhases);
            buf.writeDouble(map.flightSpeed); // [BR-FLIGHT-SPEED] 详情面板回显用
            buf.writeDouble(map.zoneTtkSeconds); // [r148-TTK] 地图级圈外淘汰秒数回显用
            // [BR-MATCHTEAM] 队伍上限也要进列表包：详情面板 reloadDetailFromList 读的就是这份。
            buf.writeInt(map.maxTeams);
            buf.writeInt(map.maxTeamSize);
            // [BR-LOOT-SAVE-FIX] 物资刷新配置必须一起回推：早先这五个字段没进列表包，
            // 详情面板保存成功后服务端立刻回推列表（MapListSync.send）→
            // reloadDetailFromList 把面板副本换成列表里这份「缺字段空壳」，
            // lootPool 输入框当场变空白（管理员看到的「前端填了根本没存进去」），
            // 再点一次「保存配置」又把空串真写回 JSON，等于把刚存的配置抹掉。
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.lootPool,
                    MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS), MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS);
            buf.writeUtf(MapEditorSyncPayload.clipForWire(map.airdropLootPool,
                    MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS), MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS);
            buf.writeVarInt(map.maxLootBoxes);
            buf.writeVarInt(map.maxAirdrops);
            var stages = map.airdropStages == null ? List.<Integer>of() : map.airdropStages;
            int stageLimit = Math.min(stages.size(), 32);
            buf.writeVarInt(stageLimit);
            for (int i = 0; i < stageLimit; i++) {
                Integer stage = stages.get(i);
                buf.writeVarInt(stage == null ? 0 : stage);
            }
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
                int y = buf.readInt();
                double z = buf.readDouble();
                String name = buf.readUtf(128);
                int font = buf.readVarInt();
                map.lootPoints.add(new LootPoint(x, y, z, name, font));
            }
            map.zoneCenterSet = buf.readBoolean();
            map.zonePhases = MapEditorSyncPayload.readZonePhases(buf);
            map.flightSpeed = buf.readDouble(); // [BR-FLIGHT-SPEED] 与 write 严格同序
            map.normalizeFlightSpeed();
            map.zoneTtkSeconds = buf.readDouble(); // [r148-TTK] 与 write 严格同序
            map.normalizeZoneTtk();
            // [BR-MATCHTEAM] 与 write 严格同序。
            map.maxTeams = buf.readInt();
            map.maxTeamSize = buf.readInt();
            map.normalizeMatchLimits();
            // [BR-LOOT-SAVE-FIX] 与 write 严格同序：两个物资池 + 两个上限 + 空投触发阶段。
            // 读完立刻过治理闸，客户端面板拿到的永远是合法配置。
            map.lootPool = buf.readUtf(MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS);
            map.airdropLootPool = buf.readUtf(MapEditorSyncPayload.MAX_LOOT_CONFIG_CHARS);
            map.maxLootBoxes = buf.readVarInt();
            map.maxAirdrops = buf.readVarInt();
            int stageCount = Math.max(0, Math.min(buf.readVarInt(), 32));
            map.airdropStages = new ArrayList<>(stageCount);
            for (int s = 0; s < stageCount; s++) {
                map.airdropStages.add(buf.readVarInt());
            }
            map.normalizeLootConfiguration();
            maps.add(map);
        }
        return maps;
    }
}
