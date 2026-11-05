package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.game.GameRules;
import com.paimeng.battleroyale.zone.ZoneManager;
import com.paimeng.battleroyale.zone.ZonePhase;
import com.paimeng.battleroyale.zone.ZoneSnapshot;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * 毒圈状态的服务端 → 客户端同步（S2C 自定义网络包，r198 协议 v3）。
 *
 * <p><b>模块三</b>：对局进行中，每个 tick 给每个装了模组的客户端同步一次
 * 当前蓝圈（收缩起点圆）、下个白圈（目标圆）、状态、剩余倒计时（秒）与缩圈总进度
 * （0.0~1.0）——客户端 HUD / 能量墙拿这份副本自己 lerp 实时半径。
 *
 * <p><b>模块六（维度隔离）</b>：包体带 mapId 与维度字符串；
 * 每个玩家只收「自己所在维度」的那圈——所在维度没有毒圈（主城 / 下界 / 别的地图）
 * 就直接收 IDLE 哨兵包，客户端 {@code ClientZoneState} 当场清空。
 * 被淘汰传送回大厅、{@code /br leave} 之后的下一个 tick 自然被这条规则清掉，
 * 主城不会残留半透明蓝圈幻影。毒圈关闭（stop）的瞬间也全员补发一次 IDLE。
 *
 * <p>只发装了本模组的客户端（{@link ServerPlayNetworking#canSend}），
 * 原版客户端 / 没装模组的玩家会被跳过，不会因为他收不到包而踢线。
 *
 * <p><b>模块四升级（r198 v3）</b>：包尾追加逐玩家的圈外每秒伤害 DPS
 * （服务端按"该玩家最大血量 / 本阶段 TTK"算好下发）。客户端
 * {@code ZoneTtkCountdown} 用它做纯时间外推的淘汰倒计时，
 * 不再依赖"血量真的掉了 1 点"才刷新数字。
 *
 * <p><b>升级预留</b>：本文件只有三处贴版本 API，都标了 [UPGRADE]。
 * 1.19 起 {@code PacketByteBufs} 从 Fabric 挪进原版；
 * 1.20.5+ 起自定义包要改成 {@code CustomPayload} 记录 + {@code ServerPlayNetworking.send(player, payload)}，
 * 字段与发送节奏完全不用动。
 */
public final class ZoneSync {

    /** 通道名：battleroyale:zone_sync（协议 v3：白圈 + 倒计时 + 进度 + 维度 + TTK + 圈外 DPS）。 */
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(BattleRoyaleMod.MOD_ID, "zone_sync");

    private static final ZonePhase[] PHASES = ZonePhase.values();

    /** 没有任何毒圈在跑时的哨兵广播间隔：每秒一次，兜住「错过 stop 那次补发」的极端情况。 */
    private static final int IDLE_KEEPALIVE_INTERVAL_TICKS = GameRules.TICKS_PER_SECOND;

    private static int idleKeepaliveTicks = 0;

    private ZoneSync() {
    }

    // ------------------------------------------------------------------
    // 服务端调用
    // ------------------------------------------------------------------

    /** 立刻把各维度当前毒圈状态按玩家所在维度路由广播（阶段切换、开局、结束时用）。 */
    public static void syncNow(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    /**
     * 挂在 {@code ServerTickEvents.END_SERVER_TICK} 上，每个 tick 被调用一次。
     * 对局进行中（有任何运行时）按需求每 tick 全员同步；没开局时每秒一次 IDLE 兜底。
     */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (ZoneManager.INSTANCE.anyActive()) {
            idleKeepaliveTicks = 0;
            syncNow(server);       // 收缩中每 tick 都发，墙 / 进度条才顺滑
            return;
        }
        if (idleKeepaliveTicks-- <= 0) {
            syncNow(server);
            idleKeepaliveTicks = IDLE_KEEPALIVE_INTERVAL_TICKS;
        }
    }

    /** 单玩家同步：按所在维度取运行时快照；没有就发 IDLE（客户端据此清空）。 */
    private static void sendTo(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, CHANNEL_ID)) {
            return;
        }
        ZoneManager.ZoneRuntime zone = ZoneManager.INSTANCE.runtimeAt(player.level().dimension());
        ZoneSnapshot snapshot = zone == null ? ZoneSnapshot.idle() : zone.snapshot();
        // [r198 协议 v3] 逐玩家注入圈外 DPS：与服务端 applyZoneDamage 的每秒步进同口径
        // （实际最大血量 / 本阶段 TTK）；phaseZero（还没开始掉血）时快照里 ttk=0，DPS 同步为 0。
        double dps = 0.0;
        if (zone != null && snapshot.zoneTtkSeconds() > 0.0) {
            dps = player.getMaxHealth() / snapshot.zoneTtkSeconds();
        }
        ServerPlayNetworking.send(player, CHANNEL_ID, encode(snapshot.withDamagePerSecond(dps)));
    }

    /** 进服补发：玩家刚连接时调一次，立刻拿到所在维度的毒圈状态（或 IDLE）。 */
    public static void sendOnJoin(ServerPlayer player) {
        sendTo(player);
    }

    // ------------------------------------------------------------------
    // 编解码：字段顺序两边必须一模一样
    // ------------------------------------------------------------------

    /**
     * 快照 → 网络包。
     * [UPGRADE] 1.19+ 把 PacketByteBufs 换成 net.minecraft.network.PacketByteBufs。
     */
    private static FriendlyByteBuf encode(ZoneSnapshot snapshot) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(snapshot.phase().ordinal());
        buf.writeVarInt(snapshot.stageIndex());
        buf.writeUtf(snapshot.mapId(), 64);
        buf.writeUtf(snapshot.dimensionId(), 192);
        buf.writeDouble(snapshot.blueCenterX());
        buf.writeDouble(snapshot.blueCenterZ());
        buf.writeDouble(snapshot.blueRadius());
        buf.writeDouble(snapshot.whiteCenterX());
        buf.writeDouble(snapshot.whiteCenterZ());
        buf.writeDouble(snapshot.whiteRadius());
        buf.writeVarInt(Math.max(0, Math.min(65535, snapshot.remainingSeconds())));
        buf.writeDouble(snapshot.progress());
        // [BR-ZONE-HUD 模块四] 圈外淘汰 TTK（秒）：客户端算"距被淘汰剩余秒数"用；
        // 阶段零（还没开始掉血）服务端上报 0。
        buf.writeDouble(snapshot.zoneTtkSeconds());
        // [r198 协议 v3] 圈外每秒伤害 DPS（服务端按该玩家血量逐人算好）：
        // 客户端倒计时 ceil(血量 / DPS) 每 tick 主动刷新，不再等血量真的掉。
        buf.writeDouble(snapshot.damagePerSecond());
        return buf;
    }

    /** 网络包 → 快照（客户端调用）。遇到越界/坏数据一律退回 IDLE，不抛异常。 */
    public static ZoneSnapshot decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        ZonePhase phase = (ordinal >= 0 && ordinal < PHASES.length) ? PHASES[ordinal] : ZonePhase.IDLE;
        int stageIndex = buf.readVarInt();
        String mapId = buf.readUtf(64);
        String dimensionId = buf.readUtf(192);
        double blueX = buf.readDouble();
        double blueZ = buf.readDouble();
        double blueRadius = buf.readDouble();
        double whiteX = buf.readDouble();
        double whiteZ = buf.readDouble();
        double whiteRadius = buf.readDouble();
        int remainingSeconds = buf.readVarInt();
        double progress = buf.readDouble();
        double zoneTtkSeconds = buf.readDouble();
        double damagePerSecond = buf.readableBytes() >= 8 ? buf.readDouble() : 0.0;
        return new ZoneSnapshot(phase, stageIndex, mapId, dimensionId,
                blueX, blueZ, blueRadius, whiteX, whiteZ, whiteRadius,
                remainingSeconds, progress, zoneTtkSeconds, damagePerSecond);
    }
}
