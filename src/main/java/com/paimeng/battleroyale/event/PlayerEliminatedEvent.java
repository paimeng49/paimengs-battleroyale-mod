package com.paimeng.battleroyale.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家被正式淘汰后发布的事件。
 *
 * <p>[BR-KILLFEED] 扩展三个字段，供千人千面播报与结算提示消费：
 * <ul>
 *   <li>{@code killer}：击杀者玩家实体；毒圈 / 摔死 / 自救失败等非玩家致死为 null；</li>
 *   <li>{@code bleedout}：true = 倒地无人救援流血淘汰（播报走"没人救"文案）；</li>
 *   <li>{@code desert}：true = 断线重连判阵亡——击杀播报要跳过（指令层
 *       已经按"逃跑"口径自行广播），但淘汰名册、结算提示与全队覆灭判定照常；</li>
 *   <li>{@code voluntaryQuit}：[r-LEAVE] true = 局内 {@code br leave} 主动退出判阵亡。
 *       播报<b>不再跳过</b>：走与被击杀同一套击倒/淘汰播报管线，击杀者取最近
 *       玩家伤害来源（"战斗拔线"兜底），没有则按「环境/主动退出」口径出文案；
 *       淘汰名册、全队覆灭与排名结算全部照常触发。</li>
 * </ul>
 */
public record PlayerEliminatedEvent(ServerPlayer player, DamageSource source, ServerPlayer killer,
                                    boolean bleedout, boolean desert, boolean voluntaryQuit,
                                    MinecraftServer server) {
}
