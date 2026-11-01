package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.MapEditSession;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.map.SelectionData;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 出生岛工具（spawn_tool）：只在游戏维度里使用。
 *
 * <p>左键记录角 1、右键记录角 2；两点齐备后自动归一化 min/max 写入所在地图的
 * {@link GameMap#spawnMinX} 等出生岛字段并保存 JSON。边界光柱由
 * {@link com.paimeng.battleroyale.map.MapEditMarker#tickGame} 每 tick 画 END_ROD 粒子。
 *
 * <p>两个角点先暂存在 {@link MapEditSession#SPAWN_SELECTIONS}（按玩家 UUID），
 * 保存成功后清空，重新圈下一块出生岛。
 */
public final class SpawnToolItem extends Item {

    public SpawnToolItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level world = context.getLevel();
        if (player == null || world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!requireGameWorld((ServerPlayer) player, world)) {
            return InteractionResult.FAIL;
        }
        recordCorner2((ServerPlayer) player, world, context.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        if (!world.isClientSide() && miner instanceof ServerPlayer player) {
            if (requireGameWorld(player, world)) {
                recordCorner1(player, pos);
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.add(Component.literal("在游戏维度里使用").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("左键点击方块：记录出生岛角 1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("右键点击方块：记录出生岛角 2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("两点齐备自动保存，并画出 END_ROD 光柱边界").withStyle(ChatFormatting.GRAY));
    }

    /** 维度校验：出生岛工具只能在游戏维度里圈。 */
    private static boolean requireGameWorld(ServerPlayer player, Level world) {
        if (GameWorldManager.isGameWorld(world.dimension())) {
            return true;
        }
        player.displayClientMessage(BrText.of("出生岛工具只能在游戏维度中使用"
                + "（/paimengmods manager game enter <mapId>）"), false);
        return false;
    }

    private static void recordCorner1(ServerPlayer player, BlockPos pos) {
        SelectionData data = MapEditSession.spawnSelectionOf(player.getUUID());
        data.x1 = pos.getX();
        data.z1 = pos.getZ();
        player.displayClientMessage(BrText.of("出生岛角1 已设置：(" + pos.getX() + ", " + pos.getZ() + ")"), false);
    }

    private static void recordCorner2(ServerPlayer player, Level world, BlockPos pos) {
        SelectionData data = MapEditSession.spawnSelectionOf(player.getUUID());
        data.x2 = pos.getX();
        data.z2 = pos.getZ();
        player.displayClientMessage(BrText.of("出生岛角2 已设置：(" + pos.getX() + ", " + pos.getZ() + ")"), false);
        if (!data.hasPoint1()) {
            player.displayClientMessage(BrText.of("请再用左键记录出生岛角 1"), false);
            return;
        }
        saveSpawnArea(player, world, data);
    }

    /** 两点齐备：写入所在地图的出生岛区域并保存 JSON，然后清空暂存的角点。 */
    private static void saveSpawnArea(ServerPlayer player, Level world, SelectionData data) {
        GameMap map = GameWorldManager.mapForWorld(world.dimension());
        if (map == null) {
            player.displayClientMessage(BrText.of("当前游戏维度没有关联的地图配置，无法保存出生岛"), false);
            return;
        }
        map.spawnMinX = data.minX();
        map.spawnMaxX = data.maxX();
        map.spawnMinZ = data.minZ();
        map.spawnMaxZ = data.maxZ();
        map.spawnAreaSet = true;
        MapManager.INSTANCE.saveMap(map);
        MapEditSession.SPAWN_SELECTIONS.remove(player.getUUID());
        BattleRoyaleMod.LOGGER.info("地图 {} 出生岛区域已保存：X {}~{}，Z {}~{}",
                map.mapId, map.spawnMinX, map.spawnMaxX, map.spawnMinZ, map.spawnMaxZ);
        player.displayClientMessage(BrText.of("出生岛区域已保存：" + map.mapId
                + "（X " + map.spawnMinX + "~" + map.spawnMaxX
                + "，Z " + map.spawnMinZ + "~" + map.spawnMaxZ + "），"
                + "手持出生岛工具可见 END_ROD 光柱边界；开局时全部玩家传送到区域中心顶部"), false);
    }
}
