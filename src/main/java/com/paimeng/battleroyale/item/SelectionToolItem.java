package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.MapEditSession;
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

/** 左键记点 1、右键记点 2 的选区工具。 */
public final class SelectionToolItem extends Item {
    public static final int MAX_SPAN = 2048;

    public SelectionToolItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level world = context.getLevel();
        if (player == null || world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!allowInWorld((ServerPlayer) player, world)) {
            return InteractionResult.FAIL;
        }
        recordPoint2((ServerPlayer) player, context.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        if (!world.isClientSide() && miner instanceof ServerPlayer player) {
            if (allowInWorld(player, world)) {
                recordPoint1(player, pos);
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.add(Component.literal("左键点击方块：记录地图区域的点 1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("右键点击方块：记录地图区域的点 2").withStyle(ChatFormatting.GRAY));
    }

    /**
     * 选区工具只允许在编辑维度（battleroyale_edit）使用。
     * 底层圈点逻辑不变，这里只做维度隔离。
     */
    private static boolean allowInWorld(ServerPlayer player, Level world) {
        if (EditWorldManager.isEditWorld(world.dimension())) {
            return true;
        }
        if (GameWorldManager.isGameWorld(world.dimension())) {
            player.displayClientMessage(BrText.of("请在编辑副本中使用区域选区工具"), false);
            return false;
        }
        player.displayClientMessage(BrText.of("请先用 /paimengmods manager enter <存档名> 进入副本"), false);
        return false;
    }

    private static void recordPoint1(ServerPlayer player, BlockPos pos) {
        SelectionData data = MapEditSession.selectionOf(player.getUUID());
        data.x1 = pos.getX();
        data.z1 = pos.getZ();
        player.displayClientMessage(BrText.of("已记录地图区域点 1：(" + pos.getX() + ", " + pos.getZ() + ")"), false);
        warnSpan(player, data);
    }

    private static void recordPoint2(ServerPlayer player, BlockPos pos) {
        SelectionData data = MapEditSession.selectionOf(player.getUUID());
        data.x2 = pos.getX();
        data.z2 = pos.getZ();
        if (!data.hasPoint1()) {
            player.displayClientMessage(BrText.of("已记录地图区域点 2：(" + pos.getX() + ", " + pos.getZ() + ")，请再用左键记录点 1"), false);
            return;
        }
        player.displayClientMessage(BrText.of("已记录地图区域点 2：(" + pos.getX() + ", " + pos.getZ()
                + ")，范围：" + data.minX() + "~" + data.maxX() + ", " + data.minZ() + "~" + data.maxZ()), false);
        warnSpan(player, data);
    }

    private static void warnSpan(ServerPlayer player, SelectionData data) {
        if (!data.isComplete()) {
            return;
        }
        if (data.spanX() > MAX_SPAN || data.spanZ() > MAX_SPAN) {
            player.displayClientMessage(BrText.of("选区跨度不能超过 " + MAX_SPAN + " 格，请重新圈选"), false);
        }
    }
}
