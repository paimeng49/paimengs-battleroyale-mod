package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * HUD 小地图入口。
 *
 * <p>C3-1 起：这里只剩「什么时候画」的判断，具体画什么全部交给 {@link MapRenderer}，
 * 底图数据来自 C2 的 {@link ClientMapCache}（服务端 Tile 切片），
 * 不再读旧的 {@code MapThumbnailGenerator} 实时采样缓存。
 */
@Environment(EnvType.CLIENT)
public final class MapHudRenderer {
    /** 小地图边长（像素），与 MapRenderer 保持一致。 */
    private static final int SIZE = 100;
    /** 小地图距屏幕边缘的像素。 */
    private static final int EDGE = 8;
    /** 上一次打印过位置日志的窗口逻辑尺寸，只在尺寸变化时重新打印。 */
    private static int lastLoggedWidth = -1;
    private static int lastLoggedHeight = -1;

    private MapHudRenderer() {
    }

    public static void render(GuiGraphics graphics, float tickDelta) {
        PoseStack matrices = graphics.pose();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        // 任何界面（包括全屏地图）打开时都不画常驻 HUD：两套坐标不会同屏，也互不覆盖。
        if (player == null || client.options.hideGui || client.screen != null) {
            return;
        }
        // [BR-HUD-DIM] 维度隔离闸：右上角小地图（连同正下方的缩圈进度条）只属于
        // 吃鸡比赛维度 battleroyale_game:*。主世界（大厅）、编辑维度等一律直接返回——
        // 否则回到大厅后 ClientMapCache 里还留着上一局的底图切片，会画出一张
        // 和脚下地形无关的“鬼影小地图”。判断与 StatsHudRenderer 的维度闸同款。
        if (client.level == null || !GameWorldManager.isGameWorld(client.level.dimension())) {
            return;
        }
        int scaledWidth = client.getWindow().getGuiScaledWidth();
        int scaledHeight = client.getWindow().getGuiScaledHeight();
        int left = scaledWidth - SIZE - EDGE;
        int top = EDGE;
        logPosition(matrices, scaledWidth, scaledHeight, left, top);
        // 把 HUD 自己的绘制圈在独立的矩阵层里：外部残留的 push/translate 不会被我们继续放大，
        // 我们的 push/pop 也不会影响后面的 HUD。
        matrices.pushPose();
        try {
            MapRenderer.renderMinimap(graphics, player);
            // [BR-ZONE] 模块五：缩圈进度条画在小地图正下方；
            // 内部自带维度隔离闸（主城 / 别的地图直接不画）。
            ZoneHudRenderer.render(matrices, left, top, SIZE);
        } finally {
            matrices.popPose();
        }
    }

    /**
     * 位置日志：只在窗口逻辑尺寸变化时打印一次，不每帧刷屏。
     * 额外检查进入 HUD 时矩阵栈是否还是单位矩阵——若不是，说明别的 HUD 渲染器
     * 的 push/translate 没配对，会把小地图推离原位。
     */
    private static void logPosition(PoseStack matrices, int scaledWidth, int scaledHeight, int left, int top) {
        if (scaledWidth == lastLoggedWidth && scaledHeight == lastLoggedHeight) {
            return;
        }
        lastLoggedWidth = scaledWidth;
        lastLoggedHeight = scaledHeight;
        BattleRoyaleMod.LOGGER.info("HUD 小地图位置：屏幕 {}x{}，小地图 {}x{}，绘制坐标 ({}, {})",
                scaledWidth, scaledHeight, SIZE, SIZE, left, top);
        Matrix4f current = matrices.last().pose();
        if (!current.equals(new Matrix4f())) {
            BattleRoyaleMod.LOGGER.warn("HUD 小地图矩阵异常：进入 HUD 前矩阵已不是单位矩阵，"
                    + "小地图会被外部平移推离原位，当前矩阵={}", current);
        }
    }

    /** 居中对齐 + 深色描边的地图文字（留给付费标记/标点等后续阶段复用）。 */
    static void drawOutlinedLabel(PoseStack matrices, net.minecraft.client.gui.Font renderer,
                                  String text, double centerX, double centerY, float scale) {
        if (text == null || text.isEmpty() || scale <= 0.0f) {
            return;
        }
        int width = renderer.width(text);
        matrices.pushPose();
        matrices.translate(centerX, centerY, 0.0);
        matrices.scale(scale, scale, 1.0f);
        float x = -width / 2.0f;
        float y = -renderer.lineHeight / 2.0f;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                GuiDraw.drawTextWithShadow(matrices, renderer, text, x + ox, y + oy, 0xFF202020);
            }
        }
        GuiDraw.drawTextWithShadow(matrices, renderer, text, x, y, 0xFFFFFFFF);
        matrices.popPose();
    }

    /**
     * 供后续队友标记复用：按玩家 UUID 返回队伍颜色。
     * [BR-NOLOBBYNUMBER] 战术色只属于局内 MatchTeam —— 大厅快照（match=false）
     * 一律回退白色，不再拿大厅队伍门牌号去查颜色表。
     */
    public static int getTeamColor(UUID playerUuid) {
        if (!ClientTeamState.isMatch()) {
            return 0xFFFFFFFF;
        }
        int teamNumber = ClientTeamState.teamNumberOf(playerUuid);
        return switch (teamNumber) {
            case 1 -> 0xFFFFFF55; // 黄色
            case 2 -> 0xFFFFAA33; // 橙色
            case 3 -> 0xFF5599FF; // 蓝色
            case 4 -> 0xFF55DD55; // 绿色
            default -> 0xFFFFFFFF;
        };
    }
}
