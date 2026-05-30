package com.finndog.structure_editor;

import com.google.gson.*;
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
import net.minecraft.registry.RegistryOps;

import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.List;
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
    public static String saveStructures(MinecraftServer server, SelectionManager.Region selection, JsonObject request) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                JsonObject result = new JsonObject();
                JsonArray savedList = new JsonArray();
                ServerWorld world = server.getOverworld();

                if (request.has("x") && request.has("y") && request.has("z")) {
                    int x = request.get("x").getAsInt();
                    int y = request.get("y").getAsInt();
                    int z = request.get("z").getAsInt();
                    BlockPos pos = new BlockPos(x, y, z);
                    
                    world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    BlockEntity be = world.getBlockEntity(pos);

                    if (be instanceof StructureBlockBlockEntity structBlock) {
                        boolean success = structBlock.saveStructure();
                        JsonObject item = new JsonObject();
                        item.addProperty("x", pos.getX());
                        item.addProperty("y", pos.getY());
                        item.addProperty("z", pos.getZ());
                        
                        NbtCompound nbt = structBlock.createNbt(server.getRegistryManager());
                        String name = nbt.getString("name").orElse("");
                        item.addProperty("name", name);
                        
                        item.addProperty("success", success);
                        savedList.add(item);
                    } else {
                        result.addProperty("error", "Block at " + pos.toShortString() + " is not a structure block");
                        future.complete(result);
                        return;
                    }
                } else {
                    if (!selection.isComplete()) {
                        result.addProperty("error", "No complete selection active");
                        future.complete(result);
                        return;
                    }

                    BlockPos min = selection.getMin();
                    BlockPos max = selection.getMax();

                    int minChunkX = min.getX() >> 4;
                    int maxChunkX = max.getX() >> 4;
                    int minChunkZ = min.getZ() >> 4;
                    int maxChunkZ = max.getZ() >> 4;

                    int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                    if (chunkCount > 4096) {
                        throw new IllegalArgumentException("Selection covers too many chunks to scan safely (max: 4096)");
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
                                        if (be instanceof StructureBlockBlockEntity structBlock) {
                                            boolean success = structBlock.saveStructure();
                                            JsonObject item = new JsonObject();
                                            item.addProperty("x", pos.getX());
                                            item.addProperty("y", pos.getY());
                                            item.addProperty("z", pos.getZ());
                                            
                                            NbtCompound nbt = structBlock.createNbt(server.getRegistryManager());
                                            String name = nbt.getString("name").orElse("");
                                            item.addProperty("name", name);
                                            
                                            item.addProperty("success", success);
                                            savedList.add(item);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                result.addProperty("count", savedList.size());
                result.add("results", savedList);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return GSON.toJson(future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
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
        if(!request.has("x") || !request.has("y") || !request.has("z")) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Request must include x, y, z");
            return GSON.toJson(err);
        }

        int x = request.get("x").getAsInt();
        int y = request.get("y").getAsInt();
        int z = request.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                BlockEntity be = world.getBlockEntity(pos);

                if(be == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "No block entity at " + pos.toShortString());
                    future.complete(err);
                    return;
                }
                if(!(be instanceof Inventory inv)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Block at " + pos.toShortString() + " is not a container (got " + be.getClass().getSimpleName() + ")");
                    future.complete(err);
                    return;
                }

                JsonObject result = new JsonObject();
                result.addProperty("type", be.getClass().getSimpleName());
                result.addProperty("size", inv.size());

                // Read loot table if present
                if(be instanceof LootableContainerBlockEntity lootable) {
                    RegistryKey<LootTable> lootKey = lootable.getLootTable();
                    result.addProperty("loot_table", lootKey != null ? lootKey.getValue().toString() : null);
                    result.addProperty("loot_table_seed", lootable.getLootTableSeed());
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
        if(!request.has("x") || !request.has("y") || !request.has("z")) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Request must include x, y, z");
            return GSON.toJson(err);
        }

        int x = request.get("x").getAsInt();
        int y = request.get("y").getAsInt();
        int z = request.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            try {
                ServerWorld world = server.getOverworld();
                RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                BlockEntity be = world.getBlockEntity(pos);

                if(be == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "No block entity at " + pos.toShortString());
                    future.complete(err);
                    return;
                }
                if(!(be instanceof Inventory inv)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Block at " + pos.toShortString() + " is not a container (got " + be.getClass().getSimpleName() + ")");
                    future.complete(err);
                    return;
                }

                // Always clear first
                inv.clear();

                if(request.has("loot_table") && !request.get("loot_table").isJsonNull()) {
                    // Loot table mode — mutually exclusive with items
                    if(!(be instanceof LootableContainerBlockEntity lootable)) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Block at " + pos.toShortString() + " does not support loot tables (" + be.getClass().getSimpleName() + ")");
                        future.complete(err);
                        return;
                    }
                    String lootTableId = request.get("loot_table").getAsString();
                    long seed = request.has("loot_table_seed") ? request.get("loot_table_seed").getAsLong() : 0L;
                    RegistryKey<LootTable> lootKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(lootTableId));
                    lootable.setLootTable(lootKey, seed);
                    be.markDirty();
                    world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                    JsonObject ok = new JsonObject();
                    ok.addProperty("success", true);
                    ok.addProperty("mode", "loot_table");
                    ok.addProperty("loot_table", lootTableId);
                    ok.addProperty("loot_table_seed", seed);
                    future.complete(ok);
                    return;
                }

                // Item write mode — clear loot table if present
                if(be instanceof LootableContainerBlockEntity lootable) {
                    lootable.setLootTable(null, 0L);
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
                        ItemStack stack = ItemStack.CODEC.parse(nbtOps, itemNbt).resultOrPartial(e -> {}).orElse(ItemStack.EMPTY);
                        if(!stack.isEmpty()) {
                            inv.setStack(slot, stack);
                            written++;
                        }
                    }
                }

                be.markDirty();
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

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
                                        
                                        if (be instanceof LootableContainerBlockEntity lootable) {
                                            RegistryKey<LootTable> lootKey = lootable.getLootTable();
                                            obj.addProperty("loot_table", lootKey != null ? lootKey.getValue().toString() : null);
                                            obj.addProperty("loot_table_seed", lootable.getLootTableSeed());
                                        } else if (be instanceof net.minecraft.block.entity.VaultBlockEntity) {
                                            NbtCompound nbt = be.createNbt(registries);
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
                                            NbtCompound nbt = be.createNbt(registries);
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

    public static String scanEntities(MinecraftServer server, SelectionManager.Region selection, JsonArray targetEntities) {
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
                Box box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
                
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
                wrapper.add("entities", results);
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
}
