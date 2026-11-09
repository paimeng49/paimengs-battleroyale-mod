package com.paimeng.battleroyale.team;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.PingSync;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>标点系统的服务端核心</b>：收 {@code battleroyale:ping} 包 → 校验 → 通过
 * {@link TeamMessageSender} 给队友发固定文案的聊天消息（绿色高亮 + 经验拾取音效）
 * + {@link PingSync} 给队友发世界标记。注册点在 {@code BattleRoyaleMod.onInitialize}。
 *
 * <p><b>消息文案不可自定义</b>：包里根本没有文本字段，内容全部由 {@link PingType}
 * 写死——WAYPOINT 发"玩家名：我标记了一处地点"，ENEMY 发"玩家名：发现敌人"，
 * 正文绿色高亮，前缀沿用 {@link TeamMessageSender#buildTeamMessage} 的队伍/编号格式。
 *
 * <p>为什么要服务端过一道：坐标是客户端算的（准星命中点），恶意客户端可以随便填。
 * 所以这里做三件事：
 * <ol>
 *   <li><b>冷却</b>：同一个玩家两次标点至少隔 {@link #COOLDOWN_MS}（1 秒），防刷屏；</li>
 *   <li><b>距离</b>：标点必须离发起者水平 {@link #MAX_DISTANCE} 格以内——客户端准星
 *       射线最大就是 128 格（修复三），服务端上限取 128 加一点浮点余量，改包扔不出更远；</li>
 *   <li><b>脏数据</b>：坐标 NaN / 类型 id 未知，直接丢弃（类型在
 *       {@link PingSync#decodePingRequest} 就拦掉了）。</li>
 * </ol>
 *
 * <p>没队伍的玩家也能标点（需求没禁止），只是效果降级：消息和标记只发给他自己，
 * 并告知一句"没有队伍，标点仅自己可见"。
 *
 * <p><b>生命周期不在这里管</b>：ENEMY 10 秒、自己的 WAYPOINT 永久、队友的 WAYPOINT
 * 世界渲染 10 秒（小地图永久）——这些都是纯客户端显示规则，包里只带类型和发起者 UUID，
 * 客户端 {@code ClientPingState} 自己算，见修复四。
 *
 * <p>线程约定：Fabric 的 {@code ServerPlayNetworking} 回调跑在<b>服务端主线程</b>，
 * 所以 {@link TeamManager}、sendMessage、playNotifySound 都能直接调，不用转线程。
 */
public final class PingService {

    /** 同一玩家两次标点的最小间隔（毫秒）。 */
    private static final long COOLDOWN_MS = 1000L;

    /**
     * 标点离发起者的最大水平距离（格）。准星射线最大 128 格（修复三），
     * 服务端上限取 128 + 1 格浮点余量——正常客户端永远够得着，
     * 这个上限只是防"改包把标记扔到别人家"。
     */
    private static final double MAX_DISTANCE = 129.0;

    /** 玩家UUID → 上次成功标点的毫秒时间戳（只对服务端主线程可见，不加锁）。 */
    private final Map<UUID, Long> lastPingAt = new HashMap<>();

    public static final PingService INSTANCE = new PingService();

    private PingService() {
    }

    /** 服务端入口注册（{@code BattleRoyaleMod.onInitialize} 调一次）。 */
    public void registerServer() {
        // Fabric 1.18.2 回调签名：
        // (MinecraftServer, ServerPlayer, ServerPlayNetworkHandler, FriendlyByteBuf, PacketSender)
        ServerPlayNetworking.registerGlobalReceiver(
                PingSync.PING_CHANNEL_ID, (server, player, handler, buf, responseSender) -> {
                    // 服务端第一道硬闸门：大厅及其他维度的标点包一律丢弃，绝不解析或广播。
                    if (!GameWorldManager.isGameWorld(player.level().dimension())) {
                        BattleRoyaleMod.LOGGER.warn("拒绝非比赛维度的标点包: player={}, dimension={}",
                                player.getGameProfile().getName(), player.level().dimension().location());
                        return;
                    }
                    PingSync.PingRequest request;
                    try {
                        request = PingSync.decodePingRequest(buf);
                    } catch (RuntimeException e) {
                        return;                       // 脏包：丢弃，不踢人
                    }
                    handle(server, player, request);
                });
    }

    /** 校验 + 广播（服务端主线程）。 */
    private void handle(MinecraftServer server,
                        ServerPlayer player, PingSync.PingRequest request) {
        // 1) 坐标检查（类型已在解码时验过，非法包进不来这里）
        if (!Double.isFinite(request.x()) || !Double.isFinite(request.y()) || !Double.isFinite(request.z())) {
            return;
        }

        UUID playerId = player.getUUID();

        // 2) 冷却检查
        long now = System.currentTimeMillis();
        Long last = lastPingAt.get(playerId);
        if (last != null && now - last < COOLDOWN_MS) {
            player.displayClientMessage(BrText.mutable("标点太快了，歇一秒再点。").withStyle(ChatFormatting.GRAY), false);
            return;
        }

        // 3) 距离检查（水平面，和毒圈判定同一套路；Y 不算——隔着山朝天上标记要能过）
        double dx = request.x() - player.getX();
        double dz = request.z() - player.getZ();
        if (Math.hypot(dx, dz) > MAX_DISTANCE) {
            player.displayClientMessage(BrText.mutable("标点超出 " + (int) MAX_DISTANCE + " 格范围，没发出去。")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }

        lastPingAt.put(playerId, now);

        // 4) 发聊天 + 发标记：消息文案写死在 PingType 里，正文绿色高亮
        // [BR-MATCHTEAM 模块四] 标点按本局 MatchTeam 座次表广播；大厅 / 未开局自动回退大厅队。
        boolean hasTeam = MatchTeamManager.INSTANCE.hasTeamView(playerId);
        java.util.List<UUID> memberIds = MatchTeamManager.INSTANCE.memberIdsIncludingSelf(playerId);
        String initiatorName = player.getGameProfile().getName();
        int memberNumber = MatchTeamManager.INSTANCE.squadIndexOf(playerId);
        MutableComponent content = BrText.mutable(request.type().fixedText()).withStyle(ChatFormatting.GREEN);
        MutableComponent message = TeamMessageSender.buildTeamMessage(player, content);

        if (hasTeam) {
            // 聊天（绿色高亮 + 经验拾取提示音）+ 世界标记，队友全员（含发起者本人）
            TeamMessageSender.sendToTeam(player, message, true);
            PingSync.sendShowToMembers(memberIds, server, request.x(), request.y(), request.z(),
                    request.type(), Math.max(0, memberNumber), playerId, initiatorName);
        } else {
            // 降级：没有队伍，消息（含经验球提示音）和标记都只发发起者本人
            TeamMessageSender.sendToSelf(player, message);
            PingSync.sendShowTo(player, request.x(), request.y(), request.z(),
                    request.type(), 0, playerId, initiatorName);
            player.displayClientMessage(BrText.mutable("（你还没有队伍，这条标点只有你自己看得见。"
                    + "建队：/paimengmods br team create）").withStyle(ChatFormatting.GRAY), false);
        }

        BattleRoyaleMod.LOGGER.info("标点: {} 在 ({}, {}, {}) 发了一个 {} 标记",
                initiatorName, (int) request.x(), (int) request.y(), (int) request.z(),
                request.type().name());
    }

    /** 玩家断线时清掉他的冷却记录（复用 TeamManager 的断线钩子调用）。 */
    public void handleDisconnect(UUID playerId) {
        lastPingAt.remove(playerId);
    }
}
