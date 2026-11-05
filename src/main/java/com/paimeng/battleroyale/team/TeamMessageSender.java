package com.paimeng.battleroyale.team;

import com.paimeng.battleroyale.util.BrText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * <b>队伍消息发送的公共出口</b>（上一轮需求指定的抽象点）：标点系统、快捷消息强化
 * 都要把内容送给"同队的队友们"，本类统一承担这件事，两边只调方法、不自己拼循环。
 *
 * <p>方法（标点 + 快捷消息两条链路现在都是<b>绿色高亮 + 经验拾取音效</b>）：
 * <ul>
 *   <li>{@link #sendToTeam(ServerPlayer, Component, boolean)}：发给发送者的<b>全部在线队友</b>
 *       （含发送者自己）。没有队伍时返回 0，由调用方决定兜底（现在两条链路都是
 *       "只发自己"，全服广播功能已按需求删除）。{@code playSound} 为 true 时每个收件人
 *       都响一声经验球拾取音——走原版成就音同款的 {@code playNotifySound}（MASTER
 *       频道、本人位置，不受"玩家声音"子频道和实体追踪影响），标点和快捷消息都传 true。</li>
 *   <li>{@link #sendToSelf(ServerPlayer, Component)}：只发给发送者本人（没队伍时的
 *       兜底路径），同样响一声经验球拾取音，保持手感一致。</li>
 *   <li>{@link #buildTeamMessage(ServerPlayer, Component)}：拼一条带队伍前缀的消息。
 *       局内 MatchTeam = {@code [#N队][座号] 名字: 内容}（座号和名字用<b>局内座号</b>
 *       对应的标志色：1 黄、2 橙（金）、3 蓝（天蓝）、4 绿，超出兜底白）；
 *       大厅队 = {@code [队伍] 名字: 内容}（<b>[BR-NOLOBBYNUMBER] 不带任何编号、
 *       不带战术色</b>——大厅阶段还没有局内座号可上色）。</li>
 * </ul>
 *
 * <p>为什么塞进 {@code team} 包：发送逻辑只依赖 {@link TeamManager}/{@link Team}，
 * 不依赖任何客户端类，服务端主线程直接用。
 *
 * <p><b>升级预留</b>：{@code sendMessage(Component, boolean)} 在 1.20.5+ 改名
 * {@code displayClientMessage}，改三处调用即可；提示音统一走
 * {@link ServerPlayer#playNotifySound(net.minecraft.sounds.SoundEvent, SoundSource, float, float)}。
 */
public final class TeamMessageSender {

    /**
     * 消息提示音：经验球拾取音（标点与快捷消息统一用它，需求指定）。
     * 走 playNotifySound + MASTER 频道——和原版成就提示音同一条路线，
     * 不受"玩家声音"子频道静音和实体追踪状态影响，收件人一定听得见。
     */
    private static final float SOUND_VOLUME = 0.7f;
    private static final float SOUND_PITCH = 1.0f;

    private TeamMessageSender() {
    }

    // ------------------------------------------------------------------
    // 给队友发（快捷消息 / 标点的共同出口）
    // ------------------------------------------------------------------

    /**
     * 把一条消息发给发送者的全部在线队友（含发送者自己）。
     *
     * @param sender    发送者（服务端对象，必须是服务端主线程调用）
     * @param message   要展示的完整消息（调用方先用 {@link #buildTeamMessage} 拼好）
     * @param playSound true = 每个收件人都播一声经验球拾取音（标点、快捷消息都用）
     * @return 实际收到的队友人数；0 = 发送者没有队伍（调用方要自己兜底）
     */
    public static int sendToTeam(ServerPlayer sender, Component message, boolean playSound) {
        if (sender == null || message == null) {
            return 0;
        }
        // [BR-MATCHTEAM] 队友列表走 MatchTeamManager：比赛进行中发给了本局同队的人，
        // 未开局 / 回大厅自动回退大厅队 —— 同一个调用点覆盖两种场景。
        if (!MatchTeamManager.INSTANCE.hasTeamView(sender.getUUID())) {
            return 0;
        }
        java.util.List<java.util.UUID> memberIds =
                MatchTeamManager.INSTANCE.memberIdsIncludingSelf(sender.getUUID());
        if (memberIds.isEmpty()) {
            return 0;
        }
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer member : server.getPlayerList().getPlayers()) {
            if (memberIds.contains(member.getUUID())) {
                member.displayClientMessage(message, false);
                if (playSound) {
                    playNotifyPickup(member);
                }
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 没队伍时的兜底：只发发送者本人
    // ------------------------------------------------------------------

    /**
     * 只把消息发给发送者本人并响一声经验球拾取音。
     * 标点和快捷消息在"发送者没有队伍"时走这里（全服广播功能已按需求删除）。
     */
    public static void sendToSelf(ServerPlayer sender, Component message) {
        if (sender == null || message == null) {
            return;
        }
        sender.displayClientMessage(message, false);
        playNotifyPickup(sender);
    }

    /** 给指定玩家响一声经验球拾取提示音（原版成就音同款 playNotifySound 路线）。 */
    private static void playNotifyPickup(ServerPlayer player) {
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.MASTER, SOUND_VOLUME, SOUND_PITCH);
    }

    // ------------------------------------------------------------------
    // 拼消息
    // ------------------------------------------------------------------

    /**
     * 拼一条队伍消息：局内 MatchTeam 才显示 "[#N队][座号]" 和座号颜色；
     * 大厅队只显示不带编号、不带战术色的 "[队伍] 名字"。
     */
    public static MutableComponent buildTeamMessage(ServerPlayer sender, Component content) {
        String name = sender.getGameProfile().getName();
        java.util.UUID senderId = sender.getUUID();
        MatchTeam matchTeam = MatchTeamManager.INSTANCE.teamOf(senderId);
        if (matchTeam != null) {
            int seat = matchTeam.squadIndexOf(senderId);
            return BrText.mutable("[#" + matchTeam.getTeamNumber() + "队] ").withStyle(ChatFormatting.GRAY)
                    .append(BrText.mutable("[" + seat + "] " + name + " ").withStyle(formattingOf(seat)))
                    .append(BrText.mutable(": ").withStyle(ChatFormatting.GRAY))
                    .append(content);
        }
        if (com.paimeng.battleroyale.team.TeamManager.INSTANCE.teamOf(senderId) != null) {
            return BrText.mutable("[队伍] ").withStyle(ChatFormatting.GRAY)
                    .append(BrText.mutable(name + ": ").withStyle(ChatFormatting.WHITE))
                    .append(content);
        }
        return BrText.mutable(name + ": ").withStyle(ChatFormatting.WHITE).append(content);
    }

    /**
     * <b>局内座号</b> → 聊天标志色（只有 MatchTeam 才有座号，大厅队拿不到颜色）。
     * 规则与屏幕 HUD（{@code TeamHudRenderer.colorOf}）一致：
     * 1 黄、2 橙、3 蓝、4 绿；座号越界（含 0 / 负数，异常情况）兜底白色。
     * 聊天只有 16 色可用，橙色/蓝色取最接近的 GOLD / AQUA。
     */
    public static ChatFormatting formattingOf(int squadIndex) {
        return switch (squadIndex) {
            case 1 -> ChatFormatting.YELLOW;   // 0xFFFF00
            case 2 -> ChatFormatting.GOLD;     // ≈0xFFAA00（最接近 0xFFA500 橙色）
            case 3 -> ChatFormatting.AQUA;     // ≈0x55FFFF（最接近 0x00BFFF 天蓝）
            case 4 -> ChatFormatting.GREEN;    // 0x55FF55（最接近 0x00FF00）
            default -> ChatFormatting.WHITE;
        };
    }
}