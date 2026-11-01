package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.network.DownedSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.UUID;

/** 击倒玩家屏幕提示和救援进度条。 */
@Environment(EnvType.CLIENT)
public final class DownedHudRenderer implements HudRenderCallback {
    private DownedHudRenderer() {
    }

    @Override
    public void onHudRender(GuiGraphics graphics, float tickDelta) {
        PoseStack matrices = graphics.pose();
        renderHud(matrices, tickDelta);
    }

    public static void render(GuiGraphics graphics, float tickDelta) {
        PoseStack matrices = graphics.pose();
        renderHud(matrices, tickDelta);
    }

    private static void renderHud(PoseStack matrices, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        UUID id = client.player.getUUID();
        DownedSyncPayload own = ClientDownedState.get(id);
        Font text = client.font;
        if (own != null && own.downed()) {
            int seconds = Mth.ceil(own.remainingTicks() / 20.0f);
            String message = "击倒中：" + seconds + " 秒";
            int x = (client.getWindow().getGuiScaledWidth() - text.width(message)) / 2;
            GuiDraw.drawTextWithShadow(matrices, text, message, x, 20, 0xFFFF5555);
        }
        // 被救者自己的视角：显示当前救援者和剩余救援秒数。
        if (own != null && own.rescuerUuid() != null && own.rescueProgress() > 0.0f) {
            String rescuerName = client.level == null || findClientPlayer(client.level, own.rescuerUuid()) == null
                    ? "队友" : findClientPlayer(client.level, own.rescuerUuid()).getName().getString();
            renderRescueBar(matrices, client, text,
                    "正在被 " + rescuerName + " 救援：" + formatSeconds(10.0f - own.rescueRemainingSeconds())
                            + " / 10.0 秒", own.rescueProgress());
        }

        // 救援者自己的视角：遍历所有倒地状态，找到 UUID 指向自己的条目。
        for (Map.Entry<UUID, DownedSyncPayload> entry : ClientDownedState.snapshot().entrySet()) {
            DownedSyncPayload state = entry.getValue();
            if (state.downed() && client.player.getUUID().equals(state.rescuerUuid()) && state.rescueProgress() > 0.0f) {
                String targetName = client.level == null || findClientPlayer(client.level, entry.getKey()) == null
                        ? "队友" : findClientPlayer(client.level, entry.getKey()).getName().getString();
                renderRescueBar(matrices, client, text,
                        "救援中：" + targetName + "（" + formatSeconds(10.0f - state.rescueRemainingSeconds())
                                + " / 10.0 秒）", state.rescueProgress());
                break;
            }
        }
    }

    private static void renderRescueBar(PoseStack matrices, Minecraft client, Font text,
                                         String message, float progress) {
        int width = 120;
        int left = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int top = client.getWindow().getGuiScaledHeight() / 2 + 22;
        GuiDraw.fill(matrices, left, top, left + width, top + 8, 0xAA555555);
        int filled = (int) (width * Mth.clamp(progress, 0.0f, 1.0f));
        GuiDraw.fill(matrices, left, top, left + filled, top + 8, 0xFF55FF55);
        GuiDraw.drawTextWithShadow(matrices, text, message, left + (width - text.width(message)) / 2, top - 12, 0xFFFFFFFF);
    }

    private static String formatSeconds(float seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0.0f, seconds));
    }

    /** 1.20.1 移植：ClientLevel 没有 getPlayer(UUID)，手动遍历。 */
    private static net.minecraft.client.player.AbstractClientPlayer findClientPlayer(
            net.minecraft.client.multiplayer.ClientLevel level, java.util.UUID id) {
        if (level == null) return null;
        for (net.minecraft.client.player.AbstractClientPlayer player : level.players()) {
            if (player.getUUID().equals(id)) return player;
        }
        return null;
    }
}
