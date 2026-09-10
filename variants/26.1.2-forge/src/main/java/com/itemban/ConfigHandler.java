package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
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

    // 支持两种格式：普通字符串（封禁整个物品）或对象（支持 NBT）
    private static final List<BlacklistRule> blacklistRules = new ArrayList<>();

    // 独立的方块黑名单列表
    private static final List<BlacklistRule> blockBlacklistRules = new ArrayList<>();
    private static volatile List<BlacklistRule> itemRulesSnapshot = List.of();
    private static volatile List<BlacklistRule> blockRulesSnapshot = List.of();
    private static volatile Set<String> blockIdsNeedingNbt = Set.of();
    private static volatile Set<Block> trackedBlocks = Set.of();
    private static volatile boolean itemRulesPresent = false;
    private static volatile boolean blockRulesPresent = false;


    /** 是否在聊天栏公示获取违禁物品的玩家（默认开启） */
    public static boolean publicAnnounce = true;

    /** 是否在玩家获取违禁物品时自动封禁该玩家（默认 false） */
    public static boolean autoBanOnViolation = false;

    /** 不记录审计日志的违禁物品 ID 列表 */
    private static Set<String> excludeFromLog = new HashSet<>();

    /** 是否检测掉落物（ItemEntity），默认开启 */
    public static boolean detectDroppedItems = true;

    /** 是否检测世界中的违禁方块，默认开启 */
    public static boolean detectWorldBlocks = true;

    public static boolean webEnabled = true;
    public static int webPort = 25580;
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

    public static class BlacklistRule {
        public String id;
        public Map<String, Object> nbt;        // 兼容旧格式
        public String nbtString;               // 新格式：原始 SNBT 字符串，支持任意 NBT
        transient CompoundTag parsedNbt;

        public boolean matches(String itemId, CompoundTag itemNbt) {
            if (id == null || !id.equals(itemId)) return false;
            if (parsedNbt == null || parsedNbt.isEmpty()) return true;
            if (itemNbt == null || itemNbt.isEmpty()) return false;
            return nbtContains(itemNbt, parsedNbt);
        }

        /** 用于精确删除时判断是否完全匹配 */
        public boolean equalsRule(String itemId, String nbtString) {
            if (!id.equals(itemId)) return false;

            if (nbtString == null || nbtString.isEmpty()) {
                return this.nbtString == null || this.nbtString.isEmpty();
            }
            return nbtString.equals(this.nbtString);
        }
    }

    private static CompoundTag mapToCompoundTag(Map<String, Object> map) throws Exception {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                tag.put(entry.getKey(), mapToCompoundTag((Map<String, Object>) value));
            } else if (value instanceof List) {
                // 兼容 1.20.1 的 ListTag 处理（Enchantments 等）
                net.minecraft.nbt.ListTag listTag = new net.minecraft.nbt.ListTag();
                for (Object v : (List<?>) value) {
                    try {
                        if (v instanceof Map) {
                            listTag.add(mapToCompoundTag((Map<String, Object>) v));
                        } else {
                            listTag.add(net.minecraft.nbt.StringTag.valueOf(v.toString()));
                        }
                    } catch (Exception e) {
                        listTag.add(net.minecraft.nbt.StringTag.valueOf(""));
                    }
                }
                tag.put(entry.getKey(), listTag);
            } else if (value instanceof Number) {
                Number num = (Number) value;
                if (num instanceof Integer || num instanceof Long) {
                    tag.putInt(entry.getKey(), num.intValue());
                } else {
                    tag.putFloat(entry.getKey(), num.floatValue());
                }
            } else {
                tag.putString(entry.getKey(), value.toString());
            }
        }
        return tag;
    }

    private static boolean nbtContains(CompoundTag itemNbt, CompoundTag required) {
        for (String key : required.keySet()) {
            if (!itemNbt.contains(key)) return false;

            var itemValue = itemNbt.get(key);
            var requiredValue = required.get(key);

            if (itemValue == null || requiredValue == null) return false;

            if (requiredValue instanceof CompoundTag requiredCompound) {
                if (!(itemValue instanceof CompoundTag itemCompound)) return false;
                if (!nbtContains(itemCompound, requiredCompound)) return false;
            } else if (requiredValue instanceof net.minecraft.nbt.ListTag requiredList) {
                if (!(itemValue instanceof net.minecraft.nbt.ListTag itemList)) return false;
                // 简单包含检查：只要 requiredList 中的每个元素都在 itemList 中出现即可
                for (var reqElement : requiredList) {
                    boolean found = false;
                    for (var itemElement : itemList) {
                        if (reqElement.equals(itemElement) || 
                            (reqElement instanceof CompoundTag rc && itemElement instanceof CompoundTag ic && nbtContains(ic, rc))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false;
                }
            } else {
                if (!itemValue.equals(requiredValue)) return false;
            }
        }
        return true;
    }

    private static void compileRuleNbt(BlacklistRule rule) {
        rule.parsedNbt = null;
        try {
            if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                rule.parsedNbt = TagParser.parseCompoundFully(rule.nbtString);
            } else if (rule.nbt != null && !rule.nbt.isEmpty()) {
                rule.parsedNbt = mapToCompoundTag(rule.nbt);
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("NBT 预解析失败: {}", rule.nbtString != null ? rule.nbtString : e.getMessage());
            rule.parsedNbt = null;
        }
    }

    private static void rebuildIndexes() {
        for (BlacklistRule rule : blacklistRules) {
            compileRuleNbt(rule);
        }
        for (BlacklistRule rule : blockBlacklistRules) {
            compileRuleNbt(rule);
        }
        itemRulesSnapshot = List.copyOf(blacklistRules);
        blockRulesSnapshot = List.copyOf(blockBlacklistRules);
        itemRulesPresent = !blacklistRules.isEmpty();
        blockRulesPresent = !blockBlacklistRules.isEmpty();

        Set<String> nbtIds = new HashSet<>();
        Set<Block> blocks = new HashSet<>();
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.id == null) {
                continue;
            }
            if (rule.parsedNbt != null && !rule.parsedNbt.isEmpty()) {
                nbtIds.add(rule.id);
            }
            try {
                Block block = BuiltInRegistries.BLOCK.get(Identifier.parse(rule.id))
                        .map(net.minecraft.core.Holder::value)
                        .orElse(null);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    blocks.add(block);
                }
            } catch (Exception ignored) {
            }
        }
        blockIdsNeedingNbt = Set.copyOf(nbtIds);
        trackedBlocks = Set.copyOf(blocks);
    }

    public static boolean hasItemBlacklist() {
        return itemRulesPresent;
    }

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
                    if (config.containsKey("publicAnnounce")) {
                        publicAnnounce = (Boolean) config.get("publicAnnounce");
                    }
                    if (config.containsKey("autoBanOnViolation")) {
                        autoBanOnViolation = (Boolean) config.get("autoBanOnViolation");
                    }
                    if (config.containsKey("excludeFromLog")) {
                        Object listObj = config.get("excludeFromLog");
                        if (listObj instanceof List) {
                            excludeFromLog = new HashSet<>((List<String>) listObj);
                        }
                    }
                    if (config.containsKey("detectDroppedItems")) {
                        detectDroppedItems = (Boolean) config.get("detectDroppedItems");
                    }
                    if (config.containsKey("detectWorldBlocks")) {
                        detectWorldBlocks = (Boolean) config.get("detectWorldBlocks");
                    }
                    if (config.containsKey("webEnabled")) {
                        webEnabled = (Boolean) config.get("webEnabled");
                    }
                    if (config.containsKey("webPort") && config.get("webPort") instanceof Number) {
                        webPort = ((Number) config.get("webPort")).intValue();
                    }
                    applyWebAuthFromConfig(config);

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
            config.put("excludeFromLog", new ArrayList<>(excludeFromLog));
            config.put("detectDroppedItems", detectDroppedItems);
            config.put("detectWorldBlocks", detectWorldBlocks);
            config.put("webEnabled", webEnabled);
            config.put("webPort", webPort);
            config.put("webAccounts", webAccountsForSave());
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_FILE, json);
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save config", e);
        }
    }

    public static void setPublicAnnounce(boolean value) {
        publicAnnounce = value;
        saveConfig();
    }

    public static void setAutoBanOnViolation(boolean value) {
        autoBanOnViolation = value;
        saveConfig();
    }

    public static void setDetectDroppedItems(boolean value) {
        detectDroppedItems = value;
        saveConfig();
    }

    public static void setDetectWorldBlocks(boolean value) {
        detectWorldBlocks = value;
        saveConfig();
    }

    /** 添加物品到不记录审计日志的列表 */
    public static void addExcludeFromLog(String itemId) {
        excludeFromLog.add(itemId);
        saveConfig();
    }

    /** 从不记录审计日志的列表移除物品 */
    public static void removeExcludeFromLog(String itemId) {
        excludeFromLog.remove(itemId);
        saveConfig();
    }

    /** 获取不记录审计日志的物品列表 */
    public static Set<String> getExcludeFromLog() {
        return Collections.unmodifiableSet(excludeFromLog);
    }

    /** 判断物品是否在不记录审计日志的列表中 */
    public static boolean isExcludedFromLog(String itemId) {
        return excludeFromLog.contains(itemId);
    }

    public static void loadBlacklist() {
        try {
            if (Files.exists(BLACKLIST_FILE)) {
                String json = Files.readString(BLACKLIST_FILE);
                Type type = new TypeToken<List<Object>>(){}.getType();
                List<Object> rawList = GSON.fromJson(json, type);
                blacklistRules.clear();

                if (rawList != null) {
                    for (Object entry : rawList) {
                        if (entry instanceof String) {
                            // 兼容旧格式：纯字符串
                            BlacklistRule rule = new BlacklistRule();
                            rule.id = (String) entry;
                            blacklistRules.add(rule);
                        } else if (entry instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) entry;
                            BlacklistRule rule = new BlacklistRule();
                            rule.id = (String) map.get("id");

                            // 优先读取 nbtString
                            Object nbtStr = map.get("nbtString");
                            if (nbtStr instanceof String) {
                                rule.nbtString = (String) nbtStr;
                            } else {
                                // 兼容旧的 Map 格式
                                Object nbtObj = map.get("nbt");
                                if (nbtObj instanceof Map) {
                                    rule.nbt = (Map<String, Object>) nbtObj;
                                }
                            }
                            if (rule.id != null) {
                                blacklistRules.add(rule);
                            }
                        }
                    }
                }
            } else {
                blacklistRules.clear();
                saveBlacklist();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load blacklist", e);
        }
        rebuildIndexes();
    }

    public static void loadBlockBlacklist() {
        try {
            if (Files.exists(BLOCK_BLACKLIST_FILE)) {
                String json = Files.readString(BLOCK_BLACKLIST_FILE);
                Type type = new TypeToken<List<Object>>(){}.getType();
                List<Object> rawList = GSON.fromJson(json, type);
                blockBlacklistRules.clear();

                if (rawList != null) {
                    for (Object entry : rawList) {
                        if (entry instanceof String) {
                            BlacklistRule rule = new BlacklistRule();
                            rule.id = (String) entry;
                            blockBlacklistRules.add(rule);
                        } else if (entry instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) entry;
                            BlacklistRule rule = new BlacklistRule();
                            rule.id = (String) map.get("id");

                            Object nbtStr = map.get("nbtString");
                            if (nbtStr instanceof String) {
                                rule.nbtString = (String) nbtStr;
                            } else {
                                Object nbtObj = map.get("nbt");
                                if (nbtObj instanceof Map) {
                                    rule.nbt = (Map<String, Object>) nbtObj;
                                }
                            }
                            if (rule.id != null) {
                                blockBlacklistRules.add(rule);
                            }
                        }
                    }
                }
            } else {
                blockBlacklistRules.clear();
                saveBlockBlacklist();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load block blacklist", e);
        }
        rebuildIndexes();
    }

    public static void saveBlockBlacklist() {
        try {
            List<Object> toSave = new ArrayList<>();
            for (BlacklistRule rule : blockBlacklistRules) {
                if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("id", rule.id);
                    obj.put("nbtString", rule.nbtString);
                    toSave.add(obj);
                } else if (rule.nbt != null && !rule.nbt.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("id", rule.id);
                    obj.put("nbt", rule.nbt);
                    toSave.add(obj);
                } else {
                    toSave.add(rule.id);
                }
            }
            String json = GSON.toJson(toSave);
            Files.writeString(BLOCK_BLACKLIST_FILE, json);
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save block blacklist", e);
        }
    }

    public static void saveBlacklist() {
        try {
            List<Object> toSave = new ArrayList<>();
            for (BlacklistRule rule : blacklistRules) {
                if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    // 优先保存 nbtString 格式
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("id", rule.id);
                    obj.put("nbtString", rule.nbtString);
                    toSave.add(obj);
                } else if (rule.nbt != null && !rule.nbt.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("id", rule.id);
                    obj.put("nbt", rule.nbt);
                    toSave.add(obj);
                } else {
                    toSave.add(rule.id);
                }
            }
            String json = GSON.toJson(toSave);
            Files.writeString(BLACKLIST_FILE, json);
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save blacklist", e);
        }
    }

    public static List<BlacklistRule> getBlacklistRules() {
        return itemRulesSnapshot;
    }

    public static List<BlacklistRule> getBlockBlacklistRules() {
        return blockRulesSnapshot;
    }

    public static void addToBlacklist(String itemId) {
        addToBlacklist(itemId, null);
    }

    public static void addToBlacklist(String itemId, String nbtString) {
        BlacklistRule rule = new BlacklistRule();
        rule.id = itemId;
        rule.nbtString = nbtString;
        blacklistRules.add(rule);
        saveBlacklist();
        rebuildIndexes();
    }

    public static void removeFromBlacklist(String itemId) {
        removeFromBlacklist(itemId, null);
    }

    public static void removeFromBlacklist(String itemId, String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) {
            // 删除该物品的所有规则
            blacklistRules.removeIf(r -> r.id.equals(itemId));
        } else {
            // 只删除完全匹配 NBT 的规则
            blacklistRules.removeIf(r -> r.equalsRule(itemId, nbtString));
        }
        saveBlacklist();
        rebuildIndexes();
    }

    // ===== 方块黑名单独立 API =====

    public static void addToBlockBlacklist(String blockId) {
        addToBlockBlacklist(blockId, null);
    }

    public static void addToBlockBlacklist(String blockId, String nbtString) {
        BlacklistRule rule = new BlacklistRule();
        rule.id = blockId;
        rule.nbtString = nbtString;
        blockBlacklistRules.add(rule);
        saveBlockBlacklist();
        rebuildIndexes();
    }

    public static void removeFromBlockBlacklist(String blockId) {
        removeFromBlockBlacklist(blockId, null);
    }

    public static void removeFromBlockBlacklist(String blockId, String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) {
            blockBlacklistRules.removeIf(r -> r.id.equals(blockId));
        } else {
            blockBlacklistRules.removeIf(r -> r.equalsRule(blockId, nbtString));
        }
        saveBlockBlacklist();
        rebuildIndexes();
    }


    public static boolean hasBlockBlacklist() {
        return blockRulesPresent;
    }

    public static boolean isTrackedBlock(Block block) {
        return !trackedBlocks.isEmpty() && trackedBlocks.contains(block);
    }

    public static boolean blockIdNeedsNbt(String blockId) {
        return blockIdsNeedingNbt.contains(blockId);
    }

    public static boolean isBlockBlacklisted(String blockId, CompoundTag nbt) {
        for (BlacklistRule rule : blockRulesSnapshot) {
            if (rule.matches(blockId, nbt)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(String itemId, CompoundTag nbt) {
        for (BlacklistRule rule : itemRulesSnapshot) {
            if (rule.matches(itemId, nbt)) {
                return true;
            }
        }
        return false;
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
        sb.append("§f地址: http://<服务器IP>:").append(webPort).append("\n");
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
