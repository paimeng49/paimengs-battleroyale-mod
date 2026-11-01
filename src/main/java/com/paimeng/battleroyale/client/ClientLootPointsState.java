package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.network.LootPointsSync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/** 客户端物资点缓存：只在收到 S2C 包时更新，HUD 每帧只读这里。 */
@Environment(EnvType.CLIENT)
public final class ClientLootPointsState {
    private static volatile String mapId = "";
    private static volatile List<LootPoint> points = List.of();

    private ClientLootPointsState() {
    }

    public static void apply(LootPointsSync.Payload payload) {
        if (payload == null) {
            clear();
            return;
        }
        mapId = payload.mapId() == null ? "" : payload.mapId();
        points = payload.points() == null ? List.of() : List.copyOf(payload.points());
    }

    public static void clear() {
        mapId = "";
        points = List.of();
    }

    public static String mapId() {
        return mapId;
    }

    public static List<LootPoint> points() {
        List<LootPoint> snapshot = points;
        return snapshot == null ? List.of() : snapshot;
    }

    /**
     * 当前维度是否可以显示物资点。
     * 进入游戏维度后：必须正好是该地图对应的 battleroyale_game 维度。
     * 还没进维度（join 后在主世界等待）：只要已经收到该地图的物资点包，就显示。
     */
    public static boolean visibleIn(ResourceKey<Level> worldKey) {
        String id = mapId;
        List<LootPoint> snapshot = points;
        if (worldKey == null || id.isEmpty() || snapshot.isEmpty()) {
            return false;
        }
        ResourceLocation value = worldKey.location();
        if (GameWorldManager.DIMENSION_NAMESPACE.equals(value.getNamespace())) {
            return MapManager.safeId(id).toLowerCase().equals(value.getPath());
        }
        return true;
    }
}
