package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.event.PlayerDownedEvent;
import com.paimeng.battleroyale.event.PlayerEliminatedEvent;
import com.paimeng.battleroyale.team.MatchTeam;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-KILLFEED] 模块二：千人千面击杀播报系统。
 *
 * <p>监听击倒（{@link PlayerDownedEvent}）与淘汰（{@link PlayerEliminatedEvent}），
 * 同一事件按观看者视角分发不同文案与音效（击杀者 A 属甲队、死者 B 属乙队）：
 * <ul>
 *   <li>无关人员（丙队）：[白] {@code A 使用 [武器] 淘汰了 B}，无音效；</li>
 *   <li>击杀者队（甲队）：[绿] {@code 队友 A 使用 [武器] 淘汰了 B}，高音音符盒；</li>
 *   <li>死者队（乙队）：[红] {@code 队友 B 被 A 使用 [武器] 淘汰了}，低音音符盒。</li>
 * </ul>
 *
 * <p>基础口径：武器名取击杀者主手 DisplayName，空手显示"徒手"；
 * 击杀距离（击杀者 ↔ 死者）≤50 米不带距离，>50 米插入 {@code 在 N 米外}；
 * 击倒与淘汰仅动词不同（击倒 ↔ 淘汰），结构完全一致。
 *
 * <p>全队覆灭：某队最后一名"存活/倒地"的队员死亡时，全服播报
 * {@code #N队 全队覆灭！}——击杀者队绿字 + 凋灵音效，死者队红字 + 鼓声，其余白字静音。
 *
 * <p>毒圈 / 流血淘汰没有击杀者，走环境文案（死者队红字 + 低音，其余白字）；
 * 断线重连判死（desert=true）不出击杀文案（指令层已按"逃跑"口径自行广播），但照常参与覆灭判定；
 * [r-LEAVE] 局内主动退出（voluntaryQuit=true）照常走整套击倒/淘汰播报：有最近玩家伤害来源
 * 就按正常击杀口径播（Kill 记给最后伤害者），没有则播「环境/主动退出」文案。
 */
public final class KillFeed {

    public static final KillFeed INSTANCE = new KillFeed();

    /** 超远击杀阈值（米）：击杀者与死者距离超过它，播报插入"在 N 米外"。 */
    private static final double LONG_RANGE_METERS = 50.0;
    /** 音符盒 pling 音色用 pitch 拉出高音 / 低音（需求里的"高音 / 低音音符盒"）。 */
    private static final float PITCH_HIGH = 2.0f;
    private static final float PITCH_LOW = 0.5f;

    /** 本局已播报过"全队覆灭"的队伍 ID（级联淘汰时防重复）。 */
    private final ConcurrentHashMap<UUID, Boolean> wipedTeams = new ConcurrentHashMap<>();

    private KillFeed() {
    }

    /** 新一局分配完成（PREPARING）时清空覆灭记录。 */
    public void resetMatchState() {
        wipedTeams.clear();
    }

    // ==================================================================
    // 事件入口（主类订阅；PlayerEliminatedEvent 必须排在 MatchTeamManager 之后，
    // 覆灭判定依赖淘汰名册已更新）
    // ==================================================================

    public void onPlayerDowned(PlayerDownedEvent event) {
        announce(event.player(), event.killer(), event.source(), false, false);
    }

    public void onPlayerEliminated(PlayerEliminatedEvent event) {
        if (!event.desert()) {
            announce(event.player(), event.killer(), event.source(), true,
                    event.bleedout(), event.voluntaryQuit());
        }
        checkTeamWipe(event);
    }

    // ==================================================================
    // 播报正文
    // ==================================================================

    private void announce(ServerPlayer victim, ServerPlayer killer, DamageSource source,
                          boolean eliminated, boolean bleedout) {
        announce(victim, killer, source, eliminated, bleedout, false);
    }

    private void announce(ServerPlayer victim, ServerPlayer killer, DamageSource source,
                          boolean eliminated, boolean bleedout, boolean voluntaryQuit) {
        MinecraftServer server = victim.server;
        if (server == null) {
            return;
        }
        String verb = eliminated ? "淘汰" : "击倒";
        String victimName = victim.getGameProfile().getName();

        boolean zone = ZoneDamage.isZoneDamage(source)
                || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.STARVE);
        boolean byPlayer = killer != null && !bleedout;
        // 死因分类：玩家击杀 / 流血淘汰 / 毒圈 / 其它意外（摔落、岩浆、/kill）。
        int meters = byPlayer ? Math.round(killer.distanceTo(victim)) : -1;
        String weapon = byPlayer ? weaponName(killer) : null;
        String killerName = byPlayer ? killer.getGameProfile().getName() : null;
        UUID killerId = byPlayer ? killer.getUUID() : null;

        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (!com.paimeng.battleroyale.util.BrText.inGameWorld(receiver)) {
                continue;
            }
            UUID receiverId = receiver.getUUID();
            if (receiverId.equals(victim.getUUID())) {
                // 自己击倒/被淘汰：按"被对面打"口径给红字（自己不响音效，避免吵到自己）。
                receiver.displayClientMessage(
                        Component.literal(passiveText(victimName, killerName, weapon, meters, verb, bleedout, zone, voluntaryQuit))
                                .withStyle(ChatFormatting.RED), false);
            } else if (receiverId.equals(killerId)) {
                // 击杀者本人：绿字"你用 X 淘汰了 B"，高音。
                receiver.displayClientMessage(Component.literal(
                                "你 使用 " + weapon + ' ' + verb + '了' + victimName)
                        .withStyle(ChatFormatting.GREEN), false);
                playNote(receiver, PITCH_HIGH);
            } else if (MatchTeamManager.INSTANCE.isTeammate(receiverId, victim.getUUID())) {
                // 死者队：红字 + 低音音符盒
                receiver.displayClientMessage(
                        Component.literal("队友 " + passiveText(victimName, killerName, weapon, meters, verb, bleedout, zone, voluntaryQuit))
                                .withStyle(ChatFormatting.RED), false);
                playNote(receiver, PITCH_LOW);
            } else if (killerId != null && MatchTeamManager.INSTANCE.isTeammate(receiverId, killerId)) {
                // 击杀者队：绿字 + 高音音符盒
                receiver.displayClientMessage(
                        Component.literal("队友 " + activeText(killerName, weapon, victimName, meters, verb))
                                .withStyle(ChatFormatting.GREEN), false);
                playNote(receiver, PITCH_HIGH);
            } else {
                // 无关人员：白字，无音效
                receiver.displayClientMessage(Component
                        .literal(byPlayer ? activeText(killerName, weapon, victimName, meters, verb)
                                : passiveText(victimName, killerName, weapon, meters, verb, bleedout, zone, voluntaryQuit))
                        .withStyle(ChatFormatting.WHITE), false);
            }
        }
    }

    /** 主动句：{@code A 使用 [武器] 淘汰了 B}（>50 米插入"在 N 米外"）。 */
    private static String activeText(String killerName, String weapon, String victimName, int meters, String verb) {
        StringBuilder sb = new StringBuilder();
        sb.append(killerName);
        if (meters > LONG_RANGE_METERS) {
            sb.append(" 在 ").append(meters).append(" 米外");
        }
        sb.append(" 使用 ").append(weapon).append(' ').append(verb).append('了').append(victimName);
        return sb.toString();
    }

    /** 被动句：{@code B 被 A 使用 [武器] 淘汰了}；无击杀者时走环境/意外文案。 */
    private static String passiveText(String victimName, String killerName, String weapon,
                                      int meters, String verb, boolean bleedout, boolean zone,
                                      boolean voluntaryQuit) {
        if (killerName == null) {
            return envText(victimName, verb, bleedout, zone, voluntaryQuit);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(victimName).append(" 被 ").append(killerName);
        if (meters > LONG_RANGE_METERS) {
            sb.append(" 在 ").append(meters).append(" 米外");
        }
        sb.append(" 使用 ").append(weapon).append(' ').append(verb).append('了');
        return sb.toString();
    }

    /** 环境（无击杀者）文案：流血淘汰单列，毒圈按"圈外太久"口径，主动退出单列，其余按意外口径。 */
    private static String envText(String victimName, String verb, boolean bleedout, boolean zone,
                                  boolean voluntaryQuit) {
        if (voluntaryQuit) {
            return victimName + " 主动退出对局，击杀者记为「环境/主动退出」，判定阵亡";
        }
        if (bleedout) {
            return victimName + " 因为在倒地后没有人救援而被淘汰了";
        }
        if (zone) {
            return victimName + " 因为在安全区外停留时间太长而被" + verb + '了';
        }
        return victimName + " 因为意外情况被" + verb + '了';
    }

    /** 击杀者武器名：主手 DisplayName；空手 → "徒手"（需求口径）。 */
    private static String weaponName(ServerPlayer killer) {
        ItemStack stack = killer.getMainHandItem();
        if (stack.isEmpty() || stack.is(Items.AIR)) {
            return "徒手";
        }
        String name = stack.getHoverName().getString();
        return (name == null || name.isBlank()) ? "徒手" : name;
    }

    /**
     * 从伤害源解析击杀者：本体攻击取 getEntity；弓箭 / 子弹取弹体 Owner
     * （Projectile#getOwner），都找不到（毒圈 / 摔死 / 岩浆）返回 null。
     */
    public static ServerPlayer resolveKiller(ServerPlayer victim, DamageSource source) {
        if (source == null) {
            return null;
        }
        if (source.getEntity() instanceof ServerPlayer player && player != victim) {
            return player;
        }
        if (source.getDirectEntity() instanceof Projectile projectile
                && projectile.getOwner() instanceof ServerPlayer shooter && shooter != victim) {
            return shooter;
        }
        return null;
    }

    // ==================================================================
    // 全队覆灭（Team Wipe）
    // ==================================================================

    /**
     * 乙队最后一名"存活/倒地"的队员死亡时全服播报 {@code #N队 全队覆灭！}。
     *
     * <p>判定口径：本队没有任何"未被淘汰"的队员才算覆灭——倒地者还没死透不算，
     * 等流血清级联把他淘汰后（同样走本事件）再判定；
     * 每名队员被淘汰都会触发一次本检查，最后一个触发成功，
     * {@link #wipedTeams} 闩保证只播一次。
     */
    private void checkTeamWipe(PlayerEliminatedEvent event) {
        MinecraftServer server = event.server();
        MatchTeam victimTeam = MatchTeamManager.INSTANCE.teamOf(event.player().getUUID());
        if (server == null || victimTeam == null) {
            return;
        }
        for (UUID memberId : victimTeam.memberIds()) {
            if (!MatchTeamManager.INSTANCE.wasEliminatedThisMatch(memberId)) {
                return; // 还有存活/倒地（倒地此刻尚未进淘汰名册）的队员
            }
        }
        if (wipedTeams.putIfAbsent(victimTeam.getMatchTeamId(), Boolean.TRUE) != null) {
            return; // 已经播过
        }
        String wipeText = "#" + victimTeam.getTeamNumber() + "队 全队覆灭！";
        UUID killerId = event.killer() == null ? null : event.killer().getUUID();
        UUID victimId = event.player().getUUID();
        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (!com.paimeng.battleroyale.util.BrText.inGameWorld(receiver)) {
                continue;
            }
            UUID receiverId = receiver.getUUID();
            if (receiverId.equals(victimId) || MatchTeamManager.INSTANCE.isTeammate(receiverId, victimId)) {
                // 死者队（含被淘汰者本人）：红字 + 鼓声
                receiver.displayClientMessage(Component.literal(wipeText).withStyle(ChatFormatting.RED), false);
                receiver.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 1.0f, 1.0f);
            } else if (killerId != null && MatchTeamManager.INSTANCE.isTeammate(receiverId, killerId)) {
                // 击杀者队：绿字 + 凋灵音效
                receiver.displayClientMessage(Component.literal(wipeText).withStyle(ChatFormatting.GREEN), false);
                receiver.playSound(SoundEvents.WITHER_DEATH, 1.0f, 1.0f);
            } else {
                receiver.displayClientMessage(Component.literal(wipeText).withStyle(ChatFormatting.WHITE), false);
            }
        }
    }

    private static void playNote(ServerPlayer receiver, float pitch) {
        receiver.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch);
    }
}
