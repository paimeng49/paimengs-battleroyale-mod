package com.paimeng.battleroyale.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 地图 JSON 与导入存档的统一管理器。 */
public final class MapManager {
    public static final MapManager INSTANCE = new MapManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, GameMap> maps = new LinkedHashMap<>();
    /** [BR-COPY-SAFE] 单次复制最多打印多少条坏区块提示。 */
    private static final int BAD_CHUNK_LOG_LIMIT = 20;

    /**
     * [BR-COPY-SAFE] 坏区块提示：同一次复制最多打印 {@value #BAD_CHUNK_LOG_LIMIT} 条，
     * 其余只累计进 badChunks 汇总，绝不让一个坏 region 文件把日志刷满。
     */
    private static void noteBadChunk(String mapId, int chunkX, int chunkZ, String reason, int[] logged) {
        if (logged[0] >= BAD_CHUNK_LOG_LIMIT) {
            return;
        }
        logged[0]++;
        BattleRoyaleMod.LOGGER.warn("[BR-COPY-SAFE] 地图 {} 区块 {},{} 数据损坏，复制时跳过：{}",
                mapId, chunkX, chunkZ, reason);
        if (logged[0] == BAD_CHUNK_LOG_LIMIT) {
            BattleRoyaleMod.LOGGER.warn("[BR-COPY-SAFE] 地图 {} 后续坏区块不再逐条打印，最后统一汇总", mapId);
        }
    }

    private MapManager() {}

    public Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("battleroyale").resolve("maps");
    }

    public Path importDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("battleroyale").resolve("import");
    }

    public synchronized void loadAll() {
        maps.clear();
        try {
            Files.createDirectories(directory());
            try (var stream = Files.list(directory())) {
                stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(this::loadFile);
            }
        } catch (IOException e) {
            BattleRoyaleMod.LOGGER.warn("读取地图目录失败: {}", e.toString());
        }
        scanImports();
    }

    private void loadFile(Path path) {
        try {
            GameMap map = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), GameMap.class);
            if (map != null && map.mapId != null && !map.mapId.isBlank()) {
                // [BR-FLIGHT-SPEED] 旧地图 JSON 没有 flightSpeed 字段：Gson 保留初始值 4.0；
                // 坏值（手改出 0 / 负数 / 离谱大值）当场夹回安全区间。
                map.normalizeFlightSpeed();
                // [BR-MATCHTEAM] 同理：旧 JSON 没有 maxTeams / maxTeamSize，初始值 25 / 4；坏值夹回。
                map.normalizeMatchLimits();
                // [BR-LOOT] 旧 JSON 没有物资池配置：初始值（空串 / 默认上限）照常过治理闸。
                map.normalizeLootConfiguration();
                maps.put(map.mapId, map);
            }
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("读取地图 {} 失败: {}", path, e.toString());
        }
    }

    public synchronized void saveAll() {
        for (GameMap map : maps.values()) {
            saveMap(map);
        }
    }

    public synchronized void saveMap(GameMap map) {
        if (map == null || map.mapId == null || !map.mapId.matches("[A-Za-z0-9_-]+")) {
            return;
        }
        try {
            Files.createDirectories(directory());
            map.normalizeFlightSpeed(); // [BR-FLIGHT-SPEED] 落盘前最后一道闸：JSON 里永远是合法速度
            map.normalizeMatchLimits();  // [BR-MATCHTEAM] 队伍上限同样过闸
            map.normalizeLootConfiguration(); // [BR-LOOT] 物资池 / 空投配置落盘前过闸
            ensureCover(map);
            Files.writeString(directory().resolve(map.mapId + ".json"), GSON.toJson(map), StandardCharsets.UTF_8);
            maps.put(map.mapId, map);
        } catch (IOException e) {
            BattleRoyaleMod.LOGGER.warn("保存地图 {} 失败: {}", map.mapId, e.toString());
        }
    }

    private void ensureCover(GameMap map) throws IOException {
        Path covers = directory().resolve("covers");
        Files.createDirectories(covers);
        Path target = covers.resolve(map.mapId + ".png");
        if (!Files.exists(target)) {
            Path source = null;
            if (map.sourceArchivePath != null && !map.sourceArchivePath.isBlank()) {
                Path root = Path.of(map.sourceArchivePath);
                for (String name : new String[] {"icon.png", "cover.png", "thumbnail.png"}) {
                    Path candidate = root.resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        source = candidate;
                        break;
                    }
                }
            }
            if (source != null) {
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                BufferedImage placeholder = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                int color = 0xFF466B4A;
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        placeholder.setRGB(x, y, color);
                    }
                }
                ImageIO.write(placeholder, "png", target.toFile());
            }
        }
        map.coverImagePath = "covers/" + map.mapId + ".png";
    }

    public synchronized GameMap getMap(String id) {
        return maps.get(id);
    }

    /**
     * 按地图配置记录的源存档路径查找地图，不使用副本维度 ID 作为 key。
     *
     * <p>比较做归一化：统一反斜杠 / 正斜杠方向、去掉结尾多余分隔符、忽略大小写
     * （Windows 下同一个目录可能被写成大小写不同、斜杠不同的路径）。
     * 完整路径都对不上时，再退一步按「存档目录名」匹配（存档都放在 import 目录下）。
     */
    public synchronized GameMap findBySourceArchivePath(String sourceArchivePath) {
        if (sourceArchivePath == null || sourceArchivePath.isBlank()) {
            return null;
        }
        try {
            String wanted = normalizePathKey(sourceArchivePath);
            String wantedName = lastSegment(wanted);
            GameMap byName = null;
            List<String> candidates = new ArrayList<>();
            for (GameMap map : maps.values()) {
                if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) {
                    candidates.add(map.mapId + " -> (空)");
                    continue;
                }
                String key = normalizePathKey(map.sourceArchivePath);
                if (key.equals(wanted)) {
                    return map;
                }
                candidates.add(map.mapId + " -> " + map.sourceArchivePath);
                if (byName == null && !wantedName.isEmpty() && wantedName.equals(lastSegment(key))) {
                    byName = map;
                }
            }
            if (byName != null) {
                BattleRoyaleMod.LOGGER.info("源存档完整路径不一致，但按存档目录名匹配到地图 {}（请求={}）",
                        byName.mapId, sourceArchivePath);
                return byName;
            }
            BattleRoyaleMod.LOGGER.warn("未找到源存档对应的地图配置：路径={}，现有地图的 sourceArchivePath={}",
                    sourceArchivePath, candidates);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("匹配地图源存档路径失败：{}", sourceArchivePath);
        }
        return null;
    }

    /** 路径归一化：统一斜杠方向、去尾部斜杠、转小写；不触碰文件系统，非法字符也不会抛异常。 */
    static String normalizePathKey(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private static String lastSegment(String normalizedPath) {
        int idx = normalizedPath.lastIndexOf('/');
        return idx < 0 ? normalizedPath : normalizedPath.substring(idx + 1);
    }

    public synchronized GameMap createMap(String id, String displayName) {
        GameMap map = new GameMap();
        map.mapId = id;
        map.displayName = displayName;
        maps.put(id, map);
        return map;
    }

    public synchronized GameMap createMap(String id, String displayName, int minX, int minZ, int maxX, int maxZ) {
        GameMap map = createMap(id, displayName);
        map.minX = Math.min(minX, maxX);
        map.maxX = Math.max(minX, maxX);
        map.minZ = Math.min(minZ, maxZ);
        map.maxZ = Math.max(minZ, maxZ);
        map.zoneCenterX = (map.minX + map.maxX) / 2.0;
        map.zoneCenterZ = (map.minZ + map.maxZ) / 2.0;
        return map;
    }

    public synchronized boolean deleteMap(String id) {
        GameMap removed = maps.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            Files.deleteIfExists(directory().resolve(id + ".json"));
        } catch (IOException ignored) {
        }
        return true;
    }

    public synchronized Collection<GameMap> listMaps() {
        return new ArrayList<>(maps.values());
    }

    public synchronized GameMap firstOrCreate() {
        if (!maps.isEmpty()) {
            return maps.values().iterator().next();
        }
        GameMap map = createMap("default", "默认地图");
        saveMap(map);
        return map;
    }

    /** 扫描 import 目录；zip 会解压到 _extracted 后再校验。 */
    public synchronized List<ArchiveEntry> scanImports() {
        List<ArchiveEntry> result = new ArrayList<>();
        try {
            Files.createDirectories(importDirectory());
            Path extracted = importDirectory().resolve("_extracted");
            Files.createDirectories(extracted);
            try (var stream = Files.list(importDirectory())) {
                stream.sorted().forEach(path -> {
                    try {
                        Path candidate = path;
                        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".zip")) {
                            candidate = extracted.resolve(stripZip(path.getFileName().toString()));
                            unzipIfNeeded(path, candidate);
                        }
                        ArchiveEntry entry = validateArchive(candidate);
                        if (entry != null) {
                            result.add(entry);
                        }
                    } catch (IOException e) {
                        BattleRoyaleMod.LOGGER.warn("扫描导入存档 {} 失败: {}", path, e.toString());
                    }
                });
            }
        } catch (IOException e) {
            BattleRoyaleMod.LOGGER.warn("创建导入目录失败: {}", e.toString());
        }
        return result;
    }

    public ArchiveEntry findArchive(String archiveName) {
        if (archiveName == null || archiveName.isBlank()) {
            return null;
        }
        for (ArchiveEntry entry : scanImports()) {
            if (archiveName.equals(entry.name()) || archiveName.equals(safeId(entry.name()))) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 按名字定位 import 目录下的存档文件夹，返回绝对路径；找不到或不是完整存档时返回 null。
     *
     * <p>这里不做全目录扫描（scanImports 会统计整个存档体积，大存档很慢），只做几次存在性检查。
     * zip 解压出来的存档放在 {@code import/_extracted/} 下，所以两处都找一遍。
     */
    public Path resolveImportPath(String archiveName) {
        if (archiveName == null || archiveName.isBlank()) {
            return null;
        }
        String cleaned = archiveName.trim().replace('\\', '/');
        if (cleaned.contains("/") || cleaned.equals(".") || cleaned.equals("..")) {
            return null;
        }
        Path root = importDirectory().toAbsolutePath().normalize();
        for (Path base : List.of(root, root.resolve("_extracted"))) {
            Path candidate = base.resolve(cleaned).normalize();
            if (!candidate.startsWith(root)) {
                continue;
            }
            if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("level.dat"))) {
                return candidate;
            }
        }
        return null;
    }

    public static String safeId(String name) {
        String id = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]", "_");
        return id.isBlank() ? "imported_map" : id.substring(0, Math.min(48, id.length()));
    }

    private static String stripZip(String name) {
        return name.substring(0, name.length() - 4).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static ArchiveEntry validateArchive(Path candidate) throws IOException {
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        Path level = candidate.resolve("level.dat");
        Path region = candidate.resolve("region");
        if (!Files.isRegularFile(level) || !Files.isDirectory(region)) {
            return null;
        }
        long size;
        try (var stream = Files.walk(candidate)) {
            size = stream.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
        return new ArchiveEntry(candidate.getFileName().toString(), candidate.toAbsolutePath().normalize(), size, Files.getLastModifiedTime(level).toMillis());
    }

    private static void unzipIfNeeded(Path zip, Path target) throws IOException {
        if (Files.isRegularFile(target.resolve("level.dat")) && Files.isDirectory(target.resolve("region"))) {
            return;
        }
        Files.createDirectories(target);
        try (InputStream input = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path nested = findLevelRoot(target);
        if (nested != null && !nested.equals(target)) {
            Path temp = target.resolveSibling(target.getFileName() + "_root");
            Files.move(nested, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var stream = Files.list(temp)) {
                stream.forEach(p -> {
                    try {
                        Files.move(p, target.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(temp);
        }
    }

    private static Path findLevelRoot(Path root) throws IOException {
        try (var stream = Files.walk(root, 3)) {
            return stream.filter(p -> p.getFileName().toString().equals("level.dat")).map(Path::getParent).findFirst().orElse(root);
        }
    }

    /**
     * 区块复制结果：error 非空 = 前置检查失败，调用方必须中止创建游戏维度；
     * skipped = true 表示目标维度已有数据、按「不覆盖已有数据」原则跳过了复制（不是错误）。
     */
    public static final class CopyOutcome {
        public final int copied;
        public final String error;
        public final boolean skipped;

        private CopyOutcome(int copied, String error, boolean skipped) {
            this.copied = copied;
            this.error = error;
            this.skipped = skipped;
        }

        static CopyOutcome ok(int copied) {
            return new CopyOutcome(copied, null, false);
        }

        static CopyOutcome skip() {
            return new CopyOutcome(0, null, true);
        }

        static CopyOutcome fail(String message) {
            return new CopyOutcome(0, message, false);
        }
    }

    /**
     * 逐区块复制选区覆盖的区块（方案 B：按区块 NBT 读写）；源存档保持不变。
     *
     * <p>旧实现是"整个 .mca region 文件复制"（方案 A）：一个 .mca 覆盖 32×32=1024 个区块，
     * 只要选区碰到该文件边界，整个文件（含选区外最多 1023 个区块）都会被复制进游戏维度，
     * 表现为"创建游戏维度却把编辑维度生成过的地形全带过来了"。
     * 现在改为用原版 {@link RegionFile} 打开源文件、只读选区覆盖的区块的 NBT，
     * 再逐个写入目标维度对应的 .mca——选区外的区块不落盘，由游戏维度的虚空生成器补空气。
     *
     * <p>负坐标用 {@link Math#floorDiv} 换算区块/region 索引（向下取整，不是截断），
     * 不会出现偏移一个区块的问题。
     *
     * <p>每一步都打 [BR-COPY-DIAG] 诊断日志（源/目标绝对路径、选区区块范围、
     * 复制区块数 vs 选区覆盖区块数、源缺失区块数、旧方案会多复制多少）。
     * 一个区块都复制不到时返回带原因的错误，而不是静默返回 0——静默 0 会让游戏维度
     * 建成一片全虚空，玩家进去什么也看不到。
     *
     * <p>注意：1.18.2 的实体数据单独存在存档 entities/ 目录，本方法只复制区块
     * （方块/方块实体/高度图等都在区块 NBT 里）；复制是后台 IO 线程的文件级读取，
     * 与编辑维度的自动保存并发时个别区块可能读到写一半的数据，会被按坏区块跳过并告警。
     */
    public CopyOutcome copySelectedChunks(GameMap map, Path destinationRegion) {
        String mapId = map == null ? "?" : map.mapId;
        if (map == null) {
            String error = "游戏维度生成失败：地图配置为 null";
            BattleRoyaleMod.LOGGER.error(error);
            return CopyOutcome.fail(error);
        }
        if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) {
            String error = "游戏维度生成失败：map.sourceArchivePath 为空（config/battleroyale/maps/"
                    + mapId + ".json 没有记录源存档路径）。请在游戏内执行 /paimengmods manager gui 打开地图管理，"
                    + "在「地图管理」里点开这张地图的详情，点「点击关联存档」选中 import 目录下的存档（会自动保存），"
                    + "再进入游戏维度或执行 /paimengmods manager game rebuild " + mapId + "。";
            BattleRoyaleMod.LOGGER.error(error);
            return CopyOutcome.fail(error);
        }
        Path sourceRoot = Path.of(map.sourceArchivePath);
        Path sourceRegion = sourceRoot.resolve("region");
        boolean regionExists = Files.isDirectory(sourceRegion);
        BattleRoyaleMod.LOGGER.info("源 region 目录：{}，存在：{}", sourceRegion, regionExists);
        if (!regionExists) {
            String error = "游戏维度生成失败：源存档缺少 region 目录：" + sourceRegion
                    + "（存档目录" + (Files.isDirectory(sourceRoot) ? "存在但不是一个完整存档" : "本身不存在")
                    + "）。请确认存档已解压导入到 config/battleroyale/import/ 下。";
            BattleRoyaleMod.LOGGER.error(error);
            return CopyOutcome.fail(error);
        }
        // 选区方块坐标 → 区块索引：floorDiv 向负无穷取整，负坐标不会偏移一个区块。
        int minChunkX = Math.floorDiv(map.minX, 16);
        int maxChunkX = Math.floorDiv(map.maxX, 16);
        int minChunkZ = Math.floorDiv(map.minZ, 16);
        int maxChunkZ = Math.floorDiv(map.maxZ, 16);
        long selectionChunks = ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 复制粒度=逐区块 NBT 读写（方案 B）| mapId={} | "
                        + "源 region 绝对路径：{} | 目标 region 绝对路径：{}",
                mapId, sourceRegion.toAbsolutePath(), destinationRegion.toAbsolutePath());
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 选区方块坐标 X {}~{}，Z {}~{}（来自已保存的 GameMap 字段）"
                        + " → 选区覆盖区块范围 X {}~{}，Z {}~{}，共 {} 个区块；对应 region 范围 X {}~{}，Z {}~{}",
                map.minX, map.maxX, map.minZ, map.maxZ,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ, selectionChunks,
                Math.floorDiv(minChunkX, 32), Math.floorDiv(maxChunkX, 32),
                Math.floorDiv(minChunkZ, 32), Math.floorDiv(maxChunkZ, 32));
        if (selectionChunks <= 0) {
            String error = "游戏维度生成失败：选区坐标异常（maxX/minX 或 maxZ/minZ 顺序颠倒），"
                    + "请在编辑副本里重新圈选并保存地图。";
            BattleRoyaleMod.LOGGER.error("[BR-COPY-DIAG] {}", error);
            return CopyOutcome.fail(error);
        }
        // 防覆盖守卫：目标维度 region 目录已有 .mca 数据 → 打警告跳过复制，绝不静默覆盖。
        try {
            if (Files.isDirectory(destinationRegion)) {
                boolean destHasData;
                try (var stream = Files.list(destinationRegion)) {
                    destHasData = stream.filter(Files::isRegularFile)
                            .anyMatch(p -> p.getFileName().toString().endsWith(".mca"));
                }
                if (destHasData) {
                    BattleRoyaleMod.LOGGER.warn("[BR-COPY-DIAG] 目标游戏维度 {} 已存在 region 数据 → "
                                    + "跳过复制，不覆盖已有数据（要重新复制请先 manager game rebuild {}）",
                            destinationRegion.toAbsolutePath(), mapId);
                    return CopyOutcome.skip();
                }
            }
        } catch (IOException e) {
            String error = "游戏维度生成失败：检查目标 region 目录出错：" + e;
            BattleRoyaleMod.LOGGER.error(error);
            return CopyOutcome.fail(error);
        }
        int minRegionX = Math.floorDiv(minChunkX, 32);
        int maxRegionX = Math.floorDiv(maxChunkX, 32);
        int minRegionZ = Math.floorDiv(minChunkZ, 32);
        int maxRegionZ = Math.floorDiv(maxChunkZ, 32);
        int copiedChunks = 0;
        int missingChunks = 0;
        int badChunks = 0;
        /** [BR-COPY-SAFE] 本次复制已打印的坏区块提示条数（数组是为了在静态辅助方法里累加）。 */
        final int[] badChunkLogged = new int[1];
        int intersectingFiles = 0;
        long intersectingBytes = 0;
        List<String> writtenFiles = new ArrayList<>();
        try {
            Files.createDirectories(destinationRegion);
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    String fileName = "r." + rx + "." + rz + ".mca";
                    Path source = sourceRegion.resolve(fileName);
                    // 本 region 分片与选区的区块交集（选区可能只盖住分片一角）
                    int cxLo = Math.max(minChunkX, rx * 32);
                    int cxHi = Math.min(maxChunkX, rx * 32 + 31);
                    int czLo = Math.max(minChunkZ, rz * 32);
                    int czHi = Math.min(maxChunkZ, rz * 32 + 31);
                    long tileChunks = ((long) cxHi - cxLo + 1) * ((long) czHi - czLo + 1);
                    if (!Files.isRegularFile(source)) {
                        // 整个分片在源存档里不存在 = 编辑维度从没生成过这片区块
                        missingChunks += tileChunks;
                        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 源 region 文件 {} 不存在，跳过（{} 个区块记为未生成）",
                                fileName, tileChunks);
                        continue;
                    }
                    intersectingFiles++;
                    intersectingBytes += Files.size(source);
                    int writtenHere = 0;
                    RegionFile writer = null;
                    // [BR-COPY-SAFE] 先体检：扇区表读不出来的 region 整片跳过，
                    // 坏区块（region 被截断 / 外部 .mcc 缺失）按坏区块跳过，
                    // 绝不让一个坏文件把整次重建带走，也不给原版刷 ERROR 的机会。
                    RegionHealthProbe probe = null;
                    try {
                        probe = RegionHealthProbe.open(source);
                    } catch (Throwable openError) {
                        BattleRoyaleMod.LOGGER.warn("[BR-COPY-SAFE] 源 region 文件 {} 扇区表读不出来，整片跳过：{}",
                                fileName, openError.toString());
                        missingChunks += tileChunks;
                        continue;
                    }
                    try (RegionFile reader = new RegionFile(source, sourceRegion, false)) {
                        for (int cx = cxLo; cx <= cxHi; cx++) {
                            for (int cz = czLo; cz <= czHi; cz++) {
                                ChunkPos pos = new ChunkPos(cx, cz);
                                CompoundTag nbt;
                                try {
                                    String problem = probe.problem(cx, cz, sourceRegion);
                                    if (problem != null) {
                                        noteBadChunk(mapId, cx, cz, problem, badChunkLogged);
                                        badChunks++;
                                        continue;
                                    }
                                    if (!reader.hasChunk(pos)) {
                                        missingChunks++;
                                        continue;
                                    }
                                    try (DataInputStream in = reader.getChunkDataInputStream(pos)) {
                                        if (in == null) {
                                            missingChunks++;
                                            continue;
                                        }
                                        nbt = NbtIo.read(in);
                                    }
                                } catch (IOException | RuntimeException e) {
                                    badChunks++;
                                    noteBadChunk(mapId, cx, cz, e.toString(), badChunkLogged);
                                    continue;
                                }
                                if (nbt == null) {
                                    missingChunks++;
                                    continue;
                                }
                                if (writer == null) {
                                    // 延迟创建目标文件：该分片一个区块都没复制到时不留下空 .mca
                                    writer = new RegionFile(destinationRegion.resolve(fileName), destinationRegion, false);
                                }
                                try (DataOutputStream out = writer.getChunkDataOutputStream(pos)) {
                                    NbtIo.write(nbt, out);
                                }
                                copiedChunks++;
                                writtenHere++;
                            }
                        }
                        if (writer != null) {
                            try { writer.flush(); } catch (java.io.IOException ignored) { }
                        }
                    } catch (IOException | RuntimeException regionError) {
                        // 单个源 region 文件级故障：整片跳过，不让它把整次重建带走。
                        missingChunks += tileChunks;
                        BattleRoyaleMod.LOGGER.warn("[BR-COPY-SAFE] 源 region 文件 {} 打开或读取失败，整片跳过（{} 个区块记为未复制）：{}",
                                fileName, tileChunks, regionError.toString());
                    } finally {
                        if (writer != null) {
                            try {
                                writer.close();
                            } catch (IOException ignored) {
                            }
                        }
                        probe.close();
                    }
                    if (writtenHere > 0) {
                        writtenFiles.add(fileName + "（" + writtenHere + " 个区块）");
                    }
                }
            }
        } catch (IOException e) {
            String error = "游戏维度生成失败：逐区块复制出错：" + e;
            BattleRoyaleMod.LOGGER.error(error);
            return new CopyOutcome(copiedChunks, error, false);
        }
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 复制完成：复制区块数 {}，选区覆盖区块数 {}（源未生成 {} 个、"
                        + "坏区块跳过 {} 个）；实际写出目标 region 文件 {} 个：{}",
                copiedChunks, selectionChunks, missingChunks, badChunks, writtenFiles.size(), writtenFiles);
        BattleRoyaleMod.LOGGER.info("[BR-COPY-DIAG] 对比旧方案（整 region 文件复制）：选区与源存档的 {} 个 .mca 相交"
                        + "（合计约 {} MB，每个文件最多含 1024 个区块），旧粒度会把相交文件里选区外的区块一并复制；"
                        + "新粒度按区块复制，选区外区块保持虚空",
                intersectingFiles, intersectingBytes / (1024 * 1024));
        if (copiedChunks == 0) {
            String error = "游戏维度生成失败：选区范围内一个已生成区块都没复制到——选区覆盖区块 X "
                    + minChunkX + "~" + maxChunkX + "，Z " + minChunkZ + "~" + maxChunkZ
                    + "（源未生成 " + missingChunks + " 个）。"
                    + "说明圈选的区域在编辑维度里没有真正生成过（没进过 / 没加载过），请圈一个已生成的地块再保存。";
            BattleRoyaleMod.LOGGER.error("[BR-COPY-DIAG] {}", error);
            return CopyOutcome.fail(error);
        }
        return CopyOutcome.ok(copiedChunks);
    }
}
