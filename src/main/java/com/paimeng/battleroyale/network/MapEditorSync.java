package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.command.BrCommand;
import com.paimeng.battleroyale.map.*;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 地图编辑器的存档、副本、选区和物资点网络协议。 */
public final class MapEditorSync {
    public static final ResourceLocation OPEN_CHANNEL = id("map_editor_open");
    public static final ResourceLocation SAVE_CHANNEL = id("map_editor_save");
    public static final ResourceLocation ARCHIVES_CHANNEL = id("map_editor_archives");
    public static final ResourceLocation REFRESH_CHANNEL = id("map_editor_refresh");
    public static final ResourceLocation ENTER_CHANNEL = id("map_editor_enter");
    public static final ResourceLocation SELECTION_REQUEST = id("map_editor_selection_request");
    public static final ResourceLocation SELECTION_PAYLOAD = id("map_editor_selection_payload");
    public static final ResourceLocation RENAME_LOOT = id("map_editor_rename_loot");
    public static final ResourceLocation DELETE_LOOT = id("map_editor_delete_loot");
    public static final ResourceLocation CLEAR_SELECTION = id("map_editor_clear_selection");
    public static final ResourceLocation STATE_REQUEST = id("map_editor_state_request");
    /** 详情面板「点击关联存档」：客户端只发存档名，绝对路径由服务端解析。 */
    public static final ResourceLocation LINK_ARCHIVE_CHANNEL = id("map_editor_link_archive");
    /**
     * r109 模块一：毒圈阶段配置保存（C2S）。GUI 详情面板编辑完
     * {@code waitTime / shrinkTime / radius / damage} 后整表发回，
     * 服务端按该地图选区做半径硬限制、夹合法后写进地图 JSON。
     */
    public static final ResourceLocation ZONE_SAVE_CHANNEL = id("map_editor_zone_save");

    private static ResourceLocation id(String path) { return new ResourceLocation(BattleRoyaleMod.MOD_ID, path); }
    private MapEditorSync() {}

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(ENTER_CHANNEL, (server, player, handler, buf, response) -> {
            String name = buf.readUtf(128);
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2)) return;
                String error = EditWorldManager.enter(player, name);
                player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(error == null
                        ? "已进入 " + name + "，用工具圈选区域和物资点" : error), false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SELECTION_REQUEST, (server, player, handler, buf, response) ->
                server.execute(() -> { if (player.createCommandSourceStack().hasPermission(2)) sendSelection(player); }));
        ServerPlayNetworking.registerGlobalReceiver(CLEAR_SELECTION, (server, player, handler, buf, response) ->
                server.execute(() -> { if (player.createCommandSourceStack().hasPermission(2)) { MapEditSession.SELECTIONS.remove(player.getUUID()); sendSelection(player); } }));
        ServerPlayNetworking.registerGlobalReceiver(RENAME_LOOT, (server, player, handler, buf, response) -> {
            int index = buf.readVarInt(); String name = buf.readUtf(128); int font = buf.readVarInt();
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2)) return;
                GameMap draft = MapEditSession.draftOf(player.getUUID());
                BattleRoyaleMod.LOGGER.info("RENAME_LOOT: index={}, name={}, font={}", index, name, font);
                // 先按 index 命中草稿，再用同一坐标在真实地图对象上做同样的改动（落盘 + 回推）。
                if (draft.lootPoints == null || index < 0 || index >= draft.lootPoints.size()) {
                    return;
                }
                LootPoint target = draft.lootPoints.get(index);
                GameMap real = realMapForLootEdit(player, draft);
                if (real == null) {
                    // 真实地图不在服务端内存里（新建未保存的草稿）：只改草稿。
                    target.setName(name);
                    target.setFontSize(Math.max(6, Math.min(32, font)));
                    sendOpen(player, draft);
                    return;
                }
                if (applyLootRename(real, target, name, font)) {
                    persistLootEdit(server, player, real);
                    sendOpen(player, real.copy());
                } else {
                    target.setName(name);
                    target.setFontSize(Math.max(6, Math.min(32, font)));
                    sendOpen(player, draft);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(DELETE_LOOT, (server, player, handler, buf, response) -> {
            int index = buf.readVarInt();
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2)) return;
                GameMap draft = MapEditSession.draftOf(player.getUUID());
                if (draft.lootPoints == null || index < 0 || index >= draft.lootPoints.size()) {
                    return;
                }
                LootPoint target = draft.lootPoints.get(index);
                GameMap real = realMapForLootEdit(player, draft);
                if (real == null) {
                    draft.lootPoints.remove(index);
                    sendOpen(player, draft);
                    return;
                }
                if (applyLootDelete(real, target)) {
                    persistLootEdit(server, player, real);
                    sendOpen(player, real.copy());
                    MapEditSession.DRAFTS.put(player.getUUID(), real.copy());
                } else {
                    draft.lootPoints.remove(index);
                    sendOpen(player, draft);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SAVE_CHANNEL, (server, player, handler, buf, response) -> {
            try {
                MapEditorSyncPayload.Decoded decoded = MapEditorSyncPayload.readDecoded(buf);
                GameMap map = decoded.map;
                boolean detailUpdate = decoded.detailUpdate;
                boolean lootListComplete = decoded.lootListComplete;
                server.execute(() -> {
                    if (!player.createCommandSourceStack().hasPermission(2)) return;
                    if (map.mapId == null || !map.mapId.matches("[A-Za-z0-9_-]+")) {
                        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                                "地图 ID 只能包含字母、数字、下划线和短横线：" + map.mapId), false);
                        return;
                    }
                    // 防重名第二道闸（服务端权威拦截，防止两个管理员同时新建撞车）：
                    // 同 ID 已存在时，只有「详情面板保存」或「草稿本来就装载着这张同名地图」才放行，
                    // 否则说明是拿新地图去覆盖别人已存在的 mapId，直接拒绝，不写 JSON。
                    GameMap existing = MapManager.INSTANCE.getMap(map.mapId);
                    GameMap draft = MapEditSession.draftOf(player.getUUID());
                    boolean editingSame = map.mapId.equals(draft.mapId);
                    if (existing != null && !detailUpdate && !editingSame) {
                        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                                "地图ID " + map.mapId + " 已存在，请更换其他ID！"), false);
                        BattleRoyaleMod.LOGGER.warn("拒绝保存：mapId={} 已存在且不是当前草稿对应地图（发送者={}）",
                                map.mapId, player.getName().getString());
                        return;
                    }
                    if (detailUpdate) {
                        // 详情面板保存：只把「显示名称 / 源存档 / 物资点名称字号」合并进服务端真实对象，
                        // 选区、出生点、上限人数等一律取真实对象的原值，绝不用客户端那份可能不完整的副本覆盖。
                        saveDetailConfiguration(player, map, lootListComplete);
                        return;
                    }
                    SelectionData s = MapEditSession.selectionOf(player.getUUID());
                    if (s.isComplete()) { map.minX=s.minX(); map.maxX=s.maxX(); map.minZ=s.minZ(); map.maxZ=s.maxZ(); }
                    if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) map.sourceArchivePath=s.archivePath;
                    // 「草稿保存」通道的兜底：草稿是空壳（没从 JSON 装载过物资点 / 源存档）时，
                    // 绝不允许把已有地图的物资点和源存档路径抹掉——这正是物资点被清零的另一条路径。
                    if (existing != null) {
                        if (map.lootPoints == null || map.lootPoints.isEmpty()) {
                            if (existing.lootPoints != null && !existing.lootPoints.isEmpty()) {
                                map.lootPoints = new ArrayList<>(existing.lootPoints);
                                BattleRoyaleMod.LOGGER.warn("保存地图 {} 的草稿里没有物资点，已保留原有 {} 个",
                                        map.mapId, existing.lootPoints.size());
                                player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                                        "这份草稿里没有物资点，已保留地图上原有的 "
                                        + existing.lootPoints.size() + " 个（要改单个点请在地图详情里操作）"), false);
                            }
                        }
                        if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) {
                            map.sourceArchivePath = existing.sourceArchivePath == null ? "" : existing.sourceArchivePath;
                        }
                        // 草稿没带的运营参数（人数上限 / 房间数 / 队伍上限）沿用原值，避免被默认值覆盖。
                        map.maxPlayers = map.maxPlayers <= 0 ? existing.maxPlayers : map.maxPlayers;
                        map.maxRooms = map.maxRooms <= 0 ? existing.maxRooms : map.maxRooms;
                        map.teamLimit = map.teamLimit <= 0 ? existing.teamLimit : map.teamLimit;
                    }
                    if (map.sourceArchivePath == null || map.sourceArchivePath.isBlank()) {
                        BattleRoyaleMod.LOGGER.warn("保存地图 {} 时 sourceArchivePath 仍为空：游戏维度将无法复制区块。"
                                + "请先在地图编辑器里进入该地图的源存档再保存。mapId={}", map.mapId, map.mapId);
                        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                                "提示：这张地图还没有关联源存档，游戏维度会生成不出地形。"
                                + "可在地图详情的「源存档」一行点「点击关联存档」直接选择。"), false);
                    }
                    MapManager.INSTANCE.saveMap(map);
                    MapEditSession.DRAFTS.put(player.getUUID(), map.copy());
                    player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of("地图 " + map.mapId + " 已保存"), false);
                    MapListSync.send(player);
                });
            } catch (RuntimeException e) { BattleRoyaleMod.LOGGER.warn("地图编辑器保存包解析失败: {}", e.toString()); }
        });
        ServerPlayNetworking.registerGlobalReceiver(LINK_ARCHIVE_CHANNEL, (server, player, handler, buf, response) -> {
            String mapId;
            String archiveName;
            try {
                mapId = buf.readUtf(64);
                archiveName = buf.readUtf(256);
            } catch (RuntimeException e) {
                return;
            }
            final String targetMapId = mapId;
            final String targetArchive = archiveName;
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2)) return;
                linkArchive(player, targetMapId, targetArchive);
            });
        });
        // r109 模块一：毒圈阶段配置 C2S 保存。整包在收包线程只做解码，
        // 校验 / 夹硬限制 / 落盘全部 server.execute 回主线程做（与物资点同一模式）。
        ServerPlayNetworking.registerGlobalReceiver(ZONE_SAVE_CHANNEL, (server, player, handler, buf, response) -> {
            String mapId;
            List<com.paimeng.battleroyale.map.ZonePhaseConfig> phases;
            try {
                mapId = buf.readUtf(64);
                phases = MapEditorSyncPayload.readZonePhases(buf);
            } catch (RuntimeException e) {
                return;
            }
            final String targetMapId = mapId;
            final List<com.paimeng.battleroyale.map.ZonePhaseConfig> incoming = phases;
            server.execute(() -> {
                if (!player.createCommandSourceStack().hasPermission(2)) {
                    return;
                }
                saveZonePhases(player, targetMapId, incoming);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(REFRESH_CHANNEL, (server, player, handler, buf, response) -> server.execute(() -> {
            if (player.createCommandSourceStack().hasPermission(2)) sendArchives(player, MapManager.INSTANCE.scanImports());
        }));
        ServerPlayNetworking.registerGlobalReceiver(STATE_REQUEST, (server, player, handler, buf, response) -> server.execute(() -> {
            if (player.createCommandSourceStack().hasPermission(2)) {
                // 与 /paimengmods manager gui 同一套解析：玩家站在游戏维度里时，
                // GUI 要显示的是 MapManager 里的真实地图（含刚加的物资点），不是空草稿。
                sendOpen(player, BrCommand.resolveEditorMap(player));
            }
        }));
    }

    /**
     * GUI 里改/删物资点时，找到要落盘的「真实地图对象」：
     * 优先玩家当前所在游戏维度对应的地图，其次草稿 mapId 在 MapManager 里能查到的地图。
     * 两处都找不到（新建还没保存的草稿）返回 null，调用方退回只改草稿的旧行为。
     */
    private static GameMap realMapForLootEdit(ServerPlayer player, GameMap draft) {
        if (GameWorldManager.isGameWorld(player.level().dimension())) {
            String mapId = GameWorldManager.resolveMapId(player.level().dimension());
            GameMap byWorld = mapId == null ? null : MapManager.INSTANCE.getMap(mapId);
            if (byWorld != null) {
                return byWorld;
            }
        }
        if (draft.mapId != null && !draft.mapId.isBlank() && !"default".equals(draft.mapId)) {
            return MapManager.INSTANCE.getMap(draft.mapId);
        }
        return null;
    }

    /** 在真实地图上按坐标命中草稿里那个点并改名字/字号；命中不了返回 false。 */
    private static boolean applyLootRename(GameMap real, LootPoint draftPoint, String name, int font) {
        LootPoint target = findLootAt(real, draftPoint);
        if (target == null) {
            return false;
        }
        target.setName(name);
        target.setFontSize(Math.max(6, Math.min(32, font)));
        BattleRoyaleMod.LOGGER.info("[LootEdit] 真实地图 {} 的物资点 ({}, {}) 已改名为 {}，字号 {}",
                real.mapId, draftPoint.x(), draftPoint.z(), name, font);
        return true;
    }

    /** 在真实地图上按坐标删除草稿里那个点；命中不了返回 false。 */
    private static boolean applyLootDelete(GameMap real, LootPoint draftPoint) {
        LootPoint target = findLootAt(real, draftPoint);
        if (target == null || real.lootPoints == null) {
            return false;
        }
        real.lootPoints.remove(target);
        BattleRoyaleMod.LOGGER.info("[LootEdit] 真实地图 {} 的物资点 {} ({}, {}) 已删除，剩 {} 个",
                real.mapId, draftPoint.name(), draftPoint.x(), draftPoint.z(), real.lootPoints.size());
        return true;
    }

    /** 坐标（取整）匹配物资点。 */
    private static LootPoint findLootAt(GameMap map, LootPoint point) {
        if (map.lootPoints == null || point == null) {
            return null;
        }
        for (LootPoint candidate : map.lootPoints) {
            if (Math.round(candidate.x()) == Math.round(point.x())
                    && Math.round(candidate.z()) == Math.round(point.z())) {
                return candidate;
            }
        }
        return null;
    }

    /** 物资点改动落盘 + 三路回推：编辑草稿 / GUI 地图列表 / 维度内玩家的 HUD。 */
    private static void persistLootEdit(MinecraftServer server, ServerPlayer player, GameMap real) {
        MapManager.INSTANCE.saveMap(real);
        MapEditSession.DRAFTS.put(player.getUUID(), real.copy());
        MapListSync.send(player);
        LootPointsSync.broadcastToDimension(server, real);
        BattleRoyaleMod.LOGGER.info("[LootEdit] 地图 {} 已 saveMap 落盘（物资点 {} 个）",
                real.mapId, real.lootPoints == null ? 0 : real.lootPoints.size());
    }

    /**
     * 详情面板保存的唯一落点：改动一律写到 {@code MapManager.INSTANCE.getMap(mapId)} 取到的
     * 真实内存对象上，客户端送来的那份只当「改动意图」，不作为覆盖蓝本。
     * 这样即使客户端那份是空壳（旧版本 / 列表包缺字段），也绝不会把 JSON 里的物资点清零。
     */
    private static void saveDetailConfiguration(ServerPlayer player, GameMap incoming, boolean lootListComplete) {
        GameMap real = MapManager.INSTANCE.getMap(incoming.mapId);
        if (real == null) {
            player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                    "地图 " + incoming.mapId + " 在服务端不存在，保存已取消（不会新建空壳覆盖）"), false);
            BattleRoyaleMod.LOGGER.warn("详情面板保存被拒：mapId={} 不在内存地图表里（发送者={}）",
                    incoming.mapId, player.getName().getString());
            return;
        }
        String displayName = incoming.displayName == null ? "" : incoming.displayName.trim();
        if (!displayName.isEmpty()) {
            real.displayName = displayName;
        }
        String archive = incoming.sourceArchivePath == null ? "" : incoming.sourceArchivePath.trim();
        if (!archive.isBlank()) {
            // 客户端传来的路径必须真的存在才写入；只给了存档目录名时按 import 目录解析成绝对路径。
            java.nio.file.Path asPath = java.nio.file.Path.of(archive);
            java.nio.file.Path resolved = java.nio.file.Files.isDirectory(asPath)
                    ? asPath : MapManager.INSTANCE.resolveImportPath(lastSegmentOf(archive));
            if (resolved != null) {
                real.sourceArchivePath = resolved.toAbsolutePath().normalize().toString();
            } else {
                player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                        "存档路径「" + archive + "」在服务端不存在，已保留地图原来的源存档设置"), false);
                BattleRoyaleMod.LOGGER.warn("详情面板保存地图 {}：传来的存档路径 {} 无效，保留原值 {}",
                        real.mapId, archive, real.sourceArchivePath);
            }
        }
        // [BR-FLIGHT-SPEED] 飞机速度：解码时已 normalizeFlightSpeed 治理过（0.5~20 内），直接合并。
        real.flightSpeed = incoming.flightSpeed;
        // [BR-MATCHTEAM] maxTeams / maxTeamSize：解码时已 normalizeMatchLimits 治理过（2~25 / 1~4），直接合并。
        real.maxTeams = incoming.maxTeams;
        real.maxTeamSize = incoming.maxTeamSize;
        int changed = applyDetailLootPoints(real, incoming.lootPoints, lootListComplete);
        MapManager.INSTANCE.saveMap(real);
        MapEditSession.DRAFTS.put(player.getUUID(), real.copy());
        LootPointsSync.broadcastToDimension(player.getServer(), real);
        int total = real.lootPoints == null ? 0 : real.lootPoints.size();
        boolean linked = real.sourceArchivePath != null && !real.sourceArchivePath.isBlank();
        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of("地图 " + real.mapId + " 配置已保存：显示名 "
                + real.displayName + "，飞机速度 " + real.flightSpeed + " 格/tick，物资点 " + total
                + " 个（本次改动 " + changed + " 个）"
                + (linked ? "，已关联源存档" : "，源存档仍未关联")), false);
        BattleRoyaleMod.LOGGER.info("详情面板保存地图 {}：显示名={}，源存档={}，飞机速度={} 格/tick，物资点 {} 个（本次改动 {} 个），"
                        + "选区保持 X {}~{}，Z {}~{}",
                real.mapId, real.displayName, real.sourceArchivePath, real.flightSpeed, total, changed,
                real.minX, real.maxX, real.minZ, real.maxZ);
        MapListSync.send(player);
    }

    /**
     * 把详情面板的物资点改动合并进真实对象：按坐标匹配原有点，只改名字和字号，
     * 匹配不上的按坐标补齐；面板里删掉的点（真实有、列表没有）才真正删除。
     *
     * <p>列表为空的两种解释要分清：
     * {@code lootListComplete=false}（旧客户端 / 空壳包）视为「没打算改物资点」，保留原数据；
     * {@code lootListComplete=true}（新客户端详情面板发出的完整权威列表）时空列表就是「删光物资点」。
     */
    private static int applyDetailLootPoints(GameMap real, List<LootPoint> incoming, boolean lootListComplete) {
        List<LootPoint> stored = real.lootPoints == null ? new ArrayList<>() : new ArrayList<>(real.lootPoints);
        if (incoming == null || incoming.isEmpty()) {
            real.lootPoints = stored;
            if (!stored.isEmpty() && lootListComplete) {
                real.lootPoints = new ArrayList<>();
                BattleRoyaleMod.LOGGER.info("详情面板送来了完整的空物资点列表：已删除地图 {} 的全部 {} 个物资点",
                        real.mapId, stored.size());
                return stored.size();
            }
            if (!stored.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("详情面板送来的物资点列表为空，已保留地图 {} 原有的 {} 个物资点",
                        real.mapId, stored.size());
            }
            return 0;
        }
        List<LootPoint> remaining = new ArrayList<>(stored);
        List<LootPoint> result = new ArrayList<>(incoming.size());
        int changed = 0;
        for (LootPoint p : incoming) {
            LootPoint match = null;
            for (LootPoint candidate : remaining) {
                if (Math.round(candidate.x()) == Math.round(p.x())
                        && Math.round(candidate.z()) == Math.round(p.z())) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                result.add(new LootPoint(p.x(), p.z(), p.name(), p.fontSize()));
                changed++;
                continue;
            }
            remaining.remove(match);
            if (!match.name().equals(p.name()) || match.fontSize() != p.fontSize()) {
                changed++;
            }
            match.setName(p.name());
            match.setFontSize(Math.max(6, Math.min(32, p.fontSize())));
            result.add(match);
        }
        if (!remaining.isEmpty()) {
            changed += remaining.size();
            BattleRoyaleMod.LOGGER.info("详情面板删除了地图 {} 的 {} 个物资点", real.mapId, remaining.size());
        }
        real.lootPoints = result;
        return changed;
    }

    /** 取路径最后一级目录名（统一斜杠、去尾部斜杠）。 */
    private static String lastSegmentOf(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int idx = s.lastIndexOf('/');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    /**
     * r109 模块一：毒圈阶段配置落盘的唯一落点。
     *
     * <p>和物资点同一原则：客户端送来的那份只当「改动意图」，一律写到
     * {@code MapManager} 的真实内存对象上再存 JSON；每条阶段先夹硬限制
     * （秒数 30~900、半径 0~选区短边一半、伤害 0~100），夹掉多少条回话里说清楚。
     * 空列表是合法输入（= 清空自定义，运行时用按选区缩放的默认表）。
     */
    private static void saveZonePhases(ServerPlayer player, String mapId,
                                       List<com.paimeng.battleroyale.map.ZonePhaseConfig> incoming) {
        if (mapId == null || !mapId.matches("[A-Za-z0-9_-]+")) {
            player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                    "毒圈保存被拒：地图 ID 不合法（" + mapId + "）"), false);
            return;
        }
        GameMap real = MapManager.INSTANCE.getMap(mapId);
        if (real == null) {
            player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                    "毒圈保存被拒：地图 " + mapId + " 在服务端不存在"), false);
            return;
        }
        // 半径硬限制的基准：该地图选区短边的一半（用真实对象上的选区，不信客户端）。
        double maxRadius = com.paimeng.battleroyale.zone.ZonePlanner.Bounds
                .of(real.minX, real.maxX, real.minZ, real.maxZ).maxRadius();
        List<com.paimeng.battleroyale.map.ZonePhaseConfig> cleaned = new ArrayList<>();
        int clamped = 0;
        int holes = 0;
        for (com.paimeng.battleroyale.map.ZonePhaseConfig raw : incoming) {
            if (cleaned.size() >= com.paimeng.battleroyale.map.ZonePhaseConfig.MAX_PHASES) {
                break;
            }
            // [r131 索引防错位] null 用默认值占位补齐，**不跳过**：跳过会让落盘的
            // 阶段列表整体前移，config[1] 顶替 config[0]，开局白圈小一档。
            com.paimeng.battleroyale.map.ZonePhaseConfig source = raw;
            if (source == null) {
                source = new com.paimeng.battleroyale.map.ZonePhaseConfig();
                holes++;
            }
            com.paimeng.battleroyale.map.ZonePhaseConfig phase = source.copy();
            int beforeWait = phase.waitTime;
            int beforeShrink = phase.shrinkTime;
            double beforeRadius = phase.radius;
            double beforeDamage = phase.damage;
            phase.clampIntoRange(maxRadius);
            if (beforeWait != phase.waitTime || beforeShrink != phase.shrinkTime
                    || beforeRadius != phase.radius || beforeDamage != phase.damage) {
                clamped++;
            }
            cleaned.add(phase);
        }
        real.zonePhases = cleaned;
        MapManager.INSTANCE.saveMap(real);
        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                "毒圈配置已保存：" + real.mapId + " 共 " + cleaned.size() + " 个阶段"
                        + (holes > 0 ? "（补正 " + holes + " 个空位，阶段序号不会前移）" : "")
                        + (clamped > 0 ? "（其中 " + clamped + " 个阶段被夹到合法范围，半径上限 "
                        + (int) Math.floor(maxRadius) + " 米）" : "（半径上限 " + (int) Math.floor(maxRadius) + " 米）")), false);
        BattleRoyaleMod.LOGGER.info("[BR-ZONE] 地图 {} 毒圈配置落盘：{} 个阶段（夹正 {} 个，空位补默认 {} 个，半径上限 {}）",
                real.mapId, cleaned.size(), clamped, holes, maxRadius);
        // 把最新的真实地图推回列表 → GUI 详情面板自动刷新成服务端夹好后的值。
        MapListSync.send(player);
    }

    /** 详情面板「点击关联存档」：服务端按存档名解析绝对路径写入并自动保存。 */
    private static void linkArchive(ServerPlayer player, String mapId, String archiveName) {
        GameMap real = MapManager.INSTANCE.getMap(mapId);
        if (real == null) {
            player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of("找不到地图 " + mapId + "，关联已取消"), false);
            return;
        }
        java.nio.file.Path resolved = MapManager.INSTANCE.resolveImportPath(archiveName);
        if (resolved == null) {
            player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(
                    "在 config/battleroyale/import/ 下没有找到存档「" + archiveName
                    + "」，请确认它已解压，且里面有 level.dat 和 region 文件夹"), false);
            BattleRoyaleMod.LOGGER.warn("关联存档失败：mapId={}，存档名={}（import 目录下不存在）", mapId, archiveName);
            return;
        }
        real.sourceArchivePath = resolved.toAbsolutePath().normalize().toString();
        MapManager.INSTANCE.saveMap(real);
        MapEditSession.DRAFTS.put(player.getUUID(), real.copy());
        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of("地图 " + real.mapId + " 已关联存档「"
                + resolved.getFileName() + "」并自动保存：" + real.sourceArchivePath), false);
        boolean hasSelection = !(real.minX == 0 && real.minZ == 0 && real.maxX == 0 && real.maxZ == 0);
        player.displayClientMessage(com.paimeng.battleroyale.util.BrText.of(hasSelection
                ? "下一步：游戏维度已生成过的话执行 /paimengmods manager game rebuild " + real.mapId
                  + " 重新复制区块；要改范围点详情里的「进入编辑副本去圈选」"
                : "下一步：点详情里的「进入编辑副本去圈选」圈好区域并保存，再生成游戏维度"), false);
        BattleRoyaleMod.LOGGER.info("地图 {} 的 sourceArchivePath 已写入：{}", real.mapId, real.sourceArchivePath);
        MapListSync.send(player);
    }

    private static void sendSelection(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, SELECTION_PAYLOAD)) return;
        SelectionData s = MapEditSession.SELECTIONS.get(player.getUUID());
        var b = PacketByteBufs.create();
        b.writeBoolean(s != null && s.isComplete());
        if (s != null && s.isComplete()) { b.writeInt(s.minX()); b.writeInt(s.minZ()); b.writeInt(s.maxX()); b.writeInt(s.maxZ()); }
        ServerPlayNetworking.send(player, SELECTION_PAYLOAD, b);
    }
    public static void sendOpen(ServerPlayer player, GameMap map) {
        if (player != null && map != null && ServerPlayNetworking.canSend(player, OPEN_CHANNEL)) {
            var b = PacketByteBufs.create();
            MapEditorSyncPayload.write(b, map);
            ServerPlayNetworking.send(player, OPEN_CHANNEL, b);
            BattleRoyaleMod.LOGGER.info("sendOpen 物资点字号列表: {}", map.lootPoints == null
                    ? List.of() : map.lootPoints.stream().map(LootPoint::fontSize).toList());
            BattleRoyaleMod.LOGGER.info("已同步 {} 个物资点到客户端", map.lootPoints == null ? 0 : map.lootPoints.size());
        }
    }
    public static void sendArchives(ServerPlayer player, List<ArchiveEntry> entries) {
        if (player != null && ServerPlayNetworking.canSend(player, ARCHIVES_CHANNEL)) { var b=PacketByteBufs.create(); MapEditorSyncPayload.writeArchives(b,entries); ServerPlayNetworking.send(player,ARCHIVES_CHANNEL,b); }
    }
    public static void sendSave(GameMap map) { sendSave(map, false); }
    /** detailUpdate=true 表示来自地图详情面板的配置保存（服务端据此跳过会话选区覆盖）。 */
    public static void sendSave(GameMap map, boolean detailUpdate) { var b=PacketByteBufs.create(); MapEditorSyncPayload.write(b,map,detailUpdate); net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(SAVE_CHANNEL,b); }
    public static void requestArchives() { net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(REFRESH_CHANNEL,PacketByteBufs.create()); }
    public static void requestCurrentState() { net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(STATE_REQUEST,PacketByteBufs.create()); }
    public static void enterArchive(String name) { var b=PacketByteBufs.create(); b.writeUtf(name,128); net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(ENTER_CHANNEL,b); }
    public static void requestSelection() { net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(SELECTION_REQUEST,PacketByteBufs.create()); }
    public static void clearSelection() { net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(CLEAR_SELECTION,PacketByteBufs.create()); }
    public static void renameLoot(int i,String n,int f) { var b=PacketByteBufs.create(); b.writeVarInt(i); b.writeUtf(n,128); b.writeVarInt(f); net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(RENAME_LOOT,b); }
    public static void deleteLoot(int i) { var b=PacketByteBufs.create(); b.writeVarInt(i); net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(DELETE_LOOT,b); }
    /** 详情面板：把地图关联到 import 目录下的某个存档（服务端解析绝对路径并自动保存）。 */
    public static void linkArchive(String mapId, String archiveName) {
        var b = PacketByteBufs.create();
        b.writeUtf(mapId == null ? "" : mapId, 64);
        b.writeUtf(archiveName == null ? "" : archiveName, 256);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(LINK_ARCHIVE_CHANNEL, b);
    }

    /** r109 模块一：详情面板「保存毒圈」——整表 C2S 发给服务端校验并落盘（回执走地图列表回推）。 */
    public static void saveZonePhases(String mapId, List<com.paimeng.battleroyale.map.ZonePhaseConfig> phases) {
        var b = PacketByteBufs.create();
        b.writeUtf(mapId == null ? "" : mapId, 64);
        MapEditorSyncPayload.writeZonePhases(b, phases);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(ZONE_SAVE_CHANNEL, b);
    }
}
