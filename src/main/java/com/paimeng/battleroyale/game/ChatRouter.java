package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.team.MatchTeam;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.team.TeamMessageSender;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * [BR-CHAT] 普通聊天的三频道拦截器（服务端）。
 *
 * <p>挂在 Fabric 的 {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} 上：
 * 返回 false 取消原版全服广播，再按当前 {@link GamePhase} 自己分发：
 * <ul>
 *   <li><b>WAITING（大厅阶段）</b>：直接发文本 = 全服聊天，格式
 *       {@code [大厅] 玩家名: 文本}，服务器所有在线玩家可见；</li>
 *   <li><b>PREPARING 及之后（局内阶段）</b>：
 *     <ul>
 *       <li>默认（不带前缀）：只在发送者所在的 {@link MatchTeam} 内可见，
 *           格式 {@code [队伍] 玩家名: 文本}，玩家名按座号标志色（1 黄、2 橙、3 蓝、4 绿）；</li>
 *       <li>{@code ".all "} 开头（注意空格）：截掉前缀后发给<b>本局游戏内</b>的所有玩家
 *           （被分到 MatchTeam 的参赛者 + 人在游戏维度里的旁观者 + 发送者自己），
 *           格式 {@code [全局] 玩家名: 文本}；</li>
 *       <li>边界：玩家没有 MatchTeam（旁观者 / 中途加入）→ 走全局频道。</li>
 *     </ul></li>
 * </ul>
 *
 * <p>快捷消息和标点不经过原版聊天包，不受本拦截影响；编辑副本维度的管理员
 * 聊天同样按大厅口径分发（WAITING 阶段），不做特判。
 *
 * <p>任何一次分发抛异常都返回 true 放行原版广播，保证消息不丢。
 */
public final class ChatRouter {

    /** 局内全局频道前缀：必须以 ".all" + 一个空格开头（需求指定）。 */
    private static final String ALL_PREFIX = ".all ";

    private ChatRouter() {
    }

    /** 服务端入口注册（BattleRoyaleMod.onInitialize 调用一次）。 */
    public static void registerServer() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, params) -> {
            try {
                // signedContent() = 聊天包里的原始文本（String）。
                return route(player, message.signedContent());
            } catch (RuntimeException e) {
                BattleRoyaleMod.LOGGER.warn("[BR-CHAT] 聊天分发失败，放行原版广播: {}", e.toString());
                return true;
            }
        });
    }

    /**
     * 分发一条聊天文本。
     *
     * @return false = 已接管并取消原版广播；true = 请原版照常处理
     */
    private static boolean route(ServerPlayer player, String raw) {
        if (player == null || raw == null) {
            return true;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return true;
        }
        GamePhase phase = GameManager.INSTANCE.getPhase();

        // ---- 大厅阶段：全服聊天 ----
        if (phase == GamePhase.WAITING) {
            String text = raw.trim();
            if (text.isEmpty()) {
                return false;                  // 空消息：静默吞掉，也不放行原版
            }
            Component message = prefixed("大厅", ChatFormatting.GRAY, player, text, ChatFormatting.WHITE);
            for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
                receiver.displayClientMessage(message, false);
            }
            return false;
        }

        // ---- 局内阶段（PREPARING / FLIGHT / RUNNING / ENDED）----
        boolean globalWanted = raw.startsWith(ALL_PREFIX);
        String body = globalWanted ? raw.substring(ALL_PREFIX.length()).trim() : raw.trim();
        if (body.isEmpty()) {
            if (globalWanted) {
                player.displayClientMessage(BrText.mutable("【聊天】.all 后面要带内容，例如：.all 集合攻 B 点")
                        .withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        MatchTeam team = MatchTeamManager.INSTANCE.teamOf(player.getUUID());
        if (globalWanted || team == null) {
            // 全局频道：本局游戏内的所有玩家（参赛者 + 游戏维度里的旁观者 + 发送者）。
            Component message = prefixed("全局", ChatFormatting.GRAY, player, body, ChatFormatting.WHITE);
            for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
                if (receiver == player || MatchTeamManager.INSTANCE.isAssigned(receiver.getUUID())
                        || GameWorldManager.isGameWorld(receiver.level().dimension())) {
                    receiver.displayClientMessage(message, false);
                }
            }
            return false;
        }

        // 队伍频道：只发给本队（含自己），名字带座号标志色。
        int seat = MatchTeamManager.INSTANCE.squadIndexOf(player.getUUID());
        ChatFormatting nameColor = TeamMessageSender.formattingOf(seat);
        Component message = prefixed("队伍", ChatFormatting.GRAY, player, body, nameColor);
        if (TeamMessageSender.sendToTeam(player, message, false) == 0) {
            // 理论上到不了这里（teamOf 刚查到队）：兜底只发自己，消息不丢。
            player.displayClientMessage(message, false);
        }
        return false;
    }

    /** 拼 "[频道] 名字: 文本"，名字用 {@code nameColor}，其余灰色 / 白色。 */
    private static MutableComponent prefixed(String channel, ChatFormatting channelColor,
                                             ServerPlayer player, String body, ChatFormatting nameColor) {
        String name = player.getGameProfile().getName();
        return BrText.mutable("[" + channel + "] ")
                .withStyle(channelColor)
                .append(BrText.mutable(name).withStyle(nameColor))
                .append(BrText.mutable(": ").withStyle(ChatFormatting.GRAY))
                .append(BrText.mutable(body).withStyle(ChatFormatting.WHITE));
    }
}
