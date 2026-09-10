package com.finndog.structure_editor;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
            server.createContext("/nbt/block", new BlockNbtHandler());
            server.createContext("/block/get", new BlockGetHandler());
            server.createContext("/block/set", new BlockSetHandler());
            server.createContext("/block/replace", new BlockReplaceHandler());
            server.createContext("/block/undo", new BlockUndoHandler());
            server.createContext("/file/write", new FileWriteHandler());
            server.createContext("/structure-blocks", new StructureBlocksHandler());
            server.createContext("/selection/from-structure", new SelectionFromStructureHandler());
            server.createContext("/structure-bounds", new StructureBoundsHandler());
            server.createContext("/selection/remove", new SelectionRemoveHandler());
            server.createContext("/log/tail", new LogTailHandler());
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


    // Ephemeral region built from request params for stateless multi-agent use. If both
    // pos1 and pos2 are present (JSON body objects, or x1/y1/z1 & x2/y2/z2 query params),
    // returns a stack-local Region with those coords so nothing in the shared selection
    // store is touched. Otherwise falls back to the stored region named in the request.
    private SelectionManager.Region resolveWorkingRegion(HttpExchange exchange, JsonObject body) {
        BlockPos p1 = readPosFromBody(body, "pos1");
        BlockPos p2 = readPosFromBody(body, "pos2");
        if(p1 == null || p2 == null) {
            BlockPos qp1 = readPosFromQuery(exchange, "x1", "y1", "z1");
            BlockPos qp2 = readPosFromQuery(exchange, "x2", "y2", "z2");
            if(qp1 != null && qp2 != null) { p1 = qp1; p2 = qp2; }
        }
        if(p1 != null && p2 != null) {
            SelectionManager.Region r = new SelectionManager.Region();
            r.pos1 = p1;
            r.pos2 = p2;
            return r;
        }
        return selection.getOrCreateRegion(getRegionName(exchange));
    }

    private BlockPos readPosFromBody(JsonObject body, String key) {
        if(body == null || !body.has(key) || !body.get(key).isJsonObject()) return null;
        try {
            JsonObject o = body.getAsJsonObject(key);
            return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
        } catch(Exception e) { return null; }
    }

    private BlockPos readPosFromQuery(HttpExchange exchange, String xk, String yk, String zk) {
        String x = queryParam(exchange, xk), y = queryParam(exchange, yk), z = queryParam(exchange, zk);
        if(x == null || y == null || z == null) return null;
        try {
            return new BlockPos(Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
        } catch(NumberFormatException e) { return null; }
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

            String result = BlockScanner.scanSelection(mcServer, resolveWorkingRegion(exchange, null), nameFilter);
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
            String result = BlockScanner.scanContainers(mcServer, resolveWorkingRegion(exchange, null));
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
                String result = BlockScanner.saveStructures(mcServer, resolveWorkingRegion(exchange, body), body);
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
                String result = BlockScanner.scanBlocks(mcServer, resolveWorkingRegion(exchange, null), body);
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
                String result = BlockScanner.scanEntities(mcServer, resolveWorkingRegion(exchange, null), body);
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

    class BlockNbtHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                String query = exchange.getRequestURI().getQuery();
                JsonObject req = new JsonObject();
                if(query != null) {
                    for(String param : query.split("&")) {
                        String[] kv = param.split("=");
                        if(kv.length == 2) {
                            if(kv[0].equals("x") || kv[0].equals("y") || kv[0].equals("z")) {
                                req.addProperty(kv[0], Integer.parseInt(kv[1]));
                            }
                        }
                    }
                }
                String result = BlockScanner.readBlockNbt(mcServer, req);
                sendJson(exchange, 200, result);
            } else if ("POST".equals(method)) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    String result = BlockScanner.writeBlockNbt(mcServer, body);
                    sendJson(exchange, 200, result);
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    class BlockGetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String bodyStr = readBody(exchange);
                    JsonObject body = bodyStr.isEmpty() ? new JsonObject() : JsonParser.parseString(bodyStr).getAsJsonObject();
                    SelectionManager.Region r = resolveWorkingRegion(exchange, body);
                    JsonArray posList = body.has("positions") ? body.getAsJsonArray("positions") : null;
                    String result = BlockScanner.getBlocks(mcServer, r, posList);
                    sendJson(exchange, 200, result);
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    class BlockSetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    JsonArray blocks = body.getAsJsonArray("blocks");
                    boolean allowOutside = body.has("allow_outside_selection") && body.get("allow_outside_selection").getAsBoolean();
                    SelectionManager.Region r = resolveWorkingRegion(exchange, body);
                    String result = BlockScanner.setBlocks(mcServer, blocks, r, allowOutside);
                    sendJson(exchange, 200, result);
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    class BlockReplaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    SelectionManager.Region r = resolveWorkingRegion(exchange, body);
                    JsonArray findIds = body.getAsJsonArray("find");
                    String replaceId = body.get("replace").getAsString();
                    boolean dryRun = !body.has("dry_run") || body.get("dry_run").getAsBoolean();
                    int maxBlocks = body.has("max_blocks") ? body.get("max_blocks").getAsInt() : Integer.MAX_VALUE;
                    String result = BlockScanner.replaceBlocks(mcServer, r, findIds, replaceId, dryRun, maxBlocks);
                    sendJson(exchange, 200, result);
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    class BlockUndoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    String token = body.get("undo_token").getAsString();
                    String result = BlockScanner.undoLastWrite(mcServer, token);
                    sendJson(exchange, 200, result);
                } catch(Exception e) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    class FileWriteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                if(!body.has("path") || !body.has("content")) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Missing 'path' or 'content'")));
                    return;
                }
                String pathStr = body.get("path").getAsString();
                String contentStr = body.get("content").getAsString();
                
                File baseDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
                File targetFile = new File(baseDir, pathStr);
                
                if(!targetFile.getCanonicalPath().startsWith(baseDir.getCanonicalPath())) {
                    sendJson(exchange, 403, GSON.toJson(errorJson("Access denied: Cannot write outside server root")));
                    return;
                }
                
                if(!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                }
                
                java.nio.file.Files.writeString(targetFile.toPath(), contentStr, StandardCharsets.UTF_8);

                // Automatically reload datapacks so the new loot table is available
                mcServer.getCommandManager().executeWithPrefix(mcServer.getCommandSource(), "reload");

                JsonObject ok = new JsonObject();
                ok.addProperty("success", true);
                ok.addProperty("path", targetFile.getCanonicalPath());
                sendJson(exchange, 200, GSON.toJson(ok));
            } catch(Exception e) {
                sendJson(exchange, 500, GSON.toJson(errorJson("Error writing file: " + e.getMessage())));
            }
        }
    }

    // Resolves a dimension name from a request. Accepts a bare id ("nether"),
    // a short form ("minecraft:overworld"), or a full "namespace:path" for
    // custom dims. Defaults to overworld when the param is missing.
    private RegistryKey<World> resolveDim(String raw) {
        if(raw == null || raw.isEmpty()) return World.OVERWORLD;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        switch(s) {
            case "overworld": case "minecraft:overworld": return World.OVERWORLD;
            case "nether": case "the_nether": case "minecraft:the_nether": return World.NETHER;
            case "end": case "the_end": case "minecraft:the_end": return World.END;
        }
        Identifier id = Identifier.tryParse(s);
        if(id == null) return null;
        return RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id);
    }

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if(query == null) return null;
        for(String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if(pair.length == 2 && pair[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                } catch(Exception e) { return null; }
            }
        }
        return null;
    }

    // GET /structure-blocks — list every tracked structure block from StructureBlockSaver's
    // persistent registry. Optional query params: name_filter, mode_filter, dim.
    class StructureBlocksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String dimRaw = queryParam(exchange, "dim");
            String nameFilter = queryParam(exchange, "name_filter");
            String modeFilter = queryParam(exchange, "mode_filter");
            RegistryKey<World> dim = resolveDim(dimRaw);
            if(dim == null) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Unrecognised dim: " + dimRaw)));
                return;
            }

            SbsRegistryReader.Result r = SbsRegistryReader.read(mcServer, dim);
            if(!r.present) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "sbs_tracker_missing");
                err.addProperty("dim", dim.getValue().toString());
                err.addProperty("hint", "Install StructureBlockSaver on this world, or load a structure block once to seed its tracker.");
                sendJson(exchange, 404, GSON.toJson(err));
                return;
            }

            JsonArray blocks = new JsonArray();
            int count = 0;
            for(SbsRegistryReader.Entry e : r.entries) {
                if(nameFilter != null && !e.name.contains(nameFilter)) continue;
                if(modeFilter != null && !e.mode.equalsIgnoreCase(modeFilter)) continue;
                JsonObject b = new JsonObject();
                b.addProperty("x", e.pos.getX());
                b.addProperty("y", e.pos.getY());
                b.addProperty("z", e.pos.getZ());
                b.addProperty("name", e.name);
                b.addProperty("mode", e.mode);
                b.addProperty("dim", e.dimension.toString());
                blocks.add(b);
                count++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("count", count);
            out.addProperty("total", r.entries.size());
            out.addProperty("source", "sbs");
            out.addProperty("dim", dim.getValue().toString());
            out.add("blocks", blocks);
            sendJson(exchange, 200, GSON.toJson(out));
        }
    }

    // GET /structure-bounds?name=&dim= — return the bbox that covers both the structure
    // block itself and the region it saves, without writing to any stored selection.
    // Read-only companion to /selection/from-structure for stateless multi-agent use.
    class StructureBoundsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!serverReady(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String name = queryParam(exchange, "name");
            if(name == null || name.isEmpty()) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Missing 'name' query param")));
                return;
            }
            String dimRaw = queryParam(exchange, "dim");
            RegistryKey<World> dim = resolveDim(dimRaw);
            if(dim == null) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Unrecognised dim: " + dimRaw)));
                return;
            }
            JsonObject out = resolveStructureBounds(name, dim);
            int status = out.has("error") ? (out.get("error").getAsString().equals("ambiguous") ? 409 : 404) : 200;
            sendJson(exchange, status, GSON.toJson(out));
        }
    }

    // Shared lookup used by /structure-bounds and /selection/from-structure. Returns a
    // JsonObject that either has an "error" key describing the failure (missing tracker,
    // no match, ambiguous, not_a_structure_block, ...) or the bounds payload
    // (pos1, pos2, structure_block, structure_size, mode, dim).
    private JsonObject resolveStructureBounds(String name, RegistryKey<World> dim) {
        SbsRegistryReader.Result r = SbsRegistryReader.read(mcServer, dim);
        if(!r.present) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "sbs_tracker_missing");
            err.addProperty("dim", dim.getValue().toString());
            return err;
        }
        java.util.List<SbsRegistryReader.Entry> matches = new java.util.ArrayList<>();
        for(SbsRegistryReader.Entry e : r.entries) {
            if(e.name.equals(name)) matches.add(e);
        }
        if(matches.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "not_found");
            err.addProperty("message", "No structure block with name '" + name + "' in dim " + dim.getValue());
            return err;
        }
        if(matches.size() > 1) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "ambiguous");
            err.addProperty("message", matches.size() + " structure blocks share name '" + name + "'.");
            JsonArray positions = new JsonArray();
            for(SbsRegistryReader.Entry e : matches) {
                JsonObject p = new JsonObject();
                p.addProperty("x", e.pos.getX()); p.addProperty("y", e.pos.getY()); p.addProperty("z", e.pos.getZ());
                p.addProperty("mode", e.mode);
                positions.add(p);
            }
            err.add("candidates", positions);
            return err;
        }
        SbsRegistryReader.Entry entry = matches.get(0);
        ServerWorld world = mcServer.getWorld(dim);
        if(world == null) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "no_world");
            err.addProperty("message", "Server has no loaded world for dim " + dim.getValue());
            return err;
        }
        final BlockPos entryPos = entry.pos;
        java.util.concurrent.CompletableFuture<StructureBlockBlockEntity> beFuture = new java.util.concurrent.CompletableFuture<>();
        mcServer.execute(() -> {
            try {
                world.getChunk(entryPos.getX() >> 4, entryPos.getZ() >> 4);
                BlockEntity found = world.getBlockEntity(entryPos);
                beFuture.complete(found instanceof StructureBlockBlockEntity s ? s : null);
            } catch(Exception e) {
                beFuture.completeExceptionally(e);
            }
        });
        StructureBlockBlockEntity sbe;
        try {
            sbe = beFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch(Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "timeout");
            err.addProperty("message", "Timed out loading chunk at " + entryPos.toShortString());
            return err;
        }
        if(sbe == null) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "not_a_structure_block");
            err.addProperty("message", "Tracker says a structure block '" + name + "' is at " + entryPos.toShortString() + " but that block is not a structure block (SBS tracker stale, or the block was broken).");
            return err;
        }

        BlockPos sbPos = entry.pos;
        BlockPos offset = sbe.getOffset();
        NbtCompound nbt = sbe.createNbt(world.getRegistryManager());
        int sx = nbt.getInt("sizeX").orElse(0);
        int sy = nbt.getInt("sizeY").orElse(0);
        int sz = nbt.getInt("sizeZ").orElse(0);
        BlockPos regionMin = sbPos.add(offset);
        BlockPos regionMax = regionMin.add(Math.max(sx - 1, 0), Math.max(sy - 1, 0), Math.max(sz - 1, 0));
        int minX = Math.min(sbPos.getX(), Math.min(regionMin.getX(), regionMax.getX()));
        int minY = Math.min(sbPos.getY(), Math.min(regionMin.getY(), regionMax.getY()));
        int minZ = Math.min(sbPos.getZ(), Math.min(regionMin.getZ(), regionMax.getZ()));
        int maxX = Math.max(sbPos.getX(), Math.max(regionMin.getX(), regionMax.getX()));
        int maxY = Math.max(sbPos.getY(), Math.max(regionMin.getY(), regionMax.getY()));
        int maxZ = Math.max(sbPos.getZ(), Math.max(regionMin.getZ(), regionMax.getZ()));

        JsonObject ok = new JsonObject();
        ok.addProperty("success", true);
        ok.addProperty("name", name);
        ok.addProperty("mode", nbt.getString("mode").orElse(entry.mode));
        ok.addProperty("dim", dim.getValue().toString());
        JsonObject sbJ = new JsonObject();
        sbJ.addProperty("x", sbPos.getX()); sbJ.addProperty("y", sbPos.getY()); sbJ.addProperty("z", sbPos.getZ());
        ok.add("structure_block", sbJ);
        JsonObject p1 = new JsonObject();
        p1.addProperty("x", minX); p1.addProperty("y", minY); p1.addProperty("z", minZ);
        ok.add("pos1", p1);
        JsonObject p2 = new JsonObject();
        p2.addProperty("x", maxX); p2.addProperty("y", maxY); p2.addProperty("z", maxZ);
        ok.add("pos2", p2);
        JsonObject size = new JsonObject();
        size.addProperty("x", sx); size.addProperty("y", sy); size.addProperty("z", sz);
        ok.add("structure_size", size);
        return ok;
    }

    // GET /log/tail?lines=N&grep=substr — last N lines of logs/latest.log, optionally
    // filtered to lines containing grep (case-insensitive). Read-only; lets a remote caller
    // see vanilla/mod log output (mixin apply errors, "Failed to read chunk", mod warns)
    // without a hosting-panel round trip.
    class LogTailHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            int lines = 200;
            String linesRaw = queryParam(exchange, "lines");
            if(linesRaw != null) { try { lines = Math.max(1, Math.min(5000, Integer.parseInt(linesRaw))); } catch(NumberFormatException ignored) {} }
            String grep = queryParam(exchange, "grep");
            String grepLower = grep == null ? null : grep.toLowerCase(java.util.Locale.ROOT);
            try {
                java.nio.file.Path log = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
                if(!java.nio.file.Files.isRegularFile(log)) {
                    sendJson(exchange, 404, GSON.toJson(errorJson("No logs/latest.log at " + log)));
                    return;
                }
                java.util.List<String> all = java.nio.file.Files.readAllLines(log, StandardCharsets.UTF_8);
                java.util.List<String> picked = new java.util.ArrayList<>();
                for(int i = all.size() - 1; i >= 0 && picked.size() < lines; i--) {
                    String l = all.get(i);
                    if(grepLower == null || l.toLowerCase(java.util.Locale.ROOT).contains(grepLower)) picked.add(l);
                }
                java.util.Collections.reverse(picked);
                JsonObject out = new JsonObject();
                out.addProperty("file", log.toString());
                out.addProperty("total_lines", all.size());
                out.addProperty("returned", picked.size());
                if(grep != null) out.addProperty("grep", grep);
                JsonArray arr = new JsonArray();
                for(String l : picked) arr.add(l);
                out.add("lines", arr);
                sendJson(exchange, 200, GSON.toJson(out));
            } catch(Exception e) {
                sendJson(exchange, 500, GSON.toJson(errorJson("Failed to read log: " + e.getMessage())));
            }
        }
    }

    // POST /selection/remove — remove a stored selection region.
    // Body: { "name": "..." } removes that one region; { "all": true } removes every region
    // (default is reset to empty rather than deleted so the wand always has something to bind).
    class SelectionRemoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!checkAuth(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String bodyStr = readBody(exchange);
                JsonObject body = bodyStr.trim().isEmpty() ? new JsonObject() : JsonParser.parseString(bodyStr).getAsJsonObject();
                boolean all = body.has("all") && !body.get("all").isJsonNull() && body.get("all").getAsBoolean();
                JsonObject ok = new JsonObject();
                if(all) {
                    int n = selection.removeAll();
                    StructureEditorMod.syncSelectionsToAll();
                    ok.addProperty("success", true);
                    ok.addProperty("removed", n);
                    ok.addProperty("scope", "all");
                } else if(body.has("name") && !body.get("name").isJsonNull()) {
                    String name = body.get("name").getAsString();
                    boolean existed = selection.getRegions().containsKey(name);
                    selection.clear(name);
                    StructureEditorMod.syncSelectionsToAll();
                    ok.addProperty("success", true);
                    ok.addProperty("removed", existed ? 1 : 0);
                    ok.addProperty("name", name);
                    if(name.equals("default")) ok.addProperty("note", "'default' was reset to empty rather than deleted.");
                } else {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Provide either 'name' or 'all: true' in body.")));
                    return;
                }
                sendJson(exchange, 200, GSON.toJson(ok));
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }

    // POST /selection/from-structure — resolve a structure block by its saved-structure name
    // (via SBS's tracker), then set the selection to a bbox that covers both the structure
    // block itself AND the region it saves. Body: { "name": "...", "dim"?: "...", "region"?: "..." }
    class SelectionFromStructureHandler implements HttpHandler {
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
                if(!body.has("name") || body.get("name").isJsonNull()) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Missing 'name' in body")));
                    return;
                }
                String name = body.get("name").getAsString();
                String dimRaw = body.has("dim") && !body.get("dim").isJsonNull() ? body.get("dim").getAsString() : null;
                String regionName = body.has("region") && !body.get("region").isJsonNull() ? body.get("region").getAsString() : "default";

                RegistryKey<World> dim = resolveDim(dimRaw);
                if(dim == null) {
                    sendJson(exchange, 400, GSON.toJson(errorJson("Unrecognised dim: " + dimRaw)));
                    return;
                }

                JsonObject bounds = resolveStructureBounds(name, dim);
                if(bounds.has("error")) {
                    String err = bounds.get("error").getAsString();
                    int status = err.equals("ambiguous") ? 409 : err.equals("not_a_structure_block") ? 410 : err.equals("timeout") ? 504 : 404;
                    sendJson(exchange, status, GSON.toJson(bounds));
                    return;
                }

                JsonObject p1 = bounds.getAsJsonObject("pos1");
                JsonObject p2 = bounds.getAsJsonObject("pos2");
                BlockPos min = new BlockPos(p1.get("x").getAsInt(), p1.get("y").getAsInt(), p1.get("z").getAsInt());
                BlockPos max = new BlockPos(p2.get("x").getAsInt(), p2.get("y").getAsInt(), p2.get("z").getAsInt());
                selection.setPos1(regionName, min);
                selection.setPos2(regionName, max);
                StructureEditorMod.syncSelectionsToAll();
                broadcastActionBar("Selection '" + regionName + "' set to structure '" + name + "'");

                bounds.addProperty("region", regionName);
                sendJson(exchange, 200, GSON.toJson(bounds));
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }
}
