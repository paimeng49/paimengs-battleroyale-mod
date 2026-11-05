package com.paimeng.battleroyale.entity;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<AirdropEntity> AIRDROP = Registry.register(
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE,
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "airdrop"),
            EntityType.Builder.<AirdropEntity>of(AirdropEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.5F)
                    .clientTrackingRange(256)
                    .updateInterval(1)
                    .build("airdrop"));

    private ModEntities() {
    }

    public static void register() {
        BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 自定义空投实体已注册：{}", AIRDROP);
    }
}
