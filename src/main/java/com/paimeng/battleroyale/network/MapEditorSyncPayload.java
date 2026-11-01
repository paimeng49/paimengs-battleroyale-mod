package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.map.ArchiveEntry;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.ZonePhaseConfig;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 地图编辑器打开、导入列表与保存使用的网络数据。 */
public record MapEditorSyncPayload(GameMap map) {
    /**
     * 保存方向的额外标记：
     * detailUpdate=true 表示来自「地图详情面板」的配置保存，服务端不要拿会话选区覆盖地图里已存的选区；
     * lootListComplete=true 表示详情面板送来的物资点列表是「完整权威列表」——列表为空
     * 也意味着真的要把物资点全删光，而不是旧客户端的空壳包（旧客户端恒为 false，保留防清零逻辑）。
     * 发送方向（S2C sendOpen）两个标记都固定 false。
     */
    public static void write(FriendlyByteBuf buf, GameMap map) { write(buf, map, false, false); }

    public static void write(FriendlyByteBuf buf, GameMap map, boolean detailUpdate) {
        write(buf, map, detailUpdate, detailUpdate);
    }

    public static void write(FriendlyByteBuf buf, GameMap map, boolean detailUpdate, boolean lootListComplete) {
        buf.writeUtf(map.mapId == null ? "default" : map.mapId, 64);
        buf.writeUtf(map.displayName == null ? "地图" : map.displayName, 128);
        buf.writeInt(map.minX);
        buf.writeInt(map.minZ);
        buf.writeInt(map.maxX);
        buf.writeInt(map.maxZ);
        buf.writeDouble(map.zoneCenterX);
        buf.writeDouble(map.zoneCenterZ);
        buf.writeInt(map.maxPlayers);
        buf.writeInt(map.maxRooms);
        buf.writeInt(map.teamLimit);
        buf.writeUtf(map.sourceArchivePath == null ? "" : map.sourceArchivePath, 512);
        buf.writeUtf(map.coverImagePath == null ? "" : map.coverImagePath, 256);
        var points = map.lootPoints == null ? List.<LootPoint>of() : map.lootPoints;
        buf.writeVarInt(Math.min(points.size(), 10000));
        for (LootPoint p : points) {
            buf.writeUtf(p.name(), 128);
            buf.writeDouble(p.x());
            buf.writeDouble(p.z());
            buf.writeVarInt(p.fontSize());
        }
        buf.writeDouble(map.spawnX);
        buf.writeDouble(map.spawnY);
        buf.writeDouble(map.spawnZ);
        buf.writeBoolean(map.spawnSet);
        buf.writeInt(map.spawnMinX);
        buf.writeInt(map.spawnMinZ);
        buf.writeInt(map.spawnMaxX);
        buf.writeInt(map.spawnMaxZ);
        buf.writeBoolean(map.spawnAreaSet);
        // [BR-MATCHTEAM] 队伍上限随地图数据走，详情面板才能显示 / 编辑 maxTeams、maxTeamSize。
        buf.writeInt(map.maxTeams);
        buf.writeInt(map.maxTeamSize);
        // r109（模块一）：毒圈阶段配置随地图数据走，GUI 详情面板才能显示 / 编辑。
        buf.writeBoolean(map.zoneCenterSet);
        writeZonePhases(buf, map.zonePhases);
        buf.writeDouble(map.flightSpeed); // [BR-FLIGHT-SPEED] 飞机速度随地图配置走（格/tick）
        buf.writeBoolean(detailUpdate);
        buf.writeBoolean(lootListComplete);
    }

    /** 毒圈阶段列表写入（最多 {@value ZonePhaseConfig#MAX_PHASES} 条，越界截断防刷包）。 */
    public static void writeZonePhases(FriendlyByteBuf buf, java.util.List<ZonePhaseConfig> phases) {
        java.util.List<ZonePhaseConfig> list = phases == null ? List.of() : phases;
        // [r131 索引防错位] 计数写**原始位置数**，空洞（null）用默认值占位照写一条：
        // 旧版计数含 null 但循环跳过 null，读端把后面的字段当成缺失那条的数值解析，
        // 列表乃至后续字段整体错位——「白圈看起来用了 config[1]」的污染路径之一。
        int limit = Math.min(list.size(), ZonePhaseConfig.MAX_PHASES);
        buf.writeVarInt(limit);
        for (int i = 0; i < limit; i++) {
            ZonePhaseConfig phase = list.get(i);
            if (phase == null) {
                phase = new ZonePhaseConfig();   // 默认值占位：位置保序，绝不压缩列表
            }
            buf.writeVarInt(Math.max(0, phase.waitTime));
            buf.writeVarInt(Math.max(0, phase.shrinkTime));
            buf.writeDouble(phase.radius);
            buf.writeDouble(phase.damage);
        }
    }

    /** 毒圈阶段列表读取；坏数据（NaN 等）由调用方 clampIntoRange 统一治理。 */
    public static java.util.List<ZonePhaseConfig> readZonePhases(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), ZonePhaseConfig.MAX_PHASES));
        java.util.List<ZonePhaseConfig> phases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int waitTime = buf.readVarInt();
            int shrinkTime = buf.readVarInt();
            double radius = buf.readDouble();
            double damage = buf.readDouble();
            phases.add(new ZonePhaseConfig(waitTime, shrinkTime, radius, damage));
        }
        return phases;
    }

    /** 解码结果：地图数据 + 「来自详情面板」标记 + 「物资点列表是完整权威列表」标记。 */
    public static final class Decoded {
        public final GameMap map;
        public final boolean detailUpdate;
        public final boolean lootListComplete;
        Decoded(GameMap map, boolean detailUpdate, boolean lootListComplete) {
            this.map = map;
            this.detailUpdate = detailUpdate;
            this.lootListComplete = lootListComplete;
        }
    }

    public static GameMap read(FriendlyByteBuf buf) { return readDecoded(buf).map; }

    public static Decoded readDecoded(FriendlyByteBuf buf) {
        GameMap map = new GameMap();
        map.mapId = buf.readUtf(64);
        map.displayName = buf.readUtf(128);
        map.minX = buf.readInt();
        map.minZ = buf.readInt();
        map.maxX = buf.readInt();
        map.maxZ = buf.readInt();
        map.zoneCenterX = buf.readDouble();
        map.zoneCenterZ = buf.readDouble();
        map.maxPlayers = buf.readInt();
        map.maxRooms = buf.readInt();
        map.teamLimit = buf.readInt();
        map.sourceArchivePath = buf.readUtf(512);
        map.coverImagePath = buf.readUtf(256);
        int points = Math.min(buf.readVarInt(), 10000);
        map.lootPoints = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            String name = buf.readUtf(128);
            double x = buf.readDouble();
            double z = buf.readDouble();
            int font = buf.readVarInt();
            map.lootPoints.add(new LootPoint(x, z, name, font));
        }
        map.spawnX = buf.readDouble();
        map.spawnY = buf.readDouble();
        map.spawnZ = buf.readDouble();
        map.spawnSet = buf.readBoolean();
        map.spawnMinX = buf.readInt();
        map.spawnMinZ = buf.readInt();
        map.spawnMaxX = buf.readInt();
        map.spawnMaxZ = buf.readInt();
        map.spawnAreaSet = buf.readBoolean();
        // [BR-MATCHTEAM] 与 write 严格同序：先读 maxTeams / maxTeamSize，再过治理闸。
        map.maxTeams = buf.readInt();
        map.maxTeamSize = buf.readInt();
        map.normalizeMatchLimits();
        map.zoneCenterSet = buf.readBoolean();
        map.zonePhases = readZonePhases(buf);
        map.flightSpeed = buf.readDouble(); // [BR-FLIGHT-SPEED] 与 write 严格同序
        map.normalizeFlightSpeed();         // 伪造 / 坏包在这里就治理成合法值，服务端后续只见好值
        boolean detailUpdate = buf.readBoolean();
        boolean lootComplete = buf.readBoolean();
        return new Decoded(map, detailUpdate, lootComplete);
    }

    public static void writeArchives(FriendlyByteBuf buf, List<ArchiveEntry> entries) {
        buf.writeVarInt(Math.min(entries.size(), 256));
        for (ArchiveEntry entry : entries) {
            buf.writeUtf(entry.name(), 128);
            buf.writeUtf(entry.root().toString(), 512);
            buf.writeLong(entry.sizeBytes());
            buf.writeLong(entry.modifiedTime());
            buf.writeUtf(entry.displayStatus(), 32);
        }
    }

    public static List<ArchiveEntry> readArchives(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 256);
        List<ArchiveEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new ArchiveEntry(buf.readUtf(128), Path.of(buf.readUtf(512)), buf.readLong(), buf.readLong(), buf.readUtf(32)));
        }
        return entries;
    }
}
