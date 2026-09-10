package com.itemban;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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

    public static void load(MinecraftServer server) {
        ZH.clear();
        loadStream(ItemNames.class.getResourceAsStream("/assets/itemban/lang/zh_cn_vanilla.json"));
        if (server == null) {
            return;
        }
        try {
            server.getResourceManager().listResources("lang", loc -> loc.getPath().endsWith("zh_cn.json"))
                    .forEach((id, resource) -> {
                        try (InputStream in = resource.open()) {
                            loadStream(in);
                        } catch (Exception ignored) {
                        }
                    });
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
            ResourceLocation loc = ResourceLocation.parse(id);
            Item item = BuiltInRegistries.ITEM.get(loc);
            if (item != null && item != Items.AIR) {
                return translateKey(item.getDescriptionId(), id);
            }
            Block block = BuiltInRegistries.BLOCK.get(loc);
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
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
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
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == null || block == Blocks.AIR) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
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
}
