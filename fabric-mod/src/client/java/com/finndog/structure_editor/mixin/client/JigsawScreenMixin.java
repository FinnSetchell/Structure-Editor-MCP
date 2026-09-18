package com.finndog.structure_editor.mixin.client;

import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Accessor only. The screen holds its block entity in a private field with no getter, so
// this exposes it; the external-edit guard itself lives in ScreenGuard via Fabric's
// ScreenEvents. @Accessor targets are validated at compile time, unlike @Inject strings.
@Mixin(JigsawBlockEditScreen.class)
public interface JigsawScreenMixin {
    @Accessor("jigsawEntity")
    JigsawBlockEntity structureEditor$getJigsawEntity();
}
