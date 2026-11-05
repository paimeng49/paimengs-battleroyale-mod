package com.paimeng.battleroyale.item;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * [r149 MEDICAL 模块二] 大逃杀医疗物品基类：长按右键读条，读条完成才生效。
 *
 * <p>机制约定（与用户需求一一对应）：
 * <ul>
 *   <li>{@link #getUseDuration(ItemStack)} 返回读条 tick 数（秒 × 20），
 *       {@link #getUseAnimation(ItemStack)} 统一 {@link UseAnim#EAT}，
 *       客户端自动播进食动画（咀嚼姿势）；</li>
 *   <li>{@link #use(Level, Player, InteractionHand)} 里<b>先验条件</b>
 *       （治疗类的血量门槛），不满足直接 {@code fail} —— 连读条都不开始；
 *       饮料 / 止痛药不设门槛，任何时候可读条；</li>
 *   <li>读条被打断（松手 / 切换物品 / 死亡）走 {@code Item#releaseUsing}
 *       默认实现（1.20.1 为 void 空实现），什么都不发生——<b>打断即无效</b>；</li>
 *   <li>读条走满由引擎 {@code LivingEntity#completeUsingItem} 自动调
 *       {@link #finishUsingItem(ItemStack, Level, LivingEntity)}：结算效果、
 *       播放治疗药水饮用音效（entity.generic.drink）、冒爱心粒子、扣 1 个物品
 *       （创造不消耗）。</li>
 * </ul>
 *
 * <p>饱食度自然回血在大逃杀维度已被 {@code mixin.FoodDataMixin} 整体拦截，
 * 医疗物品是这个维度里唯一的「非 Buff」回血手段；生命恢复效果
 * （MobEffects.REGENERATION）与治疗药水走 LivingEntity 自己的通道，不受影响。
 */
public final class MedicalItem extends Item {

    /** 治疗策略（决定读条前校验与读条完成后的血量结算方式）。 */
    public enum Strategy {
        /** 绷带：瞬间 +5 血（封顶 20）。限制：血量 >= 15 时无法使用。 */
        INSTANT_HEAL_5,
        /** 急救包：把血量直接抬到 15。限制：血量 >= 15 时无法使用。 */
        SET_HEALTH_15,
        /** 医疗箱：完全回满。限制：满血无法使用。 */
        FULL_HEAL,
        /** 饮料 / 止痛药：不回血，加饥饿值 + 生命恢复 Buff + 移速加成。 */
        BUFF_ONLY
    }

    private final Strategy strategy;
    private final int useTicks;
    /** 恢复的饥饿点数（0 = 治疗类，不碰饥饿值）。 */
    private final int foodRestore;
    /** 生命恢复 I 的持续 tick（0 = 不施加）。 */
    private final int regenTicks;
    /** 移速加成倍率（0.10 / 0.20；0 = 无加成）。 */
    private final double speedMultiplier;
    /** 移速加成持续 tick。 */
    private final int speedTicks;

    /**
     * @param strategy        治疗策略
     * @param useSeconds      读条秒数（绷带 2 / 急救包 6 / 医疗箱 10 / 饮料 4 / 止痛药 6）
     * @param foodRestore     恢复饥饿值（能量饮料 2、止痛药 4，其余 0）
     * @param regenSeconds    生命恢复 I 秒数（饮料 30、止痛药 60，其余 0）
     * @param speedMultiplier 移速倍率（饮料 0.10、止痛药 0.20，其余 0）
     * @param speedSeconds    移速加成秒数（与 regen 同步 30 / 60）
     */
    public MedicalItem(Properties properties, Strategy strategy, int useSeconds,
                       int foodRestore, int regenSeconds, double speedMultiplier, int speedSeconds) {
        super(properties);
        this.strategy = strategy;
        this.useTicks = useSeconds * 20;
        this.foodRestore = foodRestore;
        this.regenTicks = regenSeconds * 20;
        this.speedMultiplier = speedMultiplier;
        this.speedTicks = speedSeconds * 20;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return useTicks;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    // ------------------------------------------------------------------
    // 开始读条：条件不满足直接 fail，连动画都不起。
    // ------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String reject = battleroyale$rejectReason(player);
        if (reject != null) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                        com.paimeng.battleroyale.util.BrText.of(reject), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            // 咀嚼音效（开始下口）。UseAnim.EAT 只负责动画，治疗物品没挂原版
            // FoodProperties，引擎不会自动出声，这里手动补一次 generic.eat。
            RandomSource random = level.getRandom();
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_EAT, player.getSoundSource(), 1.0F,
                    0.85F + random.nextFloat() * 0.3F);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    /** 读条前校验：返回 null = 可以开始读条；返回文案 = 拦截原因。 */
    private String battleroyale$rejectReason(Player player) {
        switch (strategy) {
            case INSTANT_HEAL_5:
            case SET_HEALTH_15:
                // 绷带 / 急救包：血量 >= 15 时无法使用（PUBG 口径）。
                return player.getHealth() >= 15.0F ? "血量不低于 15 点，无法使用" : null;
            case FULL_HEAL:
                return player.getHealth() >= player.getMaxHealth() ? "血量已满，无法使用医疗箱" : null;
            case BUFF_ONLY:
            default:
                // 饮料 / 止痛药：任何时候都能喝（PUBG 口径——玩家会满饥饿提前攒 Buff）。
                // 饥饿值满时「回饥饿」这部分效果自然溢出浪费，Buff 照常给。
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 读条完成：结算效果。打断（releaseUsing 默认空实现）永远不会走到这里。
    // ------------------------------------------------------------------

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity livingEntity) {
        if (livingEntity instanceof Player player && !level.isClientSide) {
            battleroyale$applyEffects(player);

            // 治疗生效音效：generic.drink（原版喝治疗药水的声音）+ 爱心粒子。
            RandomSource random = level.getRandom();
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_DRINK, player.getSoundSource(), 1.0F,
                    0.8F + random.nextFloat() * 0.4F);
            // [r153 修复] 原写法 sendParticles((ServerPlayer) null, …) 走的是
            // ServerLevel 的「指定玩家」重载，传 null 会让 vanilla 内部
            // 对这个 null 玩家取连接/旁观状态 → NPE → 整个服务端崩溃
            // （用户 latest-5.log 的 "Ticking player" crash 即此处）。
            // 改走不带玩家参数的 ServerLevel 广播重载：周围所有玩家都能看见爱心粒子。
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.HEART,
                        player.getRandomX(0.6), player.getY() + player.getBbHeight() * 0.8,
                        player.getRandomZ(0.6), 4, 0.25, 0.15, 0.25, 0.01);
            }

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return stack;
    }

    private void battleroyale$applyEffects(Player player) {
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        switch (strategy) {
            case INSTANT_HEAL_5:
                player.setHealth(Math.min(maxHealth, health + 5.0F));
                break;
            case SET_HEALTH_15:
                player.setHealth(Math.min(maxHealth, 15.0F));
                break;
            case FULL_HEAL:
                player.setHealth(maxHealth);
                break;
            case BUFF_ONLY:
            default:
                break;
        }
        if (foodRestore > 0) {
            // FoodData#eat = 原版进食同款入口：饥饿值 +n（封顶 20），饱和度不加。
            player.getFoodData().eat(foodRestore, 0.0F);
        }
        if (regenTicks > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, regenTicks, 0));
        }
        if (speedMultiplier > 0.0 && player instanceof ServerPlayer serverPlayer) {
            SpeedBoostManager.apply(serverPlayer, speedMultiplier, speedTicks);
        }
    }
}
