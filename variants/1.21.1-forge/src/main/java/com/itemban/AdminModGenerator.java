package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 生成仅含本服密钥的 lowcode 管理模组 jar（确定性压缩，便于哈希核对）。 */
public final class AdminModGenerator {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Forge / NeoForge loader version range written into generated admin mods. */
    public static String LOADER_RANGE = "[52,)";

    private static final long DOS_EPOCH = 315532800000L; // 1980-01-01

    private AdminModGenerator() {}

    public static Path writeCurrent() {
        try {
            Files.createDirectories(AdminKeyManager.ADMIN_MOD_DIR);
            try (var stream = Files.newDirectoryStream(AdminKeyManager.ADMIN_MOD_DIR, "ItemBan-Admin-*.jar")) {
                for (Path old : stream) {
                    Files.deleteIfExists(old);
                }
            }
            String keyId = AdminKeyManager.currentKeyId();
            byte[] key = AdminKeyManager.currentKeyBytes();
            String tokenHash = AdminKeyManager.canonicalHash(keyId, key);
            String modId = AdminKeyManager.MOD_ID_PREFIX + keyId;
            Path jar = AdminKeyManager.ADMIN_MOD_DIR.resolve("ItemBan-Admin-" + keyId + ".jar");

            Map<String, String> token = new LinkedHashMap<>();
            token.put("v", "1");
            token.put("keyId", keyId);
            token.put("key", AdminKeyManager.bytesToHex(key));
            token.put("tokenHash", tokenHash);
            byte[] tokenJson = (GSON.toJson(token) + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] modsToml = modsToml(modId, keyId).getBytes(StandardCharsets.UTF_8);
            byte[] packMcmeta = """
                    {"pack":{"pack_format":15,"description":"ItemBan admin token"}}
                    """.getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                zip.setLevel(9);
                put(zip, "META-INF/mods.toml", modsToml);
                put(zip, "META-INF/neoforge.mods.toml", modsToml);
                put(zip, "pack.mcmeta", packMcmeta);
                put(zip, "itemban-admin.json", tokenJson);
                put(zip, "data/" + modId + "/itemban-admin.json", tokenJson);
            }
            byte[] bytes = raw.toByteArray();
            Files.write(jar, bytes);
            String sha = AdminKeyManager.sha256Hex(bytes);
            AdminKeyManager.setJarMeta(jar, sha);
            return jar;
        } catch (Exception e) {
            throw new IllegalStateException("生成管理模组失败", e);
        }
    }

    private static String modsToml(String modId, String keyId) {
        return """
                modLoader="lowcodefml"
                loaderVersion="%s"
                license="All Rights Reserved"

                [[mods]]
                modId="%s"
                version="1.0"
                displayName="ItemBan Admin %s"
                description="Server-bound ItemBan admin token. Place in the client mods folder. Multiple admin mods can coexist for different servers."
                authors="ItemBan"
                """.formatted(LOADER_RANGE, modId, keyId);
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(DOS_EPOCH);
        entry.setMethod(ZipEntry.DEFLATED);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        entry.setSize(data.length);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
