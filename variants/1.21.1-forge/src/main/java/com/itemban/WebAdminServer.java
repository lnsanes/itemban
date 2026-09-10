package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WebAdminServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LoginGate> loginGates = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int LOGIN_MAX_FAILS = 8;
    private static final long LOGIN_LOCK_MS = 60_000;
    private static final int MAX_ID_CHARS = 256;
    private static final int MAX_NBT_CHARS = 8192;
    private static final int MAX_BEARER_CHARS = 96;
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static volatile HttpServer http;
    private static volatile ExecutorService httpPool;
    private static volatile MinecraftServer gameServer;

    private static final class Session {
        final String username;
        final String role;
        final boolean setupOnly;
        final long exp;

        Session(String username, String role, boolean setupOnly, long exp) {
            this.username = username;
            this.role = role;
            this.setupOnly = setupOnly;
            this.exp = exp;
        }
    }

    private static final class LoginGate {
        int fails;
        long lockUntil;
    }

    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("请求体过大");
        }
    }

    private static final class InvalidJsonException extends IOException {
        InvalidJsonException() {
            super("JSON 无效");
        }
    }

    private WebAdminServer() {}

    public static void start(MinecraftServer server) {
        stop();
        gameServer = server;
        ItemNames.load(server);
        if (!ConfigHandler.webEnabled) {
            ItemBan.LOGGER.info("ItemBan 网页管理未启用 (webEnabled=false)");
            return;
        }
        try {
            ConfigHandler.ensureWebCredentials();
            HttpServer started = HttpServer.create(new InetSocketAddress(ConfigHandler.webPort), 0);
            started.createContext("/", WebAdminServer::handleRoot);
            started.createContext("/api/login", WebAdminServer::handleLogin);
            started.createContext("/api/logout", WebAdminServer::handleLogout);
            started.createContext("/api/account/password", WebAdminServer::handleSetPassword);
            started.createContext("/api/accounts/add", ex -> mutateAt(ex, "/api/accounts/add", WebAdminServer::addAccount));
            started.createContext("/api/accounts/remove", ex -> mutateAt(ex, "/api/accounts/remove", WebAdminServer::removeAccount));
            started.createContext("/api/accounts", WebAdminServer::handleAccounts);
            started.createContext("/api/state", ex -> jsonGetAt(ex, "/api/state", WebAdminServer::stateJson));
            started.createContext("/api/catalog", ex -> jsonGetAt(ex, "/api/catalog", WebAdminServer::catalogJson));
            started.createContext("/api/items/add", ex -> mutateAt(ex, "/api/items/add", WebAdminServer::addItem));
            started.createContext("/api/items/remove", ex -> mutateAt(ex, "/api/items/remove", WebAdminServer::removeItem));
            started.createContext("/api/blocks/add", ex -> mutateAt(ex, "/api/blocks/add", WebAdminServer::addBlock));
            started.createContext("/api/blocks/remove", ex -> mutateAt(ex, "/api/blocks/remove", WebAdminServer::removeBlock));
            started.createContext("/api/exclude/add", ex -> mutateAt(ex, "/api/exclude/add", WebAdminServer::addExclude));
            started.createContext("/api/exclude/remove", ex -> mutateAt(ex, "/api/exclude/remove", WebAdminServer::removeExclude));
            started.createContext("/api/settings", ex -> mutateAt(ex, "/api/settings", WebAdminServer::updateSettings));
            started.createContext("/api/reload", ex -> mutateAt(ex, "/api/reload", WebAdminServer::reload));
            started.createContext("/api/logs", ex -> jsonGetAt(ex, "/api/logs", WebAdminServer::logsJson));
            ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "itemban-web");
                t.setDaemon(true);
                return t;
            });
            started.setExecutor(pool);
            started.start();
            httpPool = pool;
            http = started;
            ItemBan.LOGGER.info("ItemBan 网页管理已启动: http://0.0.0.0:{}  （账号密码登录，一次性初始密码，权限节点 {}）",
                    ConfigHandler.webPort, BanPermission.NODE_NAME);
        } catch (Exception e) {
            ItemBan.LOGGER.error("启动 ItemBan 网页管理失败", e);
        }
    }

    public static void stop() {
        HttpServer current = http;
        ExecutorService pool = httpPool;
        http = null;
        httpPool = null;
        gameServer = null;
        invalidateSessions();
        if (current != null) {
            current.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public static void invalidateSessions() {
        sessions.clear();
    }

    public static void invalidateUserSessions(String username) {
        if (username == null) {
            return;
        }
        sessions.entrySet().removeIf(e -> username.equals(e.getValue().username));
    }

    private static void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendJson(ex, 404, Map.of("error", "Not Found"));
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try (InputStream in = WebAdminServer.class.getResourceAsStream("/assets/itemban/web/index.html")) {
            if (in == null) {
                send(ex, 500, "text/plain; charset=utf-8", "index.html missing");
                return;
            }
            String nonce = newSessionToken();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{CSP_NONCE}}", nonce)
                    .replace("{{SETUP_HINT_HIDDEN}}", ConfigHandler.hasPermanentWebPassword() ? " hidden" : "");
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            Headers headers = ex.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            setSecurityHeaders(headers, nonce);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        if (!exactPath(ex, "/api/login")) {
            sendJson(ex, 404, Map.of("ok", false, "error", "Not Found"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("ok", false, "error", "Method Not Allowed"));
            return;
        }
        if (!allowLoginAttempt(ex)) {
            sendJson(ex, 429, Map.of("ok", false, "error", "尝试过多，请稍后再试"));
            return;
        }
        Map<String, Object> body;
        try {
            body = readJson(ex);
        } catch (BodyTooLargeException e) {
            sendJson(ex, 413, Map.of("ok", false, "error", "请求体过大"));
            return;
        } catch (InvalidJsonException e) {
            sendJson(ex, 400, Map.of("ok", false, "error", "请求格式无效"));
            return;
        }
        String username = str(body.get("username"));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        ConfigHandler.WebLoginResult result = ConfigHandler.authenticateWeb(username, password);
        if (!result.ok) {
            noteLoginFailure(ex);
            sendJson(ex, 401, Map.of("ok", false, "error", "账号或密码错误"));
            return;
        }
        clearLoginFailures(ex);
        invalidateUserSessions(result.username);
        String token = newSession(result.username, result.role, result.setupOnly);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("username", result.username);
        out.put("role", result.role);
        out.put("canManageAccounts", ConfigHandler.canManageAccounts(result.username, result.role));
        out.put("mustChangePassword", result.setupOnly);
        sendJson(ex, 200, out);
    }

    private static void handleSetPassword(HttpExchange ex) throws IOException {
        if (!exactPath(ex, "/api/account/password")) {
            sendJson(ex, 404, Map.of("ok", false, "error", "Not Found"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("ok", false, "error", "Method Not Allowed"));
            return;
        }
        Session session = sessionOf(ex);
        if (session == null) {
            sendJson(ex, 401, Map.of("ok", false, "error", "未登录"));
            return;
        }
        Map<String, Object> body;
        try {
            body = readJson(ex);
        } catch (BodyTooLargeException e) {
            sendJson(ex, 413, Map.of("ok", false, "error", "请求体过大"));
            return;
        } catch (InvalidJsonException e) {
            sendJson(ex, 400, Map.of("ok", false, "error", "请求格式无效"));
            return;
        }
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        String error = session.setupOnly
                ? ConfigHandler.completeWebSetup(password)
                : ConfigHandler.changeOwnPassword(session.username, password);
        if (error != null) {
            sendJson(ex, 400, Map.of("ok", false, "error", error));
            return;
        }
        String token = bearerToken(ex);
        if (token != null) {
            sessions.remove(token);
        }
        String next = newSession(session.username, session.setupOnly ? ConfigHandler.WEB_ROLE_OWNER : session.role, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", next);
        out.put("username", session.username);
        out.put("role", session.setupOnly ? ConfigHandler.WEB_ROLE_OWNER : session.role);
        out.put("canManageAccounts", ConfigHandler.canManageAccounts(session.username, session.setupOnly ? ConfigHandler.WEB_ROLE_OWNER : session.role));
        sendJson(ex, 200, out);
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        if (!exactPath(ex, "/api/logout")) {
            sendJson(ex, 404, Map.of("ok", false, "error", "Not Found"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("ok", false, "error", "Method Not Allowed"));
            return;
        }
        String token = bearerToken(ex);
        if (token != null) {
            sessions.remove(token);
        }
        sendJson(ex, 200, Map.of("ok", true));
    }

    private static String newSession(String username, String role, boolean setupOnly) {
        String token = newSessionToken();
        sessions.put(token, new Session(username, role, setupOnly, System.currentTimeMillis() + SESSION_TTL_MS));
        return token;
    }

    private static String newSessionToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            return token.length() > MAX_BEARER_CHARS ? null : token;
        }
        return null;
    }

    private static Session sessionOf(HttpExchange ex) {
        String token = bearerToken(ex);
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.exp < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    private static void jsonGetAt(HttpExchange ex, String path, java.util.function.Supplier<Map<String, Object>> supplier) throws IOException {
        if (!exactPath(ex, path)) {
            sendJson(ex, 404, Map.of("error", "Not Found"));
            return;
        }
        jsonGet(ex, supplier);
    }

    private static void mutateAt(HttpExchange ex, String path, java.util.function.Function<Map<String, Object>, Map<String, Object>> handler) throws IOException {
        if (!exactPath(ex, path)) {
            sendJson(ex, 404, Map.of("error", "Not Found"));
            return;
        }
        mutate(ex, handler);
    }

    private static void jsonGet(HttpExchange ex, java.util.function.Supplier<Map<String, Object>> supplier) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method Not Allowed"));
            return;
        }
        Session session = sessionOf(ex);
        if (session == null || session.setupOnly) {
            sendJson(ex, 401, Map.of("error", session != null ? "请先设置正式密码" : "未登录"));
            return;
        }
        CURRENT.set(session);
        try {
            sendJson(ex, 200, supplier.get());
        } catch (Exception e) {
            ItemBan.LOGGER.error("网页接口失败", e);
            sendJson(ex, 500, Map.of("error", "服务器内部错误"));
        } finally {
            CURRENT.remove();
        }
    }

    private static void mutate(HttpExchange ex, java.util.function.Function<Map<String, Object>, Map<String, Object>> handler) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method Not Allowed"));
            return;
        }
        Session session = sessionOf(ex);
        if (session == null || session.setupOnly) {
            sendJson(ex, 401, Map.of("error", session != null ? "请先设置正式密码" : "未登录"));
            return;
        }
        CURRENT.set(session);
        try {
            Map<String, Object> body = readJson(ex);
            Map<String, Object> result = callOnServer(() -> {
                CURRENT.set(session);
                try {
                    return handler.apply(body);
                } finally {
                    CURRENT.remove();
                }
            });
            Object ok = result.get("ok");
            if (Boolean.FALSE.equals(ok)) {
                String err = result.get("error") == null ? "" : String.valueOf(result.get("error"));
                sendJson(ex, err.startsWith("只有") ? 403 : 400, result);
                return;
            }
            sendJson(ex, 200, result);
        } catch (BodyTooLargeException e) {
            sendJson(ex, 413, Map.of("ok", false, "error", "请求体过大"));
        } catch (InvalidJsonException e) {
            sendJson(ex, 400, Map.of("ok", false, "error", "请求格式无效"));
        } catch (Exception e) {
            ItemBan.LOGGER.error("网页接口失败", e);
            sendJson(ex, 500, Map.of("error", "服务器内部错误"));
        } finally {
            CURRENT.remove();
        }
    }

    private static Map<String, Object> stateJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", "2.1.0");
        out.put("webEnabled", ConfigHandler.webEnabled);
        out.put("webPort", ConfigHandler.webPort);
        out.put("publicAnnounce", ConfigHandler.publicAnnounce);
        out.put("autoBanOnViolation", ConfigHandler.autoBanOnViolation);
        out.put("detectDroppedItems", ConfigHandler.detectDroppedItems);
        out.put("detectWorldBlocks", ConfigHandler.detectWorldBlocks);
        out.put("items", namedRules(ConfigHandler.getBlacklistRules()));
        out.put("blocks", namedRules(ConfigHandler.getBlockBlacklistRules()));
        List<Map<String, String>> excludes = new ArrayList<>();
        for (String id : ConfigHandler.getExcludeFromLog()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", ItemNames.nameOf(id));
            row.put("nbt", "");
            excludes.add(row);
        }
        out.put("excludes", excludes);
        Session me = CURRENT.get();
        if (me != null) {
            out.put("username", me.username);
            out.put("role", me.role);
            out.put("canManageAccounts", ConfigHandler.canManageAccounts(me.username, me.role));
        }
        return out;
    }

    private static void handleAccounts(HttpExchange ex) throws IOException {
        if (!exactPath(ex, "/api/accounts")) {
            sendJson(ex, 404, Map.of("error", "Not Found"));
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method Not Allowed"));
            return;
        }
        Session session = sessionOf(ex);
        if (session == null || session.setupOnly) {
            sendJson(ex, 401, Map.of("error", session != null ? "请先设置正式密码" : "未登录"));
            return;
        }
        if (!ConfigHandler.canManageAccounts(session.username, session.role)) {
            sendJson(ex, 403, Map.of("error", "只有 admin 或 owner 可以管理账号"));
            return;
        }
        sendJson(ex, 200, Map.of("accounts", ConfigHandler.listWebAccounts()));
    }

    private static Map<String, Object> addAccount(Map<String, Object> body) {
        Session me = CURRENT.get();
        if (me == null) {
            return Map.of("ok", false, "error", "未登录");
        }
        String error = ConfigHandler.registerWebAccount(
                me.username, me.role, str(body.get("username")),
                body.get("password") == null ? "" : String.valueOf(body.get("password")),
                str(body.get("role")));
        if (error != null) {
            return Map.of("ok", false, "error", error);
        }
        return Map.of("ok", true);
    }

    private static Map<String, Object> removeAccount(Map<String, Object> body) {
        Session me = CURRENT.get();
        if (me == null) {
            return Map.of("ok", false, "error", "未登录");
        }
        String error = ConfigHandler.deleteWebAccount(me.username, me.role, str(body.get("username")));
        if (error != null) {
            return Map.of("ok", false, "error", error);
        }
        return Map.of("ok", true);
    }

    private static Map<String, Object> catalogJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", ItemNames.listItems());
        out.put("blocks", ItemNames.listBlocks());
        return out;
    }

    private static List<Map<String, String>> namedRules(List<ConfigHandler.BlacklistRule> rules) {
        List<Map<String, String>> out = new ArrayList<>();
        for (ConfigHandler.BlacklistRule rule : rules) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", rule.id);
            row.put("name", ItemNames.nameOf(rule.id));
            row.put("nbt", rule.nbtString == null ? "" : rule.nbtString);
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> addItem(Map<String, Object> body) {
        String id = str(body.get("id"));
        String nbt = str(body.get("nbt"));
        String error = validateRuleInput(id, nbt);
        if (error != null) {
            return Map.of("ok", false, "error", error);
        }
        ConfigHandler.addToBlacklist(id, emptyToNull(nbt));
        int stripped = RecipeStripper.applyFromSnapshot(gameServer);
        return Map.of("ok", true, "stripped", stripped);
    }

    private static Map<String, Object> removeItem(Map<String, Object> body) {
        ConfigHandler.removeFromBlacklist(str(body.get("id")), emptyToNull(str(body.get("nbt"))));
        int stripped = RecipeStripper.applyFromSnapshot(gameServer);
        return Map.of("ok", true, "stripped", stripped);
    }

    private static Map<String, Object> addBlock(Map<String, Object> body) {
        String id = str(body.get("id"));
        String nbt = str(body.get("nbt"));
        String error = validateRuleInput(id, nbt);
        if (error != null) {
            return Map.of("ok", false, "error", error);
        }
        ConfigHandler.addToBlockBlacklist(id, emptyToNull(nbt));
        return Map.of("ok", true);
    }

    private static Map<String, Object> removeBlock(Map<String, Object> body) {
        ConfigHandler.removeFromBlockBlacklist(str(body.get("id")), emptyToNull(str(body.get("nbt"))));
        return Map.of("ok", true);
    }

    private static Map<String, Object> addExclude(Map<String, Object> body) {
        String id = str(body.get("id"));
        String error = validateRuleInput(id, "");
        if (error != null) {
            return Map.of("ok", false, "error", error);
        }
        ConfigHandler.addExcludeFromLog(id);
        return Map.of("ok", true);
    }

    private static Map<String, Object> removeExclude(Map<String, Object> body) {
        ConfigHandler.removeExcludeFromLog(str(body.get("id")));
        return Map.of("ok", true);
    }

    private static Map<String, Object> updateSettings(Map<String, Object> body) {
        if (body.containsKey("publicAnnounce")) {
            ConfigHandler.setPublicAnnounce(bool(body.get("publicAnnounce")));
        }
        if (body.containsKey("autoBanOnViolation")) {
            ConfigHandler.setAutoBanOnViolation(bool(body.get("autoBanOnViolation")));
        }
        if (body.containsKey("detectDroppedItems")) {
            ConfigHandler.setDetectDroppedItems(bool(body.get("detectDroppedItems")));
        }
        if (body.containsKey("detectWorldBlocks")) {
            ConfigHandler.setDetectWorldBlocks(bool(body.get("detectWorldBlocks")));
        }
        return Map.of("ok", true);
    }

    private static Map<String, Object> reload(Map<String, Object> body) {
        ConfigHandler.loadBlacklist();
        ConfigHandler.loadBlockBlacklist();
        ConfigHandler.loadConfig();
        ConfigHandler.ensureWebCredentials();
        int stripped = RecipeStripper.applyFromSnapshot(gameServer);
        return Map.of("ok", true, "stripped", stripped);
    }

    private static Map<String, Object> logsJson() {
        Path dir = Paths.get("logs", "ItemBan");
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path file = dir.resolve(date + ".log");
        String text = "";
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 300);
                text = String.join("\n", lines.subList(from, lines.size()));
            }
        } catch (IOException e) {
            ItemBan.LOGGER.warn("读取网页审计日志失败", e);
            text = "读取日志失败";
        }
        return Map.of("text", text);
    }

    private static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] rawBytes = readAtMost(ex.getRequestBody(), MAX_BODY_BYTES);
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = GSON.fromJson(raw, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new InvalidJsonException();
        }
    }

    private static void sendJson(HttpExchange ex, int code, Map<String, ?> body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", GSON.toJson(body));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", type);
        setSecurityHeaders(headers, null);
        if (type.startsWith("application/json")) {
            headers.set("Cache-Control", "no-store");
        }
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void setSecurityHeaders(Headers headers, String nonce) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-XSS-Protection", "0");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        String csp = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; connect-src 'self'; img-src 'none'";
        if (nonce != null && !nonce.isEmpty()) {
            csp += "; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'";
        } else {
            csp += "; script-src 'none'; style-src 'none'";
        }
        headers.set("Content-Security-Policy", csp);
    }

    private static String validateRuleInput(String id, String nbt) {
        if (id == null || id.isEmpty()) {
            return "ID 不能为空";
        }
        if (id.length() > MAX_ID_CHARS) {
            return "ID 过长";
        }
        if (nbt != null && nbt.length() > MAX_NBT_CHARS) {
            return "NBT 过长";
        }
        return null;
    }

    private static byte[] readAtMost(java.io.InputStream in, int max) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) {
                throw new BodyTooLargeException();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean exactPath(HttpExchange ex, String expected) {
        String path = ex.getRequestURI().getPath();
        return expected.equals(path);
    }

    private static String clientKey(HttpExchange ex) {
        try {
            return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static boolean allowLoginAttempt(HttpExchange ex) {
        LoginGate gate = loginGates.get(clientKey(ex));
        return gate == null || gate.lockUntil <= System.currentTimeMillis();
    }

    private static void noteLoginFailure(HttpExchange ex) {
        String key = clientKey(ex);
        LoginGate gate = loginGates.computeIfAbsent(key, k -> new LoginGate());
        if (loginGates.size() > 4096) {
            loginGates.entrySet().removeIf(e -> e.getValue().lockUntil <= System.currentTimeMillis() && e.getValue().fails == 0);
            if (loginGates.size() > 4096) {
                loginGates.clear();
                gate = loginGates.computeIfAbsent(key, k -> new LoginGate());
            }
        }
        synchronized (gate) {
            gate.fails++;
            if (gate.fails >= LOGIN_MAX_FAILS) {
                gate.lockUntil = System.currentTimeMillis() + LOGIN_LOCK_MS;
                gate.fails = 0;
            }
        }
    }

    private static void clearLoginFailures(HttpExchange ex) {
        loginGates.remove(clientKey(ex));
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static <T> T callOnServer(java.util.function.Supplier<T> task) {
        MinecraftServer server = gameServer;
        if (server == null) {
            throw new IllegalStateException("服务器未运行");
        }
        try {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
