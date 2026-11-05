package com.paimeng.battleroyale.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 空投箱的 ASCII 路径 quad 模型加载器；运行时不读取 Blockbench 源文件。 */
final class AirdropModel {
    static final ResourceLocation TEXTURE = new ResourceLocation(
            BattleRoyaleMod.MOD_ID, "textures/entity/pubg_airdrop.png");
    private static final String RESOURCE = "assets/" + BattleRoyaleMod.MOD_ID
            + "/entity_models/airdrop_chest.json";
    private static volatile AirdropModel instance;
    private final float[] positions;
    private final float[] uvs;
    private final float[] normals;
    private final int quadCount;

    private AirdropModel(float[] positions, float[] uvs, float[] normals, int quadCount) {
        this.positions = positions;
        this.uvs = uvs;
        this.normals = normals;
        this.quadCount = quadCount;
    }

    static AirdropModel get() {
        AirdropModel local = instance;
        if (local == null) {
            synchronized (AirdropModel.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    boolean isEmpty() { return quadCount == 0; }

    void draw(Matrix4f pose, Matrix3f normal, VertexConsumer out, int light) {
        for (int q = 0; q < quadCount; q++) {
            int p = q * 12, uv = q * 8, n = q * 3;
            for (int v = 0; v < 4; v++) {
                int pi = p + v * 3, ui = uv + v * 2;
                out.vertex(pose, positions[pi], positions[pi + 1], positions[pi + 2])
                        .color(255, 255, 255, 255)
                        .uv(uvs[ui], uvs[ui + 1])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(light)
                        .normal(normal, normals[n], normals[n + 1], normals[n + 2])
                        .endVertex();
            }
        }
    }

    private static AirdropModel load() {
        try {
            Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(BattleRoyaleMod.MOD_ID);
            Optional<Path> path = mod.isPresent() ? mod.get().findPath(RESOURCE) : Optional.empty();
            if (path.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 找不到 ASCII 空投模型资源 {}", RESOURCE);
                return empty();
            }
            JsonObject root;
            try (var reader = Files.newBufferedReader(path.get())) {
                root = new JsonParser().parse(reader).getAsJsonObject();
            }
            JsonArray quads = root.getAsJsonArray("quads");
            float[] positions = new float[quads.size() * 12];
            float[] uvs = new float[quads.size() * 8];
            float[] normals = new float[quads.size() * 3];
            for (int i = 0; i < quads.size(); i++) {
                JsonObject q = quads.get(i).getAsJsonObject();
                fill(q.getAsJsonArray("p"), positions, i * 12);
                fill(q.getAsJsonArray("u"), uvs, i * 8);
                fill(q.getAsJsonArray("n"), normals, i * 3);
            }
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] ASCII 空投模型加载完成：{} 个四边形，资源 {}",
                    quads.size(), RESOURCE);
            return new AirdropModel(positions, uvs, normals, quads.size());
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 加载空投模型失败，暂不渲染模型", t);
            return empty();
        }
    }

    private static AirdropModel empty() {
        return new AirdropModel(new float[0], new float[0], new float[0], 0);
    }

    private static void fill(JsonArray source, float[] target, int offset) {
        for (int i = 0; i < source.size(); i++) {
            target[offset + i] = source.get(i).getAsFloat();
        }
    }
}
