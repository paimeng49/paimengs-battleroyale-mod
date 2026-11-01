package com.paimeng.battleroyale.item;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** 模组物品注册表。 */
public final class ModItems {
    public static final Item SELF_REVIVER = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "self_reviver"),
            new SelfReviverItem(new Item.Properties().stacksTo(1)));

    public static final Item SELECTION_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "selection_tool"),
            new SelectionToolItem(new Item.Properties().stacksTo(1)));

    public static final Item LOOT_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "loot_tool"),
            new LootToolItem(new Item.Properties().stacksTo(1)));

    public static final Item SPAWN_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "spawn_tool"),
            new SpawnToolItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    /** 1.20.1：创造标签页内容改由 ItemGroupEvents 挂载（工具页）。 */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(SELF_REVIVER);
            entries.accept(SELECTION_TOOL);
            entries.accept(LOOT_TOOL);
            entries.accept(SPAWN_TOOL);
        });
    }
}
