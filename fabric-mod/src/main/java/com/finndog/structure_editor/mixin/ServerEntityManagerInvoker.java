package com.finndog.structure_editor.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.world.ServerEntityManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Entity sections are loaded by ServerEntityManager independently of block sections.
// isLoaded tells us whether a chunk's entities are actually resident; readIfFresh
// schedules the entity-file read only when the section is still FRESH, so calling it
// on an already-loaded chunk is a no-op rather than a duplicate-entity hazard.
//
// The two maps are the diagnostic pair: managedStatuses is the per-chunk load state
// (FRESH -> PENDING -> LOADED, default FRESH when absent) and trackingStatuses is the
// per-chunk visibility the chunk map last reported (HIDDEN/TRACKED/TICKING, absent when
// the chunk has never been made visible). Together they say whether the visibility
// callback ever fired and whether the async entity-file read ever landed.
@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerInvoker {
    @Invoker("isLoaded")
    boolean structureEditor$isLoaded(long chunkPos);

    @Invoker("readIfFresh")
    void structureEditor$readIfFresh(long chunkPos);

    @Accessor("managedStatuses")
    Long2ObjectMap<?> structureEditor$getManagedStatuses();

    @Accessor("trackingStatuses")
    Long2ObjectMap<?> structureEditor$getTrackingStatuses();
}
