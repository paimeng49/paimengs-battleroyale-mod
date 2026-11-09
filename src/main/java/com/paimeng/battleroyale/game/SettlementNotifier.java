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
 *   <li>前三名：每次淘汰后重算存活队伍数，只要降到 3 支或更少即触发一次
 *       Title 副标题 "已进入前三名！本局比赛已胜利，请继续角逐冠军！"
 *       （top3Shown 防重，一局只播一次）；</li>
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
        // 第一条淘汰事件的上一状态就是开局队伍数，不能只记基线后返回，
        // 否则 4→3、2→1 的关键提示会被永远吞掉。
        if (prev < 0) {
            prev = MatchTeamManager.INSTANCE.startedTeamCount();
        }
        lastAliveTeams = alive;
        // 口径：存活队伍数 <= 3 即触发前三提示（一次团灭跨多级也能命中），
        // top3Shown 防重，一局只播一次。
        if (!top3Shown && alive <= 3) {
            top3Shown = true;
            announceTop3(server);
        }
        if (!winnerShown && alive == 1 && prev > 1) {
            winnerShown = true;
            announceWinner(server);
        }
    }

    public void onGameStateChanged(GameStateChangedEvent event) {
        if (event.to() == GamePhase.PREPARING) {
            resetMatchState();
        } else if (event.to() == GamePhase.ENDED
                && !winnerShown
                && MatchTeamManager.INSTANCE.aliveTeamCount() == 1) {
            // 单队测试局没有淘汰事件，RUNNING 首 tick 直接结束时在这里补发吃鸡 Title。
            winnerShown = true;
            announceWinner(event.server());
        }
    }

    /** 调试入口：强制走吃鸡 Title，并由调用方继续触发 ENDED→RESETTING 清理。 */
    public void debugForceWin(MinecraftServer server, ServerPlayer requestedPlayer) {
        if (server == null) {
            return;
        }
        List<ServerPlayer> targets = new ArrayList<>();
        if (requestedPlayer != null) {
            targets.add(requestedPlayer);
        } else {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (com.paimeng.battleroyale.util.BrText.inGameWorld(player)) {
                    targets.add(player);
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        winnerShown = true;
        top3Shown = true;
        VictoryFireworks.INSTANCE.start(targets);
        for (ServerPlayer target : targets) {
            sendTitle(target, "大吉大利，今晚吃鸡！", ChatFormatting.GOLD,
                    "本局击杀数：" + StatsManager.INSTANCE.killsOf(target.getUUID()), ChatFormatting.YELLOW);
            target.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        BrText.broadcast(server, "【大逃杀】调试结算：已强制触发吃鸡流程。");
    }

    // ------------------------------------------------------------------
    // 播报实现
    // ------------------------------------------------------------------

    /** 前三名：给全部存活队员发 Title 副标题（需求口径：前三为白色）。 */
    private void announceTop3(MinecraftServer server) {
        for (MatchTeam team : aliveTeams(server)) {
            for (ServerPlayer member : onlineMembers(server, team)) {
                sendTitle(member,
                        "#" + team.getTeamNumber() + "队 进入前三！", ChatFormatting.WHITE,
                        "已进入前三名！本局比赛已胜利，请继续角逐冠军！", ChatFormatting.WHITE);
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
                    "本局击杀数：" + StatsManager.INSTANCE.killsOf(member.getUUID()), ChatFormatting.YELLOW);
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
