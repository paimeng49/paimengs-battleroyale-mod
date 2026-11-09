package com.paimeng.battleroyale.map;

import com.paimeng.battleroyale.item.ModItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import java.util.List;

/** 手持工具时在选区、物资点和出生岛位置刷 END_ROD 光柱。 */
public final class MapEditMarker {
    /** 出生岛边界光柱之间的间距（格）。 */
    private static final int SPAWN_EDGE_STEP = 32;

    private MapEditMarker() {}

    public static void tick(ServerPlayer player, ServerLevel world) {
        boolean holdingSelection = player.getMainHandItem().is(ModItems.SELECTION_TOOL)
                || player.getOffhandItem().is(ModItems.SELECTION_TOOL);
        boolean holdingLoot = player.getMainHandItem().is(ModItems.LOOT_TOOL)
                || player.getOffhandItem().is(ModItems.LOOT_TOOL);
        boolean holdingChest = player.getMainHandItem().is(ModItems.CHEST_SPAWN_TOOL)
                || player.getOffhandItem().is(ModItems.CHEST_SPAWN_TOOL);
        if (!holdingSelection && !holdingLoot && !holdingChest) {
            return;
        }
        if (holdingSelection) {
            SelectionData selection = MapEditSession.selectionOf(player.getUUID());
            if (selection.hasPoint1()) {
                beam(world, player, selection.x1, selection.z1);
            }
            if (selection.hasPoint2()) {
                beam(world, player, selection.x2, selection.z2);
            }
        }
        GameMap draft = MapEditSession.draftOf(player.getUUID());
        if (holdingLoot && draft != null && draft.lootPoints != null) {
            for (LootPoint point : draft.lootPoints) {
                beam(world, player, (int) Math.floor(point.x()), (int) Math.floor(point.z()));
            }
        }
        if (holdingChest && draft != null) {
            markManualSpawns(world, player, draft.manualLootSpawns);
        }
    }

    /**
     * 游戏维度版光柱标记：
     * <ul>
     *   <li>手持 spawn_tool：画出生岛边界矩形一圈 END_ROD 光柱（四角密、边上每 {@value #SPAWN_EDGE_STEP} 格一根）；
     *       已记录但未保存的角 1/角 2 也各画一根，方便对齐；</li>
     *   <li>手持 loot_tool：给该地图已保存的物资点画光柱（编辑维度里画的是草稿，这里画正式数据）。</li>
     * </ul>
     * 只对手持对应工具的玩家本人可见（服务端按玩家的距离筛选发包，不新增网络协议）。
     */
    public static void tickGame(ServerPlayer player, ServerLevel world, GameMap map) {
        boolean holdingSpawn = player.getMainHandItem().is(ModItems.SPAWN_TOOL)
                || player.getOffhandItem().is(ModItems.SPAWN_TOOL);
        if (holdingSpawn) {
            SelectionData pending = MapEditSession.SPAWN_SELECTIONS.get(player.getUUID());
            if (pending != null) {
                if (pending.hasPoint1()) {
                    beam(world, player, pending.x1, pending.z1);
                }
                if (pending.hasPoint2()) {
                    beam(world, player, pending.x2, pending.z2);
                }
            }
            if (map.spawnAreaSet) {
                spawnBoundaryBeams(world, player, map);
            }
        }
        boolean holdingLoot = player.getMainHandItem().is(ModItems.LOOT_TOOL)
                || player.getOffhandItem().is(ModItems.LOOT_TOOL);
        if (holdingLoot && map.lootPoints != null) {
            for (LootPoint point : map.lootPoints) {
                beam(world, player, (int) Math.floor(point.x()), (int) Math.floor(point.z()));
            }
        }
        boolean holdingChest = player.getMainHandItem().is(ModItems.CHEST_SPAWN_TOOL)
                || player.getOffhandItem().is(ModItems.CHEST_SPAWN_TOOL);
        if (holdingChest) {
            markManualSpawns(world, player, map.manualLootSpawns);
        }
    }

    /** 工具标记的是具体箱子坐标，在准确方块位置生成一小团光粒，便于左键点选删除。 */
    private static void markManualSpawns(ServerLevel world, ServerPlayer player,
                                         List<ManualLootSpawn> spawns) {
        if (spawns == null) {
            return;
        }
        for (ManualLootSpawn spawn : spawns) {
            if (spawn == null) {
                continue;
            }
            BlockPos pos = spawn.blockPos();
            world.sendParticles(player, ParticleTypes.END_ROD, false,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    4, 0.2D, 0.2D, 0.2D, 0.0D);
        }
    }

    /** 出生岛矩形边界：四角各一根完整光柱，四条边每隔 SPAWN_EDGE_STEP 补一根。 */
    private static void spawnBoundaryBeams(ServerLevel world, ServerPlayer player, GameMap map) {
        int minX = Math.min(map.spawnMinX, map.spawnMaxX);
        int maxX = Math.max(map.spawnMinX, map.spawnMaxX);
        int minZ = Math.min(map.spawnMinZ, map.spawnMaxZ);
        int maxZ = Math.max(map.spawnMinZ, map.spawnMaxZ);
        beam(world, player, minX, minZ);
        beam(world, player, minX, maxZ);
        beam(world, player, maxX, minZ);
        beam(world, player, maxX, maxZ);
        for (int x = minX + SPAWN_EDGE_STEP; x < maxX; x += SPAWN_EDGE_STEP) {
            beam(world, player, x, minZ);
            beam(world, player, x, maxZ);
        }
        for (int z = minZ + SPAWN_EDGE_STEP; z < maxZ; z += SPAWN_EDGE_STEP) {
            beam(world, player, minX, z);
            beam(world, player, maxX, z);
        }
    }

    private static void beam(ServerLevel world, ServerPlayer player, int x, int z) {
        int minY = world.getMinBuildHeight();
        int maxY = world.getMaxBuildHeight();
        for (int y = minY; y < maxY; y += 4) {
            world.sendParticles(player, ParticleTypes.END_ROD, false,
                    x + 0.5, y + 0.5, z + 0.5, 1, 0.0, 0.2, 0.0, 0.0);
        }
        BlockPos sample = new BlockPos(x, Math.min(world.getMaxBuildHeight() - 1, Math.max(world.getMinBuildHeight(), (int) player.getY())), z);
        world.sendParticles(player, ParticleTypes.END_ROD, false,
                sample.getX() + 0.5, sample.getY() + 1.0, sample.getZ() + 0.5, 2, 0.05, 0.4, 0.05, 0.0);
    }
}
