package com.finndog.structure_editor.mixin;

import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ServerLevel;

@Mixin(RandomizableContainerBlockEntity.class)
public abstract class LootableContainerBlockEntityMixin extends net.minecraft.world.level.block.entity.BlockEntity {
    public LootableContainerBlockEntityMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> type, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        super(type, pos, state);
    }

    @Shadow public abstract ResourceKey<LootTable> getLootTable();

    @Inject(method = "checkUnlocked", at = @At("HEAD"), cancellable = true)
    private void checkLootTableExists(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (this.getLootTable() != null && player != null && this.getLevel() instanceof ServerLevel serverWorld) {
            LootTable table = serverWorld.getServer().reloadableRegistries().getLootTable(this.getLootTable());
            if (table == LootTable.EMPTY) {
                player.displayClientMessage(Component.literal("[Structure Editor] Warning: Loot table '")
                        .append(Component.literal(this.getLootTable().location().toString()).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal("' not found! Kept ID in chest."))
                        .withStyle(ChatFormatting.RED), false);
                cir.setReturnValue(false); // Cancel opening to prevent consuming the lootTableId
            }
        }
    }
}
