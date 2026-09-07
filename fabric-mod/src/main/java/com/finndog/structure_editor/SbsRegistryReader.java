package com.finndog.structure_editor;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Reads StructureBlockSaver's persistent tracker file directly from disk.
// Kept schema-compatible with SBS's StructureTrackerData codec so no classpath
// dependency on SBS is needed.
public class SbsRegistryReader {

    public static final String TRACKER_FILE = "sbs_structure_tracker.dat";

    public static class Entry {
        public final BlockPos pos;
        public final String name;
        public final String mode;
        public final Identifier dimension;

        public Entry(BlockPos pos, String name, String mode, Identifier dimension) {
            this.pos = pos;
            this.name = name;
            this.mode = mode;
            this.dimension = dimension;
        }
    }

    public static class Result {
        public final boolean present;
        public final List<Entry> entries;

        private Result(boolean present, List<Entry> entries) {
            this.present = present;
            this.entries = entries;
        }

        public static Result missing() { return new Result(false, List.of()); }
        public static Result of(List<Entry> entries) { return new Result(true, entries); }
    }

    // Overworld / nether / end map to <root>/data, <root>/DIM-1/data, <root>/DIM1/data.
    // Custom dimensions live at <root>/dimensions/<namespace>/<path>/data.
    private static Path dataDirFor(MinecraftServer server, RegistryKey<World> dim) {
        Path root = server.getSavePath(WorldSavePath.ROOT);
        if (dim.equals(World.OVERWORLD)) return root.resolve("data");
        if (dim.equals(World.NETHER))    return root.resolve("DIM-1").resolve("data");
        if (dim.equals(World.END))       return root.resolve("DIM1").resolve("data");
        Identifier id = dim.getValue();
        return root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("data");
    }

    public static Result read(MinecraftServer server, RegistryKey<World> dim) {
        Path file = dataDirFor(server, dim).resolve(TRACKER_FILE);
        if (!Files.isRegularFile(file)) {
            return Result.missing();
        }
        NbtCompound root;
        try {
            root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            StructureEditorMod.LOGGER.warn("Failed to read sbs tracker at {}: {}", file, e.toString());
            return Result.missing();
        }
        NbtCompound data = root.getCompound("data").orElse(null);
        if (data == null) return Result.of(List.of());
        NbtList list = data.getList("structures").orElse(null);
        if (list == null) return Result.of(List.of());

        Identifier dimId = dim.getValue();
        List<Entry> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i).orElse(null);
            if (entry == null) continue;
            int[] posArr = entry.getIntArray("pos").orElse(null);
            if (posArr == null || posArr.length < 3) continue;
            String name = entry.getString("name").orElse("");
            String mode = entry.getString("mode").orElse("DATA");
            out.add(new Entry(new BlockPos(posArr[0], posArr[1], posArr[2]), name, mode, dimId));
        }
        return Result.of(out);
    }

    // Yarn 1.21.5+ moved a bunch of NbtCompound getters to Optional. NbtList.getCompound
    // isn't Optional-based in every subversion, so tolerate either shape.
    // (Handled directly with orElse above.)
}
