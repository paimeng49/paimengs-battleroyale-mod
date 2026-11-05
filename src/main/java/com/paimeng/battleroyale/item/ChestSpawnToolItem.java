package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.ManualLootSpawn;
import com.paimeng.battleroyale.map.MapEditSession;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.network.MapEditorSync;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 物资箱生成工具：右键把点击方块上方一格的坐标记入 manualLootSpawns，
 * 开局时该坐标会强制生成一个装满物资的箱子。
 * 与旧的区域地标工具（LootToolItem → lootPoints）完全隔离，绝不写旧列表。
 */
public final class ChestSpawnToolItem extends Item {
    public ChestSpawnToolItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level world = context.getLevel();
        if (player == null || world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            addChest(serverPlayer, world, context.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        if (!world.isClientSide() && miner instanceof ServerPlayer player) {
            removeChest(player, world, pos);
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.add(Component.literal("右键点击方块：记录物资箱坐标").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("左键点击方块：移除附近物资箱坐标").withStyle(ChatFormatting.GRAY));
    }

    private static void addChest(ServerPlayer player, Level world, BlockPos clicked) {
        if (EditWorldManager.isEditWorld(world.dimension())) {
            addToMap(player, MapEditSession.draftOf(player.getUUID()), clicked, true);
            return;
        }
        if (GameWorldManager.isGameWorld(world.dimension())) {
            GameMap map = GameWorldManager.mapForWorld(world.dimension());
            if (map == null) {
                player.displayClientMessage(BrText.of("当前游戏维度没有关联的地图配置，无法添加物资箱"), false);
                return;
            }
            addToMap(player, map, clicked, false);
            return;
        }
        player.displayClientMessage(BrText.of("物资箱工具只能在编辑副本或游戏维度中使用"), false);
    }

    /** 记录点击方块上方的具体箱子坐标，只写 manualLootSpawns。 */
    private static void addToMap(ServerPlayer player, GameMap map, BlockPos clicked, boolean draft) {
        if (map == null) {
            return;
        }
        if (map.manualLootSpawns == null) {
            map.manualLootSpawns = new ArrayList<>();
        }
        BlockPos spawnPos = clicked.above().immutable();
        for (ManualLootSpawn existing : map.manualLootSpawns) {
            if (existing != null && existing.blockPos().equals(spawnPos)) {
                player.displayClientMessage(BrText.of("该位置已经标记了物资箱"), false);
                return;
            }
        }
        map.manualLootSpawns.add(new ManualLootSpawn(spawnPos));
        if (draft) {
            MapEditorSync.sendOpen(player, map);
        } else {
            MapManager.INSTANCE.saveMap(map);
        }
        player.displayClientMessage(BrText.of("已添加物资箱坐标：" + spawnPos.getX() + ", "
                + spawnPos.getY() + ", " + spawnPos.getZ()), false);
    }

    private static void removeChest(ServerPlayer player, Level world, BlockPos clicked) {
        boolean draft = EditWorldManager.isEditWorld(world.dimension());
        GameMap map = draft ? MapEditSession.draftOf(player.getUUID())
                : GameWorldManager.mapForWorld(world.dimension());
        if (map == null || map.manualLootSpawns == null || map.manualLootSpawns.isEmpty()) {
            player.displayClientMessage(BrText.of("附近没有可移除的物资箱坐标"), false);
            return;
        }
        BlockPos target = clicked.above();
        int best = -1;
        double bestDistance = 9.0D;
        for (int i = 0; i < map.manualLootSpawns.size(); i++) {
            ManualLootSpawn spawn = map.manualLootSpawns.get(i);
            if (spawn == null) continue;
            double distance = spawn.blockPos().distSqr(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0) {
            player.displayClientMessage(BrText.of("附近没有可移除的物资箱坐标"), false);
            return;
        }
        ManualLootSpawn removed = map.manualLootSpawns.remove(best);
        if (draft) {
            MapEditorSync.sendOpen(player, map);
        } else {
            MapManager.INSTANCE.saveMap(map);
        }
        BlockPos removedPos = removed.blockPos();
        player.displayClientMessage(BrText.of("已移除物资箱坐标：" + removedPos.getX() + ", "
                + removedPos.getY() + ", " + removedPos.getZ()), false);
    }
}
