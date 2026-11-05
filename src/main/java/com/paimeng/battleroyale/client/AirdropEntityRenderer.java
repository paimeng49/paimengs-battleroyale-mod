package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.paimeng.battleroyale.entity.AirdropEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** 空投箱实体渲染器：加载 ASCII 路径下已烘焙的 quad JSON，不让 MC 直接解析 bbmodel。 */
public final class AirdropEntityRenderer extends EntityRenderer<AirdropEntity> {
    private static final RenderType LAYER = RenderType.entityCutoutNoCull(AirdropModel.TEXTURE);
    private final AirdropModel model;

    public AirdropEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.75F;
        this.model = AirdropModel.get();
    }

    @Override
    public void render(AirdropEntity entity, float yaw, float tickDelta, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        if (model.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.0D, 0.02D, 0.0D);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((entity.tickCount + tickDelta) * 1.2F));
        VertexConsumer consumer = buffers.getBuffer(LAYER);
        model.draw(pose.last().pose(), pose.last().normal(), consumer, packedLight);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(AirdropEntity entity) {
        return AirdropModel.TEXTURE;
    }
}
