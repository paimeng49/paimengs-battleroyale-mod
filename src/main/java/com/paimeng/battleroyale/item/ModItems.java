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

    /** 旧的区域地标工具：右键在 GameMap.lootPoints 里添加带名字的地图标签。 */
    public static final Item LOOT_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "loot_tool"),
            new LootToolItem(new Item.Properties().stacksTo(1)));

    /** 物资箱生成工具：右键在 GameMap.manualLootSpawns 里记录开局强制出箱子的坐标。 */
    public static final Item CHEST_SPAWN_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "chest_spawn_tool"),
            new ChestSpawnToolItem(new Item.Properties().stacksTo(1)));

    public static final Item SPAWN_TOOL = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "spawn_tool"),
            new SpawnToolItem(new Item.Properties().stacksTo(1)));

    // ------------------------------------------------------------------
    // [r149 MEDICAL 模块二] 五种医疗物品：读条秒数 / 效果 / 使用限制见 MedicalItem。
    // 贴图与模型走 assets/battleroyale/{models,item,textures/item}/<id>。
    // ------------------------------------------------------------------

    /** 绷带：长按右键 2 秒，瞬间回 5 点血；血量 >= 15 无法使用；堆叠 10。 */
    public static final Item BANDAGE = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "bandage"),
            new MedicalItem(new Item.Properties().stacksTo(10),
                    MedicalItem.Strategy.INSTANT_HEAL_5, 2, 0, 0, 0.0, 0));

    /** 急救包：长按右键 6 秒，把血量直接抬到 15；血量 >= 15 无法使用；堆叠 5。 */
    public static final Item FIRST_AID_KIT = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "first_aid_kit"),
            new MedicalItem(new Item.Properties().stacksTo(5),
                    MedicalItem.Strategy.SET_HEALTH_15, 6, 0, 0, 0.0, 0));

    /** 医疗箱：长按右键 10 秒，血量完全回满；满血无法使用；堆叠 1。 */
    public static final Item MEDICAL_KIT = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "medical_kit"),
            new MedicalItem(new Item.Properties().stacksTo(1),
                    MedicalItem.Strategy.FULL_HEAL, 10, 0, 0, 0.0, 0));

    /** 能量饮料：长按右键 4 秒，+2 饥饿、30 秒生命恢复 I、30 秒 +10% 移速；堆叠 10。 */
    public static final Item ENERGY_DRINK = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "energy_drink"),
            new MedicalItem(new Item.Properties().stacksTo(10),
                    MedicalItem.Strategy.BUFF_ONLY, 4, 2, 30, 0.10, 30));

    /** 止痛药：长按右键 6 秒，+4 饥饿、60 秒生命恢复 I、60 秒 +20% 移速；堆叠 5。 */
    public static final Item PAINKILLER = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "painkiller"),
            new MedicalItem(new Item.Properties().stacksTo(5),
                    MedicalItem.Strategy.BUFF_ONLY, 6, 4, 60, 0.20, 60));

    private ModItems() {
    }

    /** 1.20.1：创造标签页内容改由 ItemGroupEvents 挂载（工具页 + 食物页）。 */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(SELF_REVIVER);
            entries.accept(SELECTION_TOOL);
            entries.accept(LOOT_TOOL);
            entries.accept(CHEST_SPAWN_TOOL);
            entries.accept(SPAWN_TOOL);
        });
        // [r149 MEDICAL] 医疗物品挂「食物与饮料」创造页，测试与摆放都顺手。
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FOOD_AND_DRINKS).register(entries -> {
            entries.accept(BANDAGE);
            entries.accept(FIRST_AID_KIT);
            entries.accept(MEDICAL_KIT);
            entries.accept(ENERGY_DRINK);
            entries.accept(PAINKILLER);
        });
    }
}
