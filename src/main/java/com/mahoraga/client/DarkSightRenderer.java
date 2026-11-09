package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Set;

/**
 * **黑暗透视**的轮廓渲染 —— 抄 Re:Zero 智慧之书那套「生命之视」。
 *
 * 原来的做法是给周围生物挂原版 `GLOWING`：一个死白色的轮廓，而且**全服可见**。
 * 现在改成自己画 ——
 *
 * 1. 把 {@link ClientDarkSight} 里的实体**再渲染一遍**到一个离屏缓冲
 *    （`OutlineBufferSource`，就是原版画发光轮廓用的那套）；
 * 2. 按类别**换颜色**：敌对红、被动绿、玩家白、精英金；
 * 3. 原版的后处理会把这个缓冲合成到主画面上 —— 于是**隔着墙也能看见**。
 *
 * 🔴 **后处理必须自己催一次**（{@link OutlineEffectSupport}）：
 * 原版只在自己那套流程里 process 一次，而那时候我们还没画完。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, value = Dist.CLIENT)
public final class DarkSightRenderer {
    private DarkSightRenderer() {
    }

    // 敌对：红（一眼看出哪个能咬人）
    private static final int[] HOSTILE = {255, 64, 64, 255};
    // 中立 / 被动：绿（不构成威胁）
    private static final int[] PASSIVE = {64, 255, 96, 255};
    // 玩家：白
    private static final int[] PLAYER = {255, 255, 255, 255};
    // 精英（最大生命 ≥ 100 的那批 —— 真值得"解析"的目标）：金
    private static final int[] ELITE = {255, 200, 40, 255};

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 实体都画完了再补轮廓 —— 这时候深度信息是齐的
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (ClientDarkSight.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        float partialTick = event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        // 先把"待会儿要单独 process 一次后处理"这件事记上
        OutlineEffectSupport.request();
        OutlineBufferSource outline = mc.renderBuffers().outlineBufferSource();

        // 顺序有讲究：精英最后画 —— 同一只生物如果两边都在，金色会盖掉红色
        draw(ClientDarkSight.hostile(), HOSTILE, level, mc, partialTick, camera, pose, dispatcher, outline);
        draw(ClientDarkSight.passive(), PASSIVE, level, mc, partialTick, camera, pose, dispatcher, outline);
        draw(ClientDarkSight.players(), PLAYER, level, mc, partialTick, camera, pose, dispatcher, outline);
        draw(ClientDarkSight.elite(), ELITE, level, mc, partialTick, camera, pose, dispatcher, outline);
    }

    private static void draw(Set<Integer> ids, int[] color, ClientLevel level, Minecraft mc,
                             float partialTick, Vec3 camera, PoseStack pose,
                             EntityRenderDispatcher dispatcher, OutlineBufferSource outline) {
        if (ids.isEmpty()) return;

        // 这一批统一的颜色 —— OutlineBufferSource 是靠 setColor 上色的，
        // 所以**必须按颜色分批画**（不然中间换色会把前面那批也染错）。
        outline.setColor(color[0], color[1], color[2], color[3]);

        boolean drewAnything = false;

        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity == mc.player) continue;

            // 插值一帧 —— 不然快速移动的生物轮廓会"跟在本体后面抖"
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - camera.x;
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - camera.y;
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - camera.z;
            float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            int light = dispatcher.getPackedLightCoords(entity, partialTick);

            pose.pushPose();
            dispatcher.render(entity, x, y, z, yaw, partialTick, pose, outline, light);
            pose.popPose();

            drewAnything = true;
        }

        if (drewAnything) outline.endOutlineBatch();
    }
}
