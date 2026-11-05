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
    /** 字符串网络长度上限（<b>UTF-8 字节</b>，物资池两行共用）；超长在写入前截断，不让 writeUtf 抛异常打断保存包。 */
    public static final int MAX_LOOT_CONFIG_CHARS = 16384;

    /**
     * 写入前把文本夹到 {@code maxBytes} 个 <b>UTF-8 字节</b>以内（按码点累加，绝不切断半个字符）。
     *
     * <p>{@code FriendlyByteBuf#writeUtf(String, int)} / {@code readUtf(int)} 的 maxLength
     * 越界都是<b>抛异常</b>而不是截断：一个字段超长会让整个保存包发不出去或让对端解码失败
     * （GUI 上表现为「点了保存毫无反应」）。物资池里出现中文 NBT（如自定义名称）时
     * 一个字占 3 字节，只按字符数夹是不够的，所以这里按字节算。
     */
    public static String clipForWire(String raw, int maxBytes) {
        if (raw == null) {
            return "";
        }
        if (raw.isEmpty() || utf8Length(raw) <= maxBytes) {
            return raw;
        }
        StringBuilder kept = new StringBuilder(raw.length());
        int used = 0;
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int size = utf8Size(cp);
            if (used + size > maxBytes) {
                break;
            }
            kept.appendCodePoint(cp);
            used += size;
            i += Character.charCount(cp);
        }
        return kept.toString();
    }

    /** UTF-8 字节长度（不分配字节数组）。 */
    private static int utf8Length(String s) {
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                total++;
            } else if (c < 0x800) {
                total += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                total += 4;
                i++;
            } else {
                total += 3;
            }
        }
        return total;
    }

    /** 单个码点的 UTF-8 字节数。 */
    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

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
        // 全部字符串先夹长度再写：writeUtf 的 maxLength 是「抛异常」而不是「截断」，
        // 任何一个字段超限都会让整个保存包发不出去（GUI 上表现为点了保存毫无反应）。
        buf.writeUtf(clipForWire(map.mapId == null ? "default" : map.mapId, 64), 64);
        buf.writeUtf(clipForWire(map.displayName == null ? "地图" : map.displayName, 128), 128);
        buf.writeInt(map.minX);
        buf.writeInt(map.minZ);
        buf.writeInt(map.maxX);
        buf.writeInt(map.maxZ);
        buf.writeDouble(map.zoneCenterX);
        buf.writeDouble(map.zoneCenterZ);
        // [r149 DYNCAP 模块一] maxPlayers 写死字段已删除：人数上限是 maxTeams × maxTeamSize
        // 的派生值（GameMap.getMaxPlayers()），下面已有的两个 int 就带齐了全部容量信息。
        buf.writeInt(map.maxRooms);
        buf.writeInt(map.teamLimit);
        buf.writeUtf(clipForWire(map.sourceArchivePath, 512), 512);
        buf.writeUtf(clipForWire(map.coverImagePath, 256), 256);
        var points = map.lootPoints == null ? List.<LootPoint>of() : map.lootPoints;
        buf.writeVarInt(Math.min(points.size(), 10000));
        for (LootPoint p : points) {
            buf.writeUtf(clipForWire(p.name(), 128), 128);
            buf.writeDouble(p.x());
            buf.writeInt(p.y());
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
        buf.writeDouble(map.zoneTtkSeconds); // [r148-TTK] 地图级圈外淘汰秒数（double，支持小数）
        // [BR-LOOT 模块一] 物资池配置随地图数据走（超长先截断，防 writeUtf 抛异常吞掉整个保存包）。
        buf.writeUtf(clipForWire(map.lootPool, MAX_LOOT_CONFIG_CHARS), MAX_LOOT_CONFIG_CHARS);
        buf.writeUtf(clipForWire(map.airdropLootPool, MAX_LOOT_CONFIG_CHARS), MAX_LOOT_CONFIG_CHARS);
        buf.writeVarInt(map.maxLootBoxes);
        buf.writeVarInt(map.maxAirdrops);
        var stages = map.airdropStages == null ? List.<Integer>of() : map.airdropStages;
        buf.writeVarInt(Math.min(stages.size(), 32));
        for (Integer stage : stages.subList(0, Math.min(stages.size(), 32))) {
            buf.writeVarInt(stage == null ? 0 : stage);
        }
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
            buf.writeDouble(phase.zoneTtkSeconds);
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
            double ttk = buf.readDouble();   // [r148] 阶段圈外淘汰秒数（浮点，0 = 跟随地图全局值）
            phases.add(new ZonePhaseConfig(waitTime, shrinkTime, radius, ttk));
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
        // [r149 DYNCAP 模块一] 与 write 严格同序：maxPlayers 不再上网络。
        map.maxRooms = buf.readInt();
        map.teamLimit = buf.readInt();
        map.sourceArchivePath = buf.readUtf(512);
        map.coverImagePath = buf.readUtf(256);
        int points = Math.min(buf.readVarInt(), 10000);
        map.lootPoints = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            String name = buf.readUtf(128);
            double x = buf.readDouble();
            int y = buf.readInt();
            double z = buf.readDouble();
            int font = buf.readVarInt();
            map.lootPoints.add(new LootPoint(x, y, z, name, font));
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
        map.zoneTtkSeconds = buf.readDouble(); // [r148-TTK] 与 write 严格同序
        map.normalizeZoneTtk();                  // 坏值（NaN / 负 / 离谱大）当场治理回 1 ~ 3600 秒
        // [BR-LOOT 模块一] 与 write 严格同序：两个物资池 + 上限 + 触发阶段列表。
        map.lootPool = buf.readUtf(16384);
        map.airdropLootPool = buf.readUtf(16384);
        map.maxLootBoxes = buf.readVarInt();
        map.maxAirdrops = buf.readVarInt();
        int stageCount = Math.max(0, Math.min(buf.readVarInt(), 32));
        map.airdropStages = new ArrayList<>(stageCount);
        for (int i = 0; i < stageCount; i++) {
            map.airdropStages.add(buf.readVarInt());
        }
        map.normalizeLootConfiguration();
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
