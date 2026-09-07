package com.finndog.structure_editor.mixin;

import com.finndog.structure_editor.StructureEditorMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla 1.21.2+ pauses the server tick loop after `pause-when-empty-seconds` of no players.
// While paused, tasks queued with server.execute() never run, so every mst request that hops
// back to the server thread (basically all of them) times out, and any chunks/block entities
// not already loaded stay that way. Force the pause threshold to 0 so the server keeps
// ticking whenever the mod is loaded (opt-out via config).
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "getPauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void structureEditor$disablePauseWhenEmpty(CallbackInfoReturnable<Integer> cir) {
        if (StructureEditorMod.getConfig() != null && StructureEditorMod.getConfig().keep_server_ticking) {
            cir.setReturnValue(0);
        }
    }
}
