package com.itemban;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemNames {
    private static final Gson GSON = new Gson();
    private static final Map<String, String> ZH = new ConcurrentHashMap<>();

    private ItemNames() {}

    public static void ensureLoaded() {
        if (!ZH.isEmpty()) {
            return;
        }
        loadStream(ItemNames.class.getResourceAsStream("/assets/itemban/lang/zh_cn_vanilla.json"));
    }

    public static void load(MinecraftServer server) {
        ZH.clear();
        loadStream(ItemNames.class.getResourceAsStream("/assets/itemban/lang/zh_cn_vanilla.json"));
        if (server == null) {
            return;
        }
        try {
            for (ResourceLocation loc : server.getResourceManager().listResources("lang", s -> s.endsWith("zh_cn.json"))) {
                Resource resource = server.getResourceManager().getResource(loc);
                try (InputStream in = resource.getInputStream()) {
                    loadStream(in);
                }
            }
        } catch (Exception e) {
            ItemBan.LOGGER.warn("加载模组中文语言文件失败: {}", e.getMessage());
        }
    }

    private static void loadStream(InputStream in) {
        if (in == null) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (map != null) {
                ZH.putAll(map);
            }
        } catch (Exception e) {
            ItemBan.LOGGER.warn("解析中文语言文件失败: {}", e.getMessage());
        }
    }

    public static String translateKey(String key, String fallback) {
        ensureLoaded();
        if (key == null || key.isEmpty()) {
            return fallback;
        }
        String zh = ZH.get(key);
        return zh != null && !zh.isEmpty() ? zh : fallback;
    }

    public static String nameOf(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        try {
            ResourceLocation loc = new ResourceLocation(id);
            Item item = ForgeRegistries.ITEMS.getValue(loc);
            if (item != null && item != Items.AIR) {
                return translateKey(item.getDescriptionId(), id);
            }
            Block block = ForgeRegistries.BLOCKS.getValue(loc);
            if (block != null && block != Blocks.AIR) {
                return translateKey(block.getDescriptionId(), id);
            }
        } catch (Exception ignored) {
        }
        int colon = id.indexOf(':');
        String ns = colon > 0 ? id.substring(0, colon) : "minecraft";
        String path = colon > 0 ? id.substring(colon + 1) : id;
        String fromItem = ZH.get("item." + ns + "." + path);
        if (fromItem != null) {
            return fromItem;
        }
        String fromBlock = ZH.get("block." + ns + "." + path);
        return fromBlock != null ? fromBlock : id;
    }

    public static List<Map<String, String>> listItems() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            if (key == null) {
                continue;
            }
            String id = key.toString();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", translateKey(item.getDescriptionId(), id));
            out.add(row);
        }
        return out;
    }

    public static List<Map<String, String>> listBlocks() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Block block : ForgeRegistries.BLOCKS) {
            if (block == null || block == Blocks.AIR) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) {
                continue;
            }
            String id = key.toString();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", translateKey(block.getDescriptionId(), id));
            out.add(row);
        }
        return out;
    }

    public static String resolveId(String raw, boolean preferBlock) {
        ensureLoaded();
        if (raw == null) {
            return "";
        }
        String id = raw.trim();
        if (id.isEmpty()) {
            return id;
        }
        String asRegistry = asRegistryId(id);
        if (asRegistry != null) {
            return asRegistry;
        }
        String blockId = findByDisplayName(listBlocks(), id);
        String itemId = findByDisplayName(listItems(), id);
        if (preferBlock) {
            if (blockId != null) {
                return blockId;
            }
            if (itemId != null) {
                return itemId;
            }
        } else if (itemId != null) {
            return itemId;
        } else if (blockId != null) {
            return blockId;
        }
        return "";
    }

    private static String asRegistryId(String id) {
        String candidate = id;
        if (id.indexOf(':') < 0) {
            if (!isRegistryToken(id)) {
                return null;
            }
            candidate = "minecraft:" + id;
        } else if (!isRegistryToken(id)) {
            return null;
        }
        if (containsId(listItems(), candidate) || containsId(listBlocks(), candidate)) {
            return candidate;
        }
        return null;
    }

    private static boolean isRegistryToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == ':' || c == '/' || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean containsId(java.util.List<java.util.Map<String, String>> rows, String id) {
        for (java.util.Map<String, String> row : rows) {
            if (id.equalsIgnoreCase(row.get("id"))) {
                return true;
            }
        }
        return false;
    }

    private static String findByDisplayName(java.util.List<java.util.Map<String, String>> rows, String name) {
        String minecraft = null;
        String other = null;
        for (java.util.Map<String, String> row : rows) {
            String rowName = row.get("name");
            if (rowName == null || !name.equals(rowName.trim())) {
                continue;
            }
            String rid = row.get("id");
            if (rid != null && rid.startsWith("minecraft:")) {
                if (minecraft == null) {
                    minecraft = rid;
                }
            } else if (other == null) {
                other = rid;
            }
        }
        return minecraft != null ? minecraft : other;
    }

}
