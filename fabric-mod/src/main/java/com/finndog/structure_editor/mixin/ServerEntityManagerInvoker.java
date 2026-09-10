package com.finndog.structure_editor.mixin;

import net.minecraft.server.world.ServerEntityManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Entity sections are loaded by ServerEntityManager independently of block sections.
// isLoaded tells us whether a chunk's entities are actually resident; readIfFresh
// schedules the entity-file read only when the section is still FRESH, so calling it
// on an already-loaded chunk is a no-op rather than a duplicate-entity hazard.
@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerInvoker {
    @Invoker("isLoaded")
    boolean structureEditor$isLoaded(long chunkPos);

    @Invoker("readIfFresh")
    void structureEditor$readIfFresh(long chunkPos);
}
