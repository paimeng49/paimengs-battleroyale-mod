package com.paimeng.battleroyale.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

/**
 * 圈外淘汰倒计时 HUD。
 *
 * <p>[r198 重构] 秒数改由 {@link ZoneTtkCountdown} 按时间外推：
 * 每帧主动算 {@code ceil(预测血量 / DPS)}，DPS 来自服务端 ZoneSync v3 逐玩家同步——
 * 数字跟着流逝的秒数 59、58、57 平滑往下走，不再"掉一滴血才动一下"。
 */
@Environment(EnvType.CLIENT)
public final class ZoneTtkHudRenderer {
    private ZoneTtkHudRenderer() {}

    public static void render(GuiGraphics graphics, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (client.level == null || player == null || client.options.hideGui || !player.isAlive()
                || player.isSpectator() || player.isCreative()
                || !ClientZoneState.visibleIn(client.level.dimension())
                || ClientDownedState.get(player.getUUID()) != null) {
            return;
        }
        // 不在圈外（或毒圈没在跑）：预测状态作废，什么都不画。
        if (!ClientZoneState.isOutsideLive(player.getX(), player.getZ())) {
            ZoneTtkCountdown.reset();
            return;
        }
        int seconds = ZoneTtkCountdown.remainingSeconds(player);
        String message = seconds < 0
                ? "[警告] 安全区外淘汰倒计时：Infinity"
                : seconds <= 0
                    ? "[警告] 安全区外即将淘汰！"
                    : "[警告] 安全区外淘汰倒计时：" + seconds + " 秒";
        int width = client.font.width(message);
        int x = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int y = 34;
        int color = seconds >= 0 && seconds <= 10 ? 0xFFFF5555 : 0xFFFFAA33;
        GuiDraw.fill(graphics, x - 4, y - 2, x + width + 4, y + 10, 0x99000000);
        GuiDraw.drawTextWithShadow(graphics, client.font, message, x, y, color);
    }
}
