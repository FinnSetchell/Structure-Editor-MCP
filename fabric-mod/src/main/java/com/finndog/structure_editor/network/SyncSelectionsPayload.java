package com.finndog.structure_editor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record SyncSelectionsPayload(Map<String, RegionData> regions) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncSelectionsPayload> ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("structure_editor", "sync_selections"));

    public record RegionData(Optional<BlockPos> pos1, Optional<BlockPos> pos2) {}

    public static final StreamCodec<RegistryFriendlyByteBuf, RegionData> REGION_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), RegionData::pos1,
        BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), RegionData::pos2,
        RegionData::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSelectionsPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, REGION_CODEC), SyncSelectionsPayload::regions,
        SyncSelectionsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
