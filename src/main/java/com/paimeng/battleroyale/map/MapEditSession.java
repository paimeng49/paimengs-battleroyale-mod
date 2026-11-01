package com.paimeng.battleroyale.map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.paimeng.battleroyale.BattleRoyaleMod;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员进出副本世界时的返回点和当前选区。 */
public final class MapEditSession {
    public static final Map<UUID, SelectionData> SELECTIONS = new ConcurrentHashMap<>();
    public static final Map<UUID, ReturnPoint> RETURNS = new ConcurrentHashMap<>();
    public static final Map<UUID, GameMap> DRAFTS = new ConcurrentHashMap<>();
    /** 出生岛工具（spawn_tool）在每个游戏维度里暂存的角 1 / 角 2，key = 玩家 UUID。 */
    public static final Map<UUID, SelectionData> SPAWN_SELECTIONS = new ConcurrentHashMap<>();

    private MapEditSession() {}

    public static SelectionData selectionOf(UUID id) {
        return SELECTIONS.computeIfAbsent(id, ignored -> new SelectionData());
    }

    /** 出生岛工具的角点暂存（与区域选区互不影响）。 */
    public static SelectionData spawnSelectionOf(UUID id) {
        return SPAWN_SELECTIONS.computeIfAbsent(id, ignored -> new SelectionData());
    }

    public static GameMap draftOf(UUID id) {
        return DRAFTS.computeIfAbsent(id, ignored -> new GameMap());
    }

    /**
     * 用地图配置的 sourceArchivePath 初始化草稿；副本维度 ID 只用于挂载世界，不能作为地图 JSON key。
     * 同一个存档再次打开时保留草稿中的未保存修改。
     */
    public static GameMap prepareDraftForArchive(UUID id, String sourceArchivePath) {
        GameMap draft = draftOf(id);
        if (sourceArchivePath == null || sourceArchivePath.isBlank()) {
            return draft;
        }
        if (sourceArchivePath.equals(draft.sourceArchivePath)) {
            return draft;
        }
        GameMap saved = null;
        if (draft.mapId != null && !draft.mapId.isBlank() && !"default".equals(draft.mapId)) {
            saved = MapManager.INSTANCE.getMap(draft.mapId);
        }
        if (saved == null) {
            saved = MapManager.INSTANCE.findBySourceArchivePath(sourceArchivePath);
        }
        if (saved != null) {
            GameMap copy = saved.copy();
            DRAFTS.put(id, copy);
            BattleRoyaleMod.LOGGER.info("已按 mapId={} 加载物资点配置：{} 个，路径={}",
                    copy.mapId, copy.lootPoints == null ? 0 : copy.lootPoints.size(), sourceArchivePath);
            return copy;
        }
        draft.sourceArchivePath = sourceArchivePath;
        BattleRoyaleMod.LOGGER.info("未找到源存档对应的地图配置：路径={}", sourceArchivePath);
        return draft;
    }

    public static void rememberReturn(ServerPlayer player) {
        RETURNS.put(player.getUUID(), new ReturnPoint(
                player.level().dimension(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()));
    }

    public record ReturnPoint(ResourceKey<Level> world, double x, double y, double z, float yaw, float pitch) {
        public Vec3 pos() {
            return new Vec3(x, y, z);
        }
    }
}
