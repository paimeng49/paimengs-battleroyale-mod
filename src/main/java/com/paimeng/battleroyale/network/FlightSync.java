package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.game.FlightManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;

/**
 * [BR-FLIGHT] 航线与跳伞的网络同步（三条轻量通道）。
 *
 * <p>[BR-MTR2] 载具数据化（MTR 架构）：C-130 不再是 Entity，原版实体追踪包通道
 * 整个不存在。飞机的权威运动状态 = {@code flight_route}（航线几何，开局一次）
 * + {@code flight_tick}（每 tick 进度，对表/软校正的唯一数据源），客户端把两者
 * 合成位置的时间纯函数自行渲染（见 {@code ClientFlightState} 与
 * {@code C130WorldRenderer}），逐帧连续、与包到达相位无关。
 *
 * <ul>
 *   <li>S2C {@code battleroyale:flight_route}：active 标志 + 地图 ID + 航线端点 A/B
 *       + startTick/speed/flightY。航线开始、结束各发一次；startTick 仅诊断参考，
 *       客户端渲染用的时钟原点由第一发 flight_tick 包在本地时间轴锚定。</li>
 *   <li>S2C {@code battleroyale:flight_tick}：(long serverTime, int progressTicks)，
 *       FLIGHT 阶段每 tick 广播给维度内所有玩家；progressTicks=-1 = 收机哨兵。</li>
 *   <li>C2S {@code battleroyale:parachute}：空包，客户端按左 Shift / F 跳伞时发一次；
 *       真正的跳伞判定（阶段、是否在航线上、是否已跳过）全部在
 *       {@link FlightManager#handleParachute} 服务端做。</li>
 * </ul>
 */
public final class FlightSync {
    /** S2C 航线同步通道名（航线开始 / 结束 / 预热预告时各发一次）。 */
    public static final ResourceLocation ROUTE_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "flight_route");
    /**
     * [BR-MTR2] S2C 权威进度通道名：FLIGHT 阶段每 tick 一发（约 12 字节）。
     * 载荷只有 (long serverTime, int progressTicks)，是 MTR「服务端按 tick 模拟、
     * 客户端拿数据渲染」架构里的模拟状态流——飞机不再是实体，原版位移包通道
     * 整个不存在，本通道就是客户端纯函数对表 / 软校正的唯一权威来源。
     */
    public static final ResourceLocation TICK_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "flight_tick");
    /** C2S 跳伞请求通道名。 */
    public static final ResourceLocation JUMP_CHANNEL = new ResourceLocation(BattleRoyaleMod.MOD_ID, "parachute");

    /** {@link #TICK_CHANNEL} 的 progressTicks 哨兵值：飞机已到终点（客户端收机、停锁定）。 */
    public static final int PLANE_FINISHED = -1;

    private FlightSync() {
    }

    /** 服务端入口注册：接收玩家的跳伞包，回到主线程再动航线状态。 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(JUMP_CHANNEL,
                (server, player, handler, buf, sender) ->
                        server.execute(() -> FlightManager.INSTANCE.handleParachute(player)));
    }

    /**
     * 发一条航线状态给指定客户端（active=false 表示航线结束、收起画线）。
     *
     * @param startTick [BR-MTR] 航线开始 tick（服务端 world.getGameTime() 口径；
     *                  预热预告阶段尚无飞机时传 0，客户端以首包位置自校准）
     * @param speed     水平速度（格/tick；active=false 或尚无飞机时传 0）
     * @param flightY   飞行高度（客户端渲染的 Y，服务端已按维度顶高夹取）
     */
    public static void sendRoute(ServerPlayer player, boolean active, String mapId,
                                 double ax, double az, double bx, double bz,
                                 long startTick, double speed, double flightY) {
        if (player == null || !ServerPlayNetworking.canSend(player, ROUTE_CHANNEL)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(active);
        buf.writeBoolean(false);
        buf.writeUtf(mapId == null ? "" : mapId, 64);
        buf.writeDouble(ax);
        buf.writeDouble(az);
        buf.writeDouble(bx);
        buf.writeDouble(bz);
        buf.writeLong(startTick);
        buf.writeDouble(speed);
        buf.writeDouble(flightY);
        ServerPlayNetworking.send(player, ROUTE_CHANNEL, buf);
    }

    /** 空投专用航线包，明确覆盖客户端普通航线状态。 */
    public static void sendAirdropRoute(ServerPlayer player, boolean active, String mapId,
                                        double ax, double az, double bx, double bz,
                                        long startTick, double speed, double flightY) {
        if (player == null || !ServerPlayNetworking.canSend(player, ROUTE_CHANNEL)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(active);
        buf.writeBoolean(true);
        buf.writeUtf(mapId == null ? "" : mapId, 64);
        buf.writeDouble(ax);
        buf.writeDouble(az);
        buf.writeDouble(bx);
        buf.writeDouble(bz);
        buf.writeLong(startTick);
        buf.writeDouble(speed);
        buf.writeDouble(flightY);
        ServerPlayNetworking.send(player, ROUTE_CHANNEL, buf);
    }


    /**
     * [BR-MTR2] 广播本 tick 的权威飞行进度给维度里所有玩家（含观战）。
     *
     * @param serverTime    发包瞬间服务端 world.getGameTime()（诊断用）
     * @param progressTicks 航线开始后的权威 tick 数；{@link #PLANE_FINISHED} = 已收机
     */
    public static void broadcastTick(ServerLevel world, long serverTime, int progressTicks) {
        if (world == null) {
            return;
        }
        for (ServerPlayer player : world.getPlayers(p -> true)) {
            sendTick(player, serverTime, progressTicks);
        }
    }

    /** 给单个客户端发一发权威进度包（不支持通道的原版客户端自动跳过）。 */
    public static void sendTick(ServerPlayer player, long serverTime, int progressTicks) {
        if (player == null || !ServerPlayNetworking.canSend(player, TICK_CHANNEL)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(serverTime);
        buf.writeInt(progressTicks);
        ServerPlayNetworking.send(player, TICK_CHANNEL, buf);
    }

    /** 客户端解码（在 netty 线程读包，数据拷进不可变 record 后再回主线程发布）。 */
    public static Payload decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        boolean isAirdrop = buf.readBoolean();
        String mapId = buf.readUtf(64);
        double ax = buf.readDouble();
        double az = buf.readDouble();
        double bx = buf.readDouble();
        double bz = buf.readDouble();
        long startTick = buf.readLong();
        double speed = buf.readDouble();
        double flightY = buf.readDouble();
        return new Payload(active, isAirdrop, mapId, ax, az, bx, bz, startTick, speed, flightY);
    }

    public record Payload(boolean active, boolean isAirdrop, String mapId, double ax, double az, double bx, double bz,
                          long startTick, double speed, double flightY) {
    }
}
