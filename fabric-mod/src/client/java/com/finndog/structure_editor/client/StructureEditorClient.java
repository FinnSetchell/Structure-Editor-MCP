package com.finndog.structure_editor.client;

import com.finndog.structure_editor.network.SyncSelectionsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;

public class StructureEditorClient implements ClientModInitializer {

    private static Map<String, SyncSelectionsPayload.RegionData> clientRegions = new HashMap<>();

    @Override
    public void onInitializeClient() {
        SelectionRenderer.register();

        ClientPlayNetworking.registerGlobalReceiver(SyncSelectionsPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                clientRegions = payload.regions();
            });
        });
    }

    public static Map<String, SyncSelectionsPayload.RegionData> getClientRegions() {
        return clientRegions;
    }
}
