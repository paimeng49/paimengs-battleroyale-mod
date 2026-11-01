package com.paimeng.battleroyale.event;

import net.minecraft.server.MinecraftServer;

/**
 * 自动匹配（野排补位）完成事件：{@code Matchmaker} 在分组、补位、合并全部做完，
 * 广播完结果之后通过 {@link EventBus} 发布（R15 任务一第 5 条）。
 *
 * <p>以后的出生点分配、跳伞编排、战绩统计等订阅这个事件即可，
 * 不需要 Matchmaker 反向认识它们。
 *
 * @param teamCount   匹配结束后参加本局的队伍总数
 * @param playerCount 实际参与匹配（在线且在名单里）的玩家数
 * @param teamLimit   本局模式的每队人数上限
 * @param modeName    模式名（"solo" / "duo" / "squad"）
 * @param server      允许为 null（防御），订阅者判空
 */
public record MatchmakingCompleteEvent(int teamCount, int playerCount, int teamLimit,
                                       String modeName, MinecraftServer server) {
}
