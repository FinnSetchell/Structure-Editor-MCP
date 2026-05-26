package com.finndog.structure_editor;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.minecraft.server.command.CommandManager.literal;

public class StructureEditorMod implements ModInitializer {

    public static final Logger LOGGER = LogManager.getLogger("structure-editor");
    public static final String MOD_ID = "structure_editor";

    private static ModConfig config;
    private static EditorHttpServer httpServer;

    @Override
    public void onInitialize() {
        config = ModConfig.load();
        LOGGER.info("Structure Editor initialising — HTTP bridge on {}:{}", config.host, config.port);

        httpServer = new EditorHttpServer(config);
        httpServer.start();

        registerCommands();
        registerWandListener();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("sedit")
                .then(literal("wand")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if(player == null) return 0;
                        player.getInventory().insertStack(Items.STICK.getDefaultStack());
                        player.sendMessage(Text.literal("[StructureEditor] Stick added — left-click block = pos1, right-click = pos2"), false);
                        return 1;
                    }))
                .then(literal("status")
                    .executes(ctx -> {
                        SelectionManager sel = httpServer.getSelection();
                        String msg;
                        if(sel.getPos1() == null || sel.getPos2() == null) {
                            msg = "[StructureEditor] No full selection yet. pos1=" + sel.getPos1() + " pos2=" + sel.getPos2();
                        } else {
                            msg = "[StructureEditor] Selection: " + sel.getPos1() + " -> " + sel.getPos2();
                        }
                        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
                        return 1;
                    }))
                .then(literal("clear")
                    .executes(ctx -> {
                        httpServer.getSelection().clear();
                        ctx.getSource().sendFeedback(() -> Text.literal("[StructureEditor] Selection cleared"), false);
                        return 1;
                    }))
            );
        });
    }

    private void registerWandListener() {
        // Right-click with a stick on a block sets pos2
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if(world.isClient()) return ActionResult.PASS;
            if(!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if(player.getMainHandStack().getItem() != Items.STICK) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            SelectionManager sel = httpServer.getSelection();
            if(pos.equals(sel.getPos2())) return ActionResult.FAIL;

            sel.setPos2(pos);
            sp.sendMessage(Text.literal("[StructureEditor] pos2 set: " + pos.toShortString()), false);
            return ActionResult.SUCCESS;
        });

        // Left-click (attack) with a stick on a block sets pos1 — use AttackBlockCallback
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if(world.isClient()) return ActionResult.PASS;
            if(!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if(player.getMainHandStack().getItem() != Items.STICK) return ActionResult.PASS;

            SelectionManager sel = httpServer.getSelection();
            if(pos.equals(sel.getPos1())) return ActionResult.FAIL;

            sel.setPos1(pos);
            sp.sendMessage(Text.literal("[StructureEditor] pos1 set: " + pos.toShortString()), false);
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
