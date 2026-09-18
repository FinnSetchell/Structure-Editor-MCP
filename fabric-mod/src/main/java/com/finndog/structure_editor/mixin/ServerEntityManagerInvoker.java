package com.finndog.structure_editor.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Entity sections are loaded by PersistentEntitySectionManager independently of block sections.
// isLoaded tells us whether a chunk's entity-file read has landed. The two maps are the
// diagnostic pair: managedStatuses is the per-chunk load state (FRESH -> PENDING -> LOADED,
// default FRESH when absent) and trackingStatuses is the per-chunk visibility the chunk map
// last reported (HIDDEN/TRACKED/TICKING, absent when never made visible).
//
// Deliberately no invoker for readIfFresh/scheduleRead: requesting the entity read ourselves
// before the chunk map has raised visibility puts the entities into HIDDEN sections that
// entity queries skip. Let vanilla's updateChunkStatus request the read.
@Mixin(PersistentEntitySectionManager.class)
public interface ServerEntityManagerInvoker {
    @Invoker("areEntitiesLoaded")
    boolean structureEditor$isLoaded(long chunkPos);

    @Accessor("chunkLoadStatuses")
    Long2ObjectMap<?> structureEditor$getManagedStatuses();

    @Accessor("chunkVisibility")
    Long2ObjectMap<?> structureEditor$getTrackingStatuses();
}
