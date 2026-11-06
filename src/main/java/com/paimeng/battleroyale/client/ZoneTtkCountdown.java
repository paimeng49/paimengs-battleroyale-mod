package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 圈外淘汰倒计时的本地锚点计时器。
 *
 * <p>[r204 金苹果修复] 完整口径：
 * <ul>
 *   <li><b>时间外推</b>：锚点 {@code baseHealth}（建立锚点那一刻的有效总血量）随本地毫秒
 *       按 DPS 连续消耗，{@code lastMillis} 只在出圈 / 阶段切换时重置——
 *       常规 ZoneSync 收包绝不碰它，倒计时跟着秒针平滑下走；</li>
 *   <li><b>增量补偿</b>：每帧拿真实有效总血量（红心 + 金苹果黄心）与时间模型预测值比对，
 *       偏差超出死区才把锚点对齐到现实：吃金苹果 / 打急救包 → 偏差为正 → 基准血量变大
 *       → 倒计时变长；受非毒圈伤害 / 黄心到期 → 偏差为负 → 基准血量变小 → 倒计时变短。
 *       补偿不改 {@code lastMillis}，因此永远不会"卡住"或回退。</li>
 * </ul>
 *
 * <p>上一版把增量写反了：{@code actualDamage = lastHealth - currentHealth} 在回血时为负，
 * 却做 {@code baseHealth += actualDamage}，导致吃金苹果（黄心 +4~8）反而把基准血量扣掉、
 * 倒计时瞬间缩短。本版补偿量为 {@code currentHealth - predictedHealth}，符号与直觉一致。
 *
 * <p>死区（{@link #SYNC_DEAD_ZONE}）的存在原因：服务端毒圈每 20 tick 结算一次、
 * "攒满 1 点才向下取整扣血、零头留池"，真实血量天然滞后于连续时间模型 0~1 点；
 * 该范围内的偏差是毒圈伤害的取整相位，交给时间模型消化，绝不补偿，避免双倍扣除。
 */
@Environment(EnvType.CLIENT)
public final class ZoneTtkCountdown {

    private static final long UNSET_MILLIS = -1L;

    /**
     * 对齐死区（血量点）。服务端小数池取整造成的固有相位差 < 1 点，加上网络延迟抖动，
     * 取 1.5 点：纯毒圈伤害永不触发补偿，而金苹果（吸收 II = +8 点）第一帧即触发。
     */
    private static final double SYNC_DEAD_ZONE = 1.5;

    /** 调试日志节流间隔（毫秒）：约每 20 个客户端 tick 打一行。 */
    private static final long DEBUG_LOG_INTERVAL_MILLIS = 1000L;

    /** 血量锚点：lastMillis 时刻的有效总血量（红心 + 吸收黄心）。 */
    private static volatile double baseHealth = -1.0;
    /** 上一次计算时的有效总血量，用来检测回血、受击和黄心变化。 */
    private static volatile double lastHealth = -1.0;
    /** 锚点建立（出圈 / 毒圈阶段切换）时的本地毫秒时间，只在重建锚点时前进。 */
    private static volatile long lastMillis = UNSET_MILLIS;
    /** 锚点建立时的 DPS，用于识别毒圈阶段切换。 */
    private static volatile double timerDps = -1.0;

    private static volatile long lastDebugMillis = 0L;

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
     * 锚点只由 {@link #remainingSeconds(LocalPlayer)} 在出圈首次计算 / DPS 阶段变化时重建；
     * 服务端每 tick 发包，这里若 reset()，lastMillis 每 0.05 秒被清零，倒计时永远走不动。
     */
    public static void onZoneUpdated() {
        // 保留兼容调用；常规网络同步不能打断本地毫秒计时。
    }

    /** 客户端 tick（END_CLIENT_TICK）中调用，维持圈外倒计时锚点并打调试日志。 */
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
     * 返回圈外剩余淘汰秒数；-1 = 当前阶段不掉血 / 数据未就绪（HUD 显示 Infinity）。
     *
     * <p>时间持续按本地毫秒流逝；有效总血量偏离预测值超过死区时做增量补偿
     * （吃金苹果 → 基准血量变大 → 倒计时增加），但绝不重置 {@code lastMillis}。
     */
    public static int remainingSeconds(LocalPlayer player) {
        double dps = ClientZoneState.effectiveDamagePerSecond(player.getMaxHealth());
        if (!(dps > 0.0) || !Double.isFinite(dps)) {
            reset();
            return -1;
        }

        double currentHealth = getEffectiveHealth(player);
        long now = System.currentTimeMillis();
        boolean healthChanged = Math.abs(currentHealth - lastHealth) > 0.01;

        if (lastMillis == UNSET_MILLIS || Double.compare(timerDps, dps) != 0) {
            // 首次建立锚点，或毒圈阶段切换：以当前有效总血量重新开始本阶段计时。
            baseHealth = currentHealth;
            lastHealth = currentHealth;
            lastMillis = now;
            timerDps = dps;
        } else {
            double predictedHealth =
                    baseHealth - Math.max(0L, now - lastMillis) / 1000.0 * dps;
            // deltaHealth > 0：血比模型预测的多（回血 / 金苹果黄心）→ 基准上调；
            // deltaHealth < 0：血比模型预测的少（非毒圈伤害 / 黄心到期）→ 基准下调。
            double deltaHealth = currentHealth - predictedHealth;
            if (Math.abs(deltaHealth) > SYNC_DEAD_ZONE) {
                baseHealth += deltaHealth;
                // 注意：lastMillis 不动。锚点血量对齐后，同一时刻的预测值
                // baseHealth - elapsed*dps 恰好等于 currentHealth，模型与现实重新缝合，
                // 之后的毒圈伤害继续交给时间流逝消化。
                lastHealth = currentHealth;
            } else if (healthChanged) {
                lastHealth = currentHealth;
            }
        }

        double elapsedSeconds = Math.max(0L, System.currentTimeMillis() - lastMillis) / 1000.0;
        double finalHealth = Math.max(0.0, baseHealth - elapsedSeconds * dps);
        int secondsLeft = (int) Math.ceil(finalHealth / dps);

        debugLog(player, secondsLeft, healthChanged, now);
        return secondsLeft;
    }

    /**
     * [r204 调试] 每 20 tick（约 1 秒）打一行 TTK-DEBUG；
     * 血量发生任何变化的那一帧立即补打一行，方便在日志里精确对齐吃金苹果的时刻。
     */
    private static void debugLog(LocalPlayer player, int secondsLeft, boolean healthChanged, long now) {
        if (!healthChanged && now - lastDebugMillis < DEBUG_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastDebugMillis = now;
        BattleRoyaleMod.LOGGER.info("[TTK-DEBUG] 红心: {}, 黄心: {}, 基准血量: {}, 剩余秒数: {} (DPS: {}, 锚点毫秒: {})",
                player.getHealth(), player.getAbsorptionAmount(),
                String.format("%.2f", baseHealth), secondsLeft,
                String.format("%.3f", timerDps), lastMillis);
    }

    /**
     * 获取玩家可用于承受毒圈伤害的有效总血量：红心加金苹果等效果提供的吸收盾（黄心）。
     */
    public static double getEffectiveHealth(Player player) {
        if (player == null) {
            return 0.0;
        }
        return Math.max(0.0, player.getHealth() + player.getAbsorptionAmount());
    }
}
