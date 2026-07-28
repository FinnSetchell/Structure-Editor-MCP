package com.finndog.structure_editor.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.world.ServerWorld;

@Mixin(LootableContainerBlockEntity.class)
public abstract class LootableContainerBlockEntityMixin extends net.minecraft.block.entity.BlockEntity {
    public LootableContainerBlockEntityMixin(net.minecraft.block.entity.BlockEntityType<?> type, net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state) {
        super(type, pos, state);
    }

    @Shadow public abstract RegistryKey<LootTable> getLootTable();

    @Inject(method = "checkUnlocked", at = @At("HEAD"), cancellable = true)
    private void checkLootTableExists(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (this.getLootTable() != null && player != null && this.getWorld() instanceof ServerWorld serverWorld) {
            LootTable table = serverWorld.getServer().getReloadableRegistries().getLootTable(this.getLootTable());
            if (table == LootTable.EMPTY) {
                player.sendMessage(Text.literal("[Structure Editor] Warning: Loot table '")
                        .append(Text.literal(this.getLootTable().getValue().toString()).formatted(Formatting.YELLOW))
                        .append(Text.literal("' not found! Kept ID in chest."))
                        .formatted(Formatting.RED), false);
                cir.setReturnValue(false); // Cancel opening to prevent consuming the lootTableId
            }
        }
    }
}
