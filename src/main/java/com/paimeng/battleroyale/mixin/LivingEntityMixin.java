package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.game.DownedManager;
import com.paimeng.battleroyale.game.FlightManager;
import com.paimeng.battleroyale.game.WarmupManager;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 在 Fabric 1.18.2 没有 ALLOW_DAMAGE 事件时，直接拦截 LivingEntity.damage。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
    private void battleroyale$interceptDownedDamage(DamageSource source, float amount,
                                                     CallbackInfoReturnable<Boolean> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof ServerPlayer player) {
            // [BR-MATCHTEAM 模块四] 队友免伤：比赛进行中，本局 MatchTeam 内的玩家互相
            // 造成的伤害直接拒绝结算（近战 / 弓箭 / 三叉戟 / 爆炸都走 source.getEntity()
            // = 攻击者）；毒圈魔法伤害没有攻击者实体，不受这条影响。
            if (MatchTeamManager.INSTANCE.isActive() && amount > 0.0f
                    && source.getEntity() instanceof ServerPlayer attacker && attacker != player
                    && MatchTeamManager.INSTANCE.isTeammate(attacker.getUUID(), player.getUUID())) {
                cir.setReturnValue(false);
                return;
            }
            // 开局热身等待期：无敌玩家直接拒绝一切伤害结算（含 /kill 走的 OUT_OF_WORLD）。
            if (WarmupManager.INSTANCE.isInvincible(player.getUUID())) {
                cir.setReturnValue(false);
                return;
            }
            // [BR-FLIGHT] 航线阶段：在“飞机”上（未跳伞）以及跳伞缓降落地前的玩家同样无敌，
            // 免高空摔死；无敌名单由 FlightManager 自己维护，不动热身倒计时逻辑。
            if (FlightManager.INSTANCE.isInvincible(player.getUUID())) {
                cir.setReturnValue(false);
                return;
            }
            boolean allowVanilla = DownedManager.INSTANCE.handleDamage(entity, source, amount);
            if (!allowVanilla) {
                cir.setReturnValue(false);
            }
        }
    }
}
