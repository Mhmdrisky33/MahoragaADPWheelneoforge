package com.mahoraga.client.render;

import com.mahoraga.MahoragaMod;
import com.mahoraga.entity.WheelEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** 把法轮画在拥有者头顶：跟随朝向、按体型缩放。 */
public class WheelRenderer extends GeoEntityRenderer<WheelEntity> {
    public WheelRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "wheel")));
        this.addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public void preRender(PoseStack poseStack, WheelEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay,
                          int renderColor) {
        LivingEntity owner = animatable.getOwner();

        if (owner == null) {
            // 主人解析不出来的时候**彻底藏掉**，不要原地画一个巨大的轮子。
            // （以前这里直接 return，GeckoLib 会继续按原始比例渲染 → 看起来"轮子不见了/变形了"）
            poseStack.scale(0.0F, 0.0F, 0.0F);
            return;
        }

        // Lower the wheel slightly so it sits close above the owner's head.
        poseStack.translate(0.0F, animatable.getBbHeight() / 2.0F - 0.22F, 0.0F);

        float yaw = Mth.lerp(partialTick, owner.yBodyRotO, owner.yBodyRot);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));

        // 按体型缩放：主人越宽，轮子越大（1.0 是满尺寸）
        float scale = Math.max(0.2F, owner.getBbWidth());
        poseStack.scale(scale, scale, scale);

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, renderColor);
    }
}
