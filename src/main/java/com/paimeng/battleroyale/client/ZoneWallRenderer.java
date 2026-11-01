package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * 毒圈蓝色能量墙（1.20.1 标准管线重写版，r122）。
 *
 * <p><b>为什么旧版看不见：</b>1.18.2 的画法是"手动 setupRenderState → 把 Sampler0 临时换成
 * 纯白纹理 → BufferUploader.draw"。移植后有两个连环问题——
 * ① {@code textures/misc/white.png} 在 1.20.1 并不会被自动注册进 {@code TextureManager}，
 * 绑一个没注册的 id 拿到的是 MissingTexture（噪点/空采样），墙等于没画；
 * ② 只要走 {@code MultiBufferSource.getBuffer(RenderType.translucent())}，图层 flush 时
 * {@code setupRenderState} 会把 Sampler0 重新绑回<b>方块图集</b>——手动换纹理的把戏在
 * BufferSource 管线里根本活不到上传那一刻，颜色还会被图集里某个方块染色。
 *
 * <p><b>1.20.1 的正确姿势（本版实现，全部公开 API，逐项按本项目的 loom 映射 jar 核实过）：</b>
 * <ol>
 *   <li>层：{@code RenderType.beaconBeam(SOLID_WHITE_TEXTURE, true)}——原版公开工厂，
 *       语义正好是"半透明混合 + 不写深度 + {@code DefaultVertexFormat.BLOCK} + QUADS +
 *       允许调用方自带纹理"。纹理位是构造参数，flush 时绑的就是我们传的白纹理，
 *       不再和方块图集打架（注意：它 1.20.1 里<b>默认开启背面剔除</b>，
 *       所以墙体顶点正反两面各发一次，见 {@link #render} 内 [r124] 注释）；</li>
 *   <li>顶点：{@code context.matrixStack()}（PoseStack）+ {@code context.consumers()}
 *       （MultiBufferSource）——{@code consumers.getBuffer(ZONE_WALL)} 拿到 VertexConsumer，
 *       提交与状态管理交给图层的 {@code endBatch}，全程不再手写 RenderSystem 状态机；</li>
 *   <li>相机：1.20.1 的 {@code WorldRenderContext.matrixStack()} 依然只含旋转不含平移，
 *       所以在 pushPose 里 {@code translate(-相机)}，顶点直接写世界坐标。</li>
 * </ol>
 *
 * <p>白纹理的注册（{@link #ensureWhiteTextureLoaded()}）仍然必需：
 * {@code TextureStateShard} 绑定同样走 {@code TextureManager.getTexture}，未注册的 id
 * 一样退化成 MissingTexture。注册后它还会顺带修好共用这张纹理的 Ping 光柱。
 */
@Environment(EnvType.CLIENT)
public final class ZoneWallRenderer {

    /** 圆周分段数：沿圆均匀取 64 个点 → 64 个竖直四边形拼成一圈围墙。 */
    private static final int SEGMENTS = 64;

    /** 墙的底（世界最低高度）与顶（建筑上限之上），从哪都看不到"墙的尽头"。 */
    private static final float BOTTOM_Y = -64.0F;
    private static final float TOP_Y = 320.0F;

    /** 蓝色 0x0000FF，透明度 alpha = 0.3（顶点色 0~255 整数，0.3 × 255 ≈ 76）。 */
    private static final int COLOR_R = 0x00;
    private static final int COLOR_G = 0x00;
    private static final int COLOR_B = 0xFF;
    private static final int COLOR_A = (int) (0.3F * 255.0F);

    /** 光照写满格（block 15 | sky 15）：BLOCK 格式要求 UV2 必须写，着色器不会用到它。 */
    private static final int FULL_LIGHT = 0x00F0 | (0x00F0 << 16);

    /**
     * 原版自带的 4x4 纯白纹理。1.20.1 里它<b>不在任何自动加载清单上</b>，
     * 第一次真要画墙时必须自己注册 + 加载（见 {@link #ensureWhiteTextureLoaded()}），
     * 否则绑到的永远是 MissingTexture——这正是旧版墙"隐身"的直接原因。
     */
    public static final ResourceLocation SOLID_WHITE_TEXTURE = new ResourceLocation("textures/misc/white.png");

    /** 白纹理注册状态（只在渲染线程访问）。 */
    private static boolean whiteTextureLoaded = false;

    private static final double TWO_PI = Math.PI * 2.0D;

    /**
     * 墙体图层：白纹理版"信标光束"层。boolean=true 给出 TRANSLUCENT 混合 +
     * 只写颜色不写深度；格式 BLOCK、模式 QUADS。
     *
     * <p><b>[r124 双面墙]</b> 核实过 1.20.1 的 {@code RenderType.BEACON_BEAM} 源码：
     * 它<b>没有</b>调用 {@code setCullState(NO_CULL)}，而 {@code CompositeState.Builder}
     * 的默认值就是开启剔除（{@code cullState = RenderStateShard.CULL}）。
     * 单绕序四边形因此在圈外看是背面、被 GPU 剔除（"单面墙"的直接根因）。
     * 公开 API 无法给 beaconBeam 追加 NO_CULL，所以本类改为<b>每个四边形正反各发一次顶点</b>
     * （外侧面 + 内侧面，法线随朝向翻转）——无论图层开不开剔除，两侧都必然可见。
     */
    private static final RenderType ZONE_WALL = RenderType.beaconBeam(SOLID_WHITE_TEXTURE, true);

    /**
     * [r130] 墙体视距裁剪阈值（世界格）：实时圈边缘到相机的最近距离超过它就整圈跳过。
     * 原版渲染视距上限 32 区块 = 512 格，这里取 1024 再兜住雾外沿——
     * 正常阶段的墙永远不会被误裁；开局超大蓝圈（半径 = 罩住全图 × 2）必然在
     * 阈值外，落地阶段玩家看到的地图上不会再出现几百格外那道"蓝色穹顶"幻影。
     */
    private static final double WALL_CULL_DISTANCE = 1024.0;

    /** 实时圈边缘（环带）到相机的最近水平距离是否已超出可见范围。 */
    private static boolean wallBeyondViewDistance(Vec3 camera,
                                                  double centerX, double centerZ, double radius) {
        double distCam = Math.hypot(camera.x - centerX, camera.z - centerZ);
        if (!Double.isFinite(distCam)) {
            return true;
        }
        // 相机在圆内：最近墙距 = radius - distCam；在圆外：distCam - radius。取绝对值即环带到相机的最短距离。
        return Math.abs(radius - distCam) > WALL_CULL_DISTANCE;
    }

    private ZoneWallRenderer() {
    }

    /**
     * 懒加载纯白纹理：注册进 {@code TextureManager} 并从资源包真正解码上传。
     *
     * <p>{@code getTexture(id)}（单参）对未注册 id 返回 MissingTexture 而不抛异常，
     * 所以这里用 {@code getTexture(id, null)} 的两参兜底版判断"到底注册了没有"。
     * 加载失败静默重试下一帧，绝不打崩渲染线程。
     */
    /** [r132] 白纹理是否已注册加载成功（Ping 光柱共用这张纹理，渲染前查一下）。 */
    public static boolean isWhiteTextureLoaded() {
        return whiteTextureLoaded;
    }

    public static void ensureWhiteTextureLoaded() {
        if (whiteTextureLoaded) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        TextureManager textures = client.getTextureManager();
        if (textures.getTexture(SOLID_WHITE_TEXTURE, null) != null) {
            whiteTextureLoaded = true;
            return;
        }
        SimpleTexture texture = new SimpleTexture(SOLID_WHITE_TEXTURE);
        try {
            texture.load(client.getResourceManager());
        } catch (IOException | RuntimeException e) {
            texture.close();
            return;   // 保持未加载标记，下一帧重试
        }
        textures.register(SOLID_WHITE_TEXTURE, texture);
        whiteTextureLoaded = true;
    }

    /**
     * 渲染入口，签名匹配 {@code WorldRenderEvents.AfterTranslucent#afterTranslucent(WorldRenderContext)}，
     * 因此 {@code BattleRoyaleModClient} 里可继续直接
     * {@code WorldRenderEvents.AFTER_TRANSLUCENT.register(ZoneWallRenderer::render)}。
     */
    public static void render(WorldRenderContext context) {
        if (context == null || context.camera() == null || context.consumers() == null
                || context.matrixStack() == null) {
            return;
        }
        // 需求 4 + 模块六：毒圈数据不在本维度（主城 / 别的地图 / 没数据）就什么都不画。
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null
                || !ClientZoneState.visibleIn(client.level.dimension())) {
            return;
        }
        // 实时半径 = 服务端「收缩起点圆 → 白圈」按总进度 lerp（模块三：客户端自己插值）。
        double radius = ClientZoneState.liveRadius();
        double centerX = ClientZoneState.liveCenterX();
        double centerZ = ClientZoneState.liveCenterZ();
        // [r122-OOM 防护] 任何非有限值 / 非正半径直接整层跳过，杜绝垃圾几何进批次。
        if (!Double.isFinite(radius) || radius <= 0.0D
                || !Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        if (camera == null || !Double.isFinite(camera.x) || !Double.isFinite(camera.y)
                || !Double.isFinite(camera.z)) {
            return;
        }
        // [r130 阶段零] 视距裁剪：实时圈边缘到相机的最近距离超出客户端视程，
        // 整圈墙一个像素都不画。开局蓝圈罩住全图时墙在世界外几百格，画出来只会
        // 是贴着天际线的巨大蓝色"穹顶"幻影；收缩逼近到视程内才自然现身。
        if (wallBeyondViewDistance(camera, centerX, centerZ, radius)) {
            return;
        }
        // 白纹理没注册进 TextureManager 就开画 = 绑到 MissingTexture（旧版全隐身的根源）。
        ensureWhiteTextureLoaded();
        if (!whiteTextureLoaded) {
            return;
        }

        PoseStack stack = context.matrixStack();
        stack.pushPose();
        try {
            // 1.20.1 的 context.matrixStack() 只含相机旋转，手动补平移，顶点写世界坐标。
            stack.translate(-camera.x, -camera.y, -camera.z);
            Matrix4f matrix = stack.last().pose();
            VertexConsumer buffer = context.consumers().getBuffer(ZONE_WALL);

            double step = TWO_PI / SEGMENTS;
            for (int i = 0; i < SEGMENTS; i++) {
                double angle0 = i * step;
                double angle1 = (i + 1) * step;
                float cos0 = (float) Math.cos(angle0);
                float sin0 = (float) Math.sin(angle0);
                float cos1 = (float) Math.cos(angle1);
                float sin1 = (float) Math.sin(angle1);

                float x0 = (float) (centerX + radius * cos0);
                float z0 = (float) (centerZ + radius * sin0);
                float x1 = (float) (centerX + radius * cos1);
                float z1 = (float) (centerZ + radius * sin1);

                // [r124] 外侧面：法线朝圆外，绕序使正面朝向圈外。
                vertex(buffer, matrix, x0, TOP_Y, z0, cos0, sin0);
                vertex(buffer, matrix, x0, BOTTOM_Y, z0, cos0, sin0);
                vertex(buffer, matrix, x1, BOTTOM_Y, z1, cos1, sin1);
                vertex(buffer, matrix, x1, TOP_Y, z1, cos1, sin1);

                // [r124] 内侧面：同一四边形反绕序重发一遍，法线取反朝圆内。
                // beaconBeam 图层默认开启背面剔除（见 ZONE_WALL 注释），只发外侧面时
                // 圈内看是正面、圈外回头看是背面被剔除 = "单面墙"。正反各发一次后
                // 每一面都有属于自己的正面，两侧玩家都必然看得见，与剔除状态无关。
                vertex(buffer, matrix, x1, TOP_Y, z1, -cos1, -sin1);
                vertex(buffer, matrix, x1, BOTTOM_Y, z1, -cos1, -sin1);
                vertex(buffer, matrix, x0, BOTTOM_Y, z0, -cos0, -sin0);
                vertex(buffer, matrix, x0, TOP_Y, z0, -cos0, -sin0);
            }
        } finally {
            stack.popPose();
        }

        // 只提交我们这一层：flush 时图层状态机自己绑定着色器 + 白纹理 + 混合，
        // 画完自动还原（clearRenderState），不影响别的模组与后续地形通道。
        if (context.consumers() instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(ZONE_WALL);
        }
    }

    /**
     * 提交一个顶点。顺序与 {@code DefaultVertexFormat.BLOCK} 完全一致：
     * POSITION → COLOR → UV → UV2(LIGHT) → NORMAL，五个元素一个都不能漏，
     * 少写任何一个 {@code endVertex()} 会抛
     * {@code IllegalStateException: Not filled all elements of the vertex}。
     */
    private static void vertex(VertexConsumer buffer, Matrix4f matrix,
                               float x, float y, float z,
                               float normalX, float normalZ) {
        buffer.vertex(matrix, x, y, z)                       // POSITION
                .color(COLOR_R, COLOR_G, COLOR_B, COLOR_A)   // COLOR
                .uv(0.0F, 0.0F)                              // TEXTURE（白纹理取左上角即可）
                .uv2(FULL_LIGHT)                             // LIGHT（本层着色器不使用）
                .normal(normalX, 0.0F, normalZ)              // NORMAL（朝圆外的水平法线）
                .endVertex();
    }
}
