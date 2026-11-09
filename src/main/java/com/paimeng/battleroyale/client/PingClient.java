package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.PingSync;
import com.paimeng.battleroyale.team.PingType;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * 标点系统客户端入口：只使用 V 键。
 *
 * <p>单击 V 后等待 300 毫秒：没有第二次按下就发送 WAYPOINT；
 * 第二次按下与第一次间隔小于 300 毫秒就发送 ENEMY。G 键、V 键切换消息、
 * {@code paimengmods ping} 指令都已删除，标点消息固定由服务端生成。
 *
 * <p>准星射线最大 128 格。命中方块取方块顶面上一格；没有命中方块时，
 * 严格取 128 格处的射线终点。
 */
@Environment(EnvType.CLIENT)
public final class PingClient {

    private static final int SUCCESS = 1;
    private static final int FAILURE = 0;

    /** 准星射线长度（格）。 */
    private static final float RAY_DISTANCE = 128.0f;

    /** 单击等待第二次按键的时间窗口。 */
    private static final long DOUBLE_CLICK_MS = 300L;

    private static KeyMapping pingKey;
    private static long firstPressAtMs = -1L;

    private PingClient() {
    }

    /** 在 {@code onInitializeClient} 里调一次：注册 V 键和客户端 tick。 */
    public static void register() {
        pingKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.battleroyale.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
                "key.categories.battleroyale"));
        ClientTickEvents.END_CLIENT_TICK.register(PingClient::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        // 维度硬闸门：大厅及其他维度不进入双击计时，也不发送标点包。
        if (client.level == null || client.player == null
                || !GameWorldManager.isGameWorld(client.level.dimension())) {
            firstPressAtMs = -1L;
            if (pingKey != null) {
                while (pingKey.consumeClick()) {
                    // 丢弃非比赛维度里积压的按键事件，避免进比赛维度后误触发。
                }
            }
            return;
        }
        if (pingKey != null) {
            while (pingKey.consumeClick()) {
                long now = System.currentTimeMillis();
                if (firstPressAtMs >= 0L && now - firstPressAtMs < DOUBLE_CLICK_MS) {
                    // 第二次按下在 300ms 内：双击标记敌人。
                    firstPressAtMs = -1L;
                    sendPing(client, PingType.ENEMY);
                } else {
                    // 第一次按下先记时间，等 300ms 确认不是双击。
                    firstPressAtMs = now;
                }
            }
        }

        if (firstPressAtMs >= 0L && System.currentTimeMillis() - firstPressAtMs >= DOUBLE_CLICK_MS) {
            // 300ms 内没有第二次按下：单击标记路标点。
            firstPressAtMs = -1L;
            sendPing(client, PingType.WAYPOINT);
        }
    }

    /** 算准星位置并发 C2S 标点包，服务端校验后才有队友可见的效果。 */
    private static void sendPing(Minecraft client, PingType type) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null
                || !GameWorldManager.isGameWorld(client.level.dimension())) {
            return;
        }
        try {
            if (!ClientPlayNetworking.canSend(PingSync.PING_CHANNEL_ID)) {
                showHotbarHint(client, "这个服务器没有标点功能（未安装本模组）");
                return;
            }
            Vec3 target = crosshairTarget(player);
            ClientPlayNetworking.send(PingSync.PING_CHANNEL_ID,
                    PingSync.encodePingRequest(target.x, target.y, target.z, type));
        } catch (RuntimeException e) {
            showHotbarHint(client, "标点发送失败：" + e.getMessage());
        }
    }

    /**
     * 准星射线找目标方块；命中取方块顶上一格，未命中方块直接取 128 格射线终点。
     * {@link LocalPlayer#raycast(float, float, boolean)} 在这里不会返回实体命中，
     * 因而非 BLOCK 结果统一按需求取终点。
     */
    private static Vec3 crosshairTarget(LocalPlayer player) {
        HitResult hit = player.pick(RAY_DISTANCE, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            return Vec3.atCenterOf(blockHit.getBlockPos()).add(0.0, 1.0, 0.0);
        }
        Vec3 start = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        return start.add(look.scale(RAY_DISTANCE));
    }

    /** hotbar 上方的一行提示（actionBar），不打扰聊天框。 */
    private static void showHotbarHint(Minecraft client, String raw) {
        MutableComponent text = BrText.mutable(raw).withStyle(ChatFormatting.AQUA);
        client.gui.setOverlayMessage(text, false);
    }
}
