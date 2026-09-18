package com.itemban.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.itemban.AdminKeyManager;
import com.itemban.ItemBan;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 扫描客户端已安装的全部管理模组（可同时对应多台服务器）。 */
public final class ClientAdminTokens {
    private static final Gson GSON = new Gson();

    public static final class Token {
        public final String keyId;
        public final byte[] key;
        public final String tokenHash;
        public final String jarSha256;
        public final Path jar;

        Token(String keyId, byte[] key, String tokenHash, String jarSha256, Path jar) {
            this.keyId = keyId;
            this.key = key;
            this.tokenHash = tokenHash;
            this.jarSha256 = jarSha256;
            this.jar = jar;
        }
    }

    private ClientAdminTokens() {}

    public static List<Token> loadAll() {
        List<Token> out = new ArrayList<>();
        Path mods = FMLPaths.GAMEDIR.get().resolve("mods");
        scanDir(mods, out);
        return out;
    }

    public static Token match(String serverKeyId, List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        if (serverKeyId != null && !serverKeyId.isEmpty()) {
            for (Token token : tokens) {
                if (serverKeyId.equalsIgnoreCase(token.keyId)) {
                    return token;
                }
            }
        }
        return tokens.size() == 1 ? tokens.get(0) : null;
    }

    private static void scanDir(Path dir, List<Token> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : stream) {
                String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("itemban-admin-") || name.contains("itembanadmin")) {
                    addIfAbsent(out, readJar(jar));
                }
            }
        } catch (Exception e) {
            ItemBan.LOGGER.debug("扫描 mods 目录失败: {}", e.toString());
        }
    }

    private static void addIfAbsent(List<Token> out, Token token) {
        if (token == null) {
            return;
        }
        for (Token existing : out) {
            if (existing.keyId.equalsIgnoreCase(token.keyId) && existing.jarSha256.equalsIgnoreCase(token.jarSha256)) {
                return;
            }
        }
        out.add(token);
    }

    private static Token readJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        try {
            byte[] all = Files.readAllBytes(jar);
            String jarSha = AdminKeyManager.sha256Hex(all);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry("itemban-admin.json");
                if (entry == null) {
                    entry = findTokenEntry(zip);
                }
                if (entry == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Map<String, String> map = GSON.fromJson(new String(in.readAllBytes()),
                            new TypeToken<Map<String, String>>(){}.getType());
                    if (map == null || map.get("keyId") == null || map.get("key") == null) {
                        return null;
                    }
                    String keyId = map.get("keyId");
                    byte[] key = AdminKeyManager.hexToBytes(map.get("key"));
                    String tokenHash = map.get("tokenHash");
                    String computed = AdminKeyManager.canonicalHash(keyId, key);
                    if (tokenHash == null || !computed.equalsIgnoreCase(tokenHash)) {
                        ItemBan.LOGGER.warn("忽略哈希不匹配的管理模组: {}", jar.getFileName());
                        return null;
                    }
                    return new Token(keyId, key, computed, jarSha, jar);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static ZipEntry findTokenEntry(ZipFile zip) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().endsWith("itemban-admin.json")) {
                return entry;
            }
        }
        return null;
    }
}
