package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
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

    private static List<BlacklistRule> blacklistRules = new ArrayList<>();
    private static List<BlacklistRule> blockBlacklistRules = new ArrayList<>();

    /** Whether to broadcast chat announcements. */
    public static boolean publicAnnounce = true;
    /** Whether to auto-ban players on violation. */
    public static boolean autoBanOnViolation = false;
    /** Drop detection mode: true = area scan, false = instant intercept. */
    public static boolean detectDroppedItems = true;
    /** Whether to scan world blocks near players. */
    public static boolean detectWorldBlocks = true;

    private static Set<String> excludeFromLog = new HashSet<>();

    public static class BlacklistRule {
        public String id;
        public String nbtString; // kept for backward compat in config files
        public Map<String, Object> nbt; // legacy map format

        public boolean matchesId(String itemId) {
            return id != null && id.equals(itemId);
        }

        public boolean equalsRule(String itemId, String nbtStr) {
            if (!matchesId(itemId)) return false;
            if (nbtStr == null || nbtStr.isEmpty()) return nbtString == null || nbtString.isEmpty();
            return Objects.equals(nbtString, nbtStr);
        }
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
                    if (config.containsKey("publicAnnounce")) publicAnnounce = (Boolean) config.get("publicAnnounce");
                    if (config.containsKey("autoBanOnViolation")) autoBanOnViolation = (Boolean) config.get("autoBanOnViolation");
                    if (config.containsKey("detectDroppedItems")) detectDroppedItems = (Boolean) config.get("detectDroppedItems");
                    if (config.containsKey("detectWorldBlocks")) detectWorldBlocks = (Boolean) config.get("detectWorldBlocks");
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

    public static void addExcludeFromLog(String id) { excludeFromLog.add(id); saveConfig(); }
    public static void removeExcludeFromLog(String id) { excludeFromLog.remove(id); saveConfig(); }
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
    }

    public static void saveBlockBlacklist() {
        try {
            Files.writeString(BLOCK_BLACKLIST_FILE, GSON.toJson(rulesToJson(blockBlacklistRules)));
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save block blacklist", e);
        }
    }

    public static List<BlacklistRule> getBlacklistRules() { return Collections.unmodifiableList(blacklistRules); }
    public static List<BlacklistRule> getBlockBlacklistRules() { return Collections.unmodifiableList(blockBlacklistRules); }

    public static void addToBlacklist(String id) { addToBlacklist(id, null); }
    public static void addToBlacklist(String id, String nbtString) {
        BlacklistRule r = new BlacklistRule();
        r.id = id;
        r.nbtString = nbtString;
        blacklistRules.add(r);
        saveBlacklist();
    }

    public static void removeFromBlacklist(String id) { removeFromBlacklist(id, null); }
    public static void removeFromBlacklist(String id, String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) blacklistRules.removeIf(r -> r.matchesId(id));
        else blacklistRules.removeIf(r -> r.equalsRule(id, nbtString));
        saveBlacklist();
    }

    public static void addToBlockBlacklist(String id) { addToBlockBlacklist(id, null); }
    public static void addToBlockBlacklist(String id, String nbtString) {
        BlacklistRule r = new BlacklistRule();
        r.id = id;
        r.nbtString = nbtString;
        blockBlacklistRules.add(r);
        saveBlockBlacklist();
    }

    public static void removeFromBlockBlacklist(String id) { removeFromBlockBlacklist(id, null); }
    public static void removeFromBlockBlacklist(String id, String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) blockBlacklistRules.removeIf(r -> r.matchesId(id));
        else blockBlacklistRules.removeIf(r -> r.equalsRule(id, nbtString));
        saveBlockBlacklist();
    }

    public static boolean isBlacklisted(String itemId) {
        return blacklistRules.stream().anyMatch(r -> r.matchesId(itemId) && (r.nbtString == null || r.nbtString.isEmpty()) && (r.nbt == null || r.nbt.isEmpty()));
    }

    /**
     * 1.21+ matching: rules that carry NBT currently match only by ID (Data Components migration).
     * Plain-ID rules behave exactly as before. NBT rules are treated as ID-only until component-aware matching is added.
     */
    public static boolean isBlacklisted(String itemId, net.minecraft.world.item.ItemStack stack) {
        for (BlacklistRule r : blacklistRules) {
            if (r.matchesId(itemId)) {
                if (r.nbtString == null || r.nbtString.isEmpty()) return true;
                // TODO(1.21): compare DataComponents; for now treat NBT rules as ID match
                return true;
            }
        }
        return false;
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
        return blockBlacklistRules.stream().anyMatch(r -> r.matchesId(blockId) && (r.nbtString == null || r.nbtString.isEmpty()) && (r.nbt == null || r.nbt.isEmpty()));
    }

    public static boolean isBlockBlacklisted(String blockId, net.minecraft.nbt.CompoundTag blockEntityData) {
        for (BlacklistRule r : blockBlacklistRules) {
            if (r.matchesId(blockId)) {
                if (r.nbtString == null || r.nbtString.isEmpty()) return true;
                // Block entities in 1.21 still expose NBT via saveAdditional/saveWithFullMetadata with provider; keep old-style contains check
                if (blockEntityData != null && r.nbtString != null) {
                    try {
                        var required = net.minecraft.nbt.TagParser.parseCompoundFully(r.nbtString);
                        return nbtContains(blockEntityData, required);
                    } catch (Exception e) {
                        return true; // fallback to ID match
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean nbtContains(net.minecraft.nbt.CompoundTag itemNbt, net.minecraft.nbt.CompoundTag required) {
        for (String key : required.keySet()) {
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
}
