package com.finndog.structure_editor.mixin.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.StructureBlockScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureBlockScreen.class)
public class StructureScreenMixin {

    @Shadow @Final private StructureBlockBlockEntity structureBlock;
    private String se_cachedName;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.se_cachedName = this.structureBlock.getTemplateName();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            BlockEntity current = client.world.getBlockEntity(this.structureBlock.getPos());
            if (current != this.structureBlock || !this.structureBlock.getTemplateName().equals(this.se_cachedName)) {
                client.setScreen(null);
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal("§cClosed GUI: Block edited externally."), true);
                }
            }
        }
    }
}
