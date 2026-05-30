package com.finndog.structure_editor;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

// Embeds a lightweight HTTP server inside the mod using the JDK's built-in com.sun.net.httpserver.
// All Minecraft world access is deferred back to the main server thread via server.execute().
public class EditorHttpServer {

    private final ModConfig config;
    private final SelectionManager selection = new SelectionManager();
    private HttpServer server;
    private volatile MinecraftServer mcServer;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public EditorHttpServer(ModConfig config) {
        this.config = config;

        // Grab the MinecraftServer reference as soon as it's ready
        ServerLifecycleEvents.SERVER_STARTED.register(s -> this.mcServer = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> this.mcServer = null);
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.host, config.port), 0);
            server.createContext("/health",    new HealthHandler());
            server.createContext("/selection", new SelectionHandler());
            server.createContext("/scan",      new ScanHandler());
            server.createContext("/scan/containers", new ContainerScanHandler());
            server.createContext("/edit",      new EditHandler());
            server.createContext("/batch",     new BatchHandler());
            server.createContext("/save",      new SaveHandler());
            server.createContext("/download",  new DownloadHandler());
            server.createContext("/container", new ContainerHandler());
            server.createContext("/container/batch", new BatchContainerHandler());
            server.createContext("/scan/blocks", new BlockScanHandler());
            server.createContext("/scan/entities", new EntityScanHandler());
            server.createContext("/structure/palette", new StructurePaletteHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            StructureEditorMod.LOGGER.info("Structure Editor HTTP server started on http://{}:{}", config.host, config.port);
        } catch(IOException e) {
            StructureEditorMod.LOGGER.error("Failed to start HTTP server: {}", e.getMessage());
        }
    }

    public void stop() {
        if(server != null) server.stop(0);
    }

    public SelectionManager getSelection() {
        return selection;
    }

    //////////////////////////////

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try(OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try(InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonObject errorJson(String msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", msg);
        return obj;
    }

    private boolean serverReady(HttpExchange exchange) throws IOException {
        if(mcServer == null) {
            sendJson(exchange, 503, GSON.toJson(errorJson("Server not ready yet")));
            return false;
        }
        return true;
    }

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        String apiKey = config.apiKey;
        if(apiKey == null || apiKey.trim().isEmpty()) {
            return true;
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if(authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if(token.equals(apiKey.trim())) {
                return true;
            }
        }
        String xApiKey = exchange.getRequestHeaders().getFirst("x-api-key");
        if(xApiKey != null && xApiKey.trim().equals(apiKey.trim())) {
            return true;
        }
        JsonObject err = new JsonObject();
        err.addProperty("error", "Unauthorized");
        sendJson(exchange, 401, GSON.toJson(err));
        return false;
    }

    private void broadcastActionBar(String message) {
        if(mcServer != null) {
            mcServer.execute(() -> {
                Text text = Text.literal("§7[Editor] §f" + message);
                mcServer.getPlayerManager().getPlayerList().forEach(player -> {
                    player.sendMessage(text, true);
                });
            });
        }
    }


    private String getRegionName(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if(query != null) {
            for(String param : query.split("&")) {
                String[] pair = param.split("=");
                if(pair.length > 1 && "region".equals(pair[0])) {
                    try {
                        return java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                    } catch(Exception e) {}
                }
            }
        }
        return "default";
    }

    //////////////////////////////

    // GET /health — basic liveness check
    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "ok");
            obj.addProperty("port", config.port);
            obj.addProperty("serverReady", mcServer != null);
            SelectionManager.Region r = selection.getRegion("default");
            if(r != null && r.pos1 != null) {
                obj.addProperty("pos1", r.pos1.toShortString());
            }
            if(r != null && r.pos2 != null) {
                obj.addProperty("pos2", r.pos2.toShortString());
            }
            sendJson(exchange, 200, GSON.toJson(obj));
        }
    }

    // GET/POST /selection — get or set the selection region
    // GET returns current pos1/pos2
    // POST body: { "pos1": {"x":0,"y":64,"z":0}, "pos2": {"x":10,"y":70,"z":10} }
    // Either pos1 or pos2 can be omitted to only update one corner
    class SelectionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            String regionName = getRegionName(exchange);
            
            if("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("list=true")) {
                    JsonObject obj = new JsonObject();
                    for(java.util.Map.Entry<String, SelectionManager.Region> e : selection.getRegions().entrySet()) {
                        JsonObject p = new JsonObject();
                        p.addProperty("complete", e.getValue().isComplete());
                        obj.add(e.getKey(), p);
                    }
                    sendJson(exchange, 200, GSON.toJson(obj));
                    return;
                }
                
                SelectionManager.Region r = selection.getRegion(regionName);
                JsonObject obj = new JsonObject();
                if(r != null && r.pos1 != null) {
                    JsonObject p1 = new JsonObject();
                    p1.addProperty("x", r.pos1.getX());
                    p1.addProperty("y", r.pos1.getY());
                    p1.addProperty("z", r.pos1.getZ());
                    obj.add("pos1", p1);
                } else {
                    obj.add("pos1", JsonNull.INSTANCE);
                }
                if(r != null && r.pos2 != null) {
                    JsonObject p2 = new JsonObject();
                    p2.addProperty("x", r.pos2.getX());
                    p2.addProperty("y", r.pos2.getY());
                    p2.addProperty("z", r.pos2.getZ());
                    obj.add("pos2", p2);
                } else {
                    obj.add("pos2", JsonNull.INSTANCE);
                }
                obj.addProperty("complete", r != null && r.isComplete());
                sendJson(exchange, 200, GSON.toJson(obj));
            }
            else if("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    if(body.has("pos1") && body.get("pos1").isJsonObject()) {
                        JsonObject p = body.getAsJsonObject("pos1");
                        selection.setPos1(regionName, new net.minecraft.util.math.BlockPos(
                            p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()
                        ));
                    }
                    if(body.has("pos2") && body.get("pos2").isJsonObject()) {
                        JsonObject p = body.getAsJsonObject("pos2");
                        selection.setPos2(regionName, new net.minecraft.util.math.BlockPos(
                            p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()
                        ));
                    }
                    SelectionManager.Region r = selection.getRegion(regionName);
                    JsonObject ok = new JsonObject();
                    ok.addProperty("success", true);
                    ok.addProperty("complete", r.isComplete());
                    sendJson(exchange, 200, GSON.toJson(ok));
                    if(r.isComplete()) {
                        broadcastActionBar("Selection '" + regionName + "' complete");
                    } else {
                        broadcastActionBar("Selection '" + regionName + "' updated");
                    }
                    StructureEditorMod.syncSelectionsToAll();
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad JSON: " + e.getMessage())));
                }
            }
            else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    // GET /scan — scan the selected region and return all jigsaw/structure blocks
    class ScanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            
            String query = exchange.getRequestURI().getQuery();
            String nameFilter = null;
            if(query != null) {
                for(String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if(pair.length > 1 && "name".equals(pair[0])) {
                        nameFilter = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        break;
                    }
                }
            }

            String result = BlockScanner.scanSelection(mcServer, selection.getOrCreateRegion(getRegionName(exchange)), nameFilter);
            sendJson(exchange, 200, result);
        }
    }

    // GET /scan/containers — scan the selected region and return all containers
    class ContainerScanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String result = BlockScanner.scanContainers(mcServer, selection.getOrCreateRegion(getRegionName(exchange)));
            sendJson(exchange, 200, result);
        }
    }

    // POST /edit — edit a single block
    // Body: { "x": 0, "y": 64, "z": 0, "fields": { "name": "new:name", "pool": "new:pool" } }
    class EditHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                String result = BlockScanner.editBlock(mcServer, body);
                sendJson(exchange, 200, result);
                if(body.has("x") && body.has("y") && body.has("z")) {
                    broadcastActionBar("Edited block at " + body.get("x").getAsInt() + ", " + body.get("y").getAsInt() + ", " + body.get("z").getAsInt());
                }
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    // POST /batch — edit multiple blocks at once
    // Body: [ { "x":0,"y":64,"z":0,"fields":{...} }, ... ]
    class BatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonArray body = JsonParser.parseString(readBody(exchange)).getAsJsonArray();
                String result = BlockScanner.editBatch(mcServer, body);
                sendJson(exchange, 200, result);
                
                try {
                    JsonObject resObj = JsonParser.parseString(result).getAsJsonObject();
                    if(resObj.has("edited")) {
                        broadcastActionBar("Batch edited " + resObj.get("edited").getAsInt() + " blocks");
                    }
                } catch(Exception ignored) {}
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    class SaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String bodyStr = readBody(exchange);
                JsonObject body = bodyStr.trim().isEmpty() ? new JsonObject() : JsonParser.parseString(bodyStr).getAsJsonObject();
                String result = BlockScanner.saveStructures(mcServer, selection.getOrCreateRegion(getRegionName(exchange)), body);
                sendJson(exchange, 200, result);
                
                try {
                    JsonObject resObj = JsonParser.parseString(result).getAsJsonObject();
                    if(resObj.has("saved")) {
                        broadcastActionBar("Saved " + resObj.get("saved").getAsInt() + " structure(s)");
                    }
                } catch(Exception ignored) {}
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String nameParam = null;
                if(query != null) {
                    for(String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if(pair.length > 1 && "name".equals(pair[0])) {
                            nameParam = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                            break;
                        }
                    }
                }

                if(nameParam == null || nameParam.trim().isEmpty()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Missing 'name' query parameter");
                    sendJson(exchange, 400, GSON.toJson(err));
                    return;
                }

                String namespace = "minecraft";
                String path = nameParam;
                if(nameParam.contains(":")) {
                    String[] parts = nameParam.split(":", 2);
                    namespace = parts[0];
                    path = parts[1];
                }

                java.nio.file.Path generatedDir = mcServer.getSavePath(net.minecraft.util.WorldSavePath.GENERATED);
                java.nio.file.Path file = generatedDir.resolve(namespace).resolve("structures").resolve(path + ".nbt");

                if(!java.nio.file.Files.exists(file) || java.nio.file.Files.isDirectory(file)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Structure file not found: " + nameParam);
                    sendJson(exchange, 404, GSON.toJson(err));
                    return;
                }

                byte[] bytes = java.nio.file.Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + path.substring(path.lastIndexOf('/') + 1) + ".nbt\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, bytes.length);
                try(OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch(Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "Download failed: " + e.getMessage());
                sendJson(exchange, 500, GSON.toJson(err));
            }
        }
    }
    class ContainerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;

            if("GET".equals(exchange.getRequestMethod())) {
                // Parse x, y, z from query string
                String query = exchange.getRequestURI().getQuery();
                JsonObject req = new JsonObject();
                if(query != null) {
                    for(String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if(pair.length > 1) {
                            try { req.addProperty(pair[0], Integer.parseInt(pair[1])); }
                            catch(NumberFormatException ignored) {}
                        }
                    }
                }
                String result = BlockScanner.readContainer(mcServer, req);
                sendJson(exchange, 200, result);
            }
            else if("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    String result = BlockScanner.writeContainer(mcServer, body);
                    sendJson(exchange, 200, result);
                    
                    try {
                        JsonObject resObj = JsonParser.parseString(result).getAsJsonObject();
                        if(resObj.has("mode")) {
                            if("items".equals(resObj.get("mode").getAsString())) {
                                broadcastActionBar("Container updated with items");
                            } else {
                                broadcastActionBar("Container assigned loot table: " + resObj.get("loot_table").getAsString());
                            }
                        }
                    } catch(Exception ignored) {}
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            }
            else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    class BatchContainerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonArray body = JsonParser.parseString(readBody(exchange)).getAsJsonArray();
                String result = BlockScanner.writeContainersBatch(mcServer, body);
                sendJson(exchange, 200, result);
                
                try {
                    JsonObject resObj = JsonParser.parseString(result).getAsJsonObject();
                    if(resObj.has("successful_writes")) {
                        broadcastActionBar("Batch wrote " + resObj.get("successful_writes").getAsInt() + " containers");
                    }
                } catch(Exception ignored) {}
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    class BlockScanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonArray body = JsonParser.parseString(readBody(exchange)).getAsJsonArray();
                String result = BlockScanner.scanBlocks(mcServer, selection.getOrCreateRegion(getRegionName(exchange)), body);
                sendJson(exchange, 200, result);
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    class EntityScanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonArray body = JsonParser.parseString(readBody(exchange)).getAsJsonArray();
                String result = BlockScanner.scanEntities(mcServer, selection.getOrCreateRegion(getRegionName(exchange)), body);
                sendJson(exchange, 200, result);
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    class StructurePaletteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String name = null;
            if(query != null) {
                for(String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if(pair.length > 1 && "name".equals(pair[0])) {
                        name = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            if(name == null) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Missing 'name' query parameter")));
                return;
            }
            String result = BlockScanner.getStructurePalette(mcServer, name);
            sendJson(exchange, 200, result);
        }
    }
}
