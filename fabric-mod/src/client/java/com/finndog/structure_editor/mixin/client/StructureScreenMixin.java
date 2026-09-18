package com.finndog.structure_editor.mixin.client;

import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Accessor only - see JigsawScreenMixin.
@Mixin(StructureBlockEditScreen.class)
public interface StructureScreenMixin {
    @Accessor("structure")
    StructureBlockEntity structureEditor$getStructure();
}
