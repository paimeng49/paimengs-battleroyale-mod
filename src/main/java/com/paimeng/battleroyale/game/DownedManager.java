package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.event.EventBus;
import com.paimeng.battleroyale.event.PlayerDownedEvent;
import com.paimeng.battleroyale.event.PlayerEliminatedEvent;
import com.paimeng.battleroyale.item.ModItems;
import com.paimeng.battleroyale.item.SpeedBoostManager;
import com.paimeng.battleroyale.network.DownedSync;
import com.paimeng.battleroyale.network.DownedSyncPayload;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.team.Team;
import com.paimeng.battleroyale.team.TeamManager;
import com.paimeng.battleroyale.zone.ZoneManager;
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

    /**
     * [r-LEAVE] 最后一次"玩家造成的伤害"记账：受害者 UUID →（攻击者 UUID、毫秒时间戳）。
     * 防"战斗拔线 / 交战中途 br leave"：br leave 判阵亡与断线判阵亡都优先把击杀
     * 结算给窗口期内的最后伤害来源；淘汰结算消费后即时清除。
     */
    private final Map<UUID, CombatHit> lastCombatHits = new HashMap<>();
    /** [r-LEAVE] 交战归因窗口（毫秒）：最后一次玩家伤害超过这个时长就不再算"刚被打"。 */
    private static final long COMBAT_ATTRIBUTION_WINDOW_MS = 10_000L;

    /** 最近一次玩家伤害记录（victim 视角）。 */
    private record CombatHit(UUID attackerId, long atMs) {
    }

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
        lastCombatHits.clear();
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
        // [BR-TEAMHUD] 状态切换当场重发本队快照：队友 HUD 立即把该成员恢复成正常显示
        // （救起、淘汰收尾、复活指令等所有走这里的变更都会联动）。
        MatchTeamManager.INSTANCE.resyncStatusFor(id, player.server);
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
        // [BR-TEAMHUD][BR-RECONNECT] 掉线不等于淘汰：倒地标记已清，
        // 全队 HUD 把他恢复成正常显示（重连后按名册判定继续打还是观战）。
        MatchTeamManager.INSTANCE.resyncStatusFor(id, server);
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
        // C：只记录播报、统计和事件，放行原版死亡界面与经验掉落；
        // 装备掉落已被 mixin.PlayerDeathChestMixin 在 Player#die HEAD 改成死亡箱（骨灰盒）。
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
        // [r-LEAVE] 先记最后玩家伤害（能通过 mixin 的队友免伤 / 热身无敌 / 航线无敌
        // 拦截到这里、且真的造成伤害的都算）：供 br leave / 断线判阵亡做击杀归因兜底。
        if (amount > 0.0f) {
            ServerPlayer attacker = KillFeed.resolveKiller(player, source);
            if (attacker != null) {
                lastCombatHits.put(id, new CombatHit(attacker.getUUID(), System.currentTimeMillis()));
            }
        }
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
        // [r148 TTK 模型] 倒地玩家走独立血量池（每 tick 浮点扣），口径和站立的完全一致：
        // 每秒伤害 = 玩家实际上限血量 / 当前阶段圈外淘汰秒数，再除以 20 就是每 tick 应扣的量
        // （独立血量池不存在无敌帧吞伤害的问题）。
        double ttk = zone.zoneTtkSeconds();
        if (!(ttk > 0.0)) return 0.0f;
        if (!zone.isOutside(player.getX(), player.getZ())) return 0.0f;
        return (float) (player.getMaxHealth() / ttk / 20.0);
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
        // [BR-KILLFEED] 击倒播报改走事件总线：KillFeed 按观看者视角千人千面分发。
        EventBus.INSTANCE.publish(new PlayerDownedEvent(player, source,
                KillFeed.resolveKiller(player, source), player.server));
        checkTeamCollapse(player.server, player);
        // [BR-TEAMHUD] 倒地瞬间联动队伍 HUD：全队立刻把该成员画成深红"倒地"。
        MatchTeamManager.INSTANCE.resyncStatusFor(id, player.server);
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
        // [r-LEAVE] 正式淘汰即消费掉最后伤害记录，避免结算窗口跨事件残留。
        lastCombatHits.remove(id);
        clearNameplate(player);
        if (!YSM_LOADED) player.setPose(Pose.STANDING);
        StatsManager.INSTANCE.onEliminated(player, source);
        // [BR-DEATHCHEST][r-fix3] 关键顺序：必须在切成旁观模式<b>之前</b>结算死亡箱。
        // DeathChestManager.eligible 会排除旁观 / 创造玩家（原版本来不掉装备），
        // 而旧逻辑先 onEliminated 切旁观、后 die()，导致 die 里的 Mixin 判定为旁观
        // 直接拒绝收装备——骨灰盒永不生成、物品原地消失。这里趁玩家仍是生存显式收走。
        boolean chestPlaced = DeathChestManager.onDeath(player);
        BattleRoyaleMod.LOGGER.info("[BR-DEATHCHEST] {} 淘汰：切旁观前显式结算死亡箱 -> {}",
                player.getGameProfile().getName(), chestPlaced ? "已生成" : "未生成（空背包 / 局外 / 不可用地面）");
        // [BR-GAMEMODE] 淘汰 → 旁观模式：先切换再判死，死亡界面直接落在观战视角。
        GameModeManager.INSTANCE.onEliminated(player);
        if (finalizeDeath && !player.isRemoved()) {
            player.setHealth(0.0f);
            player.die(source);
        }
        checkTeamCollapse(player.server, player);
        // [BR-KILLFEED][BR-SETTLE] 播报 / 全队覆灭 / 前三 / 吃鸡全部走事件总线。
        // 发布放在级联淘汰之后：此刻本队淘汰名册已是最终状态，订阅方的
        // 覆灭与存活队伍数判定不会读到半更新数据（重复播报由订阅方自行去重）。
        EventBus.INSTANCE.publish(new PlayerEliminatedEvent(player, source,
                KillFeed.resolveKiller(player, source), bleedout, false, false, player.server));
        // 淘汰流程最后一步清理完整状态，并发送 isClear=true 包给所有客户端。
        clearPlayer(player);
    }

    /**
     * [r-LEAVE] 赛中判阵亡统一入口（{@code br leave} 主动退出、断线重连补判共用）：
     * 走与被击杀<b>完全相同</b>的服务端淘汰管线——
     * 原地生成死亡掉落箱（骨灰盒）→ 清击倒状态 → 标记淘汰 → 清全部 Buff
     * （医疗回血/移速、倒地减速虚弱、缓降）→ 收降落伞并归还飞行豁免
     * → 存活人数与击杀数结算（含「战斗拔线」归因，见下）→ 队伍坍缩级联
     * → 发布淘汰事件（本局名册、队伍 HUD、全队覆灭播报、前三/吃鸡排名结算
     * 全部由订阅方按「阵亡」口径照常完成）→ 广播清除包。
     * 调用方<b>之后</b>才允许传送回大厅并恢复原游戏模式。
     *
     * <p>与被击杀仅两处刻意差异：
     * <ol>
     *   <li>不切旁观、不调 {@code player.die}——人马上被传送回大厅恢复原模式，
     *       死亡界面与旁观视角都不该出现；Buff 清理因此改为在本方法内显式完成；</li>
     *   <li>播报口径：主动退出（voluntaryQuit=true）照常走击倒/淘汰播报，
     *       有最近玩家伤害来源就按正常击杀播（Kill 记给他），没有则记
     *       「环境/主动退出」；断线补判（voluntaryQuit=false、desert=true）跳过
     *       击杀文案（指令层已按「逃跑」口径广播），覆灭与排名结算不受影响。</li>
     * </ol>
     *
     * <p>防「战斗拔线」：{@value #COMBAT_ATTRIBUTION_WINDOW_MS} 毫秒内吃过玩家伤害的，
     * 死因按最后伤害来源结算——攻击者离线 / 已死 / 超时则退回环境口径。
     *
     * @param voluntaryQuit true = 玩家在局内主动 br leave；false = 断线重连补判阵亡
     */
    public void eliminateForDesert(ServerPlayer player, boolean voluntaryQuit) {
        UUID id = player.getUUID();
        if (eliminated.contains(id) || MatchTeamManager.INSTANCE.wasEliminatedThisMatch(id)) {
            return; // 已经正式结算过：绝不二次扣存活人数、二次发骨灰盒
        }
        // [r-LEAVE] 击杀归因：窗口期内最后玩家伤害来源优先，否则按环境/主动退出。
        ServerPlayer attacker = recentAttackerOf(player);
        DamageSource source = attacker != null
                ? player.damageSources().mobAttack(attacker)
                : player.damageSources().generic();
        lastCombatHits.remove(id);
        // 1) 骨灰盒在阵亡点原位生成——必须趁玩家仍是生存模式、背包未动之前结算
        //    （与正常淘汰的 [BR-DEATHCHEST][r-fix3] 顺序要求同一口径）。
        boolean chestPlaced = DeathChestManager.onDeath(player);
        BattleRoyaleMod.LOGGER.info("[BR-LEAVE] {} 判阵亡：死亡箱 -> {}（击杀归因：{}）",
                player.getGameProfile().getName(), chestPlaced ? "已生成" : "未生成",
                attacker == null ? "环境/主动退出" : attacker.getGameProfile().getName());
        // 2) 击倒状态清理 + 进淘汰标记。
        clearPlayerState(id);
        eliminated.add(id);
        clearNameplate(player);
        if (!YSM_LOADED) player.setPose(Pose.STANDING);
        // 3) Buff 全清：医疗回血（再生）、能量饮料/止痛药移速（属性修饰符）、
        //    倒地减速虚弱、降落伞缓降——不走 die/respawn，就必须在这里显式摘干净。
        player.removeAllEffects();
        SpeedBoostManager.remove(player);
        // 4) 跳伞途中退出：向同维度客户端收伞 + 归还零重力/无敌/飞行豁免（幂等）。
        FlightManager.INSTANCE.handleDesert(player);
        // 5) 存活人数 -1、击杀数结算（source.getEntity() = attacker 时 Kills 记给最后伤害者）。
        StatsManager.INSTANCE.onEliminated(player, source);
        // 6) 全队覆灭级联：队友全倒地且无人能救时统一补淘汰（与被击杀同一实现）。
        checkTeamCollapse(player.server, player);
        // 7) 淘汰事件：主动退出走正常播报管线（desert=false、voluntaryQuit=true），
        //    名册 / HUD / 覆灭播报 / 排名结算全部由订阅方照常完成。
        EventBus.INSTANCE.publish(new PlayerEliminatedEvent(player, source, attacker,
                false, !voluntaryQuit, voluntaryQuit, player.server));
        // 8) 与被击杀的最后一步一致：清完整状态并向所有客户端广播清除包。
        clearPlayer(player);
    }

    /**
     * [r-LEAVE] 取受害者窗口期（{@value #COMBAT_ATTRIBUTION_WINDOW_MS} 毫秒）内的最后
     * 玩家伤害来源；超时、攻击者已离线或已死亡都算没有（返回 null，走"环境/主动退出"口径）。
     */
    public ServerPlayer recentAttackerOf(ServerPlayer victim) {
        CombatHit hit = lastCombatHits.get(victim.getUUID());
        if (hit == null || System.currentTimeMillis() - hit.atMs() > COMBAT_ATTRIBUTION_WINDOW_MS) {
            return null;
        }
        if (victim.server == null) {
            return null;
        }
        ServerPlayer attacker = victim.server.getPlayerList().getPlayer(hit.attackerId());
        return attacker == null || attacker == victim || !attacker.isAlive() ? null : attacker;
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
        for (Team.Entry entry : lobby.membersInJoinOrder()) ids.add(entry.playerId());
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
