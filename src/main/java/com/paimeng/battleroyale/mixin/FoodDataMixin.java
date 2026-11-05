package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.map.GameWorldManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * [r149 MEDICAL 模块二] 大逃杀维度里拦截原版「吃饱自动回血」。
 *
 * <p>1.20.1 的满饱食回血发生在 {@code FoodData#tick(Player)} 内部（约每 4 秒
 * 调一次 {@code player.heal(...)}：饱和回血与 food>=18 慢速回血两条分支）。
 * 这里用两个 @Redirect 精确掐掉这两处 {@code heal} 调用——
 * 饥饿消耗、饿死伤害、回血时照付的饱食攻击（exhaustion）等其余逻辑原样保留；
 * 生命恢复 Buff / 治疗药水走的是 {@code LivingEntity#heal}，
 * 不经过这条调用点，完全不受影响。
 *
 * <p>只在 {@code battleroyale_game} 维度命名空间生效（每张地图一个维度，
 * 路径不同、命名空间相同），主世界与编辑维度（battleroyale_edit）不吃这条规则。
 *
 * <p>[r172 取证] 对照 1.20.1 真实字节码逐一核实过：{@code FoodData#tick(Player)} 内
 * 恰好两处 {@code Player#heal(F)} 调用点（饱和快回血 / food≥18 慢回血），
 * 本类 ordinal=0/1 两个 @Redirect 全数覆盖；构建产物 refmap 中
 * {@code tick → class_1702.method_7588}、{@code heal → class_1657.method_6025}
 * 均已正确生成，生产环境（intermediary 名字）同样生效。
 * 注意不要用 HEAD 处 {@code ci.cancel()} 整方法取消——那会连饥饿消耗与饿死伤害
 * 一起掐掉，违反"饥饿消耗、饿死伤害必须照常工作"的需求。
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {

    /** 饱和快速回血分支（heal 调用点 ordinal=0）。 */
    @Redirect(method = "tick(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;heal(F)V",
                    ordinal = 0))
    private void battleroyale$skipSaturatedRegen(Player player, float amount) {
        if (!battleroyale$inGameWorld(player)) {
            player.heal(amount);
        }
    }

    /** 慢速回血分支（heal 调用点 ordinal=1）。 */
    @Redirect(method = "tick(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;heal(F)V",
                    ordinal = 1))
    private void battleroyale$skipSlowRegen(Player player, float amount) {
        if (!battleroyale$inGameWorld(player)) {
            player.heal(amount);
        }
    }

    /** 是否身处大逃杀游戏维度（按维度命名空间判断，与 MapRenderer / TeamSync 同一口径）。 */
    private static boolean battleroyale$inGameWorld(Player player) {
        // 防御：FoodData#tick 的 player 形参带 @Nullable——拿不到玩家上下文时不做判断，
        // 放行原版 heal 调用（现实中 heal 调用点本身要求 player 非空，到不了这里）。
        if (player == null) {
            return false;
        }
        var dimension = player.level().dimension();
        return dimension != null
                && GameWorldManager.DIMENSION_NAMESPACE.equals(dimension.location().getNamespace());
    }
}
