package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.game.DownedManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.Level;

/** 击倒状态下长按右键进行十秒自救的消耗品。 */
public final class SelfReviverItem extends Item {
    public SelfReviverItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        if (!DownedManager.INSTANCE.isDowned(user.getUUID()) || !hasAnySelfReviver(user)) {
            return InteractionResultHolder.fail(stack);
        }
        user.startUsingItem(hand);
        return InteractionResultHolder.success(stack);
    }

    private static boolean hasAnySelfReviver(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.SELF_REVIVER)) return true;
        }
        return false;
    }
}
