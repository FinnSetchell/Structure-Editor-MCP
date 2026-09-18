package com.finndog.structure_editor.mixin;

import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerWorldAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager structureEditor$getEntityManager();
}
