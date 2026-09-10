package com.finndog.structure_editor.mixin;

import com.finndog.structure_editor.StructureEditorMod;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MinecraftDedicatedServer overrides getPauseWhenEmptySeconds to read server.properties
// directly, and the pause check calls it virtually. So on a dedicated server the injection
// in MinecraftServerMixin never runs and the server still pauses after
// pause-when-empty-seconds. Same override here so both paths return 0.
@Mixin(MinecraftDedicatedServer.class)
public class MinecraftDedicatedServerMixin {

    @Inject(method = "getPauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void structureEditor$disablePauseWhenEmptyDedicated(CallbackInfoReturnable<Integer> cir) {
        if (StructureEditorMod.getConfig() != null && StructureEditorMod.getConfig().keep_server_ticking) {
            cir.setReturnValue(0);
        }
    }
}
