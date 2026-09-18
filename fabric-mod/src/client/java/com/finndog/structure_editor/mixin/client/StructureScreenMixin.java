package com.finndog.structure_editor.mixin.client;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureBlockEditScreen.class)
public class StructureScreenMixin {

    @Shadow @Final private StructureBlockEntity structure;
    private String se_cachedName;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.se_cachedName = this.structure.getStructureName();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            BlockEntity current = client.level.getBlockEntity(this.structure.getBlockPos());
            if (current != this.structure || !this.structure.getStructureName().equals(this.se_cachedName)) {
                client.setScreen(null);
                if (client.player != null) {
                    client.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cClosed GUI: Block edited externally."), true);
                }
            }
        }
    }
}
