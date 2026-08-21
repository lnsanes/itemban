package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("ItemBan");
    private static final Path BLACKLIST_FILE = CONFIG_DIR.resolve("blacklist.json");
    private static final Path BLOCK_BLACKLIST_FILE = CONFIG_DIR.resolve("block_blacklist.json");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    // 支持两种格式：普通字符串（封禁整个物品）或对象（支持 NBT）
    private static List<BlacklistRule> blacklistRules = new ArrayList<>();

    // 独立的方块黑名单列表
    private static List<BlacklistRule> blockBlacklistRules = new ArrayList<>();

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

    public static class BlacklistRule {
        public String id;
        public Map<String, Object> nbt;        // 兼容旧格式
        public String nbtString;               // 新格式：原始 SNBT 字符串，支持任意 NBT

        public boolean matches(String itemId, CompoundTag itemNbt) {
            if (!id.equals(itemId)) return false;

            // 优先使用 nbtString（更可靠）
            if (nbtString != null && !nbtString.isEmpty()) {
                if (itemNbt == null || itemNbt.isEmpty()) return false;
                try {
                    CompoundTag required = TagParser.parseTag(nbtString);
                    return nbtContains(itemNbt, required);
                } catch (Exception e) {
                    ItemBan.LOGGER.error("NBT 解析失败: {}", nbtString);
                    return false;
                }
            }

            // 回退到旧的 Map 格式
            if (nbt == null || nbt.isEmpty()) return true;
            if (itemNbt == null || itemNbt.isEmpty()) return false;

            try {
                CompoundTag required = mapToCompoundTag(nbt);
                return nbtContains(itemNbt, required);
            } catch (Exception e) {
                ItemBan.LOGGER.error("NBT 匹配失败: {}", e.getMessage());
                return false;
            }
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
        for (String key : required.getAllKeys()) {
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
        return Collections.unmodifiableList(blacklistRules);
    }

    public static List<BlacklistRule> getBlockBlacklistRules() {
        return Collections.unmodifiableList(blockBlacklistRules);
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
    }

    public static boolean hasBlockBlacklist() {
        return !blockBlacklistRules.isEmpty();
    }

    public static boolean isBlockIdTracked(String blockId) {
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.id != null && rule.id.equals(blockId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean blockIdNeedsNbt(String blockId) {
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.id == null || !rule.id.equals(blockId)) {
                continue;
            }
            if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                return true;
            }
            if (rule.nbt != null && !rule.nbt.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlockBlacklisted(String blockId) {
        return blockBlacklistRules.stream().anyMatch(r -> r.id.equals(blockId) && (r.nbt == null || r.nbt.isEmpty()) && (r.nbtString == null || r.nbtString.isEmpty()));
    }

    public static boolean isBlockBlacklisted(String blockId, CompoundTag nbt) {
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.matches(blockId, nbt)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(String itemId) {
        return blacklistRules.stream().anyMatch(r -> r.id.equals(itemId) && (r.nbt == null || r.nbt.isEmpty()));
    }

    public static boolean isBlacklisted(String itemId, CompoundTag nbt) {
        for (BlacklistRule rule : blacklistRules) {
            if (rule.matches(itemId, nbt)) {
                return true;
            }
        }
        return false;
    }
}
