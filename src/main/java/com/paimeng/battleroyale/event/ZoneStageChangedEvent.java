package com.paimeng.battleroyale.event;

import com.paimeng.battleroyale.zone.ZonePhase;
import com.paimeng.battleroyale.zone.ZoneStage;
import net.minecraft.server.MinecraftServer;

/**
 * 毒圈阶段变化事件：某个维度的毒圈进入新阶段、开始收缩、收缩完成、被关闭时，
 * 由 {@code ZoneManager.ZoneRuntime} 通过 {@link EventBus#publish(Object)} 发布。
 *
 * <p>GUI（画圈、倒计时 HUD）、音效、播报等后续模块订阅这个事件即可，
 * 不要轮询 ZoneManager。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code stageIndex}：当前阶段下标，从 0 开始；-1 = 毒圈未激活（IDLE）；</li>
 *   <li>{@code phase}：毒圈小阶段（WAITING / SHRINKING / DONE / IDLE）；</li>
 *   <li>{@code stage}：当前阶段配置，IDLE 时为 null，订阅者请判空；</li>
 *   <li>{@code centerX / centerZ / radius}：事件发生瞬间的蓝圈（收缩起点圆）快照，
 *       方便 GUI 直接取用；</li>
 *   <li>{@code phase0}（r130 新增）：是否仍是阶段零（开局超大蓝圈罩住全图、
 *       第一次收缩尚未走完）——播报文案要用它说"当前全图安全"；</li>
 *   <li>{@code mapId}（r109 新增）：这圈毒属于哪张地图（一局可能多张地图同时在跑，
 *       播报文案要带上）；</li>
 *   <li>{@code server}：允许为 null（仅防御），广播前请判空。</li>
 * </ul>
 *
 * <p>Java 17 record，除 server 外不依赖易变的 MC API，升级预留同其它事件。
 */
public record ZoneStageChangedEvent(int stageIndex, ZonePhase phase, ZoneStage stage,
                                    double centerX, double centerZ, double radius,
                                    boolean phase0,
                                    String mapId, MinecraftServer server) {
}
