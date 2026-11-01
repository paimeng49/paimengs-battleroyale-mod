package com.paimeng.battleroyale.zone;

/**
 * 毒圈某一瞬间的完整快照（服务端 {@code ZoneManager} → {@code network.ZoneSync} → 客户端）。
 *
 * <p>模块三升级：除了「当前蓝圈」，还带上
 * <ul>
 *   <li>下个白圈中心/半径（{@code whiteCenterX/Z, whiteRadius}）；</li>
 *   <li>剩余倒计时（秒，SHRINKING 起锁定为 0）；</li>
 *   <li>缩圈总进度百分比 0.0~1.0（客户端据此 lerp 实时蓝圈半径）；</li>
 *   <li>mapId 与维度字符串（模块六：客户端渲染前校验维度，主城一律不画）。</li>
 * </ul>
 *
 * <p>{@code blueCenterX/Z, blueRadius} 是本轮收缩的<b>起点圆</b>（收缩开始后保持不变），
 * 客户端实时蓝圈 = lerp(起点圆 → 白圈, progress)；WAITING 阶段 progress=0，
 * lerp 结果就是蓝圈本身，语义统一。
 *
 * <p>纯 Java（{@code dimensionId} 用字符串而不是 ResourceKey，保证本类不 import Minecraft 类），
 * 升级到高版本时原样可用。
 */
public record ZoneSnapshot(ZonePhase phase, int stageIndex,
                           String mapId, String dimensionId,
                           double blueCenterX, double blueCenterZ, double blueRadius,
                           double whiteCenterX, double whiteCenterZ, double whiteRadius,
                           int remainingSeconds, double progress) {

    /** 一个"毒圈关闭"的哨兵快照：客户端收到就清空本地状态、不再画墙/圈/进度条。 */
    public static ZoneSnapshot idle() {
        return new ZoneSnapshot(ZonePhase.IDLE, -1, "", "",
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
    }

    /** 毒圈是否正在生效（决定客户端画不画）。 */
    public boolean active() {
        return phase != null && phase.isActive();
    }

    /** 进度夹回 [0,1]：网络坏包也不至于把 lerp 推到圈外。 */
    public double clampedProgress() {
        return Math.max(0.0, Math.min(1.0, progress));
    }
}
