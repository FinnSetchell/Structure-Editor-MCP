package com.finndog.structure_editor.client;

import com.finndog.structure_editor.network.SyncSelectionsPayload;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

import java.util.Map;

public class SelectionRenderer {

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(SelectionRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        Map<String, SyncSelectionsPayload.RegionData> regions = StructureEditorClient.getClientRegions();
        if (regions == null || regions.isEmpty()) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack poseStack = context.matrices();
        if (poseStack == null) return;

        Vec3d camPos = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        poseStack.push();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        
        net.minecraft.client.network.ClientPlayerEntity player = net.minecraft.client.MinecraftClient.getInstance().player;
        String active = "default";
        if (player != null) {
            active = com.finndog.structure_editor.StructureEditorMod.getWandRegion(player.getMainHandStack()).orElse("default");
        }

        for (Map.Entry<String, SyncSelectionsPayload.RegionData> entry : regions.entrySet()) {
            SyncSelectionsPayload.RegionData data = entry.getValue();
            boolean isActive = entry.getKey().equals(active);
            
            int r = isActive ? 255 : 150;
            int g = isActive ? 255 : 150;
            int b = isActive ? 0 : 150;
            int a = isActive ? 255 : 128;

            BlockPos p1 = data.pos1().orElse(null);
            BlockPos p2 = data.pos2().orElse(null);

            if (p1 != null && p2 == null) {
                drawBox(poseStack, lines, p1.getX(), p1.getY(), p1.getZ(), p1.getX() + 1, p1.getY() + 1, p1.getZ() + 1, r, g, b, a);
            } else if (p2 != null && p1 == null) {
                drawBox(poseStack, lines, p2.getX(), p2.getY(), p2.getZ(), p2.getX() + 1, p2.getY() + 1, p2.getZ() + 1, r, g, b, a);
            } else if (p1 != null && p2 != null) {
                int minX = Math.min(p1.getX(), p2.getX());
                int minY = Math.min(p1.getY(), p2.getY());
                int minZ = Math.min(p1.getZ(), p2.getZ());
                int maxX = Math.max(p1.getX(), p2.getX()) + 1;
                int maxY = Math.max(p1.getY(), p2.getY()) + 1;
                int maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
                drawBox(poseStack, lines, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
            }
        }

        poseStack.pop();
    }

    private static void drawBox(MatrixStack poseStack, VertexConsumer consumer,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2,
                                int r, int g, int b, int a) {
        MatrixStack.Entry pose = poseStack.peek();

        line(consumer, pose, x1,y1,z1, x2,y1,z1, r,g,b,a);
        line(consumer, pose, x2,y1,z1, x2,y1,z2, r,g,b,a);
        line(consumer, pose, x2,y1,z2, x1,y1,z2, r,g,b,a);
        line(consumer, pose, x1,y1,z2, x1,y1,z1, r,g,b,a);
        line(consumer, pose, x1,y2,z1, x2,y2,z1, r,g,b,a);
        line(consumer, pose, x2,y2,z1, x2,y2,z2, r,g,b,a);
        line(consumer, pose, x2,y2,z2, x1,y2,z2, r,g,b,a);
        line(consumer, pose, x1,y2,z2, x1,y2,z1, r,g,b,a);
        line(consumer, pose, x1,y1,z1, x1,y2,z1, r,g,b,a);
        line(consumer, pose, x2,y1,z1, x2,y2,z1, r,g,b,a);
        line(consumer, pose, x2,y1,z2, x2,y2,z2, r,g,b,a);
        line(consumer, pose, x1,y1,z2, x1,y2,z2, r,g,b,a);
    }

    private static void line(VertexConsumer consumer, MatrixStack.Entry pose,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             int r, int g, int b, int a) {
        float dx = (float)(x2 - x1);
        float dy = (float)(y2 - y1);
        float dz = (float)(z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;

        Matrix4f mat = pose.getPositionMatrix();
        Matrix3f nmat = pose.getNormalMatrix();
        
        consumer.vertex(mat, (float) x1, (float) y1, (float) z1)
                .color(r, g, b, a)
                .normal(pose, dx / len, dy / len, dz / len);
        consumer.vertex(mat, (float) x2, (float) y2, (float) z2)
                .color(r, g, b, a)
                .normal(pose, dx / len, dy / len, dz / len);
    }
}
