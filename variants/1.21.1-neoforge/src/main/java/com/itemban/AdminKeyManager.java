package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器管理密钥：首次开服随机生成后持久化，可热重载，并据此写出仅匹配本服的管理模组。
 */
public final class AdminKeyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String TOKEN_PREFIX = "ItemBanAdminV1";
    public static final String MOD_ID_PREFIX = "itembanadmin_";

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("ItemBan");
    private static final Path KEY_FILE = CONFIG_DIR.resolve("admin-key.json");
    public static final Path ADMIN_MOD_DIR = CONFIG_DIR.resolve("admin-mods");

    private static volatile String keyId = "";
    private static volatile byte[] keyBytes = new byte[0];
    private static volatile String tokenHash = "";
    private static volatile String jarSha256 = "";
    private static volatile Path lastJar = null;

    private AdminKeyManager() {}

    public static synchronized Path rotateAndWriteMod() {
        generateKey();
        Path jar = AdminModGenerator.writeCurrent();
        persist();
        return jar;
    }

    public static synchronized Path writeModForCurrentKey() {
        ensureKey();
        Path jar = AdminModGenerator.writeCurrent();
        persist();
        return jar;
    }

    public static synchronized void reloadFromDisk() {
        if (!Files.exists(KEY_FILE)) {
            rotateAndWriteMod();
            return;
        }
        try {
            String json = Files.readString(KEY_FILE, StandardCharsets.UTF_8);
            Map<String, Object> map = GSON.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            if (map == null || map.get("keyId") == null || map.get("key") == null) {
                rotateAndWriteMod();
                return;
            }
            keyId = String.valueOf(map.get("keyId"));
            keyBytes = hexToBytes(String.valueOf(map.get("key")));
            tokenHash = canonicalHash(keyId, keyBytes);
            if (map.get("jarSha256") != null) {
                jarSha256 = String.valueOf(map.get("jarSha256")).toLowerCase();
            }
            if (map.get("jarPath") != null) {
                lastJar = Path.of(String.valueOf(map.get("jarPath")));
            }
            String expected = map.get("tokenHash") == null ? "" : String.valueOf(map.get("tokenHash"));
            if (!tokenHash.equalsIgnoreCase(expected)) {
                ItemBan.LOGGER.warn("ItemBan 管理密钥文件哈希不匹配，已按密钥内容重算");
                persist();
            }
            ItemBan.LOGGER.info("ItemBan 管理密钥已热重载 keyId={}", keyId);
        } catch (Exception e) {
            ItemBan.LOGGER.error("重载管理密钥失败，将重新生成", e);
            rotateAndWriteMod();
        }
    }

    public static synchronized void onServerStarting() {
        boolean existed = Files.exists(KEY_FILE);
        if (existed) {
            reloadFromDisk();
        }
        Path jar = writeModForCurrentKey();
        ItemBan.LOGGER.info("ItemBan {}管理密钥 keyId={} 管理模组={} SHA-256={}",
                existed ? "已加载现有" : "已首次生成", keyId, jar.toAbsolutePath(), jarSha256);
    }

    public static String keyId() {
        return keyId;
    }

    public static String tokenHash() {
        return tokenHash;
    }

    public static String jarSha256() {
        return jarSha256;
    }

    public static Path lastJar() {
        return lastJar;
    }

    static void setJarMeta(Path jar, String sha) {
        lastJar = jar;
        jarSha256 = sha == null ? "" : sha.toLowerCase();
    }

    static String currentKeyId() {
        return keyId;
    }

    static byte[] currentKeyBytes() {
        return keyBytes;
    }

    public static boolean matchesProof(String proofKeyId, String proofHmac, String proofTokenHash, String proofJarSha, byte[] nonce) {
        if (keyBytes.length == 0 || keyId.isEmpty()) {
            return false;
        }
        if (!equalHex(keyId, proofKeyId)) {
            return false;
        }
        if (!equalHex(tokenHash, proofTokenHash)) {
            return false;
        }
        if (jarSha256 != null && !jarSha256.isEmpty()) {
            if (!equalHex(jarSha256, proofJarSha)) {
                return false;
            }
        }
        String expectedHmac = hmacHex(keyBytes, nonce);
        return equalHex(expectedHmac, proofHmac);
    }

    /** 十六进制恒定时间比较，避免 HMAC/哈希逐字节泄露。 */
    static boolean equalHex(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        String a = expected.trim().toLowerCase(java.util.Locale.ROOT);
        String b = actual.trim().toLowerCase(java.util.Locale.ROOT);
        byte[] left = a.getBytes(StandardCharsets.US_ASCII);
        byte[] right = b.getBytes(StandardCharsets.US_ASCII);
        if (left.length != right.length) {
            MessageDigest.isEqual(left, left);
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }

    public static String canonicalHash(String id, byte[] key) {
        return sha256Hex((TOKEN_PREFIX + "|" + id + "|" + bytesToHex(key)).getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacHex(byte[] key, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return bytesToHex(mac.doFinal(nonce));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String bytesToHex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    public static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex.trim());
    }

    private static void ensureKey() {
        if (keyBytes.length == 0 || keyId.isEmpty()) {
            generateKey();
        }
    }

    private static void generateKey() {
        keyBytes = new byte[32];
        RANDOM.nextBytes(keyBytes);
        byte[] idBytes = new byte[8];
        RANDOM.nextBytes(idBytes);
        keyId = bytesToHex(idBytes);
        tokenHash = canonicalHash(keyId, keyBytes);
        jarSha256 = "";
        lastJar = null;
    }

    private static void persist() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("keyId", keyId);
            map.put("key", bytesToHex(keyBytes));
            map.put("tokenHash", tokenHash);
            map.put("jarSha256", jarSha256);
            map.put("jarPath", lastJar == null ? "" : lastJar.toAbsolutePath().toString());
            Files.writeString(KEY_FILE, GSON.toJson(map), StandardCharsets.UTF_8);
            restrictPrivateFile(KEY_FILE);
        } catch (Exception e) {
            ItemBan.LOGGER.error("写入管理密钥失败", e);
        }
    }

    private static void restrictPrivateFile(Path file) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
        }
    }
}
