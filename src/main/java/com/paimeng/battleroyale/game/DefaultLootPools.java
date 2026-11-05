package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * [BR-LOOT-FIX] 内置默认物资池：地图的 lootPool / airdropLootPool 没配置
 * （或配了但全部非法解析成空）时不再"整局不刷物资"，而是退回这里的开箱即用配置，
 * 保证空投与物资箱功能在任何地图上都能立即生效。
 *
 * <p>语法与 {@link LootPoolParser} 完全一致；条目只用原版物品，
 * 未安装任何模组依赖，绝不会出现"物品未注册"整组作废的情况。
 * 管理员在地图编辑器 GUI 里填了自己的物资池后，默认池自动让位。
 */
public final class DefaultLootPools {

    /** 普通物资箱默认池（14 组：冷兵器 / 弓矢 / 镐 / 食物 / 护具 / 建材 / 照明）。 */
    public static final String NORMAL = String.join(" || ",
            "minecraft:wooden_axe",
            "minecraft:stone_sword",
            "minecraft:iron_sword",
            "minecraft:iron_pickaxe",
            "minecraft:bow |and| minecraft:arrow 16",
            "minecraft:arrow 32",
            "minecraft:bread 4",
            "minecraft:cooked_beef 3",
            "minecraft:leather_chestplate",
            "minecraft:iron_chestplate",
            "minecraft:torch 16",
            "minecraft:oak_log 12",
            "minecraft:iron_ingot 3",
            "minecraft:golden_carrot 2");

    /** 空投箱默认池（9 组：钻石档武器护具 / 金苹果 / 弓矢补给 / 高价值材料）。 */
    public static final String AIRDROP = String.join(" || ",
            "minecraft:diamond_sword",
            "minecraft:diamond_chestplate",
            "minecraft:diamond_pickaxe",
            "minecraft:golden_apple 3",
            "minecraft:diamond |and| minecraft:iron_ingot 8",
            "minecraft:bow |and| minecraft:arrow 64",
            "minecraft:ender_pearl 4",
            "minecraft:experience_bottle 16",
            "minecraft:iron_boots |and| minecraft:iron_helmet");

    private static volatile List<List<ItemStack>> normalPool;
    private static volatile List<List<ItemStack>> airdropPool;

    private DefaultLootPools() {
    }

    /** 普通物资默认池（服务端首次使用时解析一次并缓存）。 */
    public static List<List<ItemStack>> normal() {
        List<List<ItemStack>> pool = normalPool;
        if (pool == null) {
            pool = LootPoolParser.parse(NORMAL, "BR-LOOT", "builtin-default");
            normalPool = pool;
        }
        return pool;
    }

    /** 空投默认物资池（服务端首次使用时解析一次并缓存）。 */
    public static List<List<ItemStack>> airdrop() {
        List<List<ItemStack>> pool = airdropPool;
        if (pool == null) {
            pool = LootPoolParser.parse(AIRDROP, "BR-AIRDROP", "builtin-default");
            airdropPool = pool;
        }
        return pool;
    }

    /** 配置串解析结果为空时的统一回退口：空池直接给默认池并记一条日志。 */
    public static List<List<ItemStack>> orFallback(String tag, String mapId,
                                                   List<List<ItemStack>> parsed, boolean isAirdrop) {
        if (parsed != null && !parsed.isEmpty()) {
            return parsed;
        }
        BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物资池未配置或全部非法：已回退内置默认{}物资池",
                tag, mapId, isAirdrop ? "空投" : "普通");
        List<List<ItemStack>> fallback = isAirdrop ? airdrop() : normal();
        return fallback.isEmpty() ? Collections.emptyList() : fallback;
    }
}
