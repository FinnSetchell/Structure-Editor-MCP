package com.finndog.structure_editor.mixin.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.JigsawBlockScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JigsawBlockScreen.class)
public class JigsawScreenMixin {

    @Shadow @Final private JigsawBlockEntity jigsaw;
    private String se_cachedName;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.se_cachedName = this.jigsaw.getName().toString();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            BlockEntity current = client.world.getBlockEntity(this.jigsaw.getPos());
            if (current != this.jigsaw || !this.jigsaw.getName().toString().equals(this.se_cachedName)) {
                client.setScreen(null);
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal("§cClosed GUI: Block edited externally."), true);
                }
            }
        }
    }
}
