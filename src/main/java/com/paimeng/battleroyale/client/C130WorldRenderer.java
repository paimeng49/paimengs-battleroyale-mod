package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * [BR-MTR2][BR-R3] C-130 世界渲染器：数据驱动，彻底脱离实体管线。
 *
 * <p>MTR 的火车/飞机不卡，根本原因是载具<b>不是 Entity</b>：没有实体追踪包、
 * 没有 prev/lastRender 插值、没有按实体包围盒做的视锥剔除——网络包到达相位
 * 根本无法影响画面。本类是同一架构在 C-130 上的落地：
 * <ol>
 *   <li>位置来自 {@link ClientFlightState} 的时间纯函数
 *       {@code f(world.getGameTime() − 1 + tickDelta)}，每帧直接算，零缓存零插值状态；
 *       "−1 + tickDelta" 与原版镜头 {@code lerp(prev=f(t−1), pos=f(t), delta)}
 *       展开后是同一表达式，机上第一/三人称看到机身纹丝不动（数学恒等）；</li>
 *   <li>绘制挂在 {@code WorldRenderEvents.AFTER_ENTITIES}：1.20.1 该时点的
 *       {@code matrixStack()} 与 1.18.2 语义一致——只含相机旋转、不含相机平移
 *       （镜头位移折进了投影矩阵），所以顶点坐标仍要自己减去相机坐标
 *       （与 {@link ZoneWallRenderer} 同一坑位同一解法）；</li>
 *   <li>剔除由本类自理（距离裁掉 + 第一人称在机身内时整体隐藏），不存在
 *       "模型画在远处、实体坐标在起点被剔掉"的问题——这里根本没有实体。</li>
 * </ol>
 *
 * <p><b>[BR-R3] 1.20.1 提交路径大修</b>：旧版自带私有 BufferBuilder，
 * begin→写顶点→end→{@code layer.setupRenderState → BufferUploader.draw → clearRenderState}
 * 一帧内手动闭环。移植到 1.20.1 后改成标准管线：
 * 从 {@code context.consumers()}（本帧的 {@link MultiBufferSource}）按
 * {@code RenderType.entityCutout(贴图)} 取 {@link VertexConsumer}，
 * 在 {@link PoseStack} 上做平移 + 旋转，让 {@link C130Model} 走公开的
 * {@code vertex(Matrix4f, …) / normal(Matrix3f, …)} 签名吐顶点；
 * 提交（flush）时机、纹理绑定、混合/剔除状态全部交回原版实体管线在帧末统一处理，
 * 本类不再碰 RenderSystem / BufferUploader / CHUNK_OFFSET。
 *
 * <p>本类只决定飞机模型「画不画 / 画在哪」，不碰跳伞、按键与航线计算。
 */
@Environment(EnvType.CLIENT)
public final class C130WorldRenderer {

    /** 光照写满格（block 15 | sky 15）：315 高空本就没有方块遮挡。 */
    private static final int FULL_LIGHT = 0x00F0 | (0x00F0 << 16);
    /** 距离裁剪：超过这个距离（格）不画（比旧实体方案 384 格上限更宽松）。 */
    private static final double MAX_RENDER_DISTANCE_SQ = 640.0 * 640.0;
    /** 第一人称防挡视线：镜头距机身 10 格内不画（机上玩家视角）。 */
    private static final double ONBOARD_HIDE_RADIUS_SQ = 100.0;

    /**
     * 图层缓存：{@code RenderType.entityCutout} 每次调用都会新建对象，
     * 固定纹理只建一次；每帧只是用它向 consumers() 要顶点点位，
     * 顶点数据本身仍每帧重写，不会跨帧残留。
     */
    private static RenderType layer;

    private C130WorldRenderer() {
    }

    /** 挂 {@code WorldRenderEvents.AFTER_ENTITIES}。 */
    public static void render(WorldRenderContext context) {
        if (!ClientFlightState.planeSyncActive() || context.world() == null) {
            return;
        }
        if (!ClientFlightState.visibleIn(context.world().dimension())) {
            return;
        }
        C130Model model = C130Model.get();
        if (model.isEmpty()) {
            return;
        }

        // ---- 权威纯函数采样：与镜头锁定同一条曲线的同一时刻 ----
        double renderTime = context.world().getGameTime() - 1.0 + context.tickDelta();
        double x = ClientFlightState.planeXAt(renderTime);
        double y = ClientFlightState.planeY();
        double z = ClientFlightState.planeZAt(renderTime);

        Vec3 camera = context.camera().getPosition();
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (!(distSq <= MAX_RENDER_DISTANCE_SQ)) {
            return; // 太远（或 NaN）不画
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.options != null
                && client.options.getCameraType().isFirstPerson()
                && distSq < ONBOARD_HIDE_RADIUS_SQ) {
            return; // 机上第一人称：机身会糊满屏幕，隐藏
        }

        if (layer == null) {
            layer = RenderType.entityCutout(C130Model.TEXTURE);
        }

        // ---- [BR-R3] 标准提交：本帧 MultiBufferSource + PoseStack 变换 ----
        MultiBufferSource buffers = context.consumers();
        VertexConsumer consumer = buffers.getBuffer(layer);

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        // 相机相对坐标（该事件矩阵不含相机平移，与原版实体同一坐标系）
        poseStack.translate(dx, dy, dz);
        // Blockbench 模型机头朝模型 -Z；MC yaw=0 的前进方向是 +Z，沿用原版实体的 180-yaw 变换。
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(180.0F - ClientFlightState.planeYaw())));

        PoseStack.Pose pose = poseStack.last();
        model.draw(pose.pose(), pose.normal(), consumer,
                FULL_LIGHT, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();

        // AFTER_ENTITIES 时原版实体图层已经按类型 flush 过，我们写入的是同一图层的
        // 新批次；显式冲掉“只属于本图层”的批次（endBatch(layer) 不动其它 mod 的挂起
        // 批次），保证这一帧一定上屏，而不是等帧末某个全局 flush 碰运气。
        if (buffers instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(layer);
        }
    }
}
