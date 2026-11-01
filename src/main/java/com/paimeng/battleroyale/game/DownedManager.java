package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.PlayerEliminatedEvent;
import com.paimeng.battleroyale.item.ModItems;
import com.paimeng.battleroyale.network.DownedSync;
import com.paimeng.battleroyale.network.DownedSyncPayload;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.team.Team;
import com.paimeng.battleroyale.team.TeamManager;
import com.paimeng.battleroyale.zone.ZoneManager;
import com.paimeng.battleroyale.zone.ZoneStage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端权威的击倒、救援、自救和淘汰状态机（独立击倒血量池版本）。 */
public final class DownedManager {
    public static final DownedManager INSTANCE = new DownedManager();
    public static final int DOWNED_TICKS = 60 * 20;
    public static final int RESCUE_TICKS = 10 * 20;
    /** 独立击倒血量池的上限（与原版血量数值一致，都是 20）。 */
    public static final float DOWNED_HEALTH_MAX = 20.0f;
    /** 击倒基础衰减：每 tick 扣 20 / (60 * 20) ≈ 0.016667，60 秒从 20 掉到 0。 */
    private static final float DOWNED_DECAY_PER_TICK = DOWNED_HEALTH_MAX / (60.0f * 20.0f);
    private static final double RESCUE_RADIUS_SQUARED = 4.0;
    private static final double MAX_RESCUER_MOVE_SQUARED = 0.25;
    private static final boolean YSM_LOADED = FabricLoader.getInstance().isModLoaded("ysm");

    /** 独立击倒血量池：UUID → 剩余击倒血量。含该键即处于 DOWNED 状态。 */
    private final Map<UUID, Float> downedHealthMap = new HashMap<>();
    /** 进入击倒的时间戳（毫秒），仅作记录与展示兜底。 */
    private final Map<UUID, Long> downedAtMs = new HashMap<>();
    /** 累计击倒 tick 数：同步包按它节流（每 5 tick 一次），进池即清零。 */
    private final Map<UUID, Integer> downedTicks = new HashMap<>();
    /** 头顶名牌当前展示的击倒血量百分比：数值不变就不重发，避免每 tick 广播名字包。 */
    private final Map<UUID, Integer> shownPercent = new HashMap<>();
    private final Map<UUID, Rescue> rescues = new HashMap<>();
    /** 被救者 UUID → 当前救援者 UUID，用于向双方客户端同步救援状态。 */
    private final Map<UUID, UUID> rescuerMap = new HashMap<>();
    private final Map<UUID, SelfRescue> selfRescues = new HashMap<>();
    private final Set<UUID> eliminated = new HashSet<>();

    public void reset(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isDowned(player.getUUID())) {
                clearNameplate(player);
                player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                player.removeEffect(MobEffects.WEAKNESS);
                if (!YSM_LOADED) player.setPose(Pose.STANDING);
            }
            DownedSync.broadcast(server, DownedSyncPayload.empty(player.getUUID()));
        }
        downedHealthMap.clear();
        downedAtMs.clear();
        downedTicks.clear();
        shownPercent.clear();
        rescues.clear();
        rescuerMap.clear();
        selfRescues.clear();
        eliminated.clear();
    }

    private DownedManager() {
    }

    public boolean isDowned(UUID id) {
        return downedHealthMap.containsKey(id);
    }

    /** [BR-MATCHTEAM] 是否已被本局淘汰（存活队伍数统计 / 胜负判定读取，成员静态编制不变）。 */
    public boolean isEliminated(UUID id) {
        return id != null && eliminated.contains(id);
    }

    /**
     * 清除一个玩家的全部服务端击倒状态，并广播清除包。
     * 同时清理反向救援映射，避免救援者继续持有旧目标。
     */
    public void clearPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        clearPlayerState(id);
        eliminated.remove(id);
        clearNameplate(player);
        DownedSync.broadcast(player.server, DownedSyncPayload.empty(id));
    }

    /** 按 UUID 清除指定玩家的核心击倒状态；供淘汰、救起、自救和复活流程调用。 */
    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        downedHealthMap.remove(playerId);
        downedAtMs.remove(playerId);
        rescuerMap.remove(playerId);
        downedTicks.remove(playerId);
        shownPercent.remove(playerId);
        rescues.remove(playerId);
        selfRescues.remove(playerId);
        // 若救援者本人断线/复活，也不能让旧目标继续显示“正在被救援”。
        rescuerMap.entrySet().removeIf(entry -> playerId.equals(entry.getValue()));
    }

    /** 仅清除 transient 击倒 Map，不负责名称牌和网络包；淘汰等完整流程复用。 */
    private void clearPlayerState(UUID id) {
        clearPlayer(id);
    }

    /** 玩家复活时由 AFTER_RESPAWN 事件调用：服务端状态、姿态、属性和客户端 HUD 全部归零。 */
    public void handleRespawn(ServerPlayer player) {
        UUID id = player.getUUID();
        clearPlayerState(id);
        eliminated.remove(id);
        clearNameplate(player);
        player.setHealth(DOWNED_HEALTH_MAX);
        player.hurtTime = 0;
        player.invulnerableTime = 0;
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.WEAKNESS);
        if (!YSM_LOADED) player.setPose(Pose.STANDING);
        DownedSync.broadcast(player.server, DownedSyncPayload.empty(id));
    }

    /** 断线时清服务端状态；保留淘汰标记给本局结算，复活事件会正式移除它。 */
    public void handleDisconnect(ServerPlayer player, MinecraftServer server) {
        UUID id = player.getUUID();
        clearPlayerState(id);
        clearNameplate(player);
        DownedSync.broadcast(server, DownedSyncPayload.empty(id));
    }

    /** 剩余击倒血量（0 ~ 20）；未处于击倒状态返回 -1。 */
    public float downedHealthOf(UUID id) {
        Float h = downedHealthMap.get(id);
        return h == null ? -1.0f : h;
    }

    /** ALLOW_DEATH 回调：true 允许原版死亡，false 拦截并进入击倒。 */
    public boolean handleDeath(ServerPlayer player, DamageSource source) {
        if (!GameManager.INSTANCE.isJoined(player.getGameProfile().getName()) || eliminated.contains(player.getUUID())) {
            return true;
        }
        if (isDowned(player.getUUID())) {
            eliminate(player, source);
            return true;
        }
        if (hasLivingTeammate(player) || hasSelfReviver(player)) {
            enterDowned(player, source);
            return false;
        }
        // C：只记录播报、统计和事件，放行原版 onDeath 完成掉落与死亡界面。
        eliminate(player, source, false, false);
        return true;
    }

    /**
     * ALLOW_DAMAGE 回调：true 放行原版伤害结算，false 拦截。
     * 击倒玩家受到的任何伤害（玩家、生物、摔落、爆炸等）都被拦截：
     * 不扣原版血量，直接扣 downedHealth；hurtTime 清零，不产生无敌帧。
     */
    public boolean handleDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer player)) {
            return true;
        }
        UUID id = player.getUUID();
        Float health = downedHealthMap.get(id);
        if (health == null) {
            return true;
        }
        player.hurtTime = 0;
        player.invulnerableTime = 0;
        if (amount > 0.0f) {
            float left = health - amount;
            if (left <= 0.0f) {
                eliminate(player, source);
            } else {
                downedHealthMap.put(id, left);
                updateNameplate(player, left);
                sync(player.server, player);
            }
        }
        return false;
    }

    public void tick(MinecraftServer server) {
        if (downedHealthMap.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Float health = downedHealthMap.get(id);
            if (health == null) continue;
            downedTicks.merge(id, 1, Integer::sum);

            // 原版血量锁死在 20：击倒期间的伤害全部由独立血量池结算，不参与原版死亡判定。
            if (player.getHealth() != DOWNED_HEALTH_MAX) player.setHealth(DOWNED_HEALTH_MAX);
            player.hurtTime = 0;
            player.invulnerableTime = 0;
            if (!YSM_LOADED) player.setPose(Pose.SWIMMING);
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 25, 2, true, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 25, 1, true, false, true));

            // 基础衰减与毒圈线性掉血不叠加，取两者中较大的一档。
            float drain = Math.max(DOWNED_DECAY_PER_TICK, zoneDecayPerTick(player));
            health -= drain;
            if (health <= 0.0f) {
                eliminate(player, player.damageSources().generic(), true);
                continue;
            }
            downedHealthMap.put(id, health);
            updateNameplate(player, health);
            updateRescue(server, player);
            updateSelfRescue(player);
            if (!downedHealthMap.containsKey(id)) continue;   // 本 tick 刚被救起 / 自救成功
            if (downedTicks.get(id) % 5 == 0) sync(server, player);
        }
    }

    /** 圈外击倒玩家每秒应扣的池血量（毒圈未激活 / 在圈内 / 无伤害时都是 0）。 */
    private float zoneDecayPerTick(ServerPlayer player) {
        // [BR-ZONE] 模块六：毒圈按维度独立——只查这名玩家所在维度的那一圈。
        // 倒地后被传送回主城 / 别的地图的玩家，runtimeAt 返回 null，天然不吃毒。
        ZoneManager.ZoneRuntime zone = ZoneManager.INSTANCE.runtimeAt(player.level().dimension());
        if (zone == null || !zone.phase().isActive()) return 0.0f;
        ZoneStage stage = zone.currentStage();
        if (stage == null) return 0.0f;
        float dps = stage.damagePerSecond();
        if (dps <= 0.0f) return 0.0f;
        if (!zone.isOutside(player.getX(), player.getZ())) return 0.0f;
        return dps / 20.0f;
    }

    private void enterDowned(ServerPlayer player, DamageSource source) {
        UUID id = player.getUUID();
        downedHealthMap.put(id, DOWNED_HEALTH_MAX);
        downedAtMs.put(id, System.currentTimeMillis());
        downedTicks.put(id, 0);
        rescues.remove(id);
        rescuerMap.remove(id);
        selfRescues.remove(id);
        player.setHealth(DOWNED_HEALTH_MAX);
        player.hurtTime = 0;
        player.invulnerableTime = 0;
        if (!YSM_LOADED) player.setPose(Pose.SWIMMING);
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, true, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, true, false, true));
        player.displayClientMessage(Component.literal("你被击倒了！等待队友救援或长按自救器，剩余 60 秒。"), true);
        updateNameplate(player, DOWNED_HEALTH_MAX);
        sync(player.server, player);
        broadcastOutcome(player, source, false);
        checkTeamCollapse(player.server, player);
    }

    private boolean hasLivingTeammate(ServerPlayer player) {
        // [BR-MATCHTEAM] 局内按 MatchTeam 座次表找队友；未开局自动回退大厅队。
        for (UUID memberId : MatchTeamManager.INSTANCE.memberIdsIncludingSelf(player.getUUID())) {
            if (memberId.equals(player.getUUID())) continue;
            ServerPlayer teammate = player.server.getPlayerList().getPlayer(memberId);
            if (teammate != null && teammate.isAlive() && !isDowned(memberId) && !eliminated.contains(memberId)) return true;
        }
        return false;
    }

    private boolean hasSelfReviver(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.SELF_REVIVER)) return true;
        }
        return false;
    }

    private void updateRescue(MinecraftServer server, ServerPlayer downedPlayer) {
        Rescue current = rescues.get(downedPlayer.getUUID());
        if (current == null) {
            for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
                if (!candidate.getUUID().equals(downedPlayer.getUUID()) && candidate.isCrouching()
                        && sameTeam(candidate, downedPlayer)
                        && candidate.distanceToSqr(downedPlayer) <= RESCUE_RADIUS_SQUARED) {
                    current = new Rescue(candidate.getUUID(), candidate.position(), downedPlayer.position());
                    rescues.put(downedPlayer.getUUID(), current);
                    rescuerMap.put(downedPlayer.getUUID(), candidate.getUUID());
                    sync(server, downedPlayer);
                    break;
                }
            }
        }
        ServerPlayer rescuer = current == null ? null : server.getPlayerList().getPlayer(current.rescuer);
        if (rescuer == null || !rescuer.isCrouching() || !sameTeam(rescuer, downedPlayer)
                || rescuer.distanceToSqr(downedPlayer) > RESCUE_RADIUS_SQUARED
                || rescuer.distanceToSqr(current.startPos) > MAX_RESCUER_MOVE_SQUARED
                || downedPlayer.distanceToSqr(current.targetPos) > RESCUE_RADIUS_SQUARED) {
            rescues.remove(downedPlayer.getUUID());
            rescuerMap.remove(downedPlayer.getUUID());
            sync(server, downedPlayer);
            return;
        }
        current.progress++;
        if (current.progress >= RESCUE_TICKS) {
            revive(downedPlayer, 10.0f);
            rescues.remove(downedPlayer.getUUID());
            rescuerMap.remove(downedPlayer.getUUID());
            rescuer.displayClientMessage(Component.literal("救援成功。"), true);
            downedPlayer.displayClientMessage(Component.literal("你已被队友救起。"), true);
        }
    }

    private void updateSelfRescue(ServerPlayer player) {
        SelfRescue current = selfRescues.get(player.getUUID());
        boolean holding = player.isUsingItem() && player.getUseItem().is(ModItems.SELF_REVIVER) && hasSelfReviver(player);
        if (!holding) {
            selfRescues.remove(player.getUUID());
            return;
        }
        if (current == null || player.distanceToSqr(current.startPos) > MAX_RESCUER_MOVE_SQUARED) {
            selfRescues.remove(player.getUUID());
            selfRescues.put(player.getUUID(), new SelfRescue(player.position()));
            return;
        }
        current.progress++;
        if (current.progress >= RESCUE_TICKS) {
            consumeOne(player);
            revive(player, 10.0f);
            player.displayClientMessage(Component.literal("你使用自救器恢复了战斗能力。"), true);
            selfRescues.remove(player.getUUID());
        }
    }

    private void consumeOne(ServerPlayer player) {
        if (!player.isCreative()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(ModItems.SELF_REVIVER)) {
                    stack.shrink(1);
                    return;
                }
            }
        }
    }

    private boolean sameTeam(ServerPlayer a, ServerPlayer b) {
        // [BR-MATCHTEAM 模块四] 局内队友判定（救援、击杀播报染色、队伍坍缩）统一走
        // MatchTeamManager：比赛进行中认本局 MatchTeam，回大厅 / 未开局回退大厅队。
        return MatchTeamManager.INSTANCE.isTeammate(a.getUUID(), b.getUUID());
    }

    private void revive(ServerPlayer player, float health) {
        clearPlayer(player);
        player.setHealth(health);
        player.invulnerableTime = 0;
    }

    private void eliminate(ServerPlayer player, DamageSource source) {
        eliminate(player, source, false, true);
    }

    private void eliminate(ServerPlayer player, DamageSource source, boolean bleedout) {
        eliminate(player, source, bleedout, true);
    }

    private void eliminate(ServerPlayer player, DamageSource source, boolean bleedout, boolean finalizeDeath) {
        UUID id = player.getUUID();
        clearPlayerState(id);
        eliminated.add(id);
        clearNameplate(player);
        if (!YSM_LOADED) player.setPose(Pose.STANDING);
        StatsManager.INSTANCE.onEliminated(player, source);
        broadcastOutcome(player, source, true, bleedout);
        EventBus.INSTANCE.publish(new PlayerEliminatedEvent(player, source, player.server));
        if (finalizeDeath && !player.isRemoved()) {
            player.setHealth(0.0f);
            player.die(source);
        }
        checkTeamCollapse(player.server, player);
        // 淘汰流程最后一步清理完整状态，并发送 isClear=true 包给所有客户端。
        clearPlayer(player);
    }

    /**
     * 赛中逃跑（/paimengmods br leave 判定阵亡）入口：走与被击杀完全相同的服务端淘汰管线——
     * 清击倒状态与减速/虚弱、标记淘汰、存活人数结算（StatsManager）、
     * 发布淘汰事件、队伍坍缩检查（checkTeamCollapse），最后广播清除包刷新各客户端 HUD。
     *
     * <p>与被击杀仅有两点差异：
     * <ol>
     *   <li>不走原版死亡结算（不弹死亡界面、不掉装备）——逃跑者随后就被传送回大厅，
     *       走 finalizeDeath 会在传送瞬间触发死亡界面，交互不对；</li>
     *   <li>不发「击杀/圈外淘汰」播报——聊天文案由指令层按「逃跑」口径广播。</li>
     * </ol>
     */
    public void eliminateForDesert(ServerPlayer player) {
        UUID id = player.getUUID();
        if (eliminated.contains(id)) {
            return; // 已经淘汰过，防止重复扣人数
        }
        clearPlayerState(id);
        eliminated.add(id);
        clearNameplate(player);
        if (!YSM_LOADED) player.setPose(Pose.STANDING);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.WEAKNESS);
        StatsManager.INSTANCE.onEliminated(player, player.damageSources().generic());
        EventBus.INSTANCE.publish(new PlayerEliminatedEvent(player, player.damageSources().generic(), player.server));
        checkTeamCollapse(player.server, player);
        // 与被击杀的最后一步一致：清完整状态并向所有客户端广播清除包（淘汰标记不留存，同原版流程）。
        clearPlayer(player);
    }

    private void checkTeamCollapse(MinecraftServer server, ServerPlayer changed) {
        // [BR-MATCHTEAM] 队伍坍缩（全队列覆则判该队淘汰）按本局 MatchTeam 编制统计。
        com.paimeng.battleroyale.team.MatchTeam matchTeam =
                MatchTeamManager.INSTANCE.teamOf(changed.getUUID());
        java.util.List<UUID> memberIds = matchTeam != null
                ? matchTeam.memberIds() : lobbyMemberIds(server, changed);
        if (memberIds.isEmpty()) return;   // 大厅也没队：单人自生自灭
        boolean living = false;
        boolean hasReviver = false;
        for (UUID memberId : memberIds) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null && !eliminated.contains(memberId) && !isDowned(memberId) && member.isAlive()) living = true;
            if (member != null && isDowned(memberId) && hasSelfReviver(member)) hasReviver = true;
        }
        if (!living && !hasReviver) {
            for (UUID memberId : memberIds) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member != null && isDowned(memberId)) eliminate(member, member.damageSources().generic(), true);
            }
        }
    }

    /** 大厅队成员 ID 列表（比赛未开时的坍缩判定兜底）。 */
    private java.util.List<UUID> lobbyMemberIds(MinecraftServer server, ServerPlayer changed) {
        Team lobby = TeamManager.INSTANCE.teamOf(changed.getUUID());
        if (lobby == null) return java.util.List.of();
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (Team.Entry entry : lobby.membersSortedByNumber()) ids.add(entry.playerId());
        return ids;
    }

    /**
     * 击倒玩家头顶名牌：显示剩余击倒血量百分比（downedHealth / 20）。
     * 走原版自定义名同步，周围玩家都能看见；百分比不变就不重发包。
     */
    private void updateNameplate(ServerPlayer player, float health) {
        int percent = Math.max(0, Math.min(100, (int) Math.ceil(health / DOWNED_HEALTH_MAX * 100.0)));
        Integer last = shownPercent.put(player.getUUID(), percent);
        if (last != null && last == percent) return;
        player.setCustomName(Component.literal("击倒 " + percent + "%").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        player.setCustomNameVisible(true);
    }

    private void clearNameplate(ServerPlayer player) {
        shownPercent.remove(player.getUUID());
        if (player.hasCustomName()) player.setCustomName(null);
        player.setCustomNameVisible(false);
    }

    /**
     * 击杀者主手武器的显示名；空手（AIR 或空槽）时返回「拳头」。
     * 不再使用 DamageSource.getName()——它返回的是伤害类型内部名（如 player），不是武器。
     */
    private static String weaponName(ServerPlayer attacker) {
        ItemStack stack = attacker.getMainHandItem();
        if (stack.isEmpty() || stack.is(Items.AIR)) {
            return "拳头";
        }
        String name = stack.getHoverName().getString();
        return name == null || name.isBlank() ? "拳头" : name;
    }

    /** eliminated=true 播报「淘汰了」，false 播报「击倒了」；毒圈判定与颜色、距离规则两者一致。 */
    private void broadcastOutcome(ServerPlayer victim, DamageSource source, boolean eliminated) {
        broadcastOutcome(victim, source, eliminated, false);
    }

    private void broadcastOutcome(ServerPlayer victim, DamageSource source, boolean eliminated, boolean bleedout) {
        ServerPlayer killer = source.getEntity() instanceof ServerPlayer p ? p : null;
        boolean zone = source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC) || killer == null;
        String verb = eliminated ? "淘汰了" : "击倒了";
        for (ServerPlayer receiver : victim.server.getPlayerList().getPlayers()) {
            boolean victimAlly = sameTeam(receiver, victim);
            boolean killerAlly = killer != null && sameTeam(receiver, killer);
            int color = victimAlly ? 0xFF5555 : killerAlly ? 0x55FF55 : 0xFFFF55;
            Component message;
            if (bleedout) {
                message = Component.literal(victim.getGameProfile().getName()
                        + " 因为在倒地后没有人救援而被淘汰");
            } else if (zone) {
                message = Component.literal(victim.getGameProfile().getName()
                        + " 因为在安全区外停留时间太长而被" + (eliminated ? "淘汰" : "击倒"));
            } else {
                String weapon = weaponName(killer);
                String suffix = receiver.distanceToSqr(victim) > 2500.0
                        ? "（" + Math.round(Math.sqrt(receiver.distanceToSqr(victim))) + " 米外）" : "";
                message = Component.literal(killer.getGameProfile().getName() + " 使用 " + weapon + " " + verb
                        + " " + victim.getGameProfile().getName() + suffix);
            }
            receiver.displayClientMessage(message.copy().setStyle(message.getStyle().withColor(color)), false);
            // 音效只在「淘汰」且与接收者所在队伍相关时播放（击倒播报不播音效）。
            if (eliminated && (bleedout ? victimAlly : (victimAlly || killerAlly))) {
                receiver.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
            }
        }
    }

    /** 同步包：包含击倒血量、救援者 UUID、进度比例和剩余秒数。 */
    private void sync(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        Float h = downedHealthMap.get(id);
        int leftTicks = h == null ? 0 : (int) Math.ceil(h / DOWNED_HEALTH_MAX * DOWNED_TICKS);
        Rescue rescue = rescues.get(id);
        SelfRescue self = selfRescues.get(id);
        int progress = rescue == null ? (self == null ? 0 : self.progress) : rescue.progress;
        UUID rescuerUuid = rescuerMap.get(id);
        float progressRatio = rescuerUuid == null ? 0.0f : Math.min(1.0f, progress / (float) RESCUE_TICKS);
        float remainingSeconds = rescuerUuid == null ? 0.0f : Math.max(0.0f, (RESCUE_TICKS - progress) / 20.0f);
        DownedSync.broadcast(server, new DownedSyncPayload(id, h != null, leftTicks, progress, RESCUE_TICKS,
                h == null ? 0.0f : h, rescuerUuid, progressRatio, remainingSeconds, false));
    }

    private static final class Rescue {
        private final UUID rescuer;
        private final Vec3 startPos;
        private final Vec3 targetPos;
        private int progress;
        private Rescue(UUID rescuer, Vec3 startPos, Vec3 targetPos) { this.rescuer = rescuer; this.startPos = startPos; this.targetPos = targetPos; }
    }

    private static final class SelfRescue {
        private final Vec3 startPos;
        private int progress;
        private SelfRescue(Vec3 startPos) { this.startPos = startPos; }
    }
}
