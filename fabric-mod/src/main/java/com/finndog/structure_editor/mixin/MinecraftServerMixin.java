package com.finndog.structure_editor.mixin;

import com.finndog.structure_editor.StructureEditorMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla 1.21.2+ pauses the server tick loop after `pause-when-empty-seconds` of no players.
// While paused the task queue still drains, but world ticks do not: chunk tickets still load
// block chunks, yet ServerWorld.tick -> entityManager.tick -> processPendingLoads never runs,
// so finished entity-file reads are never collected and every chunk stays PENDING. Saves then
// silently capture zero entities. Force the threshold to 0 so the server keeps ticking
// whenever the mod is loaded (opt-out via config).
//
// This covers the integrated/base path only. MinecraftDedicatedServer overrides the getter,
// see MinecraftDedicatedServerMixin - both are required.
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "getPauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void structureEditor$disablePauseWhenEmpty(CallbackInfoReturnable<Integer> cir) {
        if (StructureEditorMod.getConfig() != null && StructureEditorMod.getConfig().keep_server_ticking) {
            cir.setReturnValue(0);
        }
    }
}
