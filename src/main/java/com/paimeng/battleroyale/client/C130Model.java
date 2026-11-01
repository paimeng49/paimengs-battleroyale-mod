package com.paimeng.battleroyale.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * [BR-C130] C-130 的运行时模型：一组带逐面 UV 的四边形（quad）。
 *
 * <p>来源是用户提供的 Blockbench 模型（free 格式，74 个立方体 / 444 个面，
 * 每面 UV 手工排布在 128x128 贴图上）。原版 ModelPart 只能表达
 * “自动箱式 UV”，表达不了这种逐面自定义 UV，所以用 tools/bake_c130.py
 * 在构建前把它烘焙成 {@code assets/battleroyale/entity_models/c130.json}
 * （顶点已换算成方块单位、按实体中心对中、整体放大 3 倍），
 * 渲染时用公开的 VertexConsumer API 直接吐四边形——
 * [BR-R3] 1.20.1 起 consumer 由 {@code WorldRenderContext#consumers()}（原版每帧的
 * MultiBufferSource）按 RenderType 发放，本类不再自持 BufferBuilder，
 * 也零额外前置模组依赖（不引入 GeckoLib）。
 */
@Environment(EnvType.CLIENT)
public final class C130Model {
    /** 实体贴图（从 bbmodel 内嵌数据提取）。 */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation(BattleRoyaleMod.MOD_ID, "textures/entity/c130.png");

    private static final String RESOURCE =
            "assets/" + BattleRoyaleMod.MOD_ID + "/entity_models/c130.json";

    private static final int FLOATS_PER_QUAD_P = 12; // 4 顶点 x y z
    private static final int FLOATS_PER_QUAD_U = 8;  // 4 顶点 u v
    private static final int FLOATS_PER_QUAD_N = 3;  // 面法线

    private final float[] pos;
    private final float[] uv;
    private final float[] normal;
    private final int quadCount;

    private static volatile C130Model instance;

    private C130Model(float[] pos, float[] uv, float[] normal, int quadCount) {
        this.pos = pos;
        this.uv = uv;
        this.normal = normal;
        this.quadCount = quadCount;
    }

    public static C130Model get() {
        C130Model local = instance;
        if (local == null) {
            synchronized (C130Model.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    public boolean isEmpty() {
        return quadCount == 0;
    }

    /** 把所有四边形画进 vertex consumer（模型坐标已是方块单位）。 */
    public void draw(Matrix4f modelMatrix, Matrix3f normalMatrix, VertexConsumer consumer, int light) {
        draw(modelMatrix, normalMatrix, consumer, light, OverlayTexture.NO_OVERLAY, false);
    }

    /**
     * [BR-R3] 1.20.1 标准管线入口：由调用方从 {@code MultiBufferSource.getBuffer(RenderType)}
     * 拿到 VertexConsumer，本方法只做顶点写入。
     *
     * <p>顶点写入用的是 1.19+ 的新签名族：
     * {@code vertex(Matrix4f, x, y, z)}（矩阵在 CPU 侧就乘进顶点位置，不再要求
     * BufferBuilder 处于 begin 状态）、{@code overlayCoords(int)} / {@code uv2(int)}
     * 打包单参版、{@code normal(Matrix3f, nx, ny, nz)}（法线由调用方 PoseStack 的
     * normal 矩阵变换后写入，光影包按它算受光面）。
     *
     * @param overlay 纹理叠加（damage/warning 闪烁层）；世界覆盖层传 {@code NO_OVERLAY}。
     */
    public void draw(Matrix4f modelMatrix, Matrix3f normalMatrix, VertexConsumer consumer,
                     int light, int overlay) {
        draw(modelMatrix, normalMatrix, consumer, light, overlay, false);
    }

    /**
     * @param onlyBackFaces 半透明“幽灵座舱”模式：只画背对摄像机的面。
     *                      玩家被坐标锁定在机身内部时，用这个模式画飞机，
     *      近处（朝向摄像机一侧）的蒙皮被跳过，视线可以穿过机身看到地面，
     *      远处机壳仍隐约可见。面的朝向由顶点绕序在投影空间的有向面积判定。
     */
    public void draw(Matrix4f modelMatrix, Matrix3f normalMatrix, VertexConsumer consumer,
                     int light, int overlay, boolean onlyBackFaces) {
        for (int q = 0; q < quadCount; q++) {
            int pBase = q * FLOATS_PER_QUAD_P;
            int uBase = q * FLOATS_PER_QUAD_U;
            int nBase = q * FLOATS_PER_QUAD_N;
            if (onlyBackFaces && isFrontFacing(modelMatrix, pBase)) {
                continue;
            }
            float nx = normal[nBase];
            float ny = normal[nBase + 1];
            float nz = normal[nBase + 2];
            for (int v = 0; v < 4; v++) {
                int pi = pBase + v * 3;
                int ui = uBase + v * 2;
                consumer.vertex(modelMatrix, pos[pi], pos[pi + 1], pos[pi + 2])
                        .color(255, 255, 255, onlyBackFaces ? 70 : 255)
                        .uv(uv[ui], uv[ui + 1])
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(normalMatrix, nx, ny, nz)
                        .endVertex();
            }
        }
    }

    /**
     * 投影空间有向面积 > 0 表示该面正对摄像机（顶点从外侧看为逆时针）。
     * 只做模型矩阵的仿射部分（等价于沿视线方向正交投影）来判定符号，不做透视除法。
     */
    private boolean isFrontFacing(Matrix4f m, int pBase) {
        Vector4f v0 = new Vector4f(pos[pBase], pos[pBase + 1], pos[pBase + 2], 1.0F);
        v0.mul(m);
        Vector4f v1 = new Vector4f(pos[pBase + 3], pos[pBase + 4], pos[pBase + 5], 1.0F);
        v1.mul(m);
        Vector4f v2 = new Vector4f(pos[pBase + 6], pos[pBase + 7], pos[pBase + 8], 1.0F);
        v2.mul(m);
        float x0 = v0.x;
        float y0 = v0.y;
        float x1 = v1.x;
        float y1 = v1.y;
        float x2 = v2.x;
        float y2 = v2.y;
        return (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0) > 0.0F;
    }

    private static C130Model load() {
        try {
            Optional<ModContainer> container =
                    FabricLoader.getInstance().getModContainer(BattleRoyaleMod.MOD_ID);
            Optional<Path> found = container.isPresent() ? container.get().findPath(RESOURCE)
                    : Optional.empty();
            if (found.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-C130] 找不到烘焙模型资源 {}，飞机将不可见", RESOURCE);
                return new C130Model(new float[0], new float[0], new float[0], 0);
            }
            JsonObject root;
            try (var reader = Files.newBufferedReader(found.get())) {
                root = new JsonParser().parse(reader).getAsJsonObject();
            }
            JsonArray quads = root.getAsJsonArray("quads");
            int n = quads.size();
            float[] pos = new float[n * FLOATS_PER_QUAD_P];
            float[] uv = new float[n * FLOATS_PER_QUAD_U];
            float[] normal = new float[n * FLOATS_PER_QUAD_N];
            for (int i = 0; i < n; i++) {
                JsonObject q = quads.get(i).getAsJsonObject();
                fill(q.getAsJsonArray("p"), pos, i * FLOATS_PER_QUAD_P);
                fill(q.getAsJsonArray("u"), uv, i * FLOATS_PER_QUAD_U);
                fill(q.getAsJsonArray("n"), normal, i * FLOATS_PER_QUAD_N);
            }
            BattleRoyaleMod.LOGGER.info("[BR-C130] C-130 烘焙模型加载完成：{} 个四边形", n);
            return new C130Model(pos, uv, normal, n);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-C130] 加载 C-130 烘焙模型失败，飞机将不可见", t);
            return new C130Model(new float[0], new float[0], new float[0], 0);
        }
    }

    private static void fill(JsonArray src, float[] dst, int offset) {
        for (int i = 0; i < src.size(); i++) {
            dst[offset + i] = src.get(i).getAsFloat();
        }
    }
}
