package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.FlightSync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键 + 机上跟随。M=全屏地图；跳伞键=<b>左 Shift 或 F 键</b>（双键物理检测）。
 *
 * <p>[BR-ROLLBACK] 彻底回滚 r88/r89 的骑乘/换绑方案，教训落进设计：
 * <ul>
 *   <li><b>不碰原版按键注册表</b>：全文件没有任何一行读写 {@code options.sneakKey}，
 *       更没有 {@code sneakKey.setBoundKey(...)}（那串代码就是
 *       {@code NumberFormatException: "left_shift"} 崩溃的源头）。[BR-MTR] 左 Shift
 *       只做 {@link InputConstants#isKeyPressed} 物理状态直查——不注册、不换绑、不拦截，
 *       原版潜行功能原样保留，因此也绝不会再碰崩键位解析；</li>
 *   <li><b>按键判定多通道</b>：{@code parachute.consumeClick()}（玩家在设置里改绑也生效）
 *       + GLFW 直查 <b>F 上升沿</b> + GLFW 直查 <b>左 Shift 上升沿</b>（都不依赖槽位
 *       归属，永远可靠）。只在「游戏维度 + ClientFlightState.isActive() +
 *       未发过跳伞包」窗口内生效；</li>
 *   <li><b>数据驱动坐标锁定（[BR-MTR2] 防抽搐核心）</b>：与服务端 FlightManager#lockToPlane
 *       成对。每客户端 tick 末尾把玩家 prev/pos 钉到航线<b>纯函数</b>的相邻两个 tick
 *       采样点上（数据源 = 服务端每 tick 下发的权威进度包，见 ClientFlightState），
 *       镜头插值与 {@code C130WorldRenderer} 的画点展开成同一个表达式——机身相对镜头
 *       数学恒等静止；C-130 已不是实体，prev/lastRender 一类的插值状态从根上不存在，
 *       包到达相位无从影响画面。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class ModKeyBindings {
    /** 与服务端 FlightManager.PLANE_LOCK_Y_OFFSET 严格一致。 */
    private static final double PLANE_LOCK_Y_OFFSET = 1.5;

    private static KeyMapping openFullMap;
    /** 跳伞键（默认绑定 = F 键；左 Shift 走物理直查，不占键位注册表）。航线阶段按下 → 向服务端发跳伞包。 */
    private static KeyMapping parachute;
    /** F 键上一帧的按下状态（GLFW 直查的上升沿检测基线）。 */
    private static boolean fWasDown;
    /** 左 Shift 上一帧的按下状态（GLFW 直查的上升沿检测基线）。 */
    private static boolean lshiftWasDown;
    /** 上一帧是否处于跳伞窗口（窗口开启瞬间收编已按住的键，防"走火"）。 */
    private static boolean wasInFlight;

    private ModKeyBindings() {
    }

    public static void register() {
        openFullMap = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.battleroyale.full_map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.battleroyale.map"));
        parachute = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.battleroyale.parachute",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F,
                "category.battleroyale.game"));
    }

    /** END_CLIENT_TICK：先做机上坐标锁定，再处理按键。 */
    public static void tick(Minecraft client) {
        lockOntoPlane(client);

        // [BR-HUD-DIM] 全屏地图和小地图同一口径：只在比赛维度 battleroyale_game:* 打开。
        // 大厅按 M 不再弹出一张和脚下地形无关的旧底图。
        if (openFullMap != null && openFullMap.consumeClick()
                && client.screen == null && client.level != null && client.player != null
                && GameWorldManager.isGameWorld(client.level.dimension())) {
            client.setScreen(new FullscreenMapScreen());
        }
        if (parachute == null) {
            return;
        }
        // 跳伞窗口 = 游戏维度 + 航线进行中 + 本轮还没发过跳伞包（服务端才是权威判定）。
        boolean inFlight = client.player != null && client.level != null
                && GameWorldManager.isGameWorld(client.level.dimension())
                && ClientFlightState.isActive() && !ClientFlightState.isJumpSent();

        // 通道①：parachute 键的 wasPressed（玩家在设置里把跳伞键改绑成别的键也生效）。
        boolean pressed = parachute.consumeClick();
        // 通道②/③：直查 GLFW 的 F / 左 Shift 上升沿。不经过任何键位绑定/槽位表，
        // 永远可靠，也不会像 r88 那样去动原版键位注册表（左 Shift 只"读"不"占"，
        // 原版潜行功能原样还在；机上锁定期间坐标每 tick 被钉，蹲不蹲都无所谓）。
        long window = client.getWindow() == null ? 0L : client.getWindow().getWindow();
        boolean fDown = window != 0L
                && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_F);
        boolean lshiftDown = window != 0L
                && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT);
        if (inFlight && !wasInFlight) {
            // [BR-MTR] 锁定窗口刚开启：把此刻已按住的键收进基线（MTR 用按住 30 tick
            // 才生效防误触，我们用上升沿基线达成同等效果）——登机前就按着 Shift 的
            // 玩家不会一上飞机就"走火"被踢下去。
            fWasDown = fDown;
            lshiftWasDown = lshiftDown;
        }
        wasInFlight = inFlight;
        boolean edge = (fDown && !fWasDown) || (lshiftDown && !lshiftWasDown);
        fWasDown = fDown;
        lshiftWasDown = lshiftDown;

        if (inFlight && client.screen == null && (pressed || edge)) {
            ClientFlightState.markJumpSent(); // 本帧起坐标锁定自动停，人交还重力
            ClientPlayNetworking.send(FlightSync.JUMP_CHANNEL, PacketByteBufs.create());
        }
        // 非跳伞窗口期间的按键积压直接吃掉，防止残留按下次数在下一次误触发。
        while (parachute.consumeClick()) {
            // no-op：仅清队列
        }
    }

    /**
     * [BR-MTR2] 机上玩家跟随（模仿 MTR 的"反复 TP"）：航线进行中且未跳伞时，
     * 每客户端 tick 末尾直接读<b>服务端同步过来的权威数据</b>（flight_tick 包锚定的
     * 纯函数），硬性设置玩家坐标：
     * <pre>
     * prev = f(t − 1)      pos = f(t)      （t = clientWorld.getGameTime()）
     * </pre>
     * 原版镜头位置 = {@code lerp(prev, pos, tickDelta) = f(t − 1 + tickDelta)}；
     * {@link C130WorldRenderer} 画的 = {@code f(getTime() − 1 + tickDelta)}——
     * <b>同一个表达式的同一次求值</b>，机身相对镜头是数学恒等的零位移。
     * 每 tick 都强制对齐，不存在"相对位移积累再回弹"的过程，抽搐无从发生。
     *
     * <p>纯函数未锚定（航线刚开始的最多 1~2 个 tick）时本方法停手：玩家站在
     * 服务端权威传送落下的起点 A 上方，第一发 tick 包到达即与飞机同帧上岗。
     *
     * <p>服务端一侧每 tick 清玩家物理（fallDistance=0、velocity=ZERO）并重申
     * 传送豁免（inTeleportationState），客户端上报的超前坐标不会被判作弊。
     * 到终点 B 服务端下发 progressTicks=-1，{@link ClientFlightState#planeSyncActive()}
     * 转 false，本方法自然停手，玩家交还重力（服务端同 tick 强制开伞，状态一致）。
     */
    private static void lockOntoPlane(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        if (!ClientFlightState.isActive() || ClientFlightState.isJumpSent()) {
            return;
        }
        if (!ClientFlightState.planeSyncActive()
                || !ClientFlightState.visibleIn(client.level.dimension())) {
            return; // 未锚定 / 不在本航线维度：交还原版物理（服务端 TP 兜底）
        }
        // [BR-AIRDROP 模块三] 空投 C-130 复用同一条 flight 通道做渲染：毒圈已经开跑
        // （RUNNING，Zone 快照在场）时航线只该"看"，绝不把地面上的玩家钉上飞机。
        if (ClientZoneState.isPresent() && ClientZoneState.visibleIn(client.level.dimension())) {
            return;
        }
        // [BR-LOCK2] 这里绝不做 isOnGround() 一类的"落地豁免"判定：刚登机的那几个 tick
        // 玩家客户端侧 isOnGround 仍可能为 true，一旦据此 return，锁定就整段失效，
        // 玩家被服务端每 tick 的传送拉回，表现为飞机抽搐。只要
        // planeSyncActive() 为 true，就无条件钉 pos 和 prev——航线结束（跳伞 / 到终点）
        // 由上面 isActive/isJumpSent/planeSyncActive 三个守卫自然停手。
        double t = client.level.getGameTime();
        double y = ClientFlightState.planeY() + PLANE_LOCK_Y_OFFSET;
        client.player.xOld = ClientFlightState.planeXAt(t - 1.0);
        client.player.yOld = y;
        client.player.zOld = ClientFlightState.planeZAt(t - 1.0);
        client.player.setPos(ClientFlightState.planeXAt(t), y, ClientFlightState.planeZAt(t));
        // [r127] 坐标锁定后同步旋转插值基线，避免原版用旧的 yRotO/xRotO
        // 与当前视角做错误插值，造成镜头拉扯；不修改玩家当前 Yaw/Pitch。
        client.player.yRotO = client.player.getYRot();
        client.player.xRotO = client.player.getXRot();
        client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        client.player.fallDistance = 0.0F;
    }
}
