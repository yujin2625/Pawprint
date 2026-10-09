package com.dumaru.pawprint.client.web;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.dumaru.pawprint.platform.Services;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection to the Pawprint web editor and desktop app on the same computer (docs/WEB_DESIGN.md §7). While the game
 * runs, a tiny HTTP server listens on 127.0.0.1 only and answers only pages from the Pawprint web editor or app
 * (checked by the browser-sent Origin header):
 * <ul>
 *   <li>{@code GET /status}: that Pawprint is running, with versions;</li>
 *   <li>{@code GET /blueprint/<token>}: a blueprint the player chose to open on the web ("Open in web editor");</li>
 *   <li>{@code POST /blueprint}: a blueprint sent from the editor ("Send to game"), saved to the library.</li>
 * </ul>
 */
public final class WebLink {
    /** Pages allowed to talk to the game: the published editor, the desktop app, and the editor in development. */
    private static final Set<String> ORIGINS = Set.of("https://yujin2625.github.io", "http://tauri.localhost", "tauri://localhost",
            "http://localhost:5173");
    public static final String RECEIVED_GROUP = "web";
    private static final int PORTS = 5;
    private static final int MAX_BODY = 64 * 1024 * 1024;
    private static final long SHARE_MILLIS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Shared(Path file, String name, long until) {
    }

    private static final Map<String, Shared> shared = new ConcurrentHashMap<>();
    private static volatile int port = -1;

    private WebLink() {
    }

    /** The port the server listens on, or -1 when it is off. */
    public static int port() {
        return port;
    }

    public static void start() {
        if (!Pawprint.config().enableWebLink || port >= 0) {
            return;
        }
        int first = Pawprint.config().webLinkPort;
        for (int p = first; p < first + PORTS; p++) {
            ServerSocket server;
            try {
                server = new ServerSocket(p, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            } catch (IOException e) {
                continue; // Another game (or something else) has it.
            }
            port = p;
            Thread thread = new Thread(() -> serve(server), "Pawprint web link");
            thread.setDaemon(true);
            thread.start();
            Pawprint.LOG.info("Web editor link listening on 127.0.0.1:{}", p);
            return;
        }
        Pawprint.LOG.warn("Web editor link: ports {}-{} are taken", first, first + PORTS - 1);
    }

    /**
     * Opens a blueprint file in the web editor: the browser opens the editor, which fetches the file from this game.
     * Returns false if the link server is off.
     */
    public static boolean openInWeb(Path file, String name) {
        String token = share(file, name);
        if (token == null) {
            return false;
        }
        String base = Pawprint.config().webEditorUrl;
        String url = base + (base.contains("#") ? "" : "#") + "/receive?port=" + port + "&token=" + token + "&name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
        return true;
    }

    /** Makes a file fetchable at {@code /blueprint/<token>} for ten minutes; null if the server is off. */
    public static @Nullable String share(Path file, String name) {
        if (port < 0) {
            return null;
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long now = System.currentTimeMillis();
        shared.values().removeIf(s -> s.until() < now);
        shared.put(token, new Shared(file, name, now + SHARE_MILLIS));
        return token;
    }

    private static void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(15_000);
                handle(socket);
            } catch (SocketTimeoutException e) {
                // A client that stopped talking; drop it.
            } catch (IOException | RuntimeException e) {
                Pawprint.LOG.debug("Web link request failed", e);
            }
        }
    }

    private record Request(String method, String path, Map<String, String> headers, byte[] body) {
    }

    private static void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        Request request = read(in);
        if (request == null) {
            return;
        }
        String origin = request.headers().get("origin");
        if (origin == null || !ORIGINS.contains(origin)) {
            respond(out, 403, null, "text/plain", "Not from the Pawprint web editor".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        if (request.method().equals("OPTIONS")) {
            respond(out, 204, origin, null, new byte[0], Map.of(
                    "Access-Control-Allow-Methods", "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers", "Content-Type, X-Pawprint-Name",
                    "Access-Control-Allow-Private-Network", "true",
                    "Access-Control-Max-Age", "600"));
            return;
        }
        String path = request.path().split("\\?", 2)[0];
        if (request.method().equals("GET") && path.equals("/status")) {
            JsonObject status = new JsonObject();
            status.addProperty("app", "pawprint");
            status.addProperty("version", Services.PLATFORM.modInfo(Pawprint.MOD_ID).map(i -> i.version()).orElse("?"));
            status.addProperty("mcVersion", SharedConstants.getCurrentVersion().name());
            status.addProperty("loader", Services.PLATFORM.getPlatformName().toLowerCase(Locale.ROOT));
            json(out, origin, 200, status);
        } else if (request.method().equals("GET") && path.startsWith("/blueprint/")) {
            Shared file = shared.get(path.substring("/blueprint/".length()));
            if (file == null || file.until() < System.currentTimeMillis() || !Files.isRegularFile(file.file())) {
                respond(out, 404, origin, "text/plain", "Link expired".getBytes(StandardCharsets.UTF_8), Map.of());
            } else {
                respond(out, 200, origin, "application/octet-stream", Files.readAllBytes(file.file()), Map.of());
            }
        } else if (request.method().equals("POST") && path.equals("/blueprint")) {
            String name = URLDecoder.decode(request.headers().getOrDefault("x-pawprint-name", "Blueprint"), StandardCharsets.UTF_8);
            JsonObject reply = new JsonObject();
            try {
                String saved = receive(request.body(), name);
                reply.addProperty("ok", true);
                reply.addProperty("name", saved);
                json(out, origin, 200, reply);
            } catch (IOException | RuntimeException e) {
                reply.addProperty("ok", false);
                reply.addProperty("error", String.valueOf(e.getMessage()));
                json(out, origin, 400, reply);
            }
        } else {
            respond(out, 404, origin, "text/plain", new byte[0], Map.of());
        }
    }

    /** Checks the blueprint, adds it to the library's "web" group and tells the player. */
    private static String receive(byte[] body, String name) throws IOException {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
        if (safe.isEmpty() || safe.length() > 80) {
            safe = "Blueprint";
        }
        Path temp = Files.createTempDirectory("pawprint-web").resolve(safe + BlueprintIO.EXTENSION);
        try {
            Files.write(temp, body);
            BlueprintLibrary.read(temp); // Throws for files that are not blueprints.
            BlueprintLibrary.ImportResult result = BlueprintLibrary.importDropped(List.of(temp), RECEIVED_GROUP);
            if (result.imported() == 0) {
                throw new IOException(result.failures().isEmpty() ? "not saved" : result.failures().get(0));
            }
        } finally {
            Files.deleteIfExists(temp);
            Files.deleteIfExists(temp.getParent());
        }
        String received = safe;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> PawprintClient.notify(minecraft, Component.translatable("pawprint.web.received", received)));
        Pawprint.LOG.info("Received \"{}\" from the web editor", received);
        return received;
    }

    private static @Nullable Request read(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header = readLine(in); header != null && !header.isEmpty(); header = readLine(in)) {
            int colon = header.indexOf(':');
            if (colon > 0 && headers.size() < 64) {
                headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
        }
        int length = 0;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            return null;
        }
        if (length < 0 || length > MAX_BODY) {
            return null;
        }
        byte[] body = in.readNBytes(length);
        return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], headers, body);
    }

    private static @Nullable String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != -1; b = in.read()) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            if (line.size() > 8192) {
                return null;
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void json(OutputStream out, String origin, int status, JsonObject body) throws IOException {
        respond(out, status, origin, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static void respond(OutputStream out, int status, @Nullable String origin, @Nullable String type, byte[] body,
                                Map<String, String> extra) throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (origin != null) {
            head.append("Access-Control-Allow-Origin: ").append(origin).append("\r\nVary: Origin\r\n");
        }
        if (type != null) {
            head.append("Content-Type: ").append(type).append("\r\n");
        }
        extra.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
        head.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            default -> "Not Found";
        };
    }
}
