package com.finndog.structure_editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionManager {

    public static class Region {
        public BlockPos pos1;
        public BlockPos pos2;

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }

        public BlockPos getMin() {
            if(!isComplete()) return null;
            return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
            );
        }

        public BlockPos getMax() {
            if(!isComplete()) return null;
            return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
            );
        }
    }

    private final Map<String, Region> regions = new ConcurrentHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SAVE_FILE = FabricLoader.getInstance().getConfigDir().resolve("structure_editor_selection.json").toFile();

    public SelectionManager() {
        load();
        if(!regions.containsKey("default")) {
            regions.put("default", new Region());
        }
    }

    public Region getRegion(String name) {
        if(name == null || name.isEmpty()) name = "default";
        return regions.get(name);
    }

    public Region getOrCreateRegion(String name) {
        if(name == null || name.isEmpty()) name = "default";
        return regions.computeIfAbsent(name, k -> new Region());
    }

    public Map<String, Region> getRegions() {
        return Collections.unmodifiableMap(regions);
    }

    public void setPos1(String name, BlockPos pos) {
        getOrCreateRegion(name).pos1 = pos;
        save();
    }

    public void setPos2(String name, BlockPos pos) {
        getOrCreateRegion(name).pos2 = pos;
        save();
    }

    public void clear(String name) {
        if(name == null || name.isEmpty()) name = "default";
        regions.remove(name);
        if(name.equals("default")) {
            regions.put("default", new Region());
        }
        save();
    }

    public void save() {
        try {
            SAVE_FILE.getParentFile().mkdirs();
            JsonObject root = new JsonObject();
            for(Map.Entry<String, Region> entry : regions.entrySet()) {
                Region r = entry.getValue();
                JsonObject obj = new JsonObject();
                if(r.pos1 != null) {
                    JsonObject p1 = new JsonObject();
                    p1.addProperty("x", r.pos1.getX());
                    p1.addProperty("y", r.pos1.getY());
                    p1.addProperty("z", r.pos1.getZ());
                    obj.add("pos1", p1);
                }
                if(r.pos2 != null) {
                    JsonObject p2 = new JsonObject();
                    p2.addProperty("x", r.pos2.getX());
                    p2.addProperty("y", r.pos2.getY());
                    p2.addProperty("z", r.pos2.getZ());
                    obj.add("pos2", p2);
                }
                root.add(entry.getKey(), obj);
            }
            try(FileWriter writer = new FileWriter(SAVE_FILE)) {
                GSON.toJson(root, writer);
            }
        } catch(IOException e) {
            StructureEditorMod.LOGGER.error("Failed to save selection regions", e);
        }
    }

    private void load() {
        if(!SAVE_FILE.exists()) return;
        try(FileReader reader = new FileReader(SAVE_FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            
            // Backwards compatibility: if it has "pos1" at the root, it's the old format
            if(root.has("pos1") || root.has("pos2")) {
                Region defaultRegion = new Region();
                if(root.has("pos1") && !root.get("pos1").isJsonNull()) {
                    JsonObject p = root.getAsJsonObject("pos1");
                    defaultRegion.pos1 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                }
                if(root.has("pos2") && !root.get("pos2").isJsonNull()) {
                    JsonObject p = root.getAsJsonObject("pos2");
                    defaultRegion.pos2 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                }
                regions.put("default", defaultRegion);
                return;
            }

            for(String key : root.keySet()) {
                if(!root.get(key).isJsonObject()) continue;
                JsonObject obj = root.getAsJsonObject(key);
                Region r = new Region();
                if(obj.has("pos1") && !obj.get("pos1").isJsonNull()) {
                    JsonObject p = obj.getAsJsonObject("pos1");
                    r.pos1 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                }
                if(obj.has("pos2") && !obj.get("pos2").isJsonNull()) {
                    JsonObject p = obj.getAsJsonObject("pos2");
                    r.pos2 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                }
                regions.put(key, r);
            }
        } catch(Exception e) {
            StructureEditorMod.LOGGER.error("Failed to load selection regions", e);
        }
    }
}
