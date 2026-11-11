package com.mahoraga.client.render;

import com.mahoraga.MahoragaMod;
import com.mahoraga.entity.WorldSlashProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

/**
 * 「空间斩」的渲染器 —— **几乎原样搬自原模组的 {@code WorldSlashRenderer}**。
 *
 * 就是把那张 32×16 的 `world_slash.png`（一条横着的长斩痕）
 * 按飞行方向摊成一个**巨大的四边形**，再按 roll 自转。
 *
 * ==================== 为什么原版看着是"一道光" ====================
 *
 * 原模组用的是自家的 `JJKRenderTypes.glow()`：
 * `RENDERTYPE_ENERGY_SWIRL_SHADER` + **ADDITIVE_TRANSPARENCY**（加色混合）+ 不剔除背面。
 *
 * 🔴 **原版那个是私有类，抄不过来 —— 但原版的 `RenderType.energySwirl()` 一模一样**
 * （同一个 shader、同样加色、同样不剔除），直接用它，效果一致。
 * 「剑气」那边用的是普通半透明（`entityTranslucent`），**两者别搞混** ——
 * 剑气是"一道暗色斩痕"，空间斩是"一道发光的白刃"。
 */
public class WorldSlashRenderer extends EntityRenderer<WorldSlashProjectile> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "textures/entity/world_slash.png");

    public WorldSlashRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(@NotNull WorldSlashProjectile entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight) {

        poseStack.pushPose();
        poseStack.translate(0.0F, entity.getBbHeight() / 2.0F, 0.0F);

        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XN.rotationDegrees(pitch - 90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.getRoll()));

        // 原版再按 power 把宽度放大。我们固定宽度，只让"厚度"跟着刀身长度走一点点。
        float factor = (float) entity.getLength() / WorldSlashProjectile.MAX_LENGTH;
        poseStack.scale(1.0F, 1.0F, 0.2F + 0.2F * factor);

        VertexConsumer consumer = buffer.getBuffer(RenderType.energySwirl(TEXTURE, 0.0F, 0.0F));
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
        consumer.addVertex(pose, x, 0.0F, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_SKY)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    @Override
    protected int getBlockLightLevel(@NotNull WorldSlashProjectile entity, @NotNull BlockPos pos) {
        return 15;
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull WorldSlashProjectile entity) {
        return TEXTURE;
    }
}
