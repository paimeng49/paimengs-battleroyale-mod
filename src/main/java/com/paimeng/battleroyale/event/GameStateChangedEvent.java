package com.paimeng.battleroyale.event;

import com.paimeng.battleroyale.game.GamePhase;
import net.minecraft.server.MinecraftServer;

/**
 * 游戏状态切换事件：每当状态机从 from 变到 to，就通过
 * {@link EventBus#publish(Object)} 发布一次。
 *
 * <p>后续模块（毒圈、撤离点、跳伞、GUI）不要轮询状态，
 * 一律订阅这个事件做各自的开局 / 收尾逻辑。
 *
 * <p>server 允许为 null（理论上不会发生，仅做防御），订阅者请判空。
 * 使用 Java 17 record 编写，升级到任何更高版本均可原样使用。
 */
public record GameStateChangedEvent(GamePhase from, GamePhase to, int globalTicks, MinecraftServer server) {
}
