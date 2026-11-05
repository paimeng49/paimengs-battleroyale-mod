package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.game.DeathChestManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [BR-DEATHCHEST] 在 {@code Player#die} 头部注入死亡箱结算。
 *
 * <p>为什么选这里：所有死亡路径都汇聚到这一个方法——原版伤害结算致死
 * （ALLOW_DEATH 放行后的 hurt → die 链）、淘汰流程里显式调用的
 * {@code player.die(source)}、毒圈 / 流血致死，全走原版 die()。
 * 在 HEAD 处整套收走并清空背包，原版 die() 后续的"天女散花"看到空库存，
 * 自然一件不掉；主动按 Q 丢弃的物品走 drop 路径，完全不受影响。
 *
 * <p>[r-fix3] 淘汰（DownedManager.eliminate）在切旁观<b>之前</b>已显式调用
 * {@code DeathChestManager.onDeath} 收装备——因为旁观态会被资格判定拒绝，
 * 只靠本 Mixin 会在 die() 时刻被拒。本注入仍是纯原版死亡路径（如大厅放行死亡）
 * 的兜底：淘汰路径走到 die() 时背包已空，重复调用安全、不产生第二个箱子。
 *
 * <p>判定与兜底全部在 DeathChestManager 内（局内 / 名单 / 维度 / 旁观创造排除），
 * 大厅阶段的原版死亡保持原样。
 */
@Mixin(Player.class)
public abstract class PlayerDeathChestMixin {

    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"))
    private void battleroyale$deathChest(DamageSource source, CallbackInfo ci) {
        // ServerPlayer 只存在于服务端；客户端的玩家实体与远程玩家动画天然不命中，无需再查 isClientSide。
        if ((Object) this instanceof ServerPlayer player) {
            DeathChestManager.onDeath(player);
        }
    }
}
