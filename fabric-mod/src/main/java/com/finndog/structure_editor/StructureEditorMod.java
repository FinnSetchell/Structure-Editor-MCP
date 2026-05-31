package com.finndog.structure_editor;

import com.finndog.structure_editor.network.SyncSelectionsPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

public class StructureEditorMod implements ModInitializer {

    public static final Logger LOGGER = LogManager.getLogger("structure-editor");
    public static final String MOD_ID = "structure_editor";

    private static ModConfig config;
    private static EditorHttpServer httpServer;
    private static MinecraftServer mcServer;
    
    private static final Map<UUID, String> activeRegions = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        config = ModConfig.load();
        LOGGER.info("Structure Editor initialising — HTTP bridge on {}:{}", config.host, config.port);

        httpServer = new EditorHttpServer(config);
        httpServer.start();

        PayloadTypeRegistry.playS2C().register(SyncSelectionsPayload.ID, SyncSelectionsPayload.CODEC);

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> mcServer = s);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> mcServer = null);

        // Send payload on join
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncSelectionsToPlayer(handler.player);
        });

        registerCommands();
        registerWandListener();
    }

    public static void syncSelectionsToAll() {
        if(mcServer == null || httpServer == null) return;
        for (ServerPlayerEntity player : mcServer.getPlayerManager().getPlayerList()) {
            syncSelectionsToPlayer(player);
        }
    }

    public static void syncSelectionsToPlayer(ServerPlayerEntity player) {
        if(httpServer == null) return;
        SelectionManager sel = httpServer.getSelection();
        Map<String, SyncSelectionsPayload.RegionData> regions = new HashMap<>();
        for (Map.Entry<String, SelectionManager.Region> entry : sel.getRegions().entrySet()) {
            regions.put(entry.getKey(), new SyncSelectionsPayload.RegionData(
                Optional.ofNullable(entry.getValue().pos1),
                Optional.ofNullable(entry.getValue().pos2)
            ));
        }
        ServerPlayNetworking.send(player, new SyncSelectionsPayload(regions));
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("sedit")
                .then(literal("wand")
                    .executes(ctx -> giveWand(ctx.getSource().getPlayer(), "default"))
                    .then(argument("region", word())
                        .suggests((ctx, builder) -> {
                            for (String region : httpServer.getSelection().getRegions().keySet()) {
                                if (region.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(region);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> giveWand(ctx.getSource().getPlayer(), getString(ctx, "region")))
                    )
                )
                .then(literal("region")
                    .then(literal("list")
                        .executes(ctx -> {
                            SelectionManager sel = httpServer.getSelection();
                            ctx.getSource().sendFeedback(() -> Text.literal("§7[StructureEditor]§f Saved regions: " + String.join(", ", sel.getRegions().keySet())), false);
                            return 1;
                        }))
                    .then(literal("clear")
                        .then(argument("name", word())
                            .executes(ctx -> {
                                String name = getString(ctx, "name");
                                httpServer.getSelection().clear(name);
                                syncSelectionsToAll();
                                ctx.getSource().sendFeedback(() -> Text.literal("§7[StructureEditor]§f Cleared region: " + name), false);
                                return 1;
                            }))))
                .then(literal("clear")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if(player == null) return 0;
                        getWandRegion(player.getMainHandStack()).ifPresentOrElse(region -> {
                            httpServer.getSelection().clear(region);
                            syncSelectionsToAll();
                            ctx.getSource().sendFeedback(() -> Text.literal("§7[StructureEditor]§f Cleared active region: " + region), false);
                        }, () -> {
                            ctx.getSource().sendError(Text.literal("§cYou must hold a Structure Editor Wand to clear its active region."));
                        });
                        return 1;
                    }))
            );
        });
    }

    private int giveWand(ServerPlayerEntity player, String regionName) {
        if(player == null) return 0;
        ItemStack wand = new ItemStack(Items.WOODEN_AXE);
        wand.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§dStructure Wand §8[§b" + regionName + "§8]"));
        
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putString("sedit_region", regionName);
        wand.set(DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(nbt));
        
        player.getInventory().insertStack(wand);
        player.sendMessage(Text.literal("§7[StructureEditor]§f Wand for region '"+regionName+"' added. Left-click=pos1, Right-click=pos2"), false);
        return 1;
    }

    public static Optional<String> getWandRegion(ItemStack stack) {
        if (stack == null || stack.getItem() != Items.WOODEN_AXE) return Optional.empty();
        net.minecraft.component.type.NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData != null) {
            net.minecraft.nbt.NbtCompound nbt = customData.copyNbt();
            if (nbt.contains("sedit_region")) {
                return (Optional<String>) (Object) nbt.getString("sedit_region");
            }
        }
        return Optional.empty();
    }

    private void registerWandListener() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if(world.isClient()) return ActionResult.PASS;
            if(!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            
            Optional<String> activeOpt = getWandRegion(player.getStackInHand(hand));
            if(activeOpt.isEmpty()) return ActionResult.PASS;
            
            String active = activeOpt.get();
            BlockPos pos = hitResult.getBlockPos();
            SelectionManager sel = httpServer.getSelection();
            SelectionManager.Region region = sel.getRegion(active);
            if(region != null && pos.equals(region.pos2)) return ActionResult.FAIL;

            sel.setPos2(active, pos);
            syncSelectionsToAll();
            sp.sendMessage(Text.literal("§7[StructureEditor]§f ["+active+"] pos2 set: " + pos.toShortString()), true);
            return ActionResult.SUCCESS;
        });

        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if(world.isClient()) return ActionResult.PASS;
            if(!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            
            Optional<String> activeOpt = getWandRegion(player.getStackInHand(hand));
            if(activeOpt.isEmpty()) return ActionResult.PASS;
            
            String active = activeOpt.get();
            SelectionManager sel = httpServer.getSelection();
            SelectionManager.Region region = sel.getRegion(active);
            if(region != null && pos.equals(region.pos1)) return ActionResult.FAIL;

            sel.setPos1(active, pos);
            syncSelectionsToAll();
            sp.sendMessage(Text.literal("§7[StructureEditor]§f ["+active+"] pos1 set: " + pos.toShortString()), true);
            return ActionResult.SUCCESS;
        });
    }

    public static ModConfig getConfig() {
        return config;
    }

    public static EditorHttpServer getHttpServer() {
        return httpServer;
    }
}
