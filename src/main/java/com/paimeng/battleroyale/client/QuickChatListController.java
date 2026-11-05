package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.config.QuickChatStore;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.TeamChat;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.List;

/**
 * FPS 风格快捷消息列表的<b>客户端状态与发送入口</b>（供 ChatScreenMixin 调用）。
 *
 * <p>列表数据直接读 {@link QuickChatStore}（config/battleroyale/quickchat.json，
 * 主入口 onInitialize 时已 loadOrCreate，客户端同样加载）。选中项只有一个整数索引，
 * 打开聊天框时重置为 0，↑↓ 循环移动。
 *
 * <p>发送走既有的 {@code battleroyale:quick_chat} C2S 通道（包体就是消息文本，
 * 等价于需求里的 QuickChatSendPayload），服务端 {@code TeamChat} 校验预设后
 * 广播给同队玩家——原有广播逻辑零改动。
 *
 * <p>「激活」条件（需求三的按键闸门）：消息列表非空、玩家已在游戏里、
 * 且当前服务器注册了快捷消息通道。三者缺一就完全不拦截按键，
 * 聊天框行为跟原版一模一样，绝不会把玩家「锁」在列表里。
 */
@Environment(EnvType.CLIENT)
public final class QuickChatListController {

    /** 两次发送的最小间隔，防止 Tab 连点刷屏。 */
    private static final long SEND_COOLDOWN_MS = 300L;

    /** 当前高亮索引（越界读取时统一 clamp，不额外防抖）。 */
    private static int selectedIndex = 0;

    private static long lastSendAtMs = 0L;

    private QuickChatListController() {
    }

    /** 打开聊天框（ChatScreen#init）时调用：高亮回到第一条。 */
    public static void onChatOpened() {
        selectedIndex = 0;
    }

    /**
     * [BR-QC-DIM] 纯客户端维度硬判断：玩家当前是否身处比赛维度
     * （{@code battleroyale_game:*} 命名空间，如 {@code battleroyale_game:default_losa}）。
     *
     * <p>直接读 {@code Minecraft.getInstance().player.level().dimension()}，
     * 不发任何网络包。登录大厅（主世界）、下界、末地及其他一切非比赛维度都返回 false，
     * 快捷消息的列表渲染与按键拦截由此整体关闭。
     */
    public static boolean isInGameDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && GameWorldManager.isGameWorld(mc.player.level().dimension());
    }

    /** 当前列表是否激活（见类注释的三条件闸门）。 */
    public static boolean isActive() {
        if (QuickChatStore.messages().isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return false;
        }
        return ClientPlayNetworking.canSend(TeamChat.CHANNEL_ID);
    }

    /** ↑↓ 切换：delta 为 -1 / +1，首尾循环。 */
    public static void moveSelection(int delta) {
        List<String> messages = QuickChatStore.messages();
        int size = messages.size();
        if (size == 0) {
            selectedIndex = 0;
            return;
        }
        // 先取模再加，((x % n) + n) % n 处理负数，实现循环。
        selectedIndex = ((selectedIndex + delta) % size + size) % size;
    }

    /** 当前高亮的消息文本；列表为空返回 null。 */
    public static String selectedMessage() {
        List<String> messages = QuickChatStore.messages();
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(Math.min(Math.max(selectedIndex, 0), messages.size() - 1));
    }

    /** 当前高亮索引（已 clamp，渲染窗口用）。 */
    public static int clampedIndex() {
        List<String> messages = QuickChatStore.messages();
        if (messages.isEmpty()) {
            return 0;
        }
        return Math.min(Math.max(selectedIndex, 0), messages.size() - 1);
    }

    /**
     * 发送当前高亮的快捷消息并关闭聊天框。
     *
     * @param screen 发起发送的 ChatScreen（发送成功后 onClose）
     * @return true 表示已消费本次按键（含冷却拦截，防连点）；
     *         false 表示没发出去且没关闭（如通道断开），按键留给调用方兜底提示
     */
    public static boolean sendSelectedAndClose(ChatScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        String text = selectedMessage();
        if (text == null || mc.player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendAtMs < SEND_COOLDOWN_MS) {
            // 冷却窗口内的重复按键直接吞掉，避免 Tab 连点刷屏。
            return true;
        }
        if (!ClientPlayNetworking.canSend(TeamChat.CHANNEL_ID)) {
            mc.gui.setOverlayMessage(BrText.mutable("当前服务器不支持快捷消息")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        lastSendAtMs = now;
        ClientPlayNetworking.send(TeamChat.CHANNEL_ID, TeamChat.encode(text));
        screen.onClose();
        return true;
    }
}
