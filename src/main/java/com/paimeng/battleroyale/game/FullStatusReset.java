package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.map.GameWorldManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * [r-FULLRESET] 开局满状态重置：玩家进入比赛（br join 落岛 / PREPARING 统一进场 /
 * 热身结束传送）那一刻，把血量、饥饿、饱和度拉满，并清空上一局残留的药水效果与火。
 *
 * <p>硬性约束：只在 {@code battleroyale_game} 命名空间的维度内生效——
 * 主世界大厅、编辑维度一律不动，绝不把"重置"漏到大厅玩家身上。
 *
 * <p>重置项（全部服务端直接写，客户端随同步刷新）：
 * <ol>
 *   <li>血量回满：{@code setHealth(getMaxHealth())}；</li>
 *   <li>饥饿回满：{@code getFoodData().setFoodLevel(20)}；</li>
 *   <li>饱和度回满：{@code getFoodData().setSaturation(20.0F)}；</li>
 *   <li>清除所有药水效果：{@code removeAllEffects()}（中毒/缓慢/回血 Buff 都不带进新局）；</li>
 *   <li>清除身上的火：{@code clearFire()}。</li>
 * </ol>
 *
 * <p>顺序注意：{@code clearFire()} 放在 {@code removeAllEffects()} 之后——原版
 * 防火（Fire Resistance）效果清除后若仍按 fire ticks 结算会继续扣血，先清效果
 * 再立刻灭掉火，玩家落地即是干净满状态。
 */
public final class FullStatusReset {

    private FullStatusReset() {
    }

    /** 若玩家在比赛维度内则重置为满状态；大厅/编辑维度直接跳过。 */
    public static void apply(ServerPlayer player) {
        if (player == null) {
            return;
        }
        // 维度隔离闸：只认 battleroyale_game 命名空间，主世界大厅绝不受影响。
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return;
        }
        // 1) 血量回满（先回血再清效果，避免清除瞬间还带中毒扣一下血）。
        player.setHealth(player.getMaxHealth());
        // 2) 饥饿回满 + 3) 饱和度回满。
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        // 4) 清除所有药水效果（含上局残留的中毒/缓慢，也含回血 Buff）。
        player.removeAllEffects();
        // 5) 清除身上的火。
        player.clearFire();
    }
}
