package com.finndog.structure_editor.client;

import com.finndog.structure_editor.network.SyncSelectionsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import java.util.Map;

// 26.2 reworked the render backend: WorldRenderEvents, MultiBufferSource and the
// hand-built line vertices this used to draw are all gone. Vanilla now has a gizmo
// system for exactly this kind of overlay, so the selection boxes are submitted as
// CuboidGizmos once per client tick instead of drawn manually. Per-tick gizmos are
// cleared every tick, so re-adding each tick is what keeps them on screen.
public class SelectionRenderer {

    // ARGB. Active region is the one bound to the wand in hand.
    private static final int ACTIVE_COLOUR = 0xFFFFFF00;
    private static final int INACTIVE_COLOUR = 0x80969696;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SelectionRenderer::tick);
    }

    private static void tick(Minecraft client) {
        Map<String, SyncSelectionsPayload.RegionData> regions = StructureEditorClient.getClientRegions();
        if (regions == null || regions.isEmpty()) return;
        if (client.level == null) return;

        String active = "default";
        if (client.player != null) {
            active = com.finndog.structure_editor.StructureEditorMod
                    .getWandRegion(client.player.getMainHandItem())
                    .orElse("default");
        }

        try (Gizmos.TemporaryCollection collection = client.collectPerTickGizmos()) {
            for (Map.Entry<String, SyncSelectionsPayload.RegionData> entry : regions.entrySet()) {
                AABB box = boxFor(entry.getValue());
                if (box == null) continue;
                boolean isActive = entry.getKey().equals(active);
                Gizmos.cuboid(box, GizmoStyle.stroke(isActive ? ACTIVE_COLOUR : INACTIVE_COLOUR));
            }
        }
    }

    // A half-set selection still shows: the single placed corner is drawn as its own
    // block-sized box so the wand gives feedback after the first click.
    private static AABB boxFor(SyncSelectionsPayload.RegionData data) {
        BlockPos p1 = data.pos1().orElse(null);
        BlockPos p2 = data.pos2().orElse(null);
        if (p1 == null && p2 == null) return null;
        if (p1 == null) p1 = p2;
        if (p2 == null) p2 = p1;
        return new AABB(
                Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()),
                Math.max(p1.getX(), p2.getX()) + 1.0, Math.max(p1.getY(), p2.getY()) + 1.0, Math.max(p1.getZ(), p2.getZ()) + 1.0
        );
    }
}
