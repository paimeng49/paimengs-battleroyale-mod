package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * [r147 毒圈累加器] 毒圈真实伤害的伤害源。
 *
 * <p>为什么不能直接用原版的 {@code magic()}：1.20.1 里魔法伤害虽然无视护甲，
 * 但<b>不无视受击无敌帧</b>（{@code invulnerableTime}），玩家刚被谁打了一下、
 * 或者被上一格毒打到，紧接着的那次结算就会被整个吞掉——这正是"小数伤害扣不动、
 * 每秒 1 点打不出 1 点"的根因。
 *
 * <p>所以这里注册一个自定义伤害类型 {@code battleroyale:zone_damage}
 * （数据文件在 {@code data/battleroyale/damage_type/zone_damage.json}），
 * 并用 mod 自带的数据包把它塞进原版这三个标签里（等价于 1.18.2 的
 * {@code bypassesArmor().bypassesInvulnerability()} 链式调用）：
 * <ul>
 *   <li>{@code minecraft:bypasses_armor}——护甲 / 盾牌一律拦不住毒圈；</li>
 *   <li>{@code minecraft:bypasses_invulnerability}——穿无敌帧，每秒该扣的就扣得动；</li>
 *   <li>{@code minecraft:bypasses_effects}——抗性提升（含打药、信标）也减不了毒圈伤。</li>
 * </ul>
 *
 * <p>死亡播报和淘汰判定统一走 {@link #isZoneDamage(DamageSource)}，
 * 不再靠"没有攻击者"这种间接猜测。
 */
public final class ZoneDamage {

    /** 自定义伤害类型的注册 key（对应 data/battleroyale/damage_type/zone_damage.json）。 */
    public static final ResourceKey<DamageType> ZONE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation(BattleRoyaleMod.MOD_ID, "zone_damage"));

    private ZoneDamage() {
    }

    /**
     * 取这名玩家所在世界的毒圈伤害源。自定义类型没进注册表（数据包异常 / 被别的
     * 数据包顶掉）时退回原版 {@code generic}——仅影响死亡文案，累加器 + 扣血前
     * 清无敌帧（{@code ZoneRuntime#clearKnockbackAndFrames}）保证机制表现一致。
     */
    public static DamageSource source(ServerPlayer player) {
        Registry<DamageType> registry = player.server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = registry.getHolder(ZONE_DAMAGE).orElse(null);
        return holder != null ? new DamageSource(holder) : player.damageSources().generic();
    }

    /** 这个伤害源是不是毒圈（环境致死、无击杀者）。 */
    public static boolean isZoneDamage(DamageSource source) {
        return source != null && source.is(ZONE_DAMAGE);
    }
}
