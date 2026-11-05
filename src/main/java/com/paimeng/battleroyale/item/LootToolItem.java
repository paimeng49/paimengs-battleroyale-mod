package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.MapEditSession;
import com.paimeng.battleroyale.map.MapManager;
import com.paimeng.battleroyale.network.LootPointsSync;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 物资点工具：右键添加，左键移除最近点。 */
public final class LootToolItem extends Item {
    private static final Pattern NUMBERED = Pattern.compile("^物资点(\\d+)$");

    public LootToolItem(Item.Properties settings) {
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
            addLoot(serverPlayer, world, context.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        if (!world.isClientSide() && miner instanceof ServerPlayer player) {
            removeLoot(player, world, pos);
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.add(Component.literal("右键点击方块：添加手动物资点").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("左键点击方块：移除附近物资点").withStyle(ChatFormatting.GRAY));
    }

    private static void addLoot(ServerPlayer player, Level world, BlockPos pos) {
        if (EditWorldManager.isEditWorld(world.dimension())) {
            addToMap(player, MapEditSession.draftOf(player.getUUID()), pos, true);
            return;
        }
        if (GameWorldManager.isGameWorld(world.dimension())) {
            GameMap map = GameWorldManager.mapForWorld(world.dimension());
            if (map == null) {
                player.displayClientMessage(BrText.of("当前游戏维度没有关联的地图配置，无法添加物资点"), false);
                return;
            }
            addToMap(player, map, pos, false);
            return;
        }
        player.displayClientMessage(BrText.of("物资点工具只能在编辑副本或游戏维度中使用"), false);
    }

    /**
     * 真正添加物资点的底层逻辑（去重、自动编号、写入列表）保持原样；
     * 只按维度决定写草稿还是写正式地图、以及事后怎么同步。
     */
    private static void addToMap(ServerPlayer player, GameMap map, BlockPos pos, boolean draft) {
        if (map.lootPoints == null) {
            map.lootPoints = new ArrayList<>();
        }
        double x = pos.getX();
        int y = pos.getY() + 1;
        double z = pos.getZ();
        for (LootPoint existing : map.lootPoints) {
            double dx = existing.x() - x;
            double dz = existing.z() - z;
            if (dx * dx + dz * dz <= 1.0) {
                player.displayClientMessage(BrText.of("该位置附近已有物资点 " + existing.name()), false);
                return;
            }
        }
        String name = nextName(map.lootPoints);
        map.lootPoints.add(new LootPoint(x, y, z, name, 12));
        if (draft) {
            MapEditorSync.sendOpen(player, map);
        } else {
            MapManager.INSTANCE.saveMap(map);
            LootPointsSync.sendTo(player, map);
        }
        player.displayClientMessage(BrText.of("已添加手动物资点（当前共 " + map.lootPoints.size() + " 个）"), false);
    }

    private static void removeLoot(ServerPlayer player, Level world, BlockPos pos) {
        boolean draft = EditWorldManager.isEditWorld(world.dimension());
        GameMap map = draft ? MapEditSession.draftOf(player.getUUID())
                : GameWorldManager.mapForWorld(world.dimension());
        if (map == null || map.lootPoints == null || map.lootPoints.isEmpty()) {
            player.displayClientMessage(BrText.of("附近没有可移除的手动物资点"), false);
            return;
        }
        int best = -1;
        double bestDistance = 4.0D;
        for (int i = 0; i < map.lootPoints.size(); i++) {
            LootPoint point = map.lootPoints.get(i);
            double dx = point.x() - pos.getX();
            double dy = (point.y() < 0 ? pos.getY() + 1 : point.y()) - (pos.getY() + 1);
            double dz = point.z() - pos.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0) {
            player.displayClientMessage(BrText.of("附近没有可移除的手动物资点"), false);
            return;
        }
        map.lootPoints.remove(best);
        if (draft) {
            MapEditorSync.sendOpen(player, map);
        } else {
            MapManager.INSTANCE.saveMap(map);
            LootPointsSync.sendTo(player, map);
        }
        player.displayClientMessage(BrText.of("已移除最近的手动物资点（当前共 " + map.lootPoints.size() + " 个）"), false);
    }

    private static String nextName(List<LootPoint> points) {
        int max = -1;
        for (LootPoint point : points) {
            if (point.name() == null) {
                continue;
            }
            Matcher matcher = NUMBERED.matcher(point.name());
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return "物资点" + (max + 1);
    }
}
