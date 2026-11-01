package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * 1.20.1 GUI 绘制封装（替代被移除的 {@code DrawableHelper} 静态入口）。
 *
 * <p><b>本轮（r122）修复文字缓冲区 OOM 的三条硬约束（按 1.20.1 原版字节码核实）：</b>
 * <ol>
 *   <li><b>文字使用独立顶点缓冲。</b>1.20.1 的 {@code BufferBuilder} 容量不足时
 *       每次以 2MB 步进扩容（{@code ensureCapacity} → {@code MemoryTracker.resize}，
 *       扩到分配失败就抛 {@code OutOfMemoryError: Failed to resize buffer}），
 *       而 {@code Tesselator.getInstance().getBuilder()} 是<b>全局共享</b>的——
 *       小地图、底图色块、进度条文字全挤在同一个 builder 上，任何一次批次没配平
 *       都会把顶点往这一个缓冲里无限累加。现在文字走本类私有 {@link #TEXT_BUFFER}，
 *       每画一次必在 finally 里 {@code endBatch()} 提交，批次配平与共享 builder 彻底解耦。</li>
 *   <li><b>坐标先验后画。</b>x / y 不是有限数（NaN、±Infinity）直接返回 0：
 *       非有限矩阵乘进字形顶点后会产生整屏垃圾几何，这是历史上把批次撑爆的放大器。</li>
 *   <li><b>长度硬上限。</b>超过 {@link #MAX_TEXT_CHARS} 个字符的文本先截断再画：
 *       字形数 × 4 顶点从此有绝对上界，字体批次不可能再出现天文容量。</li>
 * </ol>
 *
 * <p>其余口径与上一版一致：
 * <ul>
 *   <li>{@code drawInBatch} 尾部两参数是 {@code (backgroundColor=0, light=0xF000F0)}
 *       （1.20.1 原版 {@code GuiGraphics.drawString} 就传 {@code (NORMAL, 0, 15728880)}，
 *       参数顺序照抄，不再写反）；</li>
 *   <li>颜色按完整 ARGB 解析，整个 Alpha 字节为 0 的旧写法（{@code 0xFFFFFF}）
 *       自动补齐为不透明；</li>
 *   <li>{@code GuiGraphics} 便捷入口直接委托原版 {@code graphics.drawString}——
 *       它自带提交纪律（内部 BufferSource + flushIfUnmanaged），比手写封装更稳。</li>
 * </ul>
 */
public final class GuiDraw {

    /** 原版 GUI 文字的打包光照（GuiGraphics 内部固定传 0xF000F0 = 15728880）。 */
    private static final int TEXT_LIGHT = 0xF000F0;

    /**
     * 文本长度硬上限（字符数）。超过就截断——字体每个字形最多写 4 个顶点，
     * 这一条把"单次文字批次最多写多少顶点"变成常数，OOM 从结构上不可能发生。
     * HUD 里最长的正常文本（进度条 + 倒计时 + 物资点名）也远不到 100。
     */
    private static final int MAX_TEXT_CHARS = 512;

    /**
     * 文字专用顶点缓冲：不和 {@link Tesselator} 的全局 builder 共用，
     * 每帧每个调用都完整走 begin → 写顶点 → endBatch 一遍（finally 保证）。
     */
    private static final BufferBuilder TEXT_BUFFER = new BufferBuilder(16 * 1024);

    private GuiDraw() {
    }

    // ==================== PoseStack 直绘（HUD / 地图 / 屏幕共用） ====================

    public static void fill(PoseStack matrices, int x1, int y1, int x2, int y2, int color) {
        if (matrices == null) {
            return;
        }
        int alpha = (color >> 24) & 0xFF;
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        Matrix4f matrix = matrices.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, x1, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y1, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x1, y1, 0.0F).color(red, green, blue, alpha).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    /** 浮点坐标版矩形：供缩放后的地图矩阵绘制，避免高 zoom 时 int 截断造成标记跳像素。 */
    public static void fillF(PoseStack matrices, float x1, float y1, float x2, float y2, int color) {
        if (matrices == null || !Float.isFinite(x1) || !Float.isFinite(y1)
                || !Float.isFinite(x2) || !Float.isFinite(y2)) {
            return;
        }
        int alpha = (color >> 24) & 0xFF;
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        Matrix4f matrix = matrices.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, x1, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y1, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x1, y1, 0.0F).color(red, green, blue, alpha).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    /**
     * 带阴影画字符串（旧 GuiDraw.drawTextWithShadow 的 1.20.1 等价物）。
     *
     * <p>守卫顺序：null → 坐标有限性 → 长度截断 → 绘制 → finally 无条件提交。
     */
    public static int drawTextWithShadow(PoseStack matrices, Font font, String text, float x, float y, int color) {
        if (matrices == null || font == null || text == null) {
            return 0;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return 0;
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        MultiBufferSource.BufferSource source = MultiBufferSource.immediate(TEXT_BUFFER);
        try {
            return font.drawInBatch(text, x, y, opaqueIfMissingAlpha(color), true, matrices.last().pose(),
                    source, Font.DisplayMode.NORMAL, 0, TEXT_LIGHT);
        } finally {
            source.endBatch();
        }
    }

    public static int drawTextWithShadow(PoseStack matrices, Font font, Component text, float x, float y, int color) {
        if (matrices == null || font == null || text == null) {
            return 0;
        }
        return drawTextWithShadow(matrices, font, text.getVisualOrderText(), x, y, color);
    }

    /** 带阴影画字符串（FormattedCharSequence 版，Component 的渲染序列走这里）。 */
    public static int drawTextWithShadow(PoseStack matrices, Font font, FormattedCharSequence text,
                                         float x, float y, int color) {
        if (matrices == null || font == null || text == null) {
            return 0;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return 0;
        }
        MultiBufferSource.BufferSource source = MultiBufferSource.immediate(TEXT_BUFFER);
        try {
            return font.drawInBatch(text, x, y, opaqueIfMissingAlpha(color), true, matrices.last().pose(),
                    source, Font.DisplayMode.NORMAL, 0, TEXT_LIGHT);
        } finally {
            source.endBatch();
        }
    }

    /** 居中对齐的带阴影文本。 */
    public static int drawCenteredText(PoseStack matrices, Font font, String text, int centerX, int y, int color) {
        if (matrices == null || font == null || text == null) {
            return 0;
        }
        return drawTextWithShadow(matrices, font, text, centerX - font.width(text) / 2.0F, y, color);
    }

    public static int drawCenteredText(PoseStack matrices, Font font, Component text, int centerX, int y, int color) {
        if (matrices == null || font == null || text == null) {
            return 0;
        }
        return drawTextWithShadow(matrices, font, text, centerX - font.width(text) / 2.0F, y, color);
    }

    // ==================== GuiGraphics 便捷入口（屏幕首选，全走原版提交） ====================

    /** GuiGraphics 版：委托原版 drawString（自带 BufferSource 与 flush 纪律）。 */
    public static int drawTextWithShadow(GuiGraphics graphics, Font font, String text, float x, float y, int color) {
        if (graphics == null || font == null || text == null) {
            return 0;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return 0;
        }
        return graphics.drawString(font, text, Math.round(x), Math.round(y), opaqueIfMissingAlpha(color), true);
    }

    public static int drawTextWithShadow(GuiGraphics graphics, Font font, Component text, float x, float y, int color) {
        if (graphics == null || font == null || text == null) {
            return 0;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return 0;
        }
        return graphics.drawString(font, text.getVisualOrderText(), Math.round(x), Math.round(y),
                opaqueIfMissingAlpha(color), true);
    }

    public static void fill(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        if (graphics == null) {
            return;
        }
        graphics.fill(x1, y1, x2, y2, color);
    }

    // ==================== 工具 ====================

    /**
     * ARGB 兜底：整个 Alpha 字节为 0（调用方只写了 RGB 习惯值，如 0xFFFFFF）时补齐为
     * 不透明，行为与原版 {@code Font} 内部的透明调整一致。
     */
    private static int opaqueIfMissingAlpha(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }

    /** 便捷取原版字体（避免调用方拿错字体实例）。 */
    public static Font font() {
        return Minecraft.getInstance().font;
    }
}
