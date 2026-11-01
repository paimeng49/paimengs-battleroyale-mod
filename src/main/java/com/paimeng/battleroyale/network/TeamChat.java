package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.config.QuickChatStore;
import com.paimeng.battleroyale.team.TeamMessageSender;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>快捷消息的 C2S 通道</b>：客户端只能发送 quickchat.json 里的预设，
 * 服务端验证后通过 {@link TeamMessageSender} 发给同队玩家。
 *
 * <p>包体只有一项：消息字符串（上限 128 字符）。没有全服标记，
 * 因为 {@code qc all} 已按需求删除。
 */
public final class TeamChat {

    /** 通道名：battleroyale:quick_chat。 */
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "quick_chat");

    /** 一条快捷消息的最长字符数。 */
    public static final int MAX_TEXT_LENGTH = 128;

    private TeamChat() {
    }

    /** 服务端入口注册。 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_ID,
                (server, player, handler, buf, responseSender) -> {
                    try {
                        onQuickChat(player, buf);
                    } catch (RuntimeException e) {
                        BattleRoyaleMod.LOGGER.warn("[{}] 快捷消息包解析失败: {}", BattleRoyaleMod.MOD_ID, e.toString());
                    }
                });
    }

    /** 客户端发送用：把一条快捷消息编成 C2S 包。 */
    public static FriendlyByteBuf encode(String text) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(text, MAX_TEXT_LENGTH);
        return buf;
    }

    /** 收到快捷消息包：交给统一的服务端快捷消息入口处理。 */
    private static void onQuickChat(ServerPlayer player, FriendlyByteBuf buf) {
        sendPresetMessage(player, buf.readUtf(MAX_TEXT_LENGTH));
    }

    /**
     * 服务端执行一条快捷消息：校验预设后调用公共队伍消息出口。
     * 指令入口和旧版 C2S 包入口都复用这里，保证校验与发送行为一致。
     */
    public static boolean sendPresetMessage(ServerPlayer player, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (!QuickChatStore.isPreset(trimmed)) {
            player.displayClientMessage(BrText.mutable("只能发送预设的快捷消息").withStyle(ChatFormatting.RED), false);
            return false;
        }

        // 正文绿色高亮，提示音由公共队伍消息出口统一播放经验拾取音。
        MutableComponent teamMessage = TeamMessageSender.buildTeamMessage(
                player, BrText.mutable(trimmed).withStyle(ChatFormatting.GREEN));
        int recipients = TeamMessageSender.sendToTeam(player, teamMessage, true);
        if (recipients == 0) {
            TeamMessageSender.sendToSelf(player, teamMessage);
            player.displayClientMessage(BrText.mutable("（你还没有队伍，这条快捷消息只显示给你自己。）")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return true;
    }
}
