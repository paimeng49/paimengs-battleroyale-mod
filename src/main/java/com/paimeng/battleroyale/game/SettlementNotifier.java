package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.event.GameStateChangedEvent;
import com.paimeng.battleroyale.event.PlayerEliminatedEvent;
import com.paimeng.battleroyale.team.MatchTeam;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [BR-SETTLE] 模块三：结算提示。
 *
 * <ol>
 *   <li>淘汰提示（ActionBar）：被淘汰后仍有队友存活 →
 *       "您的队友仍在战斗，你仍有机会获取最终胜利！"；队伍全灭 →
 *       "您的队伍已被全部淘汰。"；</li>
 *   <li>前三名：存活队伍数从 >3 降为 3 的瞬间，给全部存活队员发 Title 副标题
 *       "已进入前三名！本局比赛已胜利，请继续角逐冠军！"；</li>
 *   <li>吃鸡结算：存活队伍数降为 1（开局 >=2 队才算真冠军）→
 *       冠军队 Title "大吉大利，今晚吃鸡！" + 挑战完成音效（ui.toast.challenge_complete），
 *       全服聊天播报。</li>
 * </ol>
 *
 * <p>名次跃迁用"每次淘汰事件后重算存活队伍数 + 记上一值"实现：
 * 一次事件最多让一支队伍阵亡，prev -> now 恰好跨过 3 或 1 时触发，
 * 天然防止重复弹屏；flags 随新一局（PREPARING）复位。
 */
public final class SettlementNotifier {

    public static final SettlementNotifier INSTANCE = new SettlementNotifier();

    /** 上一次统计的存活队伍数（-1 = 本局还没开始统计）。 */
    private int lastAliveTeams = -1;
    private boolean top3Shown;
    private boolean winnerShown;

    private SettlementNotifier() {
    }

    /** 新一局分配完成（PREPARING）：复位全部结算标志。 */
    public void resetMatchState() {
        lastAliveTeams = -1;
        top3Shown = false;
        winnerShown = false;
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    public void onPlayerEliminated(PlayerEliminatedEvent event) {
        MinecraftServer server = event.server();
        if (server == null) {
            return;
        }
        ServerPlayer victim = event.player();
        UUID victimId = victim.getUUID();

        // 1) 淘汰提示（Actionbar）：看队里还有没有没被淘汰的队友
        MatchTeam team = MatchTeamManager.INSTANCE.teamOf(victimId);
        if (team != null) {
            boolean teammatesFighting = false;
            for (UUID memberId : team.memberIds()) {
                if (!memberId.equals(victimId) && !MatchTeamManager.INSTANCE.wasEliminatedThisMatch(memberId)) {
                    teammatesFighting = true;
                    break;
                }
            }
            String tip = teammatesFighting
                    ? "您的队友仍在战斗，你仍有机会获取最终胜利！"
                    : "您的队伍已被全部淘汰。";
            victim.displayClientMessage(BrText.mutable(tip).withStyle(
                    teammatesFighting ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        }

        // 2) 存活队伍数跃迁：前三 / 冠军
        int alive = MatchTeamManager.INSTANCE.aliveTeamCount();
        if (alive < 0) {
            return; // 本局未激活
        }
        int prev = lastAliveTeams;
        lastAliveTeams = alive;
        if (prev <= 0) {
            return; // 首条事件只记基线
        }
        if (!top3Shown && alive == 3 && prev > 3) {
            top3Shown = true;
            announceTop3(server);
        }
        if (!winnerShown && alive == 1 && prev > 1
                && MatchTeamManager.INSTANCE.startedTeamCount() >= 2) {
            winnerShown = true;
            announceWinner(server);
        }
    }

    public void onGameStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.PREPARING) {
            resetMatchState();
        }
    }

    // ------------------------------------------------------------------
    // 播报实现
    // ------------------------------------------------------------------

    /** 前三名：给全部存活队员发 Title 副标题。 */
    private void announceTop3(MinecraftServer server) {
        for (MatchTeam team : aliveTeams(server)) {
            for (ServerPlayer member : onlineMembers(server, team)) {
                sendTitle(member,
                        "#" + team.getTeamNumber() + "队 进入前三！", ChatFormatting.GOLD,
                        "已进入前三名！本局比赛已胜利，请继续角逐冠军！", ChatFormatting.YELLOW);
            }
        }
    }

    /** 吃鸡：冠军队 Title + 挑战完成音效；全服聊天播报。 */
    private void announceWinner(MinecraftServer server) {
        List<MatchTeam> alive = aliveTeams(server);
        MatchTeam champion = alive.isEmpty() ? null : alive.get(0);
        BrText.broadcast(server, "【大逃杀】" + (champion == null
                ? "本局比赛结束！" : "#" + champion.getTeamNumber() + "队 获得本局胜利，恭喜吃鸡！"));
        if (champion == null) {
            return;
        }
        List<ServerPlayer> championMembers = onlineMembers(server, champion);
        VictoryFireworks.INSTANCE.start(championMembers);
        for (ServerPlayer member : championMembers) {
            sendTitle(member, "大吉大利，今晚吃鸡！", ChatFormatting.GOLD,
                    "#" + champion.getTeamNumber() + "队 是本局的冠军！", ChatFormatting.YELLOW);
            member.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    /** 当前还有"未淘汰队员"的队伍列表（判定前淘汰名册已由 MatchTeamManager 更新）。 */
    private static List<MatchTeam> aliveTeams(MinecraftServer server) {
        List<MatchTeam> result = new ArrayList<>();
        for (MatchTeam team : MatchTeamManager.INSTANCE.teams()) {
            for (UUID memberId : team.memberIds()) {
                if (!MatchTeamManager.INSTANCE.wasEliminatedThisMatch(memberId)) {
                    result.add(team);
                    break;
                }
            }
        }
        return result;
    }

    /** 队伍里当前在线且未淘汰的队员（掉线的跳过——重连时阶段切换会补发口径外的提示不再追）。 */
    private static List<ServerPlayer> onlineMembers(MinecraftServer server, MatchTeam team) {
        List<ServerPlayer> result = new ArrayList<>();
        for (UUID memberId : team.memberIds()) {
            if (MatchTeamManager.INSTANCE.wasEliminatedThisMatch(memberId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(memberId);
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }

    /** Title 三件套：动画参数 + 主标题 + 副标题（fadeIn 0.5s / stay 6s / fadeOut 1s）。 */
    private static void sendTitle(ServerPlayer player, String main, ChatFormatting mainColor,
                                  String sub, ChatFormatting subColor) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 120, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(BrText.mutable(main).withStyle(mainColor)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(BrText.mutable(sub).withStyle(subColor)));
    }
}
