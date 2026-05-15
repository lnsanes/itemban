package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
    private static Set<String> blacklist = new HashSet<>();

    public static void register() {
        try {
            Files.createDirectories(CONFIG_DIR);
            loadBlacklist();
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to create config dir", e);
        }
    }

    public static void loadBlacklist() {
        try {
            if (Files.exists(BLACKLIST_FILE)) {
                String json = Files.readString(BLACKLIST_FILE);
                Type type = new TypeToken<Set<String>>(){}.getType();
                blacklist = GSON.fromJson(json, type);
                if (blacklist == null) blacklist = new HashSet<>();
            } else {
                // 文件不存在时创建空黑名单，不默认禁止任何物品
                blacklist = new HashSet<>();
                saveBlacklist();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.error("Failed to load blacklist", e);
        }
    }

    public static void saveBlacklist() {
        try {
            String json = GSON.toJson(blacklist);
            Files.writeString(BLACKLIST_FILE, json);
        } catch (IOException e) {
            ItemBan.LOGGER.error("Failed to save blacklist", e);
        }
    }

    public static Set<String> getBlacklist() {
        return Collections.unmodifiableSet(blacklist);
    }

    public static void addToBlacklist(String itemId) {
        blacklist.add(itemId);
        saveBlacklist();
    }

    public static void removeFromBlacklist(String itemId) {
        blacklist.remove(itemId);
        saveBlacklist();
    }

    public static boolean isBlacklisted(String itemId) {
        return blacklist.contains(itemId);
    }
}
