package com.finndog.structure_editor.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record SyncSelectionsPayload(Map<String, RegionData> regions, String activeRegion) implements CustomPayload {

    public static final CustomPayload.Id<SyncSelectionsPayload> ID = new CustomPayload.Id<>(Identifier.of("structure_editor", "sync_selections"));

    public record RegionData(Optional<BlockPos> pos1, Optional<BlockPos> pos2) {}

    public static final PacketCodec<RegistryByteBuf, RegionData> REGION_CODEC = PacketCodec.tuple(
        BlockPos.PACKET_CODEC.collect(PacketCodecs::optional), RegionData::pos1,
        BlockPos.PACKET_CODEC.collect(PacketCodecs::optional), RegionData::pos2,
        RegionData::new
    );

    public static final PacketCodec<RegistryByteBuf, SyncSelectionsPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.map(HashMap::new, PacketCodecs.STRING, REGION_CODEC), SyncSelectionsPayload::regions,
        PacketCodecs.STRING, SyncSelectionsPayload::activeRegion,
        SyncSelectionsPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
