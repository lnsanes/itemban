package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class ConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("ItemBan");
    private static final Path BLACKLIST_FILE = CONFIG_DIR.resolve("blacklist.json");
    private static final Path BLOCK_BLACKLIST_FILE = CONFIG_DIR.resolve("block_blacklist.json");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private static final List<BlacklistRule> blacklistRules = new ArrayList<>();
    private static final List<BlacklistRule> blockBlacklistRules = new ArrayList<>();
    private static volatile List<BlacklistRule> itemRulesSnapshot = List.of();
    private static volatile List<BlacklistRule> blockRulesSnapshot = List.of();
    private static volatile Set<String> blockIdsNeedingNbt = Set.of();
    private static volatile Set<net.minecraft.world.level.block.Block> trackedBlocks = Set.of();
    private static volatile boolean itemRulesPresent = false;
    private static volatile boolean blockRulesPresent = false;


    /** Whether to broadcast chat announcements. */
    public static boolean publicAnnounce = true;
    /** Whether to auto-ban players on violation. */
    public static boolean autoBanOnViolation = false;
    /** Drop detection mode: true = area scan, false = instant intercept. */
    public static boolean detectDroppedItems = true;
    /** Whether to scan world blocks near players. */
    public static boolean detectWorldBlocks = true;

    public static boolean webEnabled = true;
    public static int webPort = 25580;
    public static String webBind = "127.0.0.1";
    public static final String WEB_ADMIN = "admin";
    public static final String WEB_ROLE_OWNER = "owner";
    public static final String WEB_ROLE_USER = "user";
    private static final List<WebAccount> webAccounts = new ArrayList<>();
    /** 一次性初始密码，只存在内存里，用过即失效 */
    private static volatile String generatedWebPassword = null;
    private static volatile String generatedWebPasswordHash = null;
    private static final int PBKDF2_ITERATIONS = 120000;

    public static class WebAccount {
        public String username;
        public String passwordHash;
        public String role;

        public WebAccount() {}

        WebAccount(String username, String passwordHash, String role) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.role = role;
        }
    }

    private static Set<String> excludeFromLog = new HashSet<>();

    public static class BlacklistRule {
        public String id;
        public String nbtString; // kept for backward compat in config files
        public Map<String, Object> nbt; // legacy map format
        transient net.minecraft.nbt.CompoundTag parsedNbt;

        public boolean matchesId(String itemId) {
            return id != null && id.equals(itemId);
        }

        public boolean equalsRule(String itemId, String nbtStr) {
            if (!matchesId(itemId)) return false;
            if (nbtStr == null || nbtStr.isEmpty()) return nbtString == null || nbtString.isEmpty();
            return Objects.equals(nbtString, nbtStr);
        }
    }


    private static void rebuildIndexes() {
        for (BlacklistRule rule : blacklistRules) {
            rule.parsedNbt = null;
            try {
                if (rule.nbtString != null && !rule.nbtString.isEmpty() && rule.nbtString.length() > 8192) {
                    ItemBan.LOGGER.warn("跳过过长 NBT 规则: {}", rule.id);
                } else if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    rule.parsedNbt = net.minecraft.nbt.TagParser.parseTag(rule.nbtString);
                }
            } catch (Exception ignored) {}
        }
        for (BlacklistRule rule : blockBlacklistRules) {
            rule.parsedNbt = null;
            try {
                if (rule.nbtString != null && !rule.nbtString.isEmpty() && rule.nbtString.length() > 8192) {
                    ItemBan.LOGGER.warn("跳过过长 NBT 规则: {}", rule.id);
                } else if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    rule.parsedNbt = net.minecraft.nbt.TagParser.parseTag(rule.nbtString);
                }
            } catch (Exception ignored) {}
        }
        itemRulesSnapshot = List.copyOf(blacklistRules);
        blockRulesSnapshot = List.copyOf(blockBlacklistRules);
        itemRulesPresent = !blacklistRules.isEmpty();
        blockRulesPresent = !blockBlacklistRules.isEmpty();
        java.util.Set<String> nbtIds = new java.util.HashSet<>();
        java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>();
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.id == null) continue;
            if (rule.parsedNbt != null && !rule.parsedNbt.isEmpty()) nbtIds.add(rule.id);
            try {
                                net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(rule.id));
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) blocks.add(block);
            } catch (Exception ignored) {}
        }
        blockIdsNeedingNbt = java.util.Set.copyOf(nbtIds);
        trackedBlocks = java.util.Set.copyOf(blocks);
    }
    public static boolean hasItemBlacklist() { return itemRulesPresent; }

    public static void register() {
        try {
            Files.createDirectories(CONFIG_DIR);
            loadConfig();
            loadBlacklist();
            loadBlockBlacklist();
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to create config dir", e);
        }
    }

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                Type type = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> config = GSON.fromJson(json, type);
                if (config != null) {
                    if (config.containsKey("publicAnnounce")) publicAnnounce = (Boolean) config.get("publicAnnounce");
                    if (config.containsKey("autoBanOnViolation")) autoBanOnViolation = (Boolean) config.get("autoBanOnViolation");
                    if (config.containsKey("detectDroppedItems")) detectDroppedItems = (Boolean) config.get("detectDroppedItems");
                    if (config.containsKey("detectWorldBlocks")) detectWorldBlocks = (Boolean) config.get("detectWorldBlocks");
                    if (config.containsKey("webEnabled")) webEnabled = (Boolean) config.get("webEnabled");
                    if (config.containsKey("webPort") && config.get("webPort") instanceof Number) {
                        int port = ((Number) config.get("webPort")).intValue();
                        if (port >= 1 && port <= 65535) {
                            webPort = port;
                        }
                    }
                    if (config.containsKey("webBind")) {
                        webBind = sanitizeWebBind(mapStr(config.get("webBind")));
                    }
                    applyWebAuthFromConfig(config);

                    Object listObj = config.get("excludeFromLog");
                    if (listObj instanceof List) excludeFromLog = new HashSet<>((List<String>) listObj);
                }
            } else {
                saveConfig();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load config", e);
        }
    }

    public static void saveConfig() {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("publicAnnounce", publicAnnounce);
            config.put("autoBanOnViolation", autoBanOnViolation);
            config.put("detectDroppedItems", detectDroppedItems);
            config.put("detectWorldBlocks", detectWorldBlocks);
            config.put("webEnabled", webEnabled);
            config.put("webPort", webPort);
            config.put("webBind", webBind);
            config.put("webAccounts", webAccountsForSave());
            config.put("excludeFromLog", new ArrayList<>(excludeFromLog));
            Files.writeString(CONFIG_FILE, GSON.toJson(config));
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save config", e);
        }
    }

    public static void setPublicAnnounce(boolean v) { publicAnnounce = v; saveConfig(); }
    public static void setAutoBanOnViolation(boolean v) { autoBanOnViolation = v; saveConfig(); }
    public static void setDetectDroppedItems(boolean v) { detectDroppedItems = v; saveConfig(); }
    public static void setDetectWorldBlocks(boolean v) { detectWorldBlocks = v; saveConfig(); }

    public static void addExcludeFromLog(String id) {
        String resolved = ItemNames.resolveId(id, false);
        if (resolved == null || resolved.isEmpty()) { return; }
        excludeFromLog.add(resolved);
        saveConfig();
    }
    public static void removeExcludeFromLog(String id) {
        excludeFromLog.remove(id);
        excludeFromLog.remove(ItemNames.resolveId(id, false));
        saveConfig();
    }
    public static Set<String> getExcludeFromLog() { return Collections.unmodifiableSet(excludeFromLog); }
    public static boolean isExcludedFromLog(String id) { return excludeFromLog.contains(id); }

    private static List<BlacklistRule> parseRules(List<Object> rawList) {
        List<BlacklistRule> out = new ArrayList<>();
        if (rawList == null) return out;
        for (Object entry : rawList) {
            if (entry instanceof String) {
                BlacklistRule r = new BlacklistRule();
                r.id = (String) entry;
                out.add(r);
            } else if (entry instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) entry;
                BlacklistRule r = new BlacklistRule();
                r.id = (String) map.get("id");
                Object ns = map.get("nbtString");
                if (ns instanceof String) r.nbtString = (String) ns;
                Object no = map.get("nbt");
                if (no instanceof Map) r.nbt = (Map<String, Object>) no;
                if (r.id != null) out.add(r);
            }
        }
        return out;
    }

    private static List<Object> rulesToJson(List<BlacklistRule> rules) {
        List<Object> out = new ArrayList<>();
        for (BlacklistRule r : rules) {
            if (r.nbtString != null && !r.nbtString.isEmpty()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", r.id);
                o.put("nbtString", r.nbtString);
                out.add(o);
            } else if (r.nbt != null && !r.nbt.isEmpty()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", r.id);
                o.put("nbt", r.nbt);
                out.add(o);
            } else {
                out.add(r.id);
            }
        }
        return out;
    }

    public static void loadBlacklist() {
        try {
            blacklistRules.clear();
            if (Files.exists(BLACKLIST_FILE)) {
                Type type = new TypeToken<List<Object>>(){}.getType();
                blacklistRules.addAll(parseRules(GSON.fromJson(Files.readString(BLACKLIST_FILE), type)));
            } else {
                saveBlacklist();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load blacklist", e);
        }
        rebuildIndexes();
    }

    public static void saveBlacklist() {
        try {
            Files.writeString(BLACKLIST_FILE, GSON.toJson(rulesToJson(blacklistRules)));
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save blacklist", e);
        }
    }

    public static void loadBlockBlacklist() {
        try {
            blockBlacklistRules.clear();
            if (Files.exists(BLOCK_BLACKLIST_FILE)) {
                Type type = new TypeToken<List<Object>>(){}.getType();
                blockBlacklistRules.addAll(parseRules(GSON.fromJson(Files.readString(BLOCK_BLACKLIST_FILE), type)));
            } else {
                saveBlockBlacklist();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load block blacklist", e);
        }
        rebuildIndexes();
    }

    public static void saveBlockBlacklist() {
        try {
            Files.writeString(BLOCK_BLACKLIST_FILE, GSON.toJson(rulesToJson(blockBlacklistRules)));
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save block blacklist", e);
        }
    }


    public static Map<String, Object> adminGuiState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publicAnnounce", publicAnnounce);
        out.put("autoBanOnViolation", autoBanOnViolation);
        out.put("detectDroppedItems", detectDroppedItems);
        out.put("detectWorldBlocks", detectWorldBlocks);
        out.put("items", namedRules(itemRulesSnapshot));
        out.put("blocks", namedRules(blockRulesSnapshot));
        java.util.List<java.util.Map<String, String>> excludes = new java.util.ArrayList<>();
        for (String id : excludeFromLog) {
            java.util.Map<String, String> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", ItemNames.nameOf(id));
            row.put("nbt", "");
            excludes.add(row);
        }
        out.put("excludes", excludes);
        out.put("keyId", AdminKeyManager.keyId());
        return out;
    }

    private static java.util.List<java.util.Map<String, String>> namedRules(java.util.List<BlacklistRule> rules) {
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        if (rules == null) {
            return out;
        }
        for (BlacklistRule rule : rules) {
            java.util.Map<String, String> row = new LinkedHashMap<>();
            row.put("id", rule.id == null ? "" : rule.id);
            row.put("name", ItemNames.nameOf(rule.id));
            row.put("nbt", rule.nbtString == null ? "" : rule.nbtString);
            out.add(row);
        }
        return out;
    }

    public static List<BlacklistRule> getBlacklistRules() { return itemRulesSnapshot; }
    public static List<BlacklistRule> getBlockBlacklistRules() { return blockRulesSnapshot; }

    public static String nbtError(String nbtString) {
        if (nbtString == null || nbtString.trim().isEmpty()) {
            return null;
        }
        String nbt = nbtString.trim();
        if (nbt.length() > 8192) {
            return "NBT 过长";
        }
        if (!nbt.startsWith("{")) {
            nbt = "{" + nbt + "}";
        }
        try {
            net.minecraft.nbt.TagParser.parseTag(nbt.trim());
            return null;
        } catch (Exception e) {
            return "NBT 格式无效";
        }
    }

    public static String validateNewRule(String itemId, String nbtString, boolean preferBlock) {
        if (itemId == null || itemId.trim().isEmpty()) {
            return "ID 不能为空";
        }
        if (itemId.length() > 256) {
            return "ID 过长";
        }
        String resolved = ItemNames.resolveId(itemId, preferBlock);
        if (resolved == null || resolved.isEmpty()) {
            return "找不到该物品/方块 ID";
        }
        return nbtError(nbtString);
    }

    public static void addToBlacklist(String id) { addToBlacklist(id, null); }
    public static void addToBlacklist(String id, String nbtString) {
        if (validateNewRule(id, nbtString, false) != null) {
            return;
        }
        BlacklistRule r = new BlacklistRule();
        r.id = ItemNames.resolveId(id, false);
        r.nbtString = nbtString;
        blacklistRules.add(r);
        saveBlacklist();
        rebuildIndexes();
    }

    public static void removeFromBlacklist(String id) { removeFromBlacklist(id, null); }
    public static void removeFromBlacklist(String id, String nbtString) {
        String resolved = ItemNames.resolveId(id, false);
        if (nbtString == null || nbtString.isEmpty()) blacklistRules.removeIf(r -> r.matchesId(id) || r.matchesId(resolved));
        else blacklistRules.removeIf(r -> r.equalsRule(id, nbtString) || r.equalsRule(resolved, nbtString));
        saveBlacklist();
        rebuildIndexes();
    }

    public static void addToBlockBlacklist(String id) { addToBlockBlacklist(id, null); }
    public static void addToBlockBlacklist(String id, String nbtString) {
        if (validateNewRule(id, nbtString, true) != null) {
            return;
        }
        BlacklistRule r = new BlacklistRule();
        r.id = ItemNames.resolveId(id, true);
        r.nbtString = nbtString;
        blockBlacklistRules.add(r);
        saveBlockBlacklist();
        rebuildIndexes();
    }

    public static void removeFromBlockBlacklist(String id) { removeFromBlockBlacklist(id, null); }
    public static void removeFromBlockBlacklist(String id, String nbtString) {
        String resolved = ItemNames.resolveId(id, true);
        if (nbtString == null || nbtString.isEmpty()) blockBlacklistRules.removeIf(r -> r.matchesId(id) || r.matchesId(resolved));
        else blockBlacklistRules.removeIf(r -> r.equalsRule(id, nbtString) || r.equalsRule(resolved, nbtString));
        saveBlockBlacklist();
        rebuildIndexes();
    }


    /**
     * 1.21+ matching: rules that carry NBT currently match only by ID (Data Components migration).
     * Plain-ID rules behave exactly as before. NBT rules are treated as ID-only until component-aware matching is added.
     */
    public static boolean isBlacklisted(String itemId, net.minecraft.world.item.ItemStack stack) {
        for (BlacklistRule r : itemRulesSnapshot) {
            if (r.matchesId(itemId)) {
                if (r.nbtString == null || r.nbtString.isEmpty()) return true;
                // TODO(1.21): compare DataComponents; for now treat NBT rules as ID match
                return true;
            }
        }
        return false;
    }


    public static boolean hasBlockBlacklist() {
        return blockRulesPresent;
    }
    public static boolean isTrackedBlock(net.minecraft.world.level.block.Block block) {
        return !trackedBlocks.isEmpty() && trackedBlocks.contains(block);
    }
    public static boolean blockIdNeedsNbt(String blockId) {
        return blockIdsNeedingNbt.contains(blockId);
    }


    public static boolean isBlockBlacklisted(String blockId, net.minecraft.nbt.CompoundTag blockEntityData) {
        for (BlacklistRule r : blockRulesSnapshot) {
            if (r.matchesId(blockId)) {
                if (r.nbtString == null || r.nbtString.isEmpty()) return true;
                // Block entities in 1.21 still expose NBT via saveAdditional/saveWithFullMetadata with provider; keep old-style contains check
                if (blockEntityData != null && r.parsedNbt != null && !r.parsedNbt.isEmpty()) {
                    return nbtContains(blockEntityData, r.parsedNbt);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean nbtContains(net.minecraft.nbt.CompoundTag itemNbt, net.minecraft.nbt.CompoundTag required) {
        for (String key : required.getAllKeys()) {
            if (!itemNbt.contains(key)) return false;
            var iv = itemNbt.get(key);
            var rv = required.get(key);
            if (iv == null || rv == null) return false;
            if (rv instanceof net.minecraft.nbt.CompoundTag rc) {
                if (!(iv instanceof net.minecraft.nbt.CompoundTag ic)) return false;
                if (!nbtContains(ic, rc)) return false;
            } else if (rv instanceof net.minecraft.nbt.ListTag rl) {
                if (!(iv instanceof net.minecraft.nbt.ListTag il)) return false;
                for (var req : rl) {
                    boolean found = false;
                    for (var it : il) {
                        if (req.equals(it) || (req instanceof net.minecraft.nbt.CompoundTag rc2 && it instanceof net.minecraft.nbt.CompoundTag ic2 && nbtContains(ic2, rc2))) {
                            found = true; break;
                        }
                    }
                    if (!found) return false;
                }
            } else if (!iv.equals(rv)) return false;
        }
        return true;
    }

    private static List<Map<String, String>> webAccountsForSave() {
        List<Map<String, String>> out = new ArrayList<>();
        for (WebAccount account : webAccounts) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("username", account.username);
            row.put("passwordHash", account.passwordHash);
            row.put("role", account.role);
            out.add(row);
        }
        return out;
    }

    private static void applyWebAuthFromConfig(Map<String, Object> config) {
        List<WebAccount> loaded = new ArrayList<>();
        Object listObj = config.get("webAccounts");
        if (listObj instanceof List) {
            for (Object row : (List<?>) listObj) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) row;
                String user = mapStr(map.get("username"));
                String hash = mapStr(map.get("passwordHash"));
                String role = normalizeRole(mapStr(map.get("role")));
                if (user.isEmpty() || hash.isEmpty()) {
                    continue;
                }
                if (WEB_ADMIN.equalsIgnoreCase(user)) {
                    user = WEB_ADMIN;
                    role = WEB_ROLE_OWNER;
                }
                loaded.add(new WebAccount(user, hash, role));
            }
        }
        boolean rewritten = false;
        if (loaded.isEmpty()) {
            String legacyHash = mapStr(config.get("webPasswordHash"));
            if (!legacyHash.isEmpty()) {
                generatedWebPasswordHash = legacyHash;
                rewritten = true;
            }
        }
        webAccounts.clear();
        webAccounts.addAll(loaded);
        Object plain = config.get("webPassword");
        if (plain != null) {
            String password = String.valueOf(plain);
            if (!password.isEmpty() && !"null".equals(password)) {
                upsertAccount(WEB_ADMIN, password, WEB_ROLE_OWNER);
                burnSetupPassword();
                rewritten = true;
            }
        }
        if (rewritten) {
            WebAdminServer.invalidateSessions();
            saveConfig();
        }
    }

    private static String mapStr(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static synchronized void ensureWebCredentials() {
        if (hasPermanentPassword()) {
            return;
        }
        if (generatedWebPasswordHash != null && !generatedWebPasswordHash.isEmpty()) {
            return;
        }
        generatedWebPassword = randomToken();
        generatedWebPasswordHash = hashPassword(generatedWebPassword);
        ItemBan.LOGGER.warn("ItemBan 网页管理一次性密码（账号 {}）：{} 。登录后立即失效，必须设置正式密码。",
                WEB_ADMIN, generatedWebPassword);
    }

    private static boolean hasPermanentPassword() {
        for (WebAccount account : webAccounts) {
            if (account.passwordHash != null && !account.passwordHash.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean hasPermanentWebPassword() {
        return hasPermanentPassword();
    }

    public static String webInfoMessage() {
        ensureWebCredentials();
        StringBuilder sb = new StringBuilder();
        sb.append("§aItemBan 网页管理\n");
        sb.append("§f地址: http://").append(webBind).append(":").append(webPort).append("\n");
        sb.append("§f系统账号: ").append(WEB_ADMIN).append("（owner）\n");
        if (!hasPermanentPassword() && generatedWebPassword != null) {
            sb.append("§e一次性密码: ").append(generatedWebPassword).append("\n");
            sb.append("§7用后即失效，登录后必须设置正式密码\n");
        } else {
            sb.append("§7已有正式账号，密码以哈希存储\n");
        }
        sb.append("§7只有 admin 或 owner 可注册/删除网页账号\n");
        sb.append("§7重置 admin 密码: /itemban web password <新密码>");
        return sb.toString();
    }

    public static synchronized String setWebPassword(String password) {
        String error = validatePassword(password);
        if (error != null) {
            return error;
        }
        upsertAccount(WEB_ADMIN, password, WEB_ROLE_OWNER);
        burnSetupPassword();
        saveConfig();
        WebAdminServer.invalidateSessions();
        return null;
    }


    public static boolean isPublicWebBind() {
        String bind = webBind == null ? "" : webBind.trim();
        return bind.isEmpty() || "0.0.0.0".equals(bind) || "*".equals(bind) || "::".equals(bind);
    }

    static String sanitizeWebBind(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "127.0.0.1";
        }
        String bind = raw.trim();
        if ("localhost".equalsIgnoreCase(bind) || "127.0.0.1".equals(bind)) {
            return "127.0.0.1";
        }
        if ("*".equals(bind) || "0.0.0.0".equals(bind) || "::".equals(bind)) {
            return "0.0.0.0".equals(bind) || "*".equals(bind) ? "0.0.0.0" : bind;
        }
        if (bind.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return bind;
        }
        if (bind.indexOf(':') >= 0 && bind.length() <= 45) {
            boolean ok = true;
            for (int i = 0; i < bind.length(); i++) {
                char c = bind.charAt(i);
                if (!(c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return bind;
            }
        }
        ItemBan.LOGGER.warn("非法 webBind 值 '{}'，已回退到 127.0.0.1", bind);
        return "127.0.0.1";
    }

    public static class WebLoginResult {
        public final boolean ok;
        public final boolean setupOnly;
        public final String username;
        public final String role;

        WebLoginResult(boolean ok, boolean setupOnly, String username, String role) {
            this.ok = ok;
            this.setupOnly = setupOnly;
            this.username = username;
            this.role = role;
        }
    }

    public static synchronized WebLoginResult authenticateWeb(String username, String password) {
        ensureWebCredentials();
        String user = username == null ? "" : username.trim();
        String pass = password == null ? "" : password;
        if (user.length() > 64 || pass.length() > 256) {
            dummyPbkdf2("x");
            return new WebLoginResult(false, false, "", "");
        }
        if (!hasPermanentPassword() && WEB_ADMIN.equals(user)) {
            boolean passOk = verifyPassword(pass, generatedWebPasswordHash);
            if (passOk) {
                burnSetupPassword();
                return new WebLoginResult(true, true, WEB_ADMIN, WEB_ROLE_OWNER);
            }
            return new WebLoginResult(false, false, "", "");
        }
        WebAccount account = findAccount(user);
        boolean userOk = account != null;
        boolean passOk = verifyPassword(pass, account == null ? "" : account.passwordHash);
        if (!(userOk & passOk)) {
            return new WebLoginResult(false, false, "", "");
        }
        return new WebLoginResult(true, false, account.username, account.role);
    }

    public static boolean canManageAccounts(String username, String role) {
        return WEB_ADMIN.equals(username) || WEB_ROLE_OWNER.equals(role);
    }

    public static synchronized List<Map<String, String>> listWebAccounts() {
        List<Map<String, String>> out = new ArrayList<>();
        for (WebAccount account : webAccounts) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("username", account.username);
            row.put("role", account.role);
            out.add(row);
        }
        if (out.isEmpty()) {
            Map<String, String> admin = new LinkedHashMap<>();
            admin.put("username", WEB_ADMIN);
            admin.put("role", WEB_ROLE_OWNER);
            out.add(admin);
        }
        return out;
    }

    public static synchronized String registerWebAccount(String actor, String actorRole, String username, String password, String role) {
        if (!canManageAccounts(actor, actorRole)) {
            return "只有 admin 或 owner 可以注册账号";
        }
        String user = username == null ? "" : username.trim();
        if (!user.matches("[A-Za-z0-9_]{2,32}")) {
            return "账号只能是 2-32 位字母、数字或下划线";
        }
        if (WEB_ADMIN.equalsIgnoreCase(user)) {
            return "admin 为系统账号，不能重复注册";
        }
        if (findAccountIgnoreCase(user) != null) {
            return "账号已存在";
        }
        String error = validatePassword(password);
        if (error != null) {
            return error;
        }
        String normalized = normalizeRole(role);
        if (WEB_ROLE_OWNER.equals(normalized) && !WEB_ADMIN.equalsIgnoreCase(actor)) {
            return "只有 admin 可以注册 owner 账号";
        }
        webAccounts.add(new WebAccount(user, hashPassword(password), normalized));
        saveConfig();
        return null;
    }

    public static synchronized String deleteWebAccount(String actor, String actorRole, String username) {
        if (!canManageAccounts(actor, actorRole)) {
            return "只有 admin 或 owner 可以删除账号";
        }
        String user = username == null ? "" : username.trim();
        if (WEB_ADMIN.equalsIgnoreCase(user)) {
            return "不能删除系统账号 admin";
        }
        if (user.equalsIgnoreCase(actor)) {
            return "不能删除当前登录账号";
        }
        WebAccount account = findAccount(user);
        if (account == null) {
            return "账号不存在";
        }
        if (WEB_ROLE_OWNER.equals(account.role) && !WEB_ADMIN.equalsIgnoreCase(actor)) {
            return "只有 admin 可以删除 owner 账号";
        }
        webAccounts.remove(account);
        saveConfig();
        WebAdminServer.invalidateUserSessions(user);
        return null;
    }

    public static synchronized String completeWebSetup(String password) {
        return setWebPassword(password);
    }

    public static synchronized String changeOwnPassword(String username, String password) {
        String error = validatePassword(password);
        if (error != null) {
            return error;
        }
        WebAccount account = findAccount(username);
        if (account == null) {
            if (WEB_ADMIN.equals(username)) {
                return setWebPassword(password);
            }
            return "账号不存在";
        }
        account.passwordHash = hashPassword(password);
        if (WEB_ADMIN.equals(account.username)) {
            account.role = WEB_ROLE_OWNER;
        }
        saveConfig();
        WebAdminServer.invalidateUserSessions(username);
        return null;
    }

    private static String validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        if (password.length() > 256) {
            return "密码过长";
        }
        if (password.length() < 8) {
            return "密码至少 8 个字符";
        }
        return null;
    }

    private static String normalizeRole(String role) {
        return WEB_ROLE_OWNER.equalsIgnoreCase(role) ? WEB_ROLE_OWNER : WEB_ROLE_USER;
    }

    private static WebAccount findAccount(String username) {
        for (WebAccount account : webAccounts) {
            if (account.username.equals(username)) {
                return account;
            }
        }
        return null;
    }

    private static WebAccount findAccountIgnoreCase(String username) {
        for (WebAccount account : webAccounts) {
            if (account.username.equalsIgnoreCase(username)) {
                return account;
            }
        }
        return null;
    }

    private static void upsertAccount(String username, String password, String role) {
        WebAccount account = findAccountIgnoreCase(username);
        String hash = hashPassword(password);
        String normalized = WEB_ADMIN.equalsIgnoreCase(username) ? WEB_ROLE_OWNER : normalizeRole(role);
        String name = WEB_ADMIN.equalsIgnoreCase(username) ? WEB_ADMIN : username;
        if (account == null) {
            webAccounts.add(new WebAccount(name, hash, normalized));
        } else {
            account.username = name;
            account.passwordHash = hash;
            account.role = normalized;
        }
    }

    private static void burnSetupPassword() {
        generatedWebPassword = null;
        generatedWebPasswordHash = null;
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64(salt) + "$" + b64(dk);
    }

    private static boolean verifyPassword(String password, String stored) {
        if (stored == null || stored.isEmpty()) {
            dummyPbkdf2(password);
            return false;
        }
        String[] parts = stored.split("\\$", 4);
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            dummyPbkdf2(password);
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            dummyPbkdf2(password);
            return false;
        }
        if (iterations < 10_000 || iterations > 200_000) {
            dummyPbkdf2(password);
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            dummyPbkdf2(password);
            return false;
        }
        byte[] actual = pbkdf2(password, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static void dummyPbkdf2(String password) {
        pbkdf2(password, new byte[16], PBKDF2_ITERATIONS);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            char[] chars = password.toCharArray();
            try {
                KeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                Arrays.fill(chars, '\0');
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法计算网页密码哈希", e);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String randomToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[9];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
