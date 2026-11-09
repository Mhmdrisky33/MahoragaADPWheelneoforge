package com.mahoraga.client.render;

import com.mahoraga.MahoragaMod;
import com.mahoraga.entity.SlashProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

/**
 * 剑气渲染器 —— **几乎原样搬自原模组的 {@code DismantleRenderer}**。
 *
 * 它做的事很简单：把那张 32×16 的斩击贴图，按飞行方向摊成一片"刀身"，
 * 再按 roll 自转一点。
 *
 * ⚠️ **贴图不要自己重画**。那张 `dismantle.png` 是**一条横着的黑色实线 + 上下两道白晕**
 * （32×16 里只有中间 3 行有像素），拉长之后就是"解"的那道斩痕 ——
 * 人家一度觉得"黑乎乎的看着像贴图丢了"就重画成青白新月，那是错的，已经换回原图。
 *
 * 渲染方式也照抄原版：普通半透明（**不是** emissive），
 * 亮度靠 {@code uv2(LightTexture.FULL_SKY)} 直接拉满，所以暗处也走得通。
 */
public class SlashRenderer extends EntityRenderer<SlashProjectile> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "textures/entity/slash.png");

    public SlashRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(@NotNull SlashProjectile entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0.0F, entity.getBbHeight() / 2.0F, 0.0F);

        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XN.rotationDegrees(pitch - 90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.getRoll()));

        float factor = (float) entity.getLength() / SlashProjectile.MAX_LENGTH;
        poseStack.scale(1.0F, 1.0F, 0.1F + (0.1F * factor));

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
        Matrix4f pose = poseStack.last().pose();
        float half = entity.getLength() / 2.0F;

        vertex(consumer, pose, -half, -1.0F, 0.0F, 0.0F);
        vertex(consumer, pose, -half, 1.0F, 0.0F, 1.0F);
        vertex(consumer, pose, half, 1.0F, 1.0F, 1.0F);
        vertex(consumer, pose, half, -1.0F, 1.0F, 0.0F);

        poseStack.popPose();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose,
                               float x, float z, float u, float v) {
        consumer.vertex(pose, x, 0.0F, z)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_SKY)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    protected int getBlockLightLevel(@NotNull SlashProjectile entity, @NotNull BlockPos pos) {
        return 15;
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull SlashProjectile entity) {
        // 原版就是这么写的：渲染时用的是 TEXTURE，这个 getter 只是别让别处去加载独立贴图
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
