package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.model.PlayerModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.effect.MobEffects;
import org.joml.Vector3f;

/**
 * [BR-PARA][BR-R3] 跳伞降落伞：挂在玩家渲染器上的 {@link RenderLayer}
 * （1.20.1 官方映射把 1.18.2 的 FeatureRenderer 改名成 RenderLayer，注册回调
 * {@code LivingEntityFeatureRendererRegistrationCallback} 不变，见
 * {@link BattleRoyaleModClient}，本类<b>不注入</b>任何
 * {@code PlayerRenderer#render}，也不碰 RenderSystem / BufferBuilder。
 *
 * <h3>RenderLayer 拿到的矩阵空间（1.20.1 反编译原版 LivingEntityRenderer#render 复核）</h3>
 * {@code LivingEntityRenderer#render} 在遍历 layers 之前依次做过：
 * <pre>
 *   setupTransforms(...)                        // 180 - bodyYaw 旋转（跟随玩家转身）
 *   poseStack.scale(-1, -1, 1);                 // 原版模型是 Y 轴向下的
 *   poseStack.scale(scale, scale, scale);       // 实体体型缩放（玩家 = 1）
 *   poseStack.translate(0, -1.501, 0);          // 原点抬到脚底上方 1.501 格（脖子）
 * </pre>
 * 也就是说 layer 空间原点在“脖子”，而且 <b>+Y 朝下</b>：直接写
 * {@code translate(0, 2, 0)} 会把伞往<b>地下</b>挪 2 格。这里先用一次
 * {@code rotateX(180°)}（纯旋转，行列式为 +1，因此<b>不会</b>翻转顶点绕序，
 * 带背面剔除的 {@code entity_cutout} 才不会把伞面整个剔掉）把 Y 轴翻回向上，
 * 再把原点移回脚底并按 {@link #CHUTE_HEIGHT} 抬高。
 * 此后模型本地坐标 = “离脚底多少格、向上为正”，改数字所见即所得。
 * （1.20.1 与 1.18.2 在这一段完全同构，翻正逻辑照旧成立。）
 *
 * <p>光照 {@code light} 与顶点提交口 {@code vertexConsumers} 全部用原版传进来的，
 * 所以伞的明暗跟玩家身体一致，光影包也能正常打光。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteFeatureRenderer
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** 伞绳汇聚点（背带处）离脚底的高度，单位=格。调大 = 整把伞往上移。 */
    private static final double CHUTE_HEIGHT = 1.15D;
    /** 整伞缩放：1.0 = Blockbench 原尺寸（伞盖直径约 4.9 格），0.5 ≈ 2.4 格。 */
    private static final float CHUTE_SCALE = 0.5F;
    /** 原版把 feature 原点放在脚底上方 1.501 格（translate(0,-1.501,0)），需要抵消。 */
    private static final double VANILLA_ORIGIN_Y = 1.501D;

    private final ParachuteModel model;

    /**
     * [BR-R3] 1.20.1 的 {@code RenderLayer} 构造收 {@code RenderLayerParent}
     * （{@code LivingEntityRenderer}/{@code PlayerRenderer} 本身就实现了它）。
     * 没有 1.19.3+ 才有的 {@code context.getModelPart(EntityModelLayer)} 用法，
     * 模型几何由 {@link ParachuteModel} 自己从 parachute.json 解析。
     */
    public ParachuteFeatureRenderer(
            net.minecraft.client.renderer.entity.RenderLayerParent<
                    AbstractClientPlayer, net.minecraft.client.model.PlayerModel<AbstractClientPlayer>> context) {
        super(context);
        this.model = new ParachuteModel();
    }

    /** [BR-PARA-DEBUG] 其他玩家降落伞渲染状态日志节流：UUID → 上次打印时间戳。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_LOG_MS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_MS = 2000L;

    @Override
    public void render(PoseStack matrices, MultiBufferSource vertexConsumers, int light,
                       AbstractClientPlayer player, float limbAngle, float limbDistance,
                       float tickDelta, float animationProgress, float headYaw, float headPitch) {
        if (this.model.isEmpty()) {
            return;
        }
        // [BR-PARA-SYNC] 双通道判定：模组自己的 parachute_state 广播包（对同维度
        // 所有客户端发，最权威），或原版缓降药效（兜底，防状态包丢失窗口）。
        // 任一为真且没落地就画伞——不再只依赖药效是否同步到他人客户端。
        boolean synced = ClientParachuteState.isParachuting(player.getUUID());
        boolean effected = player.hasEffect(MobEffects.SLOW_FALLING);
        boolean show = (synced || effected) && !player.onGround() && !player.isInvisible();

        AbstractClientPlayer self = net.minecraft.client.Minecraft.getInstance().player;
        if (self == null || !self.getUUID().equals(player.getUUID())) {
            // [BR-PARA-DEBUG] 只给"其他玩家"打日志：确认是没进渲染循环（完全没有本行）、
            // 还是状态没同步（synced/effected 为 false）。每人每 2 秒一条，防刷屏。
            if (synced || effected || player.isInvisible()) {
                long now = System.currentTimeMillis();
                Long last = LAST_LOG_MS.get(player.getUUID());
                if (last == null || now - last >= LOG_INTERVAL_MS) {
                    LAST_LOG_MS.put(player.getUUID(), now);
                    BattleRoyaleMod.LOGGER.info(
                            "[BR-PARA] 他人降落伞 uuid={} synced={} hasSlowFalling={} onGround={} invisible={} -> {}",
                            player.getUUID(), synced, effected, player.onGround(),
                            player.isInvisible(), show ? "渲染" : "跳过");
                }
            } else {
                LAST_LOG_MS.remove(player.getUUID());
            }
        }

        if (!show) {
            return;
        }

        matrices.pushPose();
        // Y 轴翻回向上（纯旋转，不破坏绕序）
        matrices.mulPose(new org.joml.Quaternionf().rotateX((float) Math.toRadians(180.0F)));
        // 原点从“脖子”移回脚底，再按需要抬高
        matrices.translate(0.0D, CHUTE_HEIGHT - VANILLA_ORIGIN_Y, 0.0D);
        matrices.scale(CHUTE_SCALE, CHUTE_SCALE, CHUTE_SCALE);
        // 复用原版 vertexConsumers / light：光影、阴影、区块距离都跟玩家本体一致
        this.model.renderToBuffer(matrices, vertexConsumers.getBuffer(ParachuteModel.layer()),
                light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        matrices.popPose();
    }
}
