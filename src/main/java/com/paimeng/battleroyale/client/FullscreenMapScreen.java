package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;

/**
 * 按 M 打开的全屏地图。
 *
 * <p>地图区域 = 屏幕宽 60% × 高 65%，水平居中，顶部留 10%、底部留 25% 完全透明；
 * 区域之外不铺任何半透明黑底，能直接看到游戏画面。
 *
 * <p>C3-1 起：底图数据来自 C2 的 {@link ClientMapCache}（服务端 64x64 Tile 切片），
 * 不再读旧的 {@code MapThumbnailGenerator}。本阶段只画底图 + 玩家箭头，
 * 不画毒圈、标点、物资点名字、队友。
 */
public final class FullscreenMapScreen extends Screen {
    /** 地图区域占屏幕宽 / 高的比例（与需求一致，勿随意改动）。 */
    private static final double REGION_WIDTH_RATIO = 0.60;
    private static final double REGION_HEIGHT_RATIO = 0.65;
    private static final double REGION_TOP_RATIO = 0.10;
    private double panX;
    private double panY;
    private double zoom = 1.0;
    private boolean zoomPending;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    public FullscreenMapScreen() {
        super(Component.literal("全屏地图"));
        // 全屏地图只读取 C2 已收到的 Tile 缓存，不在打开界面时触发重建。
    }

    @Override
    protected void init() {
        panX = 0;
        panY = 0;
        if (MapRenderer.hasBaseMap()) {
            zoom = fitZoom();
            zoomPending = false;
        } else {
            // 底图元数据还没到（第一个 Tile 在路上）：等 render 里第一次就绪时再适配。
            zoom = 1.0;
            zoomPending = true;
        }
        BattleRoyaleMod.LOGGER.info("[BR-MAP-C3] 打开全屏地图：底图就绪={}，缓存 Tile 数={}",
                MapRenderer.hasBaseMap(), ClientMapCache.size());
    }

    /** 保持底图宽高比：缩放后整幅图必须装进 60% × 65% 的区域，不足部分由区域底色填充。 */
    private double fitZoom() {
        int[] region = regionBounds();
        return MapRenderer.fitZoom(region[2] - region[0], region[3] - region[1]);
    }

    /** 地图区域四边：left, top, right, bottom（屏幕缩放像素坐标）。 */
    private int[] regionBounds() {
        int regionLeft = (int) Math.round(width * (1.0 - REGION_WIDTH_RATIO) / 2.0);
        int regionRight = width - regionLeft;
        int regionTop = (int) Math.round(height * REGION_TOP_RATIO);
        int regionBottom = (int) Math.round(regionTop + height * REGION_HEIGHT_RATIO);
        return new int[]{regionLeft, regionTop, regionRight, regionBottom};
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        PoseStack matrices = graphics.pose();
        if (zoomPending && MapRenderer.hasBaseMap()) {
            zoom = fitZoom();
            zoomPending = false;
        }
        int[] region = regionBounds();
        int regionLeft = region[0];
        int regionTop = region[1];
        int regionRight = region[2];
        int regionBottom = region[3];
        // 只给地图区域铺深色底；外围完全透明。
        GuiDraw.fill(matrices, regionLeft, regionTop, regionRight, regionBottom, 0xCC1A1A1A);
        if (MapRenderer.hasBaseMap()) {
            matrices.pushPose();
            try {
                // 底图 + 玩家白色箭头（方向换算统一在 MapRenderer 里，沿用旧公式）。
                MapRenderer.renderFullscreenMap(graphics, minecraft.player,
                        regionLeft, regionTop, regionRight, regionBottom, zoom, panX, panY);
            } finally {
                matrices.popPose();
            }
        } else {
            String hint = minecraft.player == null ? "" : "地图数据加载中…";
            if (!hint.isEmpty()) {
                GuiDraw.drawCenteredText(matrices, font, Component.literal(hint),
                        (regionLeft + regionRight) / 2, (regionTop + regionBottom) / 2, 0xFFCCCCCC);
            }
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_M || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == 0) {
            panX += mouseX - lastMouseX;
            panY += mouseY - lastMouseY;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double old = zoom;
        zoom = Math.max(0.02, Math.min(8.0, zoom * (1.0 + amount * 0.1)));
        int[] region = regionBounds();
        double centerX = (region[0] + region[2]) / 2.0;
        double centerY = (region[1] + region[3]) / 2.0;
        // panX/panY 是缩放前的屏幕平移量；用地图区域中心而不是整个屏幕中心，
        // 鼠标下的地图像素才能在滚轮缩放后保持原位。
        panX += (mouseX - centerX - panX) * (1.0 - zoom / old);
        panY += (mouseY - centerY - panY) * (1.0 - zoom / old);
        return true;
    }
}
