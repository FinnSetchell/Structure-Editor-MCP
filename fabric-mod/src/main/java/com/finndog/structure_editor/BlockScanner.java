package com.finndog.structure_editor;

import com.google.gson.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import net.minecraft.world.chunk.WorldChunk;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BlockScanner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Scans all jigsaw and structure blocks in the selection, returns JSON array string.
    // This is called from the HTTP handler thread, so we submit work to the main server thread
    // and wait for the result.
    public static String scanSelection(MinecraftServer server, SelectionManager selection) {
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
                if (chunkCount > 1024) {
                    throw new IllegalArgumentException("Selection covers " + chunkCount + " chunks, exceeding the safety limit of 1024 chunks (~512x512 blocks). Please make a smaller selection.");
                }

                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, true);
                        if (chunk != null) {
                            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                                BlockPos pos = entry.getKey();
                                if (pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                                    pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                                    pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                                    
                                    BlockEntity be = entry.getValue();
                                    if (be instanceof JigsawBlockEntity jigsaw) {
                                        results.add(jigsawToJson(jigsaw, pos, registries));
                                    } else if (be instanceof StructureBlockBlockEntity structBlock) {
                                        results.add(structureBlockToJson(structBlock, pos, registries));
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
                world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
                
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

                // Read current NBT, apply field overrides, write back
                NbtCompound nbt = be.createNbt(registries);

                for(String key : fields.keySet()) {
                    JsonElement val = fields.get(key);
                    applyNbtField(nbt, key, val);
                }

                BlockEntity newBe = BlockEntity.createFromNbt(pos, world.getBlockState(pos), nbt, registries);
                if (newBe != null) {
                    world.removeBlockEntity(pos);
                    world.addBlockEntity(newBe);
                    newBe.markDirty();
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
    public static String saveStructures(MinecraftServer server, SelectionManager selection, JsonObject request) {
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
                    
                    world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
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
                    if (chunkCount > 1024) {
                        throw new IllegalArgumentException("Selection covers too many chunks to scan safely");
                    }

                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, true);
                            if (chunk != null) {
                                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                                    BlockPos pos = entry.getKey();
                                    if (pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                                        pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                                        pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                                        
                                        BlockEntity be = entry.getValue();
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

        // Also include the full raw NBT so nothing is hidden
        obj.add("nbt", nbtToJson(nbt));
        return obj;
    }

    private static JsonObject structureBlockToJson(StructureBlockBlockEntity structBlock, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "structure_block");
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());

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

        obj.add("nbt", nbtToJson(nbt));
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
}
