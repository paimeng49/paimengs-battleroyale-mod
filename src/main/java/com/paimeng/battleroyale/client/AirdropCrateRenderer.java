package com.paimeng.battleroyale.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.paimeng.battleroyale.entity.AirdropCrateEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** 蓝色伞盖、红色箱体和发光吊绳的空投实体渲染器。 */
public final class AirdropCrateRenderer extends EntityRenderer<AirdropCrateEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("battleroyale", "textures/entity/airdrop_crate.png");
    private static final int LIGHT = 0x00F000F0;

    public AirdropCrateRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.35F;
    }

    @Override
    public void render(AirdropCrateEntity entity, float yaw, float tickDelta, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        pose.pushPose();
        pose.translate(0.0D, 1.25D, 0.0D);
        VertexConsumer solid = buffers.getBuffer(RenderType.entityCutout(TEXTURE));
        cube(pose, solid, -0.55F, 0.0F, -0.55F, 0.55F, 0.65F, 0.55F, 0.92F, 0.08F, 0.08F);
        VertexConsumer glow = buffers.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));
        canopy(pose, glow);
        pose.popPose();
        super.render(entity, yaw, tickDelta, pose, buffers, packedLight);
    }

    private static void canopy(PoseStack pose, VertexConsumer v) {
        float y = 2.65F;
        quad(pose, v, -1.6F, y, -0.8F, 1.6F, y, -0.8F, 1.05F, 0.08F, 0.82F);
        quad(pose, v, 1.6F, y, -0.8F, 1.6F, y, 0.8F, 1.05F, 0.08F, 0.82F);
        quad(pose, v, 1.6F, y, 0.8F, -1.6F, y, 0.8F, 1.05F, 0.08F, 0.82F);
        quad(pose, v, -1.6F, y, 0.8F, -1.6F, y, -0.8F, 1.05F, 0.08F, 0.82F);
        line(pose, v, -0.8F, 0.6F, -0.45F, -1.35F, y, -0.65F);
        line(pose, v, 0.8F, 0.6F, -0.45F, 1.35F, y, -0.65F);
        line(pose, v, -0.8F, 0.6F, 0.45F, -1.35F, y, 0.65F);
        line(pose, v, 0.8F, 0.6F, 0.45F, 1.35F, y, 0.65F);
    }

    private static void cube(PoseStack p, VertexConsumer v, float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b) {
        quad(p, v, x0,y0,z0,x1,y0,z0,r,g,b); quad(p,v,x1,y0,z0,x1,y0,z1,r,g,b);
        quad(p, v, x1,y0,z1,x0,y0,z1,r,g,b); quad(p,v,x0,y0,z1,x0,y0,z0,r,g,b);
        quad(p, v, x0,y1,z0,x1,y1,z0,r,g,b); quad(p,v,x1,y1,z0,x1,y1,z1,r,g,b);
        quad(p, v, x1,y1,z1,x0,y1,z1,r,g,b); quad(p,v,x0,y1,z1,x0,y1,z0,r,g,b);
    }

    private static void quad(PoseStack p, VertexConsumer v, float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float b) {
        PoseStack.Pose pose = p.last(); Matrix4f m = pose.pose(); Matrix3f n = pose.normal();
        v.vertex(m,x0,y0,z0).color(r,g,b,1).uv(0,0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LIGHT).normal(n,0,1,0).endVertex();
        v.vertex(m,x1,y1,z1).color(r,g,b,1).uv(1,0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LIGHT).normal(n,0,1,0).endVertex();
        v.vertex(m,x1,y1,z1+0.01F).color(r,g,b,1).uv(1,1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LIGHT).normal(n,0,1,0).endVertex();
        v.vertex(m,x0,y0,z0+0.01F).color(r,g,b,1).uv(0,1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LIGHT).normal(n,0,1,0).endVertex();
    }

    private static void line(PoseStack p, VertexConsumer v, float x0,float y0,float z0,float x1,float y1,float z1) {
        quad(p,v,x0-0.025F,y0,z0-0.025F,x1+0.025F,y1,z1+0.025F,0.25F,0.8F,1.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(AirdropCrateEntity entity) { return TEXTURE; }
}
