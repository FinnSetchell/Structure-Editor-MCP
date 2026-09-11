package com.finndog.structure_editor;
import com.finndog.structure_editor.mixin.ServerEntityManagerInvoker;
import com.finndog.structure_editor.mixin.ServerWorldAccessor;
import com.finndog.structure_editor.network.SyncSelectionsPayload;
import com.google.gson.*;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.registry.RegistryOps;

import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.nbt.NbtList;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;

public class BlockScanner {

    private static final Gson GSON = new Gson();

    // Scans all jigsaw and structure blocks in the selection, returns JSON array string.
    // This is called from the HTTP handler thread, so we submit work to the main server thread
    // and wait for the result.
    public static String scanSelection(MinecraftServer server, SelectionManager.Region selection, String nameFilter) {
        if(!selection.isComplete()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No complete selection. Use the stick wand to set pos1 (left-click) and pos2 (right-click).");
            return GSON.toJson(err);
        }

        CompletableFuture<JsonArray> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                JsonArray results = new JsonArray();
                BlockPos min = selection.getMin();
                BlockPos max = selection.getMax();

                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();

                int minChunkX = min.getX() >> 4;
                int maxChunkX = max.getX() >> 4;
                int minChunkZ = min.getZ() >> 4;
                int maxChunkZ = max.getZ() >> 4;

                int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                if (chunkCount > 4096) {
                    throw new IllegalArgumentException("Selection covers " + chunkCount + " chunks, exceeding the safety limit of 4096 chunks (~1024x1024 blocks). Please make a smaller selection.");
                }

                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        WorldChunk chunk = world.getChunk(cx, cz);
                        if (chunk != null) {
                            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                                if (pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                                    pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                                    pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                                    
                                    BlockEntity be = chunk.getBlockEntity(pos);
                                    if (be instanceof JigsawBlockEntity jigsaw) {
                                        JsonObject jObj = jigsawToJson(jigsaw, pos, registries);
                                        if (nameFilter == null || 
                                            jObj.get("name").getAsString().contains(nameFilter) || 
                                            jObj.get("target").getAsString().contains(nameFilter) || 
                                            jObj.get("pool").getAsString().contains(nameFilter)) {
                                            results.add(jObj);
                                        }
                                    } else if (be instanceof StructureBlockBlockEntity structBlock) {
                                        JsonObject sObj = structureBlockToJson(structBlock, pos, registries);
                                        if (nameFilter == null || 
                                            sObj.get("name").getAsString().contains(nameFilter) || 
                                            sObj.get("metadata").getAsString().contains(nameFilter)) {
                                            results.add(sObj);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                future.complete(results);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            JsonArray results = future.get(10, TimeUnit.SECONDS);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("count", results.size());
            wrapper.add("blocks", results);
            return GSON.toJson(wrapper);
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    // Edits a jigsaw or structure block at the given position.
    // The fields map contains only the fields to change — anything omitted is left as-is.
    // Returns a JSON result object.
    public static String editBlock(MinecraftServer server, JsonObject request) {
        if(!request.has("x") || !request.has("y") || !request.has("z") || !request.has("fields")) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Request must include x, y, z and fields object");
            return GSON.toJson(err);
        }

        int x = request.get("x").getAsInt();
        int y = request.get("y").getAsInt();
        int z = request.get("z").getAsInt();
        JsonObject fields = request.getAsJsonObject("fields");
        BlockPos pos = new BlockPos(x, y, z);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                
                // Force load the chunk at the specific position before checking the block entity
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                
                BlockEntity be = world.getBlockEntity(pos);

                if(be == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "No block entity at " + pos.toShortString());
                    future.complete(err);
                    return;
                }

                if(!(be instanceof JigsawBlockEntity) && !(be instanceof StructureBlockBlockEntity)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Block at " + pos.toShortString() + " is not a jigsaw or structure block (got " + be.getClass().getSimpleName() + ")");
                    future.complete(err);
                    return;
                }

                // Read current NBT including block entity ID and position tags, apply overrides, write back
                NbtCompound nbt = be.createNbtWithIdentifyingData(registries);

                for(String key : fields.keySet()) {
                    JsonElement val = fields.get(key);
                    applyNbtField(nbt, key, val);
                }

                BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), nbt, registries);
                if (newBe != null) {
                    world.removeBlockEntity(pos);
                    world.addBlockEntity(newBe);
                    newBe.markDirty();
                    world.getChunkManager().markForUpdate(pos);
                } else {
                    StructureEditorMod.LOGGER.error("Failed to recreate block entity from NBT at {}", pos.toShortString());
                }

                // Send block entity update to all players watching
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

                JsonObject ok = new JsonObject();
                ok.addProperty("success", true);
                ok.addProperty("position", pos.toShortString());
                ok.addProperty("type", be.getClass().getSimpleName());
                future.complete(ok);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    // Batch edit — takes an array of edit objects each with x, y, z, fields
    public static String editBatch(MinecraftServer server, JsonArray edits) {
        JsonArray results = new JsonArray();
        for(JsonElement elem : edits) {
            if(elem.isJsonObject()) {
                String result = editBlock(server, elem.getAsJsonObject());
                results.add(JsonParser.parseString(result));
            }
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("count", results.size());
        wrapper.add("results", results);
        return GSON.toJson(wrapper);
    }

    // Triggers the save operation on structure blocks in selection or at coordinates
    // Returns the AABB the structure's save will iterate for blocks AND entities. Same math
    // vanilla StructureBlockBlockEntity.saveStructure uses (offset -> offset + size). Returns
    // null for LOAD/CORNER/DATA modes where size is zero.
    private static Box structureBoundsBox(ServerWorld world, BlockPos sbPos, StructureBlockBlockEntity structBlock) {
        try {
            BlockPos offset = structBlock.getOffset();
            NbtCompound nbt = structBlock.createNbt(world.getRegistryManager());
            int sx = nbt.getInt("sizeX").orElse(0);
            int sy = nbt.getInt("sizeY").orElse(0);
            int sz = nbt.getInt("sizeZ").orElse(0);
            if (sx <= 0 || sy <= 0 || sz <= 0) return null;
            BlockPos minPos = sbPos.add(offset);
            BlockPos maxPos = minPos.add(sx, sy, sz);
            return new Box(
                Math.min(minPos.getX(), maxPos.getX()), Math.min(minPos.getY(), maxPos.getY()), Math.min(minPos.getZ(), maxPos.getZ()),
                Math.max(minPos.getX(), maxPos.getX()), Math.max(minPos.getY(), maxPos.getY()), Math.max(minPos.getZ(), maxPos.getZ())
            );
        } catch (Exception e) {
            StructureEditorMod.LOGGER.warn("Failed to compute structure bounds for SB at {}: {}", sbPos, e.toString());
            return null;
        }
    }

    // Enumerate every chunk that overlaps the box.
    private static java.util.List<net.minecraft.util.math.ChunkPos> chunksIn(Box box) {
        java.util.List<net.minecraft.util.math.ChunkPos> out = new java.util.ArrayList<>();
        if (box == null) return out;
        int minCx = ((int) Math.floor(box.minX)) >> 4;
        int maxCx = ((int) Math.floor(box.maxX)) >> 4;
        int minCz = ((int) Math.floor(box.minZ)) >> 4;
        int maxCz = ((int) Math.floor(box.maxZ)) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                out.add(new net.minecraft.util.math.ChunkPos(cx, cz));
            }
        }
        return out;
    }

    // Small holder for the two-phase save (plan on tick T, wait, then save on tick T+N).
    private static class SaveTarget {
        final BlockPos pos;
        final Box bounds;
        final String name;
        final java.util.List<net.minecraft.util.math.ChunkPos> ticketChunks;
        SaveTarget(BlockPos pos, Box bounds, String name, java.util.List<net.minecraft.util.math.ChunkPos> ticketChunks) {
            this.pos = pos; this.bounds = bounds; this.name = name; this.ticketChunks = ticketChunks;
        }
    }

    // Ticket radius that has to reach the target chunk. addTicket propagates the ticket level
    // outward and we need the target chunk itself at ENTITY_TICKING - a radius of 2 is what
    // vanilla /forceload uses.
    private static final int SAVE_TICKET_RADIUS = 2;

    // Must run on the server thread. Idempotent: removing a ticket that isn't there is a no-op.
    private static void releaseSaveTickets(MinecraftServer server, java.util.List<SaveTarget> targets) {
        ServerWorld world = server.getOverworld();
        for (SaveTarget t : targets) {
            for (net.minecraft.util.math.ChunkPos cp : t.ticketChunks) {
                try {
                    world.getChunkManager().removeTicket(net.minecraft.server.world.ChunkTicketType.FORCED, cp, SAVE_TICKET_RADIUS);
                } catch (Exception e) {
                    StructureEditorMod.LOGGER.warn("failed to release save ticket at {}: {}", cp, e.toString());
                }
            }
        }
    }

    public static String saveStructures(MinecraftServer server, SelectionManager.Region selection, JsonObject request) {
        // Phase 1 - server thread: enumerate structure blocks in scope, compute each SB's
        // captured AABB and the chunks it overlaps, then add a FORCED chunk-loading ticket
        // for every one of those chunks. FORCED tickets bring chunks to a level that triggers
        // the async load of entity sections (PersistentEntitySectionManager reads the entity
        // file); getChunk alone only loads block sections, which is why previous fixes
        // produced entity-less saves on cold chunks.
        CompletableFuture<Object> planFuture = new CompletableFuture<>();
        server.execute(() -> {
            // Declared outside the try so a mid-enumeration failure can release whatever
            // tickets were already added instead of leaking persisted FORCED tickets.
            java.util.List<SaveTarget> targets = new java.util.ArrayList<>();
            try {
                ServerWorld world = server.getOverworld();

                if (request.has("x") && request.has("y") && request.has("z")) {
                    BlockPos pos = new BlockPos(request.get("x").getAsInt(), request.get("y").getAsInt(), request.get("z").getAsInt());
                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof StructureBlockBlockEntity structBlock)) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Block at " + pos.toShortString() + " is not a structure block");
                        planFuture.complete(err);
                        return;
                    }
                    Box bounds = structureBoundsBox(world, pos, structBlock);
                    java.util.List<net.minecraft.util.math.ChunkPos> ticketChunks = chunksIn(bounds);
                    for (net.minecraft.util.math.ChunkPos cp : ticketChunks) {
                        world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.FORCED, cp, SAVE_TICKET_RADIUS);
                    }
                    String name = structBlock.createNbt(server.getRegistryManager()).getString("name").orElse("");
                    targets.add(new SaveTarget(pos, bounds, name, ticketChunks));
                } else {
                    if (!selection.isComplete()) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "No complete selection active");
                        planFuture.complete(err);
                        return;
                    }
                    BlockPos min = selection.getMin();
                    BlockPos max = selection.getMax();
                    int minChunkX = min.getX() >> 4, maxChunkX = max.getX() >> 4;
                    int minChunkZ = min.getZ() >> 4, maxChunkZ = max.getZ() >> 4;
                    int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                    if (chunkCount > 4096) {
                        throw new IllegalArgumentException("Selection covers too many chunks to scan safely (max: 4096)");
                    }
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            WorldChunk chunk = world.getChunk(cx, cz);
                            if (chunk == null) continue;
                            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                                if (pos.getX() < min.getX() || pos.getX() > max.getX()) continue;
                                if (pos.getY() < min.getY() || pos.getY() > max.getY()) continue;
                                if (pos.getZ() < min.getZ() || pos.getZ() > max.getZ()) continue;
                                BlockEntity be = chunk.getBlockEntity(pos);
                                if (!(be instanceof StructureBlockBlockEntity structBlock)) continue;
                                Box bounds = structureBoundsBox(world, pos, structBlock);
                                java.util.List<net.minecraft.util.math.ChunkPos> ticketChunks = chunksIn(bounds);
                                for (net.minecraft.util.math.ChunkPos cp : ticketChunks) {
                                    world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.FORCED, cp, SAVE_TICKET_RADIUS);
                                }
                                String name = structBlock.createNbt(server.getRegistryManager()).getString("name").orElse("");
                                targets.add(new SaveTarget(pos.toImmutable(), bounds, name, ticketChunks));
                            }
                        }
                    }
                }
                planFuture.complete(targets);
            } catch (Exception e) {
                releaseSaveTickets(server, targets);
                planFuture.completeExceptionally(e);
            }
        });

        Object plan;
        try {
            plan = planFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "plan phase: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return GSON.toJson(err);
        }
        if (plan instanceof JsonObject errJson) {
            return GSON.toJson(errJson);
        }
        @SuppressWarnings("unchecked")
        java.util.List<SaveTarget> targets = (java.util.List<SaveTarget>) plan;

        // Collect every chunk any target needs, then wait on the ACTUAL condition: the entity
        // manager reporting that chunk's entity sections loaded. A fixed sleep was wrong twice.
        // After a chunk has been loaded once and released, re-ticketing it does not always get
        // its entity sections re-read on its own, so each poll also calls readIfFresh, which
        // schedules the read only when the section is still FRESH (no-op otherwise, so it can
        // never duplicate entities).
        java.util.Set<Long> neededChunks = new java.util.LinkedHashSet<>();
        for (SaveTarget t : targets) for (net.minecraft.util.math.ChunkPos cp : t.ticketChunks) neededChunks.add(cp.toLong());

        long waitStart = System.currentTimeMillis();
        final long waitDeadline = waitStart + 8000;
        java.util.Set<Long> stillPending = new java.util.HashSet<>(neededChunks);
        while (!stillPending.isEmpty() && System.currentTimeMillis() < waitDeadline) {
            final java.util.Set<Long> toCheck = new java.util.HashSet<>(stillPending);
            CompletableFuture<java.util.Set<Long>> pollFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerWorld world = server.getOverworld();
                    ServerEntityManagerInvoker em = (ServerEntityManagerInvoker) ((ServerWorldAccessor) world).structureEditor$getEntityManager();
                    java.util.Set<Long> loaded = new java.util.HashSet<>();
                    for (long cp : toCheck) {
                        if (entitySectionsReady(em, cp)) loaded.add(cp);
                    }
                    pollFuture.complete(loaded);
                } catch (Exception e) {
                    pollFuture.completeExceptionally(e);
                }
            });
            try {
                stillPending.removeAll(pollFuture.get(2, TimeUnit.SECONDS));
            } catch (Exception e) {
                StructureEditorMod.LOGGER.warn("entity-load poll failed: {}", e.toString());
                break;
            }
            if (!stillPending.isEmpty()) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
        final long waitMs = System.currentTimeMillis() - waitStart;
        final boolean allEntitySectionsLoaded = stillPending.isEmpty();
        if (!allEntitySectionsLoaded) {
            // Log the stage each stuck chunk stalled at, not just that it stalled.
            final java.util.Set<Long> stuck = new java.util.HashSet<>(stillPending);
            CompletableFuture<String> stateFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerWorld world = server.getOverworld();
                    ServerEntityManagerInvoker em = (ServerEntityManagerInvoker) ((ServerWorldAccessor) world).structureEditor$getEntityManager();
                    StringBuilder sb = new StringBuilder();
                    for (long key : stuck) {
                        net.minecraft.util.math.ChunkPos cp = new net.minecraft.util.math.ChunkPos(key);
                        Object load = em.structureEditor$getManagedStatuses().get(key);
                        Object vis = em.structureEditor$getTrackingStatuses().get(key);
                        sb.append('[').append(cp.x).append(',').append(cp.z).append(" load=")
                          .append(load == null ? "FRESH" : load).append(" vis=")
                          .append(vis == null ? "ABSENT" : vis).append(" blockTicking=")
                          .append(world.getChunkManager().isTickingFutureReady(key)).append("] ");
                    }
                    stateFuture.complete(sb.toString());
                } catch (Exception e) {
                    stateFuture.complete("(state read failed: " + e + ")");
                }
            });
            String states;
            try { states = stateFuture.get(2, TimeUnit.SECONDS); } catch (Exception e) { states = "(state read timed out)"; }
            StructureEditorMod.LOGGER.warn("save_structures: {} of {} chunks never reported entity sections loaded after {}ms: {}", stillPending.size(), neededChunks.size(), waitMs, states);
        }

        // Phase 2 - server thread: run the actual save and record per-target entity counts so
        // callers can spot a zero that should not be zero. Also remove the tickets we added
        // in phase 1 (FORCED has NO_EXPIRATION so we have to release them explicitly).
        CompletableFuture<JsonObject> saveFuture = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                ServerEntityManagerInvoker em = (ServerEntityManagerInvoker) ((ServerWorldAccessor) world).structureEditor$getEntityManager();
                JsonObject result = new JsonObject();
                result.addProperty("entity_sections_loaded", allEntitySectionsLoaded);
                result.addProperty("entity_wait_ms", waitMs);
                JsonArray savedList = new JsonArray();
                for (SaveTarget target : targets) {
                    JsonObject item = new JsonObject();
                    item.addProperty("x", target.pos.getX());
                    item.addProperty("y", target.pos.getY());
                    item.addProperty("z", target.pos.getZ());
                    item.addProperty("name", target.name);

                    BlockEntity be = world.getBlockEntity(target.pos);
                    if (!(be instanceof StructureBlockBlockEntity structBlock)) {
                        item.addProperty("success", false);
                        item.addProperty("error", "not_a_structure_block");
                    } else {
                        int chunksTotal = target.ticketChunks.size();
                        int chunksEntitiesLoaded = 0;
                        int chunksTickingReady = 0;
                        JsonArray chunkStates = new JsonArray();
                        for (net.minecraft.util.math.ChunkPos cp : target.ticketChunks) {
                            long key = cp.toLong();
                            boolean loaded = em.structureEditor$isLoaded(key);
                            boolean ticking = world.getChunkManager().isTickingFutureReady(key);
                            if (loaded) chunksEntitiesLoaded++;
                            if (ticking) chunksTickingReady++;
                            Object load = em.structureEditor$getManagedStatuses().get(key);
                            Object vis = em.structureEditor$getTrackingStatuses().get(key);
                            JsonObject cs = new JsonObject();
                            cs.addProperty("cx", cp.x);
                            cs.addProperty("cz", cp.z);
                            cs.addProperty("entity_load", load == null ? "FRESH" : load.toString());
                            cs.addProperty("entity_visibility", vis == null ? "ABSENT" : vis.toString());
                            cs.addProperty("block_ticking", ticking);
                            chunkStates.add(cs);
                        }
                        int entityCount = target.bounds == null ? -1 : world.getOtherEntities(null, target.bounds, e -> true).size();
                        boolean success = structBlock.saveStructure();
                        item.addProperty("success", success);
                        item.addProperty("entities_captured", entityCount);
                        item.addProperty("chunks", chunksTotal);
                        item.addProperty("chunks_entity_sections_loaded", chunksEntitiesLoaded);
                        item.addProperty("chunks_ticking_ready", chunksTickingReady);
                        item.add("chunk_states", chunkStates);
                    }
                    savedList.add(item);
                }
                result.addProperty("count", savedList.size());
                result.add("results", savedList);
                saveFuture.complete(result);
            } catch (Exception e) {
                saveFuture.completeExceptionally(e);
            } finally {
                // FORCED is the persisted /forceload ticket type. If it is not removed it
                // survives restarts and pins the chunk forever, so release every ticket we
                // added no matter how the save went.
                releaseSaveTickets(server, targets);
            }
        });

        try {
            return GSON.toJson(saveFuture.get(15, TimeUnit.SECONDS));
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "save phase: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return GSON.toJson(err);
        }
    }

    //////////////////////////////

    private static JsonObject jigsawToJson(JigsawBlockEntity jigsaw, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "jigsaw");
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());

        NbtCompound nbt = jigsaw.createNbt(registries);
        // Expose the main user-facing fields directly for convenience
        obj.addProperty("name", nbt.getString("name").orElse(""));
        obj.addProperty("target", nbt.getString("target").orElse(""));
        obj.addProperty("pool", nbt.getString("pool").orElse(""));
        obj.addProperty("final_state", nbt.getString("final_state").orElse(""));
        obj.addProperty("joint", nbt.getString("joint").orElse(""));
        if(nbt.contains("selection_priority")) obj.addProperty("selection_priority", nbt.getInt("selection_priority").orElse(0));
        if(nbt.contains("placement_priority")) obj.addProperty("placement_priority", nbt.getInt("placement_priority").orElse(0));

        return obj;
    }

    private static JsonObject structureBlockToJson(StructureBlockBlockEntity structBlock, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "structure_block");
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        
        BlockPos offset = structBlock.getOffset();
        BlockPos origin = pos.add(offset);
        obj.addProperty("origin_x", origin.getX());
        obj.addProperty("origin_y", origin.getY());
        obj.addProperty("origin_z", origin.getZ());

        NbtCompound nbt = structBlock.createNbt(registries);
        obj.addProperty("name", nbt.getString("name").orElse(""));
        obj.addProperty("author", nbt.getString("author").orElse(""));
        obj.addProperty("mode", nbt.getString("mode").orElse(""));
        obj.addProperty("metadata", nbt.getString("metadata").orElse(""));
        obj.addProperty("posX", nbt.getInt("posX").orElse(0));
        obj.addProperty("posY", nbt.getInt("posY").orElse(0));
        obj.addProperty("posZ", nbt.getInt("posZ").orElse(0));
        obj.addProperty("sizeX", nbt.getInt("sizeX").orElse(0));
        obj.addProperty("sizeY", nbt.getInt("sizeY").orElse(0));
        obj.addProperty("sizeZ", nbt.getInt("sizeZ").orElse(0));
        obj.addProperty("rotation", nbt.getString("rotation").orElse("NONE"));
        obj.addProperty("mirror", nbt.getString("mirror").orElse("NONE"));
        obj.addProperty("ignoreEntities", nbt.getBoolean("ignoreEntities").orElse(false));
        obj.addProperty("integrity", nbt.getFloat("integrity").orElse(1.0f));

        return obj;
    }

    // Converts an NbtCompound to a JsonObject for inspection
    private static JsonObject nbtToJson(NbtCompound nbt) {
        JsonObject obj = new JsonObject();
        for(String key : nbt.getKeys()) {
            net.minecraft.nbt.NbtElement elem = nbt.get(key);
            if(elem == null) continue;
            switch(elem.getType()) {
                case net.minecraft.nbt.NbtElement.STRING_TYPE ->
                    obj.addProperty(key, nbt.getString(key).orElse(""));
                case net.minecraft.nbt.NbtElement.INT_TYPE ->
                    obj.addProperty(key, nbt.getInt(key).orElse(0));
                case net.minecraft.nbt.NbtElement.BYTE_TYPE ->
                    obj.addProperty(key, nbt.getByte(key).orElse((byte)0));
                case net.minecraft.nbt.NbtElement.FLOAT_TYPE ->
                    obj.addProperty(key, nbt.getFloat(key).orElse(0f));
                case net.minecraft.nbt.NbtElement.DOUBLE_TYPE ->
                    obj.addProperty(key, nbt.getDouble(key).orElse(0d));
                case net.minecraft.nbt.NbtElement.LONG_TYPE ->
                    obj.addProperty(key, nbt.getLong(key).orElse(0L));
                case net.minecraft.nbt.NbtElement.SHORT_TYPE ->
                    obj.addProperty(key, nbt.getShort(key).orElse((short)0));
                default ->
                    obj.addProperty(key, elem.toString());
            }
        }
        return obj;
    }

    // Applies a single JSON field value into an NbtCompound, preserving the original NBT type
    private static void applyNbtField(NbtCompound nbt, String key, JsonElement val) {
        if(val.isJsonNull()) return;

        if(val.isJsonPrimitive()) {
            JsonPrimitive prim = val.getAsJsonPrimitive();
            if(prim.isString()) {
                nbt.putString(key, prim.getAsString());
            }
            else if(prim.isBoolean()) {
                nbt.putBoolean(key, prim.getAsBoolean());
            }
            else if(prim.isNumber()) {
                // Try to preserve the original type from NBT if the key already exists
                if(nbt.contains(key)) {
                    byte existingType = nbt.get(key) != null ? nbt.get(key).getType() : net.minecraft.nbt.NbtElement.INT_TYPE;
                    switch(existingType) {
                        case net.minecraft.nbt.NbtElement.INT_TYPE    -> nbt.putInt(key, prim.getAsInt());
                        case net.minecraft.nbt.NbtElement.FLOAT_TYPE  -> nbt.putFloat(key, prim.getAsFloat());
                        case net.minecraft.nbt.NbtElement.DOUBLE_TYPE -> nbt.putDouble(key, prim.getAsDouble());
                        case net.minecraft.nbt.NbtElement.LONG_TYPE   -> nbt.putLong(key, prim.getAsLong());
                        case net.minecraft.nbt.NbtElement.SHORT_TYPE  -> nbt.putShort(key, prim.getAsShort());
                        case net.minecraft.nbt.NbtElement.BYTE_TYPE   -> nbt.putByte(key, prim.getAsByte());
                        default -> nbt.putInt(key, prim.getAsInt());
                    }
                } else {
                    // Key doesn't exist yet — infer from whether it has a decimal
                    String raw = prim.getAsString();
                    if(raw.contains(".")) {
                        nbt.putFloat(key, prim.getAsFloat());
                    } else {
                        nbt.putInt(key, prim.getAsInt());
                    }
                }
            }
        }
    }

    //////////////////////////////

    // Reads all item stacks and loot table from a container block entity at the given position.
    public static String readContainer(MinecraftServer server, JsonObject request) {
        if(!request.has("x") && !request.has("uuid")) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Request must include x/y/z or uuid");
            return GSON.toJson(err);
        }

        boolean useUuid = request.has("uuid");
        int x = 0, y = 0, z = 0;
        UUID uuid = null;
        
        if (useUuid) {
            try { uuid = UUID.fromString(request.get("uuid").getAsString()); }
            catch (Exception e) {
                JsonObject err = new JsonObject(); err.addProperty("error", "Invalid uuid"); return GSON.toJson(err);
            }
        } else {
            if(!request.has("x") || !request.has("y") || !request.has("z")) {
                JsonObject err = new JsonObject(); err.addProperty("error", "Request must include x, y, z"); return GSON.toJson(err);
            }
            x = request.get("x").getAsInt();
            y = request.get("y").getAsInt();
            z = request.get("z").getAsInt();
        }
        
        BlockPos finalPos = new BlockPos(x, y, z);
        final UUID finalUuid = uuid;

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                Inventory inv = null;
                LootableContainerBlockEntity lootable = null;
                net.minecraft.inventory.LootableInventory entityLootable = null;
                BlockPos pos = finalPos;

                if (useUuid) {
                    Entity e = world.getEntity(finalUuid);
                    if (e == null) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "No entity found with uuid " + finalUuid); future.complete(err); return;
                    }
                    if (!(e instanceof Inventory)) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "Entity " + e.getType().getUntranslatedName() + " is not a container"); future.complete(err); return;
                    }
                    inv = (Inventory) e;
                    if (e instanceof net.minecraft.inventory.LootableInventory l) entityLootable = l;
                    pos = e.getBlockPos();
                } else {
                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    BlockEntity be = world.getBlockEntity(pos);
                    if(be == null) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "No block entity at " + pos.toShortString()); future.complete(err); return;
                    }
                    if(!(be instanceof Inventory)) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "Block at " + pos.toShortString() + " is not a container (got " + be.getClass().getSimpleName() + ")"); future.complete(err); return;
                    }
                    inv = (Inventory) be;
                    if(be instanceof LootableContainerBlockEntity l) lootable = l;
                }

                JsonObject result = new JsonObject();
                result.addProperty("type", inv.getClass().getSimpleName());
                result.addProperty("size", inv.size());

                // Read loot table if present
                if(lootable != null && lootable.getLootTable() != null) {
                    result.addProperty("loot_table", lootable.getLootTable().getValue().toString());
                    result.addProperty("loot_table_seed", lootable.getLootTableSeed());
                } else if (entityLootable instanceof net.minecraft.entity.vehicle.VehicleInventory vi && vi.getLootTable() != null) {
                    result.addProperty("loot_table", vi.getLootTable().getValue().toString());
                    result.addProperty("loot_table_seed", vi.getLootTableSeed());
                } else {
                    result.add("loot_table", JsonNull.INSTANCE);
                }

                JsonArray slots = new JsonArray();
                for(int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if(stack.isEmpty()) continue;
                    RegistryOps<NbtElement> nbtOps = registries.getOps(NbtOps.INSTANCE);
                    NbtCompound nbt = (NbtCompound) ItemStack.CODEC.encodeStart(nbtOps, stack).getOrThrow();
                    JsonObject slot = new JsonObject();
                    slot.addProperty("slot", i);
                    slot.addProperty("id", nbt.getString("id").orElse("minecraft:air"));
                    slot.addProperty("count", stack.getCount());
                    // Include full component NBT for non-vanilla items or items with custom data
                    if(nbt.getKeys().size() > 2) {
                        slot.add("nbt", nbtToJson(nbt));
                    }
                    slots.add(slot);
                }
                result.addProperty("count", slots.size());
                result.add("slots", slots);
                future.complete(result);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    // Writes items and/or a loot table to a container block entity.
    // Setting a loot_table clears all items. Setting items clears any existing loot table.
    // All slots are cleared first, then the provided slots are written.
    public static String writeContainer(MinecraftServer server, JsonObject request) {
        if(!request.has("x") && !request.has("uuid")) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Request must include x/y/z or uuid");
            return GSON.toJson(err);
        }

        boolean useUuid = request.has("uuid");
        int x = 0, y = 0, z = 0;
        UUID uuid = null;
        
        if (useUuid) {
            try { uuid = UUID.fromString(request.get("uuid").getAsString()); }
            catch (Exception e) {
                JsonObject err = new JsonObject(); err.addProperty("error", "Invalid uuid"); return GSON.toJson(err);
            }
        } else {
            if(!request.has("x") || !request.has("y") || !request.has("z")) {
                JsonObject err = new JsonObject(); err.addProperty("error", "Request must include x, y, z"); return GSON.toJson(err);
            }
            x = request.get("x").getAsInt();
            y = request.get("y").getAsInt();
            z = request.get("z").getAsInt();
        }
        
        BlockPos finalPos = new BlockPos(x, y, z);
        final UUID finalUuid = uuid;

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                
                Inventory inv = null;
                net.minecraft.inventory.LootableInventory entityLootable = null;
                LootableContainerBlockEntity blockLootable = null;
                BlockEntity be = null;
                Entity e = null;
                BlockPos pos = finalPos;

                if (useUuid) {
                    e = world.getEntity(finalUuid);
                    if (e == null) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "No entity found with uuid " + finalUuid); future.complete(err); return;
                    }
                    if (!(e instanceof Inventory)) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "Entity " + e.getType().getUntranslatedName() + " is not a container"); future.complete(err); return;
                    }
                    inv = (Inventory) e;
                    if (e instanceof net.minecraft.inventory.LootableInventory l) entityLootable = l;
                    pos = e.getBlockPos();
                } else {
                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    be = world.getBlockEntity(pos);

                    if(be == null) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "No block entity at " + pos.toShortString()); future.complete(err); return;
                    }
                    if(!(be instanceof Inventory)) {
                        JsonObject err = new JsonObject(); err.addProperty("error", "Block at " + pos.toShortString() + " is not a container"); future.complete(err); return;
                    }
                    inv = (Inventory) be;
                    if(be instanceof LootableContainerBlockEntity l) blockLootable = l;
                }

                // Always clear first
                inv.clear();

                if(request.has("loot_table") && !request.get("loot_table").isJsonNull()) {
                    // Loot table mode — mutually exclusive with items
                    if(blockLootable == null && entityLootable == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Target does not support loot tables");
                        future.complete(err);
                        return;
                    }
                    String lootTableId = request.get("loot_table").getAsString();
                    long seed = request.has("loot_table_seed") ? request.get("loot_table_seed").getAsLong() : 0L;
                    RegistryKey<LootTable> lootKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(lootTableId));
                    if (blockLootable != null) blockLootable.setLootTable(lootKey, seed);
                    else if (entityLootable != null) entityLootable.setLootTable(lootKey, seed);
                    inv.markDirty();
                    if (be != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                    JsonObject ok = new JsonObject();
                    ok.addProperty("success", true);
                    ok.addProperty("mode", "loot_table");
                    ok.addProperty("loot_table", lootTableId);
                    ok.addProperty("loot_table_seed", seed);
                    future.complete(ok);
                    return;
                }

                // Item write mode — clear loot table if present
                if(blockLootable != null) {
                    blockLootable.setLootTable(null, 0L);
                } else if (entityLootable != null) {
                    entityLootable.setLootTable(null, 0L);
                }

                int written = 0;
                if(request.has("slots") && request.get("slots").isJsonArray()) {
                    JsonArray slotsArr = request.getAsJsonArray("slots");
                    for(JsonElement elem : slotsArr) {
                        if(!elem.isJsonObject()) continue;
                        JsonObject slotObj = elem.getAsJsonObject();
                        int slot = slotObj.has("slot") ? slotObj.get("slot").getAsInt() : -1;
                        if(slot < 0 || slot >= inv.size()) continue;

                        NbtCompound itemNbt;
                        if(slotObj.has("nbt") && slotObj.get("nbt").isJsonObject()) {
                            // Full NBT provided — rebuild directly from it
                            itemNbt = jsonToNbt(slotObj.getAsJsonObject("nbt"));
                        } else {
                            // Build minimal NBT from id + count
                            itemNbt = new NbtCompound();
                            itemNbt.putString("id", slotObj.has("id") ? slotObj.get("id").getAsString() : "minecraft:air");
                            itemNbt.putInt("count", slotObj.has("count") ? slotObj.get("count").getAsInt() : 1);
                        }

                        RegistryOps<NbtElement> nbtOps = registries.getOps(NbtOps.INSTANCE);
                        ItemStack stack = ItemStack.CODEC.parse(nbtOps, itemNbt).resultOrPartial(err -> {}).orElse(ItemStack.EMPTY);
                        if(!stack.isEmpty()) {
                            inv.setStack(slot, stack);
                            written++;
                        }
                    }
                }

                inv.markDirty();
                if (be != null) {
                    world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                }
                JsonObject ok = new JsonObject();
                ok.addProperty("success", true);
                ok.addProperty("mode", "items");
                ok.addProperty("slots_written", written);
                future.complete(ok);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    // Scans all containers in the selection, returns JSON array of container types and loot tables
    public static String scanContainers(MinecraftServer server, SelectionManager.Region selection) {
        if(!selection.isComplete()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No complete selection.");
            return GSON.toJson(err);
        }

        CompletableFuture<JsonArray> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                JsonArray results = new JsonArray();
                BlockPos min = selection.getMin();
                BlockPos max = selection.getMax();

                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();

                int minChunkX = min.getX() >> 4;
                int maxChunkX = max.getX() >> 4;
                int minChunkZ = min.getZ() >> 4;
                int maxChunkZ = max.getZ() >> 4;

                int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                if (chunkCount > 4096) {
                    throw new IllegalArgumentException("Selection covers too many chunks (max 4096).");
                }

                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        WorldChunk chunk = world.getChunk(cx, cz);
                        if (chunk != null) {
                            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                                if (pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                                    pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                                    pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                                    
                                    BlockEntity be = chunk.getBlockEntity(pos);
                                    if (be instanceof Inventory || be instanceof net.minecraft.block.entity.VaultBlockEntity || be instanceof net.minecraft.block.entity.TrialSpawnerBlockEntity) {
                                        JsonObject obj = new JsonObject();
                                        obj.addProperty("x", pos.getX());
                                        obj.addProperty("y", pos.getY());
                                        obj.addProperty("z", pos.getZ());
                                        obj.addProperty("type", be.getClass().getSimpleName());
                                        
                                        NbtCompound nbt = be.createNbt(registries);
                                        if (nbt.contains("LootTable")) {
                                            obj.addProperty("loot_table", nbt.getString("LootTable").orElse(null));
                                            if (nbt.contains("LootTableSeed")) {
                                                obj.addProperty("loot_table_seed", nbt.getLong("LootTableSeed").orElse(0L));
                                            }
                                        } else if (be instanceof net.minecraft.block.entity.VaultBlockEntity) {
                                            if (nbt.contains("config")) {
                                                NbtCompound config = nbt.getCompound("config").orElse(new NbtCompound());
                                                if (config.contains("loot_table")) {
                                                    String lt = config.getString("loot_table").orElse(null);
                                                    if (lt != null) {
                                                        obj.addProperty("loot_table", lt);
                                                    } else {
                                                        obj.add("loot_table", JsonNull.INSTANCE);
                                                    }
                                                } else {
                                                    obj.add("loot_table", JsonNull.INSTANCE);
                                                }
                                            } else {
                                                obj.add("loot_table", JsonNull.INSTANCE);
                                            }
                                        } else if (be instanceof net.minecraft.block.entity.TrialSpawnerBlockEntity) {
                                            if (nbt.contains("normal_config")) {
                                                NbtCompound normalConfig = nbt.getCompound("normal_config").orElse(new NbtCompound());
                                                if (normalConfig.contains("loot_tables_to_eject")) {
                                                    NbtList ejectList = normalConfig.getList("loot_tables_to_eject").orElse(new NbtList());
                                                    if (!ejectList.isEmpty()) {
                                                        String data = ejectList.getCompound(0).orElse(new NbtCompound()).getString("data").orElse(null);
                                                        if (data != null && !data.isEmpty()) {
                                                            obj.addProperty("loot_table", data);
                                                        } else {
                                                            obj.add("loot_table", JsonNull.INSTANCE);
                                                        }
                                                    } else {
                                                        obj.add("loot_table", JsonNull.INSTANCE);
                                                    }
                                                } else {
                                                    obj.add("loot_table", JsonNull.INSTANCE);
                                                }
                                            } else {
                                                obj.add("loot_table", JsonNull.INSTANCE);
                                            }
                                        } else {
                                            obj.add("loot_table", JsonNull.INSTANCE);
                                        }
                                        results.add(obj);
                                    }
                                }
                            }
                        }
                    }
                }

                future.complete(results);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            JsonArray results = future.get(10, TimeUnit.SECONDS);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("count", results.size());
            wrapper.add("containers", results);
            return GSON.toJson(wrapper);
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String scanBlocks(MinecraftServer server, SelectionManager.Region selection, JsonArray targetBlocks) {
        if(!selection.isComplete()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No complete selection.");
            return GSON.toJson(err);
        }
        
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                BlockPos min = selection.getMin();
                BlockPos max = selection.getMax();
                
                long volume = (max.getX() - min.getX() + 1L) * (max.getY() - min.getY() + 1L) * (max.getZ() - min.getZ() + 1L);
                if (volume > 1000000) {
                    throw new IllegalArgumentException("Selection too large for block scanning (max 1,000,000 blocks). Selected: " + volume);
                }

                ServerWorld world = server.getOverworld();
                
                Set<Block> targets = new HashSet<>();
                for (JsonElement e : targetBlocks) {
                    if (e.isJsonPrimitive()) {
                        Identifier id = Identifier.tryParse(e.getAsString());
                        if (id != null && Registries.BLOCK.containsId(id)) {
                            targets.add(Registries.BLOCK.get(id));
                        }
                    }
                }

                JsonArray results = new JsonArray();
                for (BlockPos pos : BlockPos.iterate(min, max)) {
                    if (results.size() >= 2000) break;
                    
                    BlockState state = world.getBlockState(pos);
                    if (targets.contains(state.getBlock())) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("x", pos.getX());
                        obj.addProperty("y", pos.getY());
                        obj.addProperty("z", pos.getZ());
                        obj.addProperty("id", Registries.BLOCK.getId(state.getBlock()).toString());
                        results.add(obj);
                    }
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("count", results.size());
                if (results.size() >= 2000) wrapper.addProperty("warning", "Result limit of 2000 reached.");
                wrapper.add("blocks", results);
                future.complete(wrapper);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    // A chunk's entities are usable only when the entity-file read has landed (LOADED) AND
    // the chunk map has raised the chunk's visibility, because sections created before that
    // are HIDDEN and getOtherEntities skips them. Never request the read ourselves: doing so
    // ahead of the visibility update is exactly what produced loaded-but-invisible entities.
    // Vanilla's updateChunkStatus requests the read itself once the ticket lands.
    private static boolean entitySectionsReady(ServerEntityManagerInvoker em, long chunkKey) {
        if (!em.structureEditor$isLoaded(chunkKey)) return false;
        Object vis = em.structureEditor$getTrackingStatuses().get(chunkKey);
        return vis != null && vis != net.minecraft.world.entity.EntityTrackingStatus.HIDDEN;
    }

    // Result of waiting for entity sections to become resident for a set of chunks.
    private static final class EntityLoadWait {
        final boolean allLoaded;
        final long waitMs;
        final int pending;
        EntityLoadWait(boolean allLoaded, long waitMs, int pending) { this.allLoaded = allLoaded; this.waitMs = waitMs; this.pending = pending; }
    }

    // Must be called from the http thread (it blocks). For every chunk key, hop to the server
    // thread and check the entity manager's isLoaded; any chunk not yet loaded gets
    // readIfFresh, which schedules the entity-file read only while the section is FRESH.
    // Bounded by timeoutMs. Same sequence save_structures uses.
    private static EntityLoadWait awaitEntitySections(MinecraftServer server, java.util.Set<Long> chunkKeys, long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        java.util.Set<Long> pending = new java.util.HashSet<>(chunkKeys);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            final java.util.Set<Long> toCheck = new java.util.HashSet<>(pending);
            CompletableFuture<java.util.Set<Long>> poll = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerWorld world = server.getOverworld();
                    ServerEntityManagerInvoker em = (ServerEntityManagerInvoker) ((ServerWorldAccessor) world).structureEditor$getEntityManager();
                    java.util.Set<Long> loaded = new java.util.HashSet<>();
                    for (long cp : toCheck) {
                        if (entitySectionsReady(em, cp)) loaded.add(cp);
                    }
                    poll.complete(loaded);
                } catch (Exception e) {
                    poll.completeExceptionally(e);
                }
            });
            try {
                pending.removeAll(poll.get(2, TimeUnit.SECONDS));
            } catch (Exception e) {
                StructureEditorMod.LOGGER.warn("entity-load poll failed: {}", e.toString());
                break;
            }
            if (!pending.isEmpty()) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return new EntityLoadWait(pending.isEmpty(), System.currentTimeMillis() - start, pending.size());
    }

    private static void releaseTickets(MinecraftServer server, java.util.List<net.minecraft.util.math.ChunkPos> chunks) {
        ServerWorld world = server.getOverworld();
        for (net.minecraft.util.math.ChunkPos cp : chunks) {
            try {
                world.getChunkManager().removeTicket(net.minecraft.server.world.ChunkTicketType.FORCED, cp, SAVE_TICKET_RADIUS);
            } catch (Exception e) {
                StructureEditorMod.LOGGER.warn("failed to release ticket at {}: {}", cp, e.toString());
            }
        }
    }

    public static String scanEntities(MinecraftServer server, SelectionManager.Region selection, JsonArray targetEntities) {
        if(!selection.isComplete()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No complete selection.");
            return GSON.toJson(err);
        }

        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();
        Box box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);

        // getOtherEntities only reports entity sections already resident. A cold area reads as
        // empty even when it is full of armour stands and mobs, so ticket every chunk in the box
        // and wait for the entity manager to actually load them, exactly like save_structures.
        final java.util.List<net.minecraft.util.math.ChunkPos> chunks = chunksIn(box);
        if (chunks.size() > 4096) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Selection covers " + chunks.size() + " chunks, exceeding the safety limit of 4096 chunks (~1024x1024 blocks). Please make a smaller selection.");
            return GSON.toJson(err);
        }
        CompletableFuture<Void> ticketed = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                for (net.minecraft.util.math.ChunkPos cp : chunks) {
                    world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.FORCED, cp, SAVE_TICKET_RADIUS);
                }
                ticketed.complete(null);
            } catch (Exception e) {
                ticketed.completeExceptionally(e);
            }
        });
        try {
            ticketed.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            server.execute(() -> releaseTickets(server, chunks));
            JsonObject err = new JsonObject();
            err.addProperty("error", "ticket phase: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return GSON.toJson(err);
        }
        java.util.Set<Long> keys = new java.util.LinkedHashSet<>();
        for (net.minecraft.util.math.ChunkPos cp : chunks) keys.add(cp.toLong());
        final EntityLoadWait wait = awaitEntitySections(server, keys, 8000);
        if (!wait.allLoaded) {
            StructureEditorMod.LOGGER.warn("scan_entities: {} of {} chunks never reported entity sections loaded after {}ms", wait.pending, chunks.size(), wait.waitMs);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                Set<EntityType<?>> targets = new HashSet<>();
                if (targetEntities != null && !targetEntities.isEmpty()) {
                    for (JsonElement e : targetEntities) {
                        if (e.isJsonPrimitive()) {
                            Identifier id = Identifier.tryParse(e.getAsString());
                            if (id != null && Registries.ENTITY_TYPE.containsId(id)) {
                                targets.add(Registries.ENTITY_TYPE.get(id));
                            }
                        }
                    }
                }

                ServerWorld world = server.getOverworld();
                List<Entity> entities = world.getOtherEntities(null, box, e -> targets.isEmpty() || targets.contains(e.getType()));
                
                JsonArray results = new JsonArray();
                int count = 0;
                for (Entity e : entities) {
                    if (count >= 1000) break;
                    JsonObject obj = new JsonObject();
                    obj.addProperty("type", Registries.ENTITY_TYPE.getId(e.getType()).toString());
                    obj.addProperty("uuid", e.getUuidAsString());
                    obj.addProperty("x", e.getX());
                    obj.addProperty("y", e.getY());
                    obj.addProperty("z", e.getZ());
                    if (e.hasCustomName()) {
                        obj.addProperty("custom_name", e.getCustomName().getString());
                    }
                    results.add(obj);
                    count++;
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("count", results.size());
                if (results.size() >= 1000) wrapper.addProperty("warning", "Result limit of 1000 reached.");
                wrapper.addProperty("chunks", chunks.size());
                wrapper.addProperty("entity_sections_loaded", wait.allLoaded);
                wrapper.addProperty("entity_wait_ms", wait.waitMs);
                if (!wait.allLoaded) wrapper.addProperty("warning_entities", wait.pending + " chunk(s) never finished loading entity sections; results may be incomplete.");
                wrapper.add("entities", results);
                future.complete(wrapper);
            } catch(Exception e) {
                future.completeExceptionally(e);
            } finally {
                releaseTickets(server, chunks);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String getStructurePalette(MinecraftServer server, String structureName) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                Identifier id = Identifier.tryParse(structureName);
                if (id == null) {
                    throw new IllegalArgumentException("Invalid structure identifier: " + structureName);
                }

                StructureTemplateManager manager = server.getStructureTemplateManager();
                Optional<StructureTemplate> opt = manager.getTemplate(id);
                
                if (opt.isEmpty()) {
                    throw new IllegalArgumentException("Structure not found: " + structureName);
                }

                StructureTemplate template = opt.get();
                NbtCompound nbt = new NbtCompound();
                nbt = template.writeNbt(nbt);
                
                Set<String> uniqueBlocks = new HashSet<>();
                
                if (nbt.contains("palettes")) {
                    nbt.getList("palettes").ifPresent(palettes -> {
                        for (int i = 0; i < palettes.size(); i++) {
                            palettes.getList(i).ifPresent(palette -> {
                                for (int j = 0; j < palette.size(); j++) {
                                    palette.getCompound(j).ifPresent(comp -> uniqueBlocks.add(comp.getString("Name").orElse("")));
                                }
                            });
                        }
                    });
                } else if (nbt.contains("palette")) {
                    nbt.getList("palette").ifPresent(palette -> {
                        for (int i = 0; i < palette.size(); i++) {
                            palette.getCompound(i).ifPresent(comp -> uniqueBlocks.add(comp.getString("Name").orElse("")));
                        }
                    });
                }

                JsonArray results = new JsonArray();
                for (String blockId : uniqueBlocks) {
                    if (!blockId.isEmpty()) {
                        results.add(blockId);
                    }
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("structure", structureName);
                wrapper.add("palette", results);
                future.complete(wrapper);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    // Bulk writes items and/or loot tables to multiple container block entities
    public static String writeContainersBatch(MinecraftServer server, JsonArray requests) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                int successCount = 0;
                int errorCount = 0;

                for (JsonElement reqElem : requests) {
                    if (!reqElem.isJsonObject()) continue;
                    JsonObject request = reqElem.getAsJsonObject();
                    
                    if(!request.has("x") || !request.has("y") || !request.has("z")) {
                        errorCount++;
                        continue;
                    }

                    int x = request.get("x").getAsInt();
                    int y = request.get("y").getAsInt();
                    int z = request.get("z").getAsInt();
                    BlockPos pos = new BlockPos(x, y, z);

                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    BlockEntity be = world.getBlockEntity(pos);

                    if(be == null) {
                        errorCount++;
                        continue;
                    }

                    if (be instanceof Inventory inv) {
                        inv.clear();
                        if(request.has("loot_table") && !request.get("loot_table").isJsonNull()) {
                            if(be instanceof LootableContainerBlockEntity lootable) {
                                String lootTableId = request.get("loot_table").getAsString();
                                long seed = request.has("loot_table_seed") ? request.get("loot_table_seed").getAsLong() : 0L;
                                RegistryKey<LootTable> lootKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(lootTableId));
                                lootable.setLootTable(lootKey, seed);
                                be.markDirty();
                                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                                successCount++;
                            } else {
                                errorCount++;
                            }
                            continue;
                        }

                        if(be instanceof LootableContainerBlockEntity lootable) {
                            lootable.setLootTable(null, 0L);
                        }

                        if(request.has("slots") && request.get("slots").isJsonArray()) {
                            JsonArray slotsArr = request.getAsJsonArray("slots");
                            for(JsonElement elem : slotsArr) {
                                if(!elem.isJsonObject()) continue;
                                JsonObject slotObj = elem.getAsJsonObject();
                                int slot = slotObj.has("slot") ? slotObj.get("slot").getAsInt() : -1;
                                if(slot < 0 || slot >= inv.size()) continue;

                                NbtCompound itemNbt;
                                if(slotObj.has("nbt") && slotObj.get("nbt").isJsonObject()) {
                                    itemNbt = jsonToNbt(slotObj.getAsJsonObject("nbt"));
                                } else {
                                    itemNbt = new NbtCompound();
                                    itemNbt.putString("id", slotObj.has("id") ? slotObj.get("id").getAsString() : "minecraft:air");
                                    itemNbt.putInt("count", slotObj.has("count") ? slotObj.get("count").getAsInt() : 1);
                                }

                                RegistryOps<NbtElement> nbtOps = registries.getOps(NbtOps.INSTANCE);
                                ItemStack stack = ItemStack.CODEC.parse(nbtOps, itemNbt).resultOrPartial(e -> {}).orElse(ItemStack.EMPTY);
                                if(!stack.isEmpty()) {
                                    inv.setStack(slot, stack);
                                }
                            }
                        }

                        be.markDirty();
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                        successCount++;
                    } else if (be instanceof net.minecraft.block.entity.VaultBlockEntity) {
                        if (request.has("loot_table") && !request.get("loot_table").isJsonNull()) {
                            String lootTableId = request.get("loot_table").getAsString();
                            NbtCompound nbt = be.createNbtWithIdentifyingData(registries);
                            NbtCompound config = nbt.getCompound("config").orElse(new NbtCompound());
                            config.putString("loot_table", lootTableId);
                            nbt.put("config", config);
                            
                            BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), nbt, registries);
                            if (newBe != null) {
                                world.removeBlockEntity(pos);
                                world.addBlockEntity(newBe);
                                newBe.markDirty();
                                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                                successCount++;
                            } else {
                                errorCount++;
                            }
                        } else {
                            errorCount++;
                        }
                    } else if (be instanceof net.minecraft.block.entity.TrialSpawnerBlockEntity) {
                        if (request.has("loot_table") && !request.get("loot_table").isJsonNull()) {
                            String lootTableId = request.get("loot_table").getAsString();
                            NbtCompound nbt = be.createNbtWithIdentifyingData(registries);
                            
                            NbtCompound normalConfig = nbt.getCompound("normal_config").orElse(new NbtCompound());
                            NbtList ejectList = new NbtList();
                            NbtCompound entry = new NbtCompound();
                            entry.putString("data", lootTableId);
                            entry.putInt("weight", 1);
                            ejectList.add(entry);
                            normalConfig.put("loot_tables_to_eject", ejectList);
                            nbt.put("normal_config", normalConfig);
                            
                            BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), nbt, registries);
                            if (newBe != null) {
                                world.removeBlockEntity(pos);
                                world.addBlockEntity(newBe);
                                newBe.markDirty();
                                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                                successCount++;
                            } else {
                                errorCount++;
                            }
                        } else {
                            errorCount++;
                        }
                    } else {
                        errorCount++;
                    }
                }

                JsonObject ok = new JsonObject();
                ok.addProperty("success", true);
                ok.addProperty("successful_writes", successCount);
                ok.addProperty("failed_writes", errorCount);
                future.complete(ok);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch(TimeoutException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Timed out waiting for server thread");
            return GSON.toJson(err);
        } catch(ExecutionException | InterruptedException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String readBlockNbt(MinecraftServer server, JsonObject request) {
        if(!request.has("x") || !request.has("y") || !request.has("z")) {
            JsonObject err = new JsonObject(); err.addProperty("error", "Request must include x, y, z"); return GSON.toJson(err);
        }
        int x = request.get("x").getAsInt(), y = request.get("y").getAsInt(), z = request.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                BlockEntity be = world.getBlockEntity(pos);
                if(be == null) {
                    JsonObject err = new JsonObject(); err.addProperty("error", "No block entity at " + pos.toShortString()); future.complete(err); return;
                }
                NbtCompound nbt = be.createNbtWithIdentifyingData(server.getRegistryManager());
                JsonElement json = Dynamic.convert(NbtOps.INSTANCE, JsonOps.INSTANCE, nbt);
                future.complete(json.getAsJsonObject());
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        try { return GSON.toJson(future.get(10, TimeUnit.SECONDS)); }
        catch(Exception e) { JsonObject err = new JsonObject(); err.addProperty("error", e.getMessage()); return GSON.toJson(err); }
    }

    public static String writeBlockNbt(MinecraftServer server, JsonObject request) {
        if(!request.has("x") || !request.has("y") || !request.has("z") || !request.has("nbt")) {
            JsonObject err = new JsonObject(); err.addProperty("error", "Request must include x, y, z, and nbt"); return GSON.toJson(err);
        }
        int x = request.get("x").getAsInt(), y = request.get("y").getAsInt(), z = request.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);
        JsonObject nbtPatch = request.getAsJsonObject("nbt");
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                BlockEntity be = world.getBlockEntity(pos);
                if(be == null) {
                    JsonObject err = new JsonObject(); err.addProperty("error", "No block entity at " + pos.toShortString()); future.complete(err); return;
                }
                NbtCompound nbt = be.createNbtWithIdentifyingData(server.getRegistryManager());
                JsonElement currentJson = Dynamic.convert(NbtOps.INSTANCE, JsonOps.INSTANCE, nbt);
                JsonObject merged = currentJson.getAsJsonObject();
                for (String key : nbtPatch.keySet()) merged.add(key, nbtPatch.get(key));
                
                NbtElement newNbt = Dynamic.convert(JsonOps.INSTANCE, NbtOps.INSTANCE, merged);
                BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), (NbtCompound)newNbt, server.getRegistryManager());
                if (newBe != null) {
                    world.removeBlockEntity(pos);
                    world.addBlockEntity(newBe);
                    newBe.markDirty();
                    world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                }
                JsonObject ok = new JsonObject(); ok.addProperty("success", true); ok.addProperty("position", pos.toShortString()); future.complete(ok);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        try { return GSON.toJson(future.get(10, TimeUnit.SECONDS)); }
        catch(Exception e) { JsonObject err = new JsonObject(); err.addProperty("error", e.getMessage()); return GSON.toJson(err); }
    }

    // Converts a JsonObject back into a flat NbtCompound (string/int/float/long values only)
    private static NbtCompound jsonToNbt(JsonObject obj) {
        NbtCompound nbt = new NbtCompound();
        for(String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if(elem.isJsonPrimitive()) {
                JsonPrimitive prim = elem.getAsJsonPrimitive();
                if(prim.isString()) {
                    nbt.putString(key, prim.getAsString());
                } else if(prim.isBoolean()) {
                    nbt.putBoolean(key, prim.getAsBoolean());
                } else if(prim.isNumber()) {
                    String raw = prim.getAsString();
                    if(raw.contains(".")) {
                        nbt.putFloat(key, prim.getAsFloat());
                    } else {
                        nbt.putInt(key, prim.getAsInt());
                    }
                }
            }
        }
        return nbt;
    }

    private static final Map<String, JsonArray> lastWriteBatch = new HashMap<>();

    public static BlockState parseBlockState(String input) {
        int bracketIndex = input.indexOf('[');
        String idStr = bracketIndex == -1 ? input : input.substring(0, bracketIndex);
        Identifier id = Identifier.of(idStr);
        Block block = Registries.BLOCK.get(id);
        if (block == null) block = net.minecraft.block.Blocks.AIR;
        BlockState state = block.getDefaultState();
        
        if (bracketIndex != -1 && input.endsWith("]")) {
            String propsStr = input.substring(bracketIndex + 1, input.length() - 1);
            String[] props = propsStr.split(",");
            for (String propStr : props) {
                String[] kv = propStr.split("=");
                if (kv.length == 2) {
                    net.minecraft.state.property.Property<?> property = block.getStateManager().getProperty(kv[0]);
                    if (property != null) {
                        state = withProperty(state, property, kv[1]);
                    }
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, net.minecraft.state.property.Property<T> property, String valueStr) {
        Optional<T> value = property.parse(valueStr);
        if (value.isPresent()) {
            return state.with(property, value.get());
        }
        return state;
    }

    public static String stateToString(BlockState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(Registries.BLOCK.getId(state.getBlock()).toString());
        if (!state.getEntries().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (java.util.Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(entry.getKey().getName()).append('=').append(propertyValueToString(entry.getKey(), entry.getValue()));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValueToString(net.minecraft.state.property.Property<T> property, Comparable<?> value) {
        return property.name((T) value);
    }

    public static String getBlocks(MinecraftServer server, SelectionManager.Region selection, JsonArray posList) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                if (posList != null && posList.size() > 0) {
                    JsonArray results = new JsonArray();
                    for (JsonElement el : posList) {
                        JsonObject obj = el.getAsJsonObject();
                        int x = obj.get("x").getAsInt();
                        int y = obj.get("y").getAsInt();
                        int z = obj.get("z").getAsInt();
                        BlockPos pos = new BlockPos(x, y, z);
                        
                        BlockState state = world.getBlockState(pos);
                        BlockEntity be = world.getBlockEntity(pos);
                        
                        JsonObject res = new JsonObject();
                        res.addProperty("x", x);
                        res.addProperty("y", y);
                        res.addProperty("z", z);
                        res.addProperty("block", stateToString(state));
                        res.addProperty("has_block_entity", be != null);
                        results.add(res);
                    }
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("blocks", results);
                    future.complete(wrapper);
                } else {
                    if (selection == null || !selection.isComplete()) {
                        throw new IllegalArgumentException("No complete selection");
                    }
                    BlockPos min = selection.getMin();
                    BlockPos max = selection.getMax();
                    
                    int minChunkX = min.getX() >> 4;
                    int maxChunkX = max.getX() >> 4;
                    int minChunkZ = min.getZ() >> 4;
                    int maxChunkZ = max.getZ() >> 4;

                    int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                    if (chunkCount > 4096) {
                        throw new IllegalArgumentException("Selection covers too many chunks (max 4096).");
                    }
                    
                    Map<String, Integer> paletteMap = new HashMap<>();
                    JsonArray paletteArr = new JsonArray();
                    JsonArray blocksArr = new JsonArray();
                    
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            WorldChunk chunk = world.getChunk(cx, cz);
                            if (chunk != null) {
                                for (int x = Math.max(min.getX(), cx * 16); x <= Math.min(max.getX(), cx * 16 + 15); x++) {
                                    for (int y = min.getY(); y <= max.getY(); y++) {
                                        for (int z = Math.max(min.getZ(), cz * 16); z <= Math.min(max.getZ(), cz * 16 + 15); z++) {
                                            BlockPos pos = new BlockPos(x, y, z);
                                            BlockState state = chunk.getBlockState(pos);
                                            BlockEntity be = chunk.getBlockEntity(pos);
                                            String stateStr = stateToString(state);
                                            
                                            if (!paletteMap.containsKey(stateStr)) {
                                                paletteMap.put(stateStr, paletteMap.size());
                                                paletteArr.add(stateStr);
                                            }
                                            
                                            JsonObject b = new JsonObject();
                                            b.addProperty("x", x);
                                            b.addProperty("y", y);
                                            b.addProperty("z", z);
                                            b.addProperty("palette_index", paletteMap.get(stateStr));
                                            b.addProperty("has_block_entity", be != null);
                                            blocksArr.add(b);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("palette", paletteArr);
                    wrapper.add("blocks", blocksArr);
                    future.complete(wrapper);
                }
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        try {
            return GSON.toJson(future.get(30, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String setBlocks(MinecraftServer server, JsonArray updates, SelectionManager.Region allowedRegion, boolean allowOutside) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (updates.size() > 10000) {
                    throw new IllegalArgumentException("Cannot update more than 10000 blocks at once.");
                }
                
                ServerWorld world = server.getOverworld();
                JsonArray prevStates = new JsonArray();
                JsonArray results = new JsonArray();
                
                for (JsonElement el : updates) {
                    JsonObject obj = el.getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();
                    BlockPos pos = new BlockPos(x, y, z);
                    
                    if (!allowOutside) {
                        if (allowedRegion == null || !allowedRegion.isComplete() || pos.getX() < allowedRegion.getMin().getX() || pos.getX() > allowedRegion.getMax().getX() || pos.getY() < allowedRegion.getMin().getY() || pos.getY() > allowedRegion.getMax().getY() || pos.getZ() < allowedRegion.getMin().getZ() || pos.getZ() > allowedRegion.getMax().getZ()) {
                            throw new IllegalArgumentException("Position " + pos + " is outside selection and allowOutside is false.");
                        }
                    }
                    
                    BlockState oldState = world.getBlockState(pos);
                    BlockEntity oldBe = world.getBlockEntity(pos);
                    
                    JsonObject prev = new JsonObject();
                    prev.addProperty("x", x);
                    prev.addProperty("y", y);
                    prev.addProperty("z", z);
                    prev.addProperty("block", stateToString(oldState));
                    if (oldBe != null) {
                        JsonElement oldNbtJson = Dynamic.convert(NbtOps.INSTANCE, JsonOps.INSTANCE, oldBe.createNbtWithIdentifyingData(server.getRegistryManager()));
                        prev.add("nbt", oldNbtJson);
                    }
                    prevStates.add(prev);
                    
                    String newBlockStr = obj.get("block").getAsString();
                    BlockState newState = parseBlockState(newBlockStr);
                    
                    // Flags: NOTIFY_LISTENERS(2) | FORCE_STATE(16) | SKIP_DROPS(32) = 50
                    world.setBlockState(pos, newState, 50);
                    
                    if (obj.has("nbt") && obj.get("nbt").isJsonObject()) {
                        BlockEntity be = world.getBlockEntity(pos);
                        if (be != null) {
                            NbtCompound currentNbt = be.createNbtWithIdentifyingData(server.getRegistryManager());
                            NbtCompound mergeNbt = jsonToNbt(obj.getAsJsonObject("nbt"));
                            for (String key : mergeNbt.getKeys()) {
                                currentNbt.put(key, mergeNbt.get(key));
                            }
                            BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), currentNbt, server.getRegistryManager());
                            if (newBe != null) {
                                world.removeBlockEntity(pos);
                                world.addBlockEntity(newBe);
                                newBe.markDirty();
                            }
                        }
                    }
                    
                    JsonObject res = new JsonObject();
                    res.addProperty("x", x);
                    res.addProperty("y", y);
                    res.addProperty("z", z);
                    res.addProperty("success", true);
                    res.addProperty("previous_block", stateToString(oldState));
                    results.add(res);
                }
                
                String token = UUID.randomUUID().toString();
                lastWriteBatch.put(token, prevStates);
                
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("undo_token", token);
                wrapper.add("results", results);
                future.complete(wrapper);
                
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        try {
            return GSON.toJson(future.get(30, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String replaceBlocks(MinecraftServer server, SelectionManager.Region selection, JsonArray findIds, String replaceId, boolean dryRun, int maxBlocks) {
        if (selection == null || !selection.isComplete()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No complete selection.");
            return GSON.toJson(err);
        }
        
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                BlockPos min = selection.getMin();
                BlockPos max = selection.getMax();
                
                Set<Identifier> findSet = new HashSet<>();
                for (JsonElement el : findIds) {
                    findSet.add(Identifier.of(el.getAsString()));
                }
                
                BlockState replaceState = parseBlockState(replaceId);
                
                int minChunkX = min.getX() >> 4;
                int maxChunkX = max.getX() >> 4;
                int minChunkZ = min.getZ() >> 4;
                int maxChunkZ = max.getZ() >> 4;

                int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                if (chunkCount > 4096) {
                    throw new IllegalArgumentException("Selection covers too many chunks (max 4096).");
                }
                
                int matched = 0;
                int changed = 0;
                int skipped_unloaded = 0;
                JsonArray sample = new JsonArray();
                JsonArray prevStates = new JsonArray();
                
                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        WorldChunk chunk = world.getChunk(cx, cz);
                        if (chunk != null) {
                            for (int x = Math.max(min.getX(), cx * 16); x <= Math.min(max.getX(), cx * 16 + 15); x++) {
                                for (int y = min.getY(); y <= max.getY(); y++) {
                                    for (int z = Math.max(min.getZ(), cz * 16); z <= Math.min(max.getZ(), cz * 16 + 15); z++) {
                                        BlockPos pos = new BlockPos(x, y, z);
                                        BlockState state = chunk.getBlockState(pos);
                                        Identifier id = Registries.BLOCK.getId(state.getBlock());
                                        if (findSet.contains(id)) {
                                            matched++;
                                            if (sample.size() < 10) {
                                                JsonObject s = new JsonObject();
                                                s.addProperty("x", x);
                                                s.addProperty("y", y);
                                                s.addProperty("z", z);
                                                s.addProperty("from", stateToString(state));
                                                sample.add(s);
                                            }
                                            if (!dryRun && changed < maxBlocks) {
                                                BlockEntity oldBe = chunk.getBlockEntity(pos);
                                                JsonObject prev = new JsonObject();
                                                prev.addProperty("x", x);
                                                prev.addProperty("y", y);
                                                prev.addProperty("z", z);
                                                prev.addProperty("block", stateToString(state));
                                                if (oldBe != null) {
                                                    JsonElement oldNbtJson = Dynamic.convert(NbtOps.INSTANCE, JsonOps.INSTANCE, oldBe.createNbtWithIdentifyingData(server.getRegistryManager()));
                                                    prev.add("nbt", oldNbtJson);
                                                }
                                                prevStates.add(prev);
                                                
                                                world.setBlockState(pos, replaceState, 50);
                                                changed++;
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            skipped_unloaded += 256 * (max.getY() - min.getY() + 1); // rough estimate
                        }
                    }
                }
                
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("matched", matched);
                wrapper.addProperty("changed", changed);
                wrapper.addProperty("skipped_unloaded", skipped_unloaded);
                wrapper.add("sample", sample);
                
                if (!dryRun && changed > 0) {
                    String token = UUID.randomUUID().toString();
                    lastWriteBatch.put(token, prevStates);
                    wrapper.addProperty("undo_token", token);
                }
                
                future.complete(wrapper);
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        try {
            return GSON.toJson(future.get(30, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }

    public static String undoLastWrite(MinecraftServer server, String undoToken) {
        if (!lastWriteBatch.containsKey(undoToken)) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Invalid or expired undo token");
            return GSON.toJson(err);
        }
        
        JsonArray batch = lastWriteBatch.get(undoToken);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        
        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                int restored = 0;
                
                for (JsonElement el : batch) {
                    JsonObject obj = el.getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();
                    BlockPos pos = new BlockPos(x, y, z);
                    
                    String oldStateStr = obj.get("block").getAsString();
                    BlockState oldState = parseBlockState(oldStateStr);
                    
                    world.setBlockState(pos, oldState, 50);
                    
                    if (obj.has("nbt")) {
                        BlockEntity be = world.getBlockEntity(pos);
                        if (be != null) {
                            NbtCompound currentNbt = be.createNbtWithIdentifyingData(server.getRegistryManager());
                            NbtCompound mergeNbt = jsonToNbt(obj.getAsJsonObject("nbt"));
                            for (String key : mergeNbt.getKeys()) {
                                currentNbt.put(key, mergeNbt.get(key));
                            }
                            BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), currentNbt, server.getRegistryManager());
                            if (newBe != null) {
                                world.removeBlockEntity(pos);
                                world.addBlockEntity(newBe);
                                newBe.markDirty();
                            }
                        }
                    }
                    restored++;
                }
                
                lastWriteBatch.remove(undoToken); // Single use
                
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("success", true);
                wrapper.addProperty("restored", restored);
                future.complete(wrapper);
                
            } catch(Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        try {
            return GSON.toJson(future.get(30, TimeUnit.SECONDS));
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return GSON.toJson(err);
        }
    }
}
