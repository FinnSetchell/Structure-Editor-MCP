package com.finndog.structure_editor;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

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
            server.createContext("/edit",      new EditHandler());
            server.createContext("/batch",     new BatchHandler());
            server.createContext("/save",      new SaveHandler());
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
            if(selection.getPos1() != null) {
                obj.addProperty("pos1", selection.getPos1().toShortString());
            }
            if(selection.getPos2() != null) {
                obj.addProperty("pos2", selection.getPos2().toShortString());
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
            if("GET".equals(exchange.getRequestMethod())) {
                JsonObject obj = new JsonObject();
                if(selection.getPos1() != null) {
                    JsonObject p1 = new JsonObject();
                    p1.addProperty("x", selection.getPos1().getX());
                    p1.addProperty("y", selection.getPos1().getY());
                    p1.addProperty("z", selection.getPos1().getZ());
                    obj.add("pos1", p1);
                } else {
                    obj.add("pos1", JsonNull.INSTANCE);
                }
                if(selection.getPos2() != null) {
                    JsonObject p2 = new JsonObject();
                    p2.addProperty("x", selection.getPos2().getX());
                    p2.addProperty("y", selection.getPos2().getY());
                    p2.addProperty("z", selection.getPos2().getZ());
                    obj.add("pos2", p2);
                } else {
                    obj.add("pos2", JsonNull.INSTANCE);
                }
                obj.addProperty("complete", selection.isComplete());
                sendJson(exchange, 200, GSON.toJson(obj));
            }
            else if("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonObject body = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
                    if(body.has("pos1") && body.get("pos1").isJsonObject()) {
                        JsonObject p = body.getAsJsonObject("pos1");
                        selection.setPos1(new net.minecraft.util.math.BlockPos(
                            p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()
                        ));
                    }
                    if(body.has("pos2") && body.get("pos2").isJsonObject()) {
                        JsonObject p = body.getAsJsonObject("pos2");
                        selection.setPos2(new net.minecraft.util.math.BlockPos(
                            p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()
                        ));
                    }
                    JsonObject ok = new JsonObject();
                    ok.addProperty("success", true);
                    ok.addProperty("complete", selection.isComplete());
                    sendJson(exchange, 200, GSON.toJson(ok));
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
            String result = BlockScanner.scanSelection(mcServer, selection);
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
                String result = BlockScanner.saveStructures(mcServer, selection, body);
                sendJson(exchange, 200, result);
            } catch(Exception e) {
                sendJson(exchange, 400, GSON.toJson(errorJson("Bad request: " + e.getMessage())));
            }
        }
    }
}
