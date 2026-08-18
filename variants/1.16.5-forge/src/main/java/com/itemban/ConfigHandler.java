package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("ItemBan");
    private static final Path BLACKLIST_FILE = CONFIG_DIR.resolve("blacklist.json");
    private static final Path BLOCK_BLACKLIST_FILE = CONFIG_DIR.resolve("block_blacklist.json");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private static List<BlacklistRule> blacklistRules = new ArrayList<BlacklistRule>();
    private static List<BlacklistRule> blockBlacklistRules = new ArrayList<BlacklistRule>();

    public static boolean publicAnnounce = true;
    public static boolean autoBanOnViolation = false;
    private static Set<String> excludeFromLog = new HashSet<String>();
    public static boolean detectDroppedItems = true;
    public static boolean detectWorldBlocks = true;

    public static class BlacklistRule {
        public String id;
        public Map<String, Object> nbt;
        public String nbtString;

        public boolean matches(String itemId, CompoundNBT itemNbt) {
            if (!id.equals(itemId)) return false;

            if (nbtString != null && !nbtString.isEmpty()) {
                if (itemNbt == null || itemNbt.isEmpty()) return false;
                try {
                    CompoundNBT required = JsonToNBT.parseTag(nbtString);
                    return nbtContains(itemNbt, required);
                } catch (Exception e) {
                    ItemBan.LOGGER.error("NBT 解析失败: {}", nbtString);
                    return false;
                }
            }

            if (nbt == null || nbt.isEmpty()) return true;
            if (itemNbt == null || itemNbt.isEmpty()) return false;

            try {
                CompoundNBT required = mapToCompoundTag(nbt);
                return nbtContains(itemNbt, required);
            } catch (Exception e) {
                ItemBan.LOGGER.error("NBT 匹配失败: {}", e.getMessage());
                return false;
            }
        }

        public boolean equalsRule(String itemId, String nbtString) {
            if (!id.equals(itemId)) return false;
            if (nbtString == null || nbtString.isEmpty()) {
                return this.nbtString == null || this.nbtString.isEmpty();
            }
            return nbtString.equals(this.nbtString);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompoundNBT mapToCompoundTag(Map<String, Object> map) throws Exception {
        CompoundNBT tag = new CompoundNBT();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                tag.put(entry.getKey(), mapToCompoundTag((Map<String, Object>) value));
            } else if (value instanceof List) {
                ListNBT listTag = new ListNBT();
                for (Object v : (List<?>) value) {
                    try {
                        if (v instanceof Map) {
                            listTag.add(mapToCompoundTag((Map<String, Object>) v));
                        } else {
                            listTag.add(StringNBT.valueOf(v.toString()));
                        }
                    } catch (Exception e) {
                        listTag.add(StringNBT.valueOf(""));
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

    private static boolean nbtContains(CompoundNBT itemNbt, CompoundNBT required) {
        for (String key : required.getAllKeys()) {
            if (!itemNbt.contains(key)) return false;

            INBT itemValue = itemNbt.get(key);
            INBT requiredValue = required.get(key);

            if (itemValue == null || requiredValue == null) return false;

            if (requiredValue instanceof CompoundNBT) {
                if (!(itemValue instanceof CompoundNBT)) return false;
                if (!nbtContains((CompoundNBT) itemValue, (CompoundNBT) requiredValue)) return false;
            } else if (requiredValue instanceof ListNBT) {
                if (!(itemValue instanceof ListNBT)) return false;
                ListNBT requiredList = (ListNBT) requiredValue;
                ListNBT itemList = (ListNBT) itemValue;
                for (int i = 0; i < requiredList.size(); i++) {
                    INBT reqElement = requiredList.get(i);
                    boolean found = false;
                    for (int j = 0; j < itemList.size(); j++) {
                        INBT itemElement = itemList.get(j);
                        if (reqElement.equals(itemElement)
                                || (reqElement instanceof CompoundNBT && itemElement instanceof CompoundNBT
                                && nbtContains((CompoundNBT) itemElement, (CompoundNBT) reqElement))) {
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

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
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

    @SuppressWarnings("unchecked")
    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = readFile(CONFIG_FILE);
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
                            excludeFromLog = new HashSet<String>((List<String>) listObj);
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
            Map<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("publicAnnounce", publicAnnounce);
            config.put("autoBanOnViolation", autoBanOnViolation);
            config.put("excludeFromLog", new ArrayList<String>(excludeFromLog));
            config.put("detectDroppedItems", detectDroppedItems);
            config.put("detectWorldBlocks", detectWorldBlocks);
            writeFile(CONFIG_FILE, GSON.toJson(config));
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

    public static void addExcludeFromLog(String itemId) {
        excludeFromLog.add(itemId);
        saveConfig();
    }

    public static void removeExcludeFromLog(String itemId) {
        excludeFromLog.remove(itemId);
        saveConfig();
    }

    public static Set<String> getExcludeFromLog() {
        return Collections.unmodifiableSet(excludeFromLog);
    }

    public static boolean isExcludedFromLog(String itemId) {
        return excludeFromLog.contains(itemId);
    }

    @SuppressWarnings("unchecked")
    public static void loadBlacklist() {
        try {
            if (Files.exists(BLACKLIST_FILE)) {
                String json = readFile(BLACKLIST_FILE);
                Type type = new TypeToken<List<Object>>(){}.getType();
                List<Object> rawList = GSON.fromJson(json, type);
                blacklistRules.clear();

                if (rawList != null) {
                    for (Object entry : rawList) {
                        if (entry instanceof String) {
                            BlacklistRule rule = new BlacklistRule();
                            rule.id = (String) entry;
                            blacklistRules.add(rule);
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

    @SuppressWarnings("unchecked")
    public static void loadBlockBlacklist() {
        try {
            if (Files.exists(BLOCK_BLACKLIST_FILE)) {
                String json = readFile(BLOCK_BLACKLIST_FILE);
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
            List<Object> toSave = new ArrayList<Object>();
            for (BlacklistRule rule : blockBlacklistRules) {
                if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<String, Object>();
                    obj.put("id", rule.id);
                    obj.put("nbtString", rule.nbtString);
                    toSave.add(obj);
                } else if (rule.nbt != null && !rule.nbt.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<String, Object>();
                    obj.put("id", rule.id);
                    obj.put("nbt", rule.nbt);
                    toSave.add(obj);
                } else {
                    toSave.add(rule.id);
                }
            }
            writeFile(BLOCK_BLACKLIST_FILE, GSON.toJson(toSave));
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save block blacklist", e);
        }
    }

    public static void saveBlacklist() {
        try {
            List<Object> toSave = new ArrayList<Object>();
            for (BlacklistRule rule : blacklistRules) {
                if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<String, Object>();
                    obj.put("id", rule.id);
                    obj.put("nbtString", rule.nbtString);
                    toSave.add(obj);
                } else if (rule.nbt != null && !rule.nbt.isEmpty()) {
                    Map<String, Object> obj = new LinkedHashMap<String, Object>();
                    obj.put("id", rule.id);
                    obj.put("nbt", rule.nbt);
                    toSave.add(obj);
                } else {
                    toSave.add(rule.id);
                }
            }
            writeFile(BLACKLIST_FILE, GSON.toJson(toSave));
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
            blacklistRules.removeIf(r -> r.id.equals(itemId));
        } else {
            blacklistRules.removeIf(r -> r.equalsRule(itemId, nbtString));
        }
        saveBlacklist();
    }

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

    public static boolean isBlockBlacklisted(String blockId) {
        return blockBlacklistRules.stream().anyMatch(r -> r.id.equals(blockId) && (r.nbt == null || r.nbt.isEmpty()));
    }

    public static boolean isBlockBlacklisted(String blockId, CompoundNBT nbt) {
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

    public static boolean isBlacklisted(String itemId, CompoundNBT nbt) {
        for (BlacklistRule rule : blacklistRules) {
            if (rule.matches(itemId, nbt)) {
                return true;
            }
        }
        return false;
    }
}
