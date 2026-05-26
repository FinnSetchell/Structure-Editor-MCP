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

public class SelectionManager {

    private volatile BlockPos pos1;
    private volatile BlockPos pos2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SAVE_FILE = FabricLoader.getInstance().getConfigDir().resolve("structure_editor_selection.json").toFile();

    public SelectionManager() {
        load();
    }

    public void setPos1(BlockPos pos) {
        this.pos1 = pos;
        save();
    }

    public void setPos2(BlockPos pos) {
        this.pos2 = pos;
        save();
    }

    public BlockPos getPos1() {
        return pos1;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }

    public void clear() {
        pos1 = null;
        pos2 = null;
        save();
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

    private void save() {
        try {
            SAVE_FILE.getParentFile().mkdirs();
            JsonObject obj = new JsonObject();
            if(pos1 != null) {
                JsonObject p1 = new JsonObject();
                p1.addProperty("x", pos1.getX());
                p1.addProperty("y", pos1.getY());
                p1.addProperty("z", pos1.getZ());
                obj.add("pos1", p1);
            }
            if(pos2 != null) {
                JsonObject p2 = new JsonObject();
                p2.addProperty("x", pos2.getX());
                p2.addProperty("y", pos2.getY());
                p2.addProperty("z", pos2.getZ());
                obj.add("pos2", p2);
            }
            try(FileWriter writer = new FileWriter(SAVE_FILE)) {
                GSON.toJson(obj, writer);
            }
        } catch(IOException e) {
            StructureEditorMod.LOGGER.error("Failed to save selection region", e);
        }
    }

    private void load() {
        if(!SAVE_FILE.exists()) return;
        try(FileReader reader = new FileReader(SAVE_FILE)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if(obj.has("pos1") && !obj.get("pos1").isJsonNull()) {
                JsonObject p = obj.getAsJsonObject("pos1");
                pos1 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            }
            if(obj.has("pos2") && !obj.get("pos2").isJsonNull()) {
                JsonObject p = obj.getAsJsonObject("pos2");
                pos2 = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            }
        } catch(Exception e) {
            StructureEditorMod.LOGGER.error("Failed to load selection region", e);
        }
    }
}
