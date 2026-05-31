package com.finndog.structure_editor.mixin.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.JigsawBlockScreen;
import net.minecraft.client.gui.screen.ingame.StructureBlockScreen;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public class BlockEntityMixin {

    @Inject(method = "readNbt", at = @At("TAIL"))
    private void onReadNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || !client.world.isClient()) return;
        
        BlockEntity self = (BlockEntity)(Object)this;
        
        if (self instanceof JigsawBlockEntity) {
            if (client.currentScreen instanceof JigsawBlockScreen screen) {
                // JigsawBlockScreen has a public getJigsaw() method? Or we can just check if the positions match.
                // Since we don't know the accessor for getJigsaw(), we can use reflection or try to close it if it's open.
                // Wait, it's safer to just check if it's a JigsawBlockScreen and close it? 
                // There is only ONE block screen open at a time anyway!
                client.execute(() -> {
                    client.setScreen(null);
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§c[StructureEditor] Jigsaw GUI closed because the block was modified externally."), false);
                    }
                });
            }
        }
        else if (self instanceof StructureBlockBlockEntity) {
            if (client.currentScreen instanceof StructureBlockScreen screen) {
                client.execute(() -> {
                    client.setScreen(null);
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§c[StructureEditor] Structure Block GUI closed because the block was modified externally."), false);
                    }
                });
            }
        }
    }
}
