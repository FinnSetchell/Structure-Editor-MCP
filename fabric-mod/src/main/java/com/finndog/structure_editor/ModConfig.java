package com.finndog.structure_editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

public class ModConfig {
    public String host = "127.0.0.1";
    public int port = 25580;
    public String apiKey = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ModConfig load() {
        File configFile = FabricLoader.getInstance().getConfigDir().resolve("structure_editor.json").toFile();
        ModConfig config;
        if(configFile.exists()) {
            try(FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, ModConfig.class);
                if(config == null) {
                    config = new ModConfig();
                }
            } catch(IOException e) {
                StructureEditorMod.LOGGER.error("Failed to load structure_editor config, using defaults", e);
                config = new ModConfig();
            }
        } else {
            config = new ModConfig();
        }

        if(config.apiKey == null || config.apiKey.trim().isEmpty()) {
            config.apiKey = generateRandomKey();
            save(config);
        }

        return config;
    }

    public static void save(ModConfig config) {
        File configFile = FabricLoader.getInstance().getConfigDir().resolve("structure_editor.json").toFile();
        try {
            configFile.getParentFile().mkdirs();
            try(FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(config, writer);
            }
        } catch(IOException e) {
            StructureEditorMod.LOGGER.error("Failed to save structure_editor config", e);
        }
    }

    private static String generateRandomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
