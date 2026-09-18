package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成仅含本服密钥的管理模组 jar。
 * 1.16.5 没有 lowcodefml，写入空的 javafml {@code @Mod} 类，避免客户端开屏把无 toml 的 jar 当成无效模组警告。
 */
public final class AdminModGenerator {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static String LOADER_RANGE = "[36,)";

    private static final long DOS_EPOCH = 315532800000L; // 1980-01-01

    private AdminModGenerator() {}

    public static Path writeCurrent() {
        try {
            Files.createDirectories(AdminKeyManager.ADMIN_MOD_DIR);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(AdminKeyManager.ADMIN_MOD_DIR, "ItemBan-Admin-*.jar")) {
                for (Path old : stream) {
                    Files.deleteIfExists(old);
                }
            }
            String keyId = AdminKeyManager.currentKeyId();
            byte[] key = AdminKeyManager.currentKeyBytes();
            String tokenHash = AdminKeyManager.canonicalHash(keyId, key);
            String modId = AdminKeyManager.MOD_ID_PREFIX + keyId;
            Path jar = AdminKeyManager.ADMIN_MOD_DIR.resolve("ItemBan-Admin-" + keyId + ".jar");
            String classInternal = "com/itemban/admin/A" + keyId;

            Map<String, String> token = new LinkedHashMap<String, String>();
            token.put("v", "1");
            token.put("keyId", keyId);
            token.put("key", AdminKeyManager.bytesToHex(key));
            token.put("tokenHash", tokenHash);
            byte[] tokenJson = (GSON.toJson(token) + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] modsToml = modsToml(modId, keyId).getBytes(StandardCharsets.UTF_8);
            byte[] packMcmeta = "{\"pack\":{\"pack_format\":6,\"description\":\"ItemBan admin token\"}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] dummyClass = dummyModClass(classInternal, modId);

            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                zip.setLevel(9);
                put(zip, "META-INF/mods.toml", modsToml);
                put(zip, "pack.mcmeta", packMcmeta);
                put(zip, classInternal + ".class", dummyClass);
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
        return "modLoader=\"javafml\"\n"
                + "loaderVersion=\"" + LOADER_RANGE + "\"\n"
                + "license=\"All Rights Reserved\"\n"
                + "\n"
                + "[[mods]]\n"
                + "modId=\"" + modId + "\"\n"
                + "version=\"1.0\"\n"
                + "displayName=\"ItemBan Admin " + keyId + "\"\n"
                + "description=\"Server-bound ItemBan admin token. Place in the client mods folder. Multiple admin mods can coexist for different servers.\"\n"
                + "authors=\"ItemBan\"\n"
                + "displayTest=\"IGNORE_ALL_VERSION\"\n";
    }

    /** 运行时写出带 {@code @Mod(modId)} 的空类，长度随 keyId 变化。 */
    private static byte[] dummyModClass(String classInternal, String modId) throws Exception {
        Pool pool = new Pool();
        int utfThis = pool.utf8(classInternal);
        int classThis = pool.classInfo(utfThis);
        int utfObject = pool.utf8("java/lang/Object");
        int classObject = pool.classInfo(utfObject);
        int utfInit = pool.utf8("<init>");
        int utfVoid = pool.utf8("()V");
        int utfCode = pool.utf8("Code");
        int utfAnno = pool.utf8("RuntimeVisibleAnnotations");
        int utfAnnoType = pool.utf8("Lnet/minecraftforge/fml/common/Mod;");
        int utfValue = pool.utf8("value");
        int utfModId = pool.utf8(modId);
        int natInit = pool.nameAndType(utfInit, utfVoid);
        int methodInit = pool.methodRef(classObject, natInit);

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x2A); // aload_0
        code.write(0xB7); // invokespecial
        u2(code, methodInit);
        code.write(0xB1); // return
        byte[] codeBytes = code.toByteArray();

        ByteArrayOutputStream codeAttr = new ByteArrayOutputStream();
        u2(codeAttr, utfCode);
        u4(codeAttr, 12 + codeBytes.length);
        u2(codeAttr, 1); // max_stack
        u2(codeAttr, 1); // max_locals
        u4(codeAttr, codeBytes.length);
        codeAttr.write(codeBytes);
        u2(codeAttr, 0); // exception_table_length
        u2(codeAttr, 0); // code attributes

        ByteArrayOutputStream annoInfo = new ByteArrayOutputStream();
        u2(annoInfo, 1); // num_annotations
        u2(annoInfo, utfAnnoType);
        u2(annoInfo, 1); // num_element_value_pairs
        u2(annoInfo, utfValue);
        annoInfo.write('s');
        u2(annoInfo, utfModId);
        byte[] annoBody = annoInfo.toByteArray();

        ByteArrayOutputStream annoAttr = new ByteArrayOutputStream();
        u2(annoAttr, utfAnno);
        u4(annoAttr, annoBody.length);
        annoAttr.write(annoBody);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u4(out, 0xCAFEBABE);
        u2(out, 0);
        u2(out, 52); // Java 8
        u2(out, pool.size() + 1);
        pool.write(out);
        u2(out, 0x0021); // public super
        u2(out, classThis);
        u2(out, classObject);
        u2(out, 0); // interfaces
        u2(out, 0); // fields
        u2(out, 1); // methods
        u2(out, 0x0001); // public <init>
        u2(out, utfInit);
        u2(out, utfVoid);
        u2(out, 1);
        codeAttr.writeTo(out);
        u2(out, 1); // class attributes
        annoAttr.writeTo(out);
        return out.toByteArray();
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

    private static void u2(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void u4(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static final class Pool {
        private final List<byte[]> entries = new ArrayList<byte[]>();

        int size() {
            return entries.size();
        }

        int utf8(String s) throws Exception {
            byte[] str = s.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(1);
            u2(out, str.length);
            out.write(str);
            entries.add(out.toByteArray());
            return entries.size();
        }

        int classInfo(int name) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(7);
            u2(out, name);
            entries.add(out.toByteArray());
            return entries.size();
        }

        int nameAndType(int name, int desc) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(12);
            u2(out, name);
            u2(out, desc);
            entries.add(out.toByteArray());
            return entries.size();
        }

        int methodRef(int cls, int nat) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(10);
            u2(out, cls);
            u2(out, nat);
            entries.add(out.toByteArray());
            return entries.size();
        }

        void write(ByteArrayOutputStream out) throws Exception {
            for (byte[] entry : entries) {
                out.write(entry);
            }
        }
    }
}
