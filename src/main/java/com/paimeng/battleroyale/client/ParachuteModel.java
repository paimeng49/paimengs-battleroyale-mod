package com.paimeng.battleroyale.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * [BR-PARA] 降落伞模型：{@code EntityModel<AbstractClientPlayer>}，
 * 几何来自 {@code assets/battleroyale/entity_models/parachute.json}
 * （由 tools/bake_parachute.py 从用户上传的 降落伞.bbmodel 导出，保留骨骼层级）。
 *
 * <h3>为什么是 quad 列表而不是 ModelPart root</h3>
 * 这份伞模型 441 个立方体全是 <b>逐面自定义 UV</b>（每个面单独采样贴图上一块
 * 12x12 的噪点色块，伞盖靠它做红白瓣的明暗过渡）。1.18.2 的
 * {@code ModelPart$Cuboid} 构造里六面 UV 是由 {@code textureX/textureY + 长宽高}
 * 按“自动箱式展开”算死的（见 ModelPart$Cuboid 的 {@code j=u+sizeZ} 那一段），
 * 表达不了自定义 UV；走 {@code EntityModelLoader.getModelPart(layer)} 会得到
 * 一张采错的贴图。所以沿用本项目 C-130 已验证的做法：<b>骨骼层级在加载时做矩阵
 * 叠加，几何烘成带逐面 UV 的四边形</b>，用公开的 {@link VertexConsumer} API 吐顶点。
 * 全程不碰 RenderSystem / BufferBuilder，光照与 overlay 由调用方（原版）传进来。
 *
 * <p>几何解析（含那套行主序 4×4）全部在 {@link ParachuteGeometry}，本类只管
 * 读资源和提交顶点。<b>这里刻意不使用 MC 的矩阵类做加载期变换</b>：1.18.2 的
 * {@code net.minecraft.util.math.Matrix4f} 是<b>零矩阵</b>而不是单位矩阵，
 * 上一版踩了这个坑导致“441 个立方体 / 0 个四边形”，伞永远看不见
 * （1.20.1 起 MC 直接用 org.joml 矩阵、new 出来是单位矩阵，但加载期已烘成
 * float 数组，这条历史教训不再有影响，详见 {@link ParachuteGeometry} 注释）。
 *
 * <h3>[BR-R3] 1.20.1 顶点写入签名</h3>
 * {@link #renderToBuffer} 走的是 1.19+ 的新 API 族：
 * {@code vertex(Matrix4f, x, y, z)}（顶点位置在 CPU 侧乘矩阵）、
 * {@code overlayCoords(int)} / {@code uv2(int)}（打包 overlay/light 单参版）、
 * {@code normal(Matrix3f, nx, ny, nz)}（法线经 PoseStack normal 矩阵变换后写入）。
 * 光影包读的正是这组数据，所以伞的受光和玩家身体一致。
 *
 * <h3>坐标约定（重要）</h3>
 * 模型本地坐标 = 方块单位、<b>+Y 向上</b>、原点在<b>伞绳汇聚点</b>
 * （Blockbench 里的 (0,-64,0)，也就是背带/挂钩处）。
 * 原版在跑 feature 时矩阵已经 {@code scale(-1,-1,1)} 过（Y 轴朝下），
 * 翻转由 {@link ParachuteFeatureRenderer} 负责，本类只管“向上为正”的几何。
 * 顶点绕序按“从外侧看逆时针”输出，与原版 ModelPart 一致，
 * 所以 {@code entity_cutout}（带背面剔除）不会把伞面剔掉。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteModel extends EntityModel<AbstractClientPlayer> {

    /** 实体贴图（从 降落伞.bbmodel 内嵌数据提取）。 */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "textures/entity/parachute.png");

    /** Blockbench 导出的骨骼模型（逐面 UV）。 */
    private static final String RESOURCE =
            "assets/" + BattleRoyaleMod.MOD_ID + "/entity_models/parachute.json";

    private static final RenderType LAYER = RenderType.entityCutout(TEXTURE);

    /** 几何全局只解析一次（玩家渲染器会被创建多次，441 立方体没必要重复解析）。 */
    private static volatile ParachuteGeometry cached;

    private final ParachuteGeometry geometry;

    public ParachuteModel() {
        super(id -> LAYER);
        this.geometry = geometry();
    }

    /** 本模型用的图层（cutout：贴图有透明区域，且带背面剔除省一半光栅化）。 */
    public static RenderType layer() {
        return LAYER;
    }

    public boolean isEmpty() {
        return geometry.isEmpty();
    }

    /** 静态挂在头顶：不做任何摆动/开合动画（需求明确禁止多余动画）。 */
    @Override
    public void setupAnim(AbstractClientPlayer entity, float limbAngle, float limbDistance,
                          float animationProgress, float headYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack matrices, VertexConsumer vertices, int light, int overlay,
                       float red, float green, float blue, float alpha) {
        if (geometry.isEmpty()) {
            return;
        }
        Matrix4f model = matrices.last().pose();
        Matrix3f normal = matrices.last().normal();
        float[] pos = geometry.pos;
        float[] uv = geometry.uv;
        float[] nrm = geometry.normal;
        int r = (int) (red * 255.0F);
        int gr = (int) (green * 255.0F);
        int b = (int) (blue * 255.0F);
        int a = (int) (alpha * 255.0F);
        for (int q = 0; q < geometry.quadCount; q++) {
            int pBase = q * ParachuteGeometry.FLOATS_PER_QUAD_P;
            int uBase = q * ParachuteGeometry.FLOATS_PER_QUAD_U;
            int nBase = q * ParachuteGeometry.FLOATS_PER_QUAD_N;
            float nx = nrm[nBase];
            float ny = nrm[nBase + 1];
            float nz = nrm[nBase + 2];
            for (int v = 0; v < 4; v++) {
                int pi = pBase + v * 3;
                int ui = uBase + v * 2;
                vertices.vertex(model, pos[pi], pos[pi + 1], pos[pi + 2])
                        .color(r, gr, b, a)
                        .uv(uv[ui], uv[ui + 1])
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(normal, nx, ny, nz)
                        .endVertex();
            }
        }
    }

    // ------------------------------------------------------------------
    // 资源读取
    // ------------------------------------------------------------------

    private static ParachuteGeometry geometry() {
        ParachuteGeometry local = cached;
        if (local == null) {
            synchronized (ParachuteModel.class) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    private static ParachuteGeometry load() {
        try {
            Optional<ModContainer> container =
                    FabricLoader.getInstance().getModContainer(BattleRoyaleMod.MOD_ID);
            Optional<Path> found = container.isPresent()
                    ? container.get().findPath(RESOURCE) : Optional.empty();
            if (found.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-PARA] 找不到降落伞模型资源 {}，跳伞时不会显示伞", RESOURCE);
                return ParachuteGeometry.EMPTY;
            }
            // [BR-PARA2] 运行时诊断：打印实际解析到的文件（开发环境是源码目录，
            // 游戏里是 jar 内路径），配合下面的四边形计数定位“资源没打进去”。
            BattleRoyaleMod.LOGGER.info("[BR-PARA] 正在解析模型文件: {}", found.get());
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(found.get())) {
                root = new JsonParser().parse(reader).getAsJsonObject();
            }
            ParachuteGeometry g = ParachuteGeometry.parse(root);
            BattleRoyaleMod.LOGGER.info("[BR-PARA] 降落伞模型加载完成：{} 根骨骼 / {} 个立方体 / "
                            + "{} 个四边形（兜底 UV 面数 {}）",
                    g.boneCount, g.cubeCount, g.quadCount, g.fallbackFaces);
            if (g.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-PARA] 四边形为 0：parachute.json 结构可能不对，"
                        + "跳伞时不会显示伞");
            }
            return g;
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-PARA] 解析降落伞模型失败，跳伞时不会显示伞", t);
            return ParachuteGeometry.EMPTY;
        }
    }
}
