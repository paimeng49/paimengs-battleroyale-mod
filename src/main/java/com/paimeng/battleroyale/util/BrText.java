package com.paimeng.battleroyale.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

/**
 * 文本与广播的统一出口（版本隔离层）。
 *
 * <p>1.18.2 里创建纯文本要用 {@code Component.literal(String)}；
 * 1.19.3+ 改成了 {@code Component.literal(String)}，1.20.5+ 广播的
 * sendMessage 也改名了。升级时只需要改这一个类，业务代码不动。
 */
public final class BrText {

    private BrText() {
    }

    /** 创建一条纯文本消息。 */
    public static Component of(String raw) {
        // [UPGRADE] 1.19.3+ 改为 return Component.literal(raw);
        return Component.literal(raw);
    }

    /** 创建一条可继续 append / formatted / styled 的文本（这三类方法只在 MutableComponent 上有）。 */
    public static MutableComponent mutable(String raw) {
        // [UPGRADE] 1.19.3+ 改为 return Component.literal(raw);（Component.literal 本身就返回 MutableComponent）
        return Component.literal(raw);
    }

    /** 给所有在线玩家广播一条系统消息。 */
    public static void broadcast(MinecraftServer server, String raw) {
        if (server == null) {
            return;
        }
        Component message = of(raw);
        // [UPGRADE] 1.20.5+ 中 sendMessage(Component, boolean) 改名为 displayClientMessage
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(message, false);
        }
    }
}
