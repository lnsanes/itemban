package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

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

    private static final List<BlacklistRule> blacklistRules = new ArrayList<BlacklistRule>();
    private static final List<BlacklistRule> blockBlacklistRules = new ArrayList<BlacklistRule>();
    private static List<BlacklistRule> itemRulesSnapshot = Collections.emptyList();
    private static List<BlacklistRule> blockRulesSnapshot = Collections.emptyList();
    private static Set<String> blockIdsNeedingNbt = Collections.emptySet();
    private static Set<Block> trackedBlocks = Collections.emptySet();
    private static volatile boolean itemRulesPresent = false;
    private static volatile boolean blockRulesPresent = false;

    public static boolean publicAnnounce = true;
    public static boolean autoBanOnViolation = false;
    private static Set<String> excludeFromLog = new HashSet<String>();
    public static boolean detectDroppedItems = true;
    public static boolean detectWorldBlocks = true;

    public static class BlacklistRule {
        public String id;
        public Map<String, Object> nbt;
        public String nbtString;
        transient CompoundNBT parsedNbt;

        public boolean matches(String itemId, CompoundNBT itemNbt) {
            if (id == null || !id.equals(itemId)) return false;
            if (parsedNbt == null || parsedNbt.isEmpty()) return true;
            if (itemNbt == null || itemNbt.isEmpty()) return false;
            return nbtContains(itemNbt, parsedNbt);
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

    private static void compileRuleNbt(BlacklistRule rule) {
        rule.parsedNbt = null;
        try {
            if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                rule.parsedNbt = JsonToNBT.parseTag(rule.nbtString);
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
        itemRulesSnapshot = Collections.unmodifiableList(new ArrayList<BlacklistRule>(blacklistRules));
        blockRulesSnapshot = Collections.unmodifiableList(new ArrayList<BlacklistRule>(blockBlacklistRules));
        itemRulesPresent = !blacklistRules.isEmpty();
        blockRulesPresent = !blockBlacklistRules.isEmpty();

        Set<String> nbtIds = new HashSet<String>();
        Set<Block> blocks = new HashSet<Block>();
        for (BlacklistRule rule : blockBlacklistRules) {
            if (rule.id == null) {
                continue;
            }
            if (rule.parsedNbt != null && !rule.parsedNbt.isEmpty()) {
                nbtIds.add(rule.id);
            }
            try {
                ResourceLocation key = new ResourceLocation(rule.id);
                Block block = ForgeRegistries.BLOCKS.getValue(key);
                if (block != null && block != Blocks.AIR) {
                    blocks.add(block);
                }
            } catch (Exception ignored) {
            }
        }
        blockIdsNeedingNbt = Collections.unmodifiableSet(nbtIds);
        trackedBlocks = Collections.unmodifiableSet(blocks);
    }

    public static boolean hasItemBlacklist() {
        return itemRulesPresent;
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
        rebuildIndexes();
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
        rebuildIndexes();
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
            blacklistRules.removeIf(r -> r.id.equals(itemId));
        } else {
            blacklistRules.removeIf(r -> r.equalsRule(itemId, nbtString));
        }
        saveBlacklist();
        rebuildIndexes();
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

    public static boolean isBlockBlacklisted(String blockId, CompoundNBT nbt) {
        for (BlacklistRule rule : blockRulesSnapshot) {
            if (rule.matches(blockId, nbt)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(String itemId, CompoundNBT nbt) {
        for (BlacklistRule rule : itemRulesSnapshot) {
            if (rule.matches(itemId, nbt)) {
                return true;
            }
        }
        return false;
    }
}
