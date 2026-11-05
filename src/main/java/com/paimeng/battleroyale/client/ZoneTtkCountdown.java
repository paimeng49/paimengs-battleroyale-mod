package com.paimeng.battleroyale.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 圈外淘汰倒计时的本地锚点计时器。
 *
 * <p>倒计时以本地毫秒为时间基准，同时用有效总血量（红心加吸收盾）
 * 的增量补偿圈外回血和非毒圈伤害，避免 HUD 只在受击时刷新。</p>
 */
@Environment(EnvType.CLIENT)
public final class ZoneTtkCountdown {

    private static final long UNSET_MILLIS = -1L;

    /** 出圈或毒圈阶段切换时的有效总血量锚点。 */
    private static volatile double baseHealth = -1.0;
    /** 上一次计算时的有效总血量，用来捕捉回血、受击和吸收盾变化。 */
    private static volatile double lastHealth = -1.0;
    /** 上一次建立或更新倒计时锚点的本地毫秒时间。 */
    private static volatile long lastMillis = UNSET_MILLIS;
    /** 锚点建立时的 DPS，用于识别毒圈阶段切换。 */
    private static volatile double timerDps = -1.0;

    private ZoneTtkCountdown() {
    }

    /** 玩家进圈、切维度、毒圈关闭或离开世界时清空本地计时器。 */
    public static void reset() {
        baseHealth = -1.0;
        lastHealth = -1.0;
        lastMillis = UNSET_MILLIS;
        timerDps = -1.0;
    }

    /**
     * 收到常规毒圈同步包时不重置本地计时锚点。
     * 锚点只由 {@link #remainingSeconds(LocalPlayer)} 在 DPS 阶段变化时重建。
     */
    public static void onZoneUpdated() {
        // 保留兼容调用；常规网络同步不能打断本地毫秒计时。
    }

    /** 客户端 tick 中调用，提前建立圈外倒计时锚点。 */
    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null || !client.player.isAlive()) {
            reset();
            return;
        }
        if (!ClientZoneState.visibleIn(client.level.dimension())
                || !ClientZoneState.isOutsideLive(client.player.getX(), client.player.getZ())) {
            reset();
            return;
        }
        remainingSeconds(client.player);
    }

    /**
     * 返回圈外剩余淘汰秒数。
     * 时间持续按本地毫秒流逝；有效总血量变化则通过增量补偿基准血量，
     * 但不会重置 {@code lastMillis}，因此倒计时不会因回血或受击卡住。
     */
    public static int remainingSeconds(LocalPlayer player) {
        double dps = ClientZoneState.effectiveDamagePerSecond(player.getMaxHealth());
        if (!(dps > 0.0) || !Double.isFinite(dps)) {
            reset();
            return -1;
        }

        double currentHealth = getEffectiveHealth(player);

        if (lastMillis == UNSET_MILLIS || Double.compare(timerDps, dps) != 0) {
            // 首次建立锚点，或毒圈阶段切换时重新开始本阶段计时。
            baseHealth = Math.max(0.0, currentHealth);
            lastHealth = currentHealth;
            lastMillis = System.currentTimeMillis();
            timerDps = dps;
        } else {
            double deltaSeconds = (System.currentTimeMillis() - lastMillis) / 1000.0;
            double expectedDamage = deltaSeconds * dps;
            double actualDamage = lastHealth - currentHealth;

            // 只有明显超出毒圈预期的掉血，或回血，才修正锚点；
            // 与预期相近的掉血视为毒圈自然伤害，交给本地时间消化，避免双倍扣除。
            if (Math.abs(actualDamage) > 0.01) {
                if (actualDamage > expectedDamage + 0.5 || actualDamage < 0) {
                    baseHealth += actualDamage;
                    lastMillis = System.currentTimeMillis();
                }
                lastHealth = currentHealth;
            }
        }

        double deltaSeconds = Math.max(0L, System.currentTimeMillis() - lastMillis) / 1000.0;
        double finalHealth = Math.max(0.0, baseHealth - (deltaSeconds * dps));
        return (int) Math.ceil(finalHealth / dps);
    }

    /**
     * 获取玩家可用于承受毒圈伤害的有效总血量：红心加金苹果等效果提供的吸收盾。
     */
    public static double getEffectiveHealth(Player player) {
        if (player == null) {
            return 0.0;
        }
        return Math.max(0.0, player.getHealth() + player.getAbsorptionAmount());
    }
}
