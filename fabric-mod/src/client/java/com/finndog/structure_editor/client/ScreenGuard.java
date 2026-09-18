package com.finndog.structure_editor.client;

import com.finndog.structure_editor.mixin.client.JigsawScreenMixin;
import com.finndog.structure_editor.mixin.client.StructureScreenMixin;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

// Closes the jigsaw/structure screen if the block is edited underneath it - otherwise
// closing the GUI would write the stale values back over an MCP edit.
//
// This used to be an @Inject into each screen's render method. That is not viable on
// 26.3: the render signature changed, and neither screen declares tick() (it is
// inherited from Screen), so injecting into "tick" would fail at runtime the moment the
// screen opened - a failure no compile or server test would catch. Fabric's ScreenEvents
// gives a per-screen tick hook directly, so the only mixins left are field accessors,
// which the annotation processor validates at compile time.
public final class ScreenGuard {

    private ScreenGuard() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof JigsawBlockEditScreen jigsawScreen) {
                jigsawBlock(jigsawScreen, client);
            } else if (screen instanceof StructureBlockEditScreen structureScreen) {
                structureBlock(structureScreen, client);
            }
        });
    }

    private static void jigsawBlock(JigsawBlockEditScreen screen, Minecraft client) {
        net.minecraft.world.level.block.entity.JigsawBlockEntity be =
                ((JigsawScreenMixin) screen).structureEditor$getJigsawEntity();
        if (be == null) return;
        String cached = String.valueOf(be.getName());
        ScreenEvents.afterTick(screen).register(s ->
                guard(client, s, be, be.getBlockPos(), () -> String.valueOf(be.getName()), cached));
    }

    private static void structureBlock(StructureBlockEditScreen screen, Minecraft client) {
        net.minecraft.world.level.block.entity.StructureBlockEntity be =
                ((StructureScreenMixin) screen).structureEditor$getStructure();
        if (be == null) return;
        String cached = String.valueOf(be.getStructureName());
        ScreenEvents.afterTick(screen).register(s ->
                guard(client, s, be, be.getBlockPos(), () -> String.valueOf(be.getStructureName()), cached));
    }

    private static void guard(Minecraft client, Screen screen, BlockEntity expected, BlockPos pos,
                              java.util.function.Supplier<String> currentName, String cachedName) {
        // afterTick only fires while this screen is the open one, so no need to re-check that.
        if (client.level == null) return;
        BlockEntity live = client.level.getBlockEntity(pos);
        if (live != expected || !cachedName.equals(currentName.get())) {
            client.setScreenAndShow(null);
            if (client.player != null) {
                client.player.sendOverlayMessage(
                        Component.literal("§cClosed GUI: block edited externally."));
            }
        }
    }
}
