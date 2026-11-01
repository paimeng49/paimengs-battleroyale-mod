package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.paimeng.battleroyale.team.PingType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 标点世界渲染（1.20.1 标准管线重写版，r132）。
 *
 * <p><b>旧版为什么整个隐身：</b>老实现还是 1.18.2 时代的手动管线——自己
 * {@code new BufferBuilder}、手动 {@code RenderType.translucent().setupRenderState()}、
 * 手动把 Sampler0 换成白纹理、再 {@code BufferUploader.draw(buffer.end())} 上传。
 * 1.20.1 里这条路线和毒圈墙 r122 修过的老问题一模一样：手动状态随时会被地形渲染
 * 通道改回去，上传时机也不对——结果是一个像素都上不了屏，但又不抛异常，
 * 表现为"标点功能彻底看不见"。
 *
 * <p><b>1.20.1 的正确姿势（逐条对齐已在游戏里验证可见的 {@link ZoneWallRenderer}）：</b>
 * <ol>
 *   <li>图层：{@code RenderType.beaconBeam(SOLID_WHITE_TEXTURE, true)}——原版公开工厂，
 *       半透明混合 + 不写深度 + {@code DefaultVertexFormat.BLOCK} + QUADS；白纹理是
 *       构造参数，图层 flush 时绑定的就是它，不再和方块图集打架，也不再依赖任何
 *       手动 RenderSystem 状态；</li>
 *   <li>顶点：{@code context.matrixStack()}（PoseStack）+ {@code context.consumers()}
 *       （MultiBufferSource）拿 VertexConsumer，提交交给图层 {@code endBatch}，
 *       画完自动还原状态；</li>
 *   <li>相机：1.20.1 的 {@code context.matrixStack()} 只含旋转不含平移，在 pushPose 里
 *       {@code translate(-相机)}，顶点直接写世界坐标（世界坐标转"屏幕"偏移这一步
 *       就是它，旧版漏了这层平移的同类错误这里从根上不存在）；</li>
 *   <li>beaconBeam 默认开背面剔除（r124 核实过）：每个四边形正反两个绕序各发一遍，
 *       任意角度都能看到完整的光柱和敌人标记。</li>
 * </ol>
 *
 * <p>时间戳：标点存活期全部用 {@code System.currentTimeMillis()}（毫秒），
 * {@link ClientPingState} 的 bornAtMs / prune / 本类的 fade 三处同一单位，
 * r132 逐行核对过，不存在"纳秒当毫秒用导致一生成就过期"的问题。
 */
@Environment(EnvType.CLIENT)
public final class PingRenderer {

    private static final double MAX_RENDER_DISTANCE = 256.0D;
    private static final double MAX_RENDER_DISTANCE_SQUARED = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final float BOTTOM_Y = -64.0F;
    private static final float TOP_Y = 320.0F;
    private static final float BEAM_HALF_WIDTH = 0.32F;
    private static final int BEAM_ALPHA = (int) (0.52F * 255.0F);

    private static final long FADE_IN_MS = 700L;
    private static final float ENEMY_CENTER_HEIGHT = 1.6F;
    private static final int FULL_LIGHT = 0x00F0 | (0x00F0 << 16);

    /**
     * 路标光柱与敌人标记共用一层：白纹理版"信标光束"（和毒圈墙 ZONE_WALL 同一工厂），
     * 半透明混合、不写深度，支持逐顶点 RGBA——队伍色和淡入淡出全靠顶点色。
     */
    private static final RenderType PING_LAYER =
            RenderType.beaconBeam(ZoneWallRenderer.SOLID_WHITE_TEXTURE, true);

    private PingRenderer() {
    }

    /**
     * 渲染入口，签名匹配 {@code WorldRenderEvents.AfterTranslucent}，
     * 注册点在 {@code BattleRoyaleModClient}（AFTER_TRANSLUCENT）。
     */
    public static void render(WorldRenderContext context) {
        if (context == null || context.camera() == null || context.consumers() == null
                || context.matrixStack() == null) {
            return;
        }
        List<ClientPingState.Marker> markers = ClientPingState.snapshot();
        if (markers.isEmpty()) {
            return;
        }
        // beaconBeam 图层 flush 时绑定的就是这张白纹理；没注册进 TextureManager
        // 会退化成 MissingTexture——和毒圈墙共用注册入口，必须先确保加载成功。
        ZoneWallRenderer.ensureWhiteTextureLoaded();
        if (!ZoneWallRenderer.isWhiteTextureLoaded()) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        if (camera == null || !Double.isFinite(camera.x) || !Double.isFinite(camera.y)
                || !Double.isFinite(camera.z)) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean hasGeometry = false;
        PoseStack stack = context.matrixStack();
        stack.pushPose();
        try {
            // 世界坐标直写：先把 PoseStack 平移回相机原点（1.20.1 该时点的 matrixStack 没有这一层）。
            stack.translate(-camera.x, -camera.y, -camera.z);
            Matrix4f matrix = stack.last().pose();
            VertexConsumer buffer = context.consumers().getBuffer(PING_LAYER);

            int drawn = 0;
            for (ClientPingState.Marker marker : markers) {
                if (drawn++ >= ClientPingState.MAX_MARKERS) {
                    break;
                }
                long alive = marker.ttlMs() <= 0L
                        ? Long.MAX_VALUE
                        : marker.ttlMs() - (now - marker.bornAtMs());
                if (marker.ttlMs() > 0L && alive <= 0L) {
                    continue;
                }
                if (squaredDistance(marker.x(), marker.y(), marker.z(), camera)
                        > MAX_RENDER_DISTANCE_SQUARED) {
                    continue;
                }

                float fade = fade(marker, now, alive);
                if (marker.type() == PingType.WAYPOINT) {
                    int color = TeamHudRenderer.colorOf(marker.memberNumber());
                    int alpha = (int) (BEAM_ALPHA * fade);
                    if (alpha > 0) {
                        drawBeam(buffer, matrix, marker.x(), marker.z(),
                                (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha);
                        hasGeometry = true;
                    }
                } else if (marker.type() == PingType.ENEMY) {
                    int alpha = (int) (255.0F * fade);
                    if (alpha > 0) {
                        drawEnemyMark(buffer, matrix, marker.x(),
                                marker.y() + ENEMY_CENTER_HEIGHT, marker.z(), alpha);
                        hasGeometry = true;
                    }
                }
            }
        } finally {
            stack.popPose();
        }

        // 只提交我们这一层：图层状态机自己绑着色器 + 白纹理 + 混合，画完自动还原。
        if (hasGeometry && context.consumers() instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(PING_LAYER);
        }
    }

    // ------------------------------------------------------------------
    // 路标：交叉光柱
    // ------------------------------------------------------------------

    /** 交叉光柱 = 两片互相垂直的竖直四边形（一片宽沿世界 X，一片宽沿世界 Z）。 */
    private static void drawBeam(VertexConsumer buffer, Matrix4f matrix,
                                 double mx, double mz, int r, int g, int b, int a) {
        beamPlane(buffer, matrix, mx, mz, false, r, g, b, a);
        beamPlane(buffer, matrix, mx, mz, true, r, g, b, a);
    }

    /** 光柱的一个竖直平面，正反绕序各发一遍（beaconBeam 默认剔除背面，r124 同款）。 */
    private static void beamPlane(VertexConsumer buffer, Matrix4f matrix,
                                  double mx, double mz, boolean alongZ,
                                  int r, int g, int b, int a) {
        double x0;
        double z0;
        double x1;
        double z1;
        if (alongZ) {
            x0 = mx;
            z0 = mz - BEAM_HALF_WIDTH;
            x1 = mx;
            z1 = mz + BEAM_HALF_WIDTH;
        } else {
            x0 = mx - BEAM_HALF_WIDTH;
            z0 = mz;
            x1 = mx + BEAM_HALF_WIDTH;
            z1 = mz;
        }
        // 正面：左下 → 右下 → 右上 → 左上
        vertex(buffer, matrix, x0, BOTTOM_Y, z0, 0.0F, 1.0F, r, g, b, a);
        vertex(buffer, matrix, x1, BOTTOM_Y, z1, 1.0F, 1.0F, r, g, b, a);
        vertex(buffer, matrix, x1, TOP_Y, z1, 1.0F, 0.0F, r, g, b, a);
        vertex(buffer, matrix, x0, TOP_Y, z0, 0.0F, 0.0F, r, g, b, a);
        // 背面：反绕序重发
        vertex(buffer, matrix, x0, TOP_Y, z0, 0.0F, 0.0F, r, g, b, a);
        vertex(buffer, matrix, x1, TOP_Y, z1, 1.0F, 0.0F, r, g, b, a);
        vertex(buffer, matrix, x1, BOTTOM_Y, z1, 1.0F, 1.0F, r, g, b, a);
        vertex(buffer, matrix, x0, BOTTOM_Y, z0, 0.0F, 1.0F, r, g, b, a);
    }

    // ------------------------------------------------------------------
    // 敌人：红色三角（警告牌描边）+ 白色感叹号，立在两个正交竖直平面上
    // ------------------------------------------------------------------

    /** 三角半宽（水平方向）。 */
    private static final double TRI_HALF_WIDTH = 0.60;
    /** 三角顶点相对中心的向上偏移。 */
    private static final double TRI_APEX_UP = 0.50;
    /** 三角底边相对中心的向下偏移。 */
    private static final double TRI_BASE_DOWN = 0.42;
    /** 三角描边粗细（四边形半宽）。 */
    private static final double TRI_EDGE_HALF = 0.05;

    private static void drawEnemyMark(VertexConsumer buffer, Matrix4f matrix,
                                      double mx, double centerY, double mz, int alpha) {
        enemyPlane(buffer, matrix, mx, centerY, mz, true, alpha);
        enemyPlane(buffer, matrix, mx, centerY, mz, false, alpha);
    }

    /**
     * 敌人标记在单个竖直平面上的版本：沿 X（alongPlane=true）或沿 Z 展开。
     * 组成 = 红色警告三角（三条细长四边形拼出描边）+ 白色感叹号（竖条 + 圆点），
     * 与旧版"发现敌人"的样式一致。
     */
    private static void enemyPlane(VertexConsumer buffer, Matrix4f matrix,
                                   double mx, double centerY, double mz,
                                   boolean alongX, int alpha) {
        double ux = alongX ? 1.0 : 0.0;
        double uz = alongX ? 0.0 : 1.0;
        double yTop = centerY + TRI_APEX_UP;
        double yBase = centerY - TRI_BASE_DOWN;
        // 红色三角描边：底边 + 左斜边 + 右斜边
        triEdge(buffer, matrix, mx, mz, ux, uz, yBase, -TRI_HALF_WIDTH, yBase, TRI_HALF_WIDTH, alpha);
        triEdge(buffer, matrix, mx, mz, ux, uz, yBase, -TRI_HALF_WIDTH, yTop, 0.0, alpha);
        triEdge(buffer, matrix, mx, mz, ux, uz, yTop, 0.0, yBase, TRI_HALF_WIDTH, alpha);
        // 白色感叹号：竖条（占三角内部中上部）+ 圆点（贴近底边）
        double barBottom = yBase + 0.16;
        double barTop = yTop - 0.20;
        rotatedQuad(buffer, matrix, mx, (barBottom + barTop) / 2.0, mz, ux, uz,
                Math.PI / 2.0, (barTop - barBottom) / 2.0, 0.055, 255, 255, 255, alpha);
        rotatedQuad(buffer, matrix, mx, yBase + 0.07, mz, ux, uz,
                0.0, 0.06, 0.06, 255, 255, 255, alpha);
    }

    /**
     * 三角的一条边：连接竖直平面内两点 (h1, y1) → (h2, y2) 的细长红色四边形。
     * h 是沿平面轴 ux/uz 的水平偏移（相对标点中心），两端各加一点重叠让角上无缝拼接。
     */
    private static void triEdge(VertexConsumer buffer, Matrix4f matrix,
                                double mx, double mz, double ux, double uz,
                                double y1, double h1, double y2, double h2, int alpha) {
        double dy = y2 - y1;
        double dh = h2 - h1;
        double len = Math.hypot(dh, dy);
        double angle = Math.atan2(dy, dh);
        double cx = mx + ux * (h1 + h2) / 2.0;
        double cz = mz + uz * (h1 + h2) / 2.0;
        double cy = (y1 + y2) / 2.0;
        rotatedQuad(buffer, matrix, cx, cy, cz, ux, uz, angle,
                len / 2.0 + TRI_EDGE_HALF, TRI_EDGE_HALF, 255, 0, 0, alpha);
    }

    /**
     * 竖直平面内的一块可旋转小四边形（angle=0 时长轴水平，沿平面轴 ux/uz；平面竖直轴恒为世界 Y）。
     * 正反两个绕序各发一遍，任意视角都可见。
     */
    private static void rotatedQuad(VertexConsumer buffer, Matrix4f matrix,
                                    double cx, double cy, double cz,
                                    double ux, double uz, double angleRad,
                                    double halfAlong, double halfAcross,
                                    int r, int g, int b, int a) {
        double ca = Math.cos(angleRad);
        double sa = Math.sin(angleRad);
        // 长轴方向 e = u·cosθ + Y·sinθ；短轴方向 f = -u·sinθ + Y·cosθ（都在同一竖直平面内）
        double ex = ux * ca;
        double ey = sa;
        double ez = uz * ca;
        double fx = -ux * sa;
        double fy = ca;
        double fz = -uz * sa;

        double[][] p = {
                {cx - ex * halfAlong - fx * halfAcross, cy - ey * halfAlong - fy * halfAcross, cz - ez * halfAlong - fz * halfAcross},
                {cx + ex * halfAlong - fx * halfAcross, cy + ey * halfAlong - fy * halfAcross, cz + ez * halfAlong - fz * halfAcross},
                {cx + ex * halfAlong + fx * halfAcross, cy + ey * halfAlong + fy * halfAcross, cz + ez * halfAlong + fz * halfAcross},
                {cx - ex * halfAlong + fx * halfAcross, cy - ey * halfAlong + fy * halfAcross, cz - ez * halfAlong + fz * halfAcross},
        };
        float[][] uv = {{0.0F, 0.0F}, {1.0F, 0.0F}, {1.0F, 1.0F}, {0.0F, 1.0F}};
        for (int i = 0; i < 4; i++) {
            vertex(buffer, matrix, p[i][0], p[i][1], p[i][2], uv[i][0], uv[i][1], r, g, b, a);
        }
        for (int i = 3; i >= 0; i--) {
            vertex(buffer, matrix, p[i][0], p[i][1], p[i][2], uv[i][0], uv[i][1], r, g, b, a);
        }
    }

    // ------------------------------------------------------------------
    // 公共小工具
    // ------------------------------------------------------------------

    /**
     * 提交一个顶点。顺序与 {@code DefaultVertexFormat.BLOCK} 完全一致：
     * POSITION → COLOR → UV → UV2(LIGHT) → NORMAL，五个元素一个都不能漏，
     * 少写任何一个 {@code endVertex()} 会抛
     * {@code IllegalStateException: Not filled all elements of the vertex}。
     */
    private static void vertex(VertexConsumer buffer, Matrix4f matrix,
                               double x, double y, double z, float u, float v,
                               int r, int g, int b, int a) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .color(r, g, b, a)
                .uv(u, v)
                .uv2(FULL_LIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    private static double squaredDistance(double x, double y, double z, Vec3 camera) {
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 淡入 700ms；有期限的标记在生命最后 25% 淡出；永久路标不淡出。 */
    private static float fade(ClientPingState.Marker marker, long now, long alive) {
        long elapsed = Math.max(0L, now - marker.bornAtMs());
        float fadeIn = Math.min(1.0F, elapsed / (float) FADE_IN_MS);
        float fadeOut = marker.ttlMs() <= 0L
                ? 1.0F
                : Math.min(1.0F, alive / (marker.ttlMs() * 0.25F));
        return fadeIn * fadeOut;
    }
}
