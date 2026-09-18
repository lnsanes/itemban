package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class AdminNetwork {
    private static final String PROTO = "1";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<UUID, Challenge> PENDING = new ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ItemBan.MODID, "admin"),
            () -> PROTO,
            AdminNetwork::acceptMissingOrProto,
            AdminNetwork::acceptMissingOrProto
    );

    private static boolean acceptMissingOrProto(String version) {
        return PROTO.equals(version)
                || NetworkRegistry.ABSENT.equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    private record Challenge(byte[] nonce, long expiresAt) {}

    private AdminNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ChallengeS2C.class, ChallengeS2C::encode, ChallengeS2C::decode, ChallengeS2C::handle);
        CHANNEL.registerMessage(id++, ProofC2S.class, ProofC2S::encode, ProofC2S::decode, ProofC2S::handle);
        CHANNEL.registerMessage(id++, OpenGuiS2C.class, OpenGuiS2C::encode, OpenGuiS2C::decode, OpenGuiS2C::handle);
        CHANNEL.registerMessage(id++, FailS2C.class, FailS2C::encode, FailS2C::decode, FailS2C::handle);
        CHANNEL.registerMessage(id++, ActionC2S.class, ActionC2S::encode, ActionC2S::decode, ActionC2S::handle);
    }

    public static void requestOpen(ServerPlayer player) {
        if (!BanPermission.hasBan(player)) {
            player.sendSystemMessage(Component.literal("§c没有 itemban.ban 权限"));
            return;
        }
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        PENDING.put(player.getUUID(), new Challenge(nonce, System.currentTimeMillis() + 30_000L));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ChallengeS2C(AdminKeyManager.keyId(), nonce));
    }

    private static boolean consumeValidProof(ServerPlayer player, ProofC2S proof) {
        Challenge challenge = PENDING.remove(player.getUUID());
        if (challenge == null || System.currentTimeMillis() > challenge.expiresAt) {
            return false;
        }
        if (!BanPermission.hasBan(player)) {
            return false;
        }
        return AdminKeyManager.matchesProof(proof.keyId, proof.hmac, proof.tokenHash, proof.jarSha, challenge.nonce);
    }

    private static void sendState(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenGuiS2C(GSON.toJson(ConfigHandler.adminGuiState())));
    }

    public static final class ChallengeS2C {
        public final String keyId;
        public final byte[] nonce;

        public ChallengeS2C(String keyId, byte[] nonce) {
            this.keyId = keyId == null ? "" : keyId;
            this.nonce = nonce == null ? new byte[0] : nonce;
        }

        static void encode(ChallengeS2C msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.keyId, 64);
            buf.writeByteArray(msg.nonce);
        }

        static ChallengeS2C decode(FriendlyByteBuf buf) {
            return new ChallengeS2C(buf.readUtf(64), buf.readByteArray(64));
        }

        static void handle(ChallengeS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.onChallenge(msg.keyId, msg.nonce)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class ProofC2S {
        public final String keyId;
        public final String hmac;
        public final String tokenHash;
        public final String jarSha;

        public ProofC2S(String keyId, String hmac, String tokenHash, String jarSha) {
            this.keyId = keyId == null ? "" : keyId;
            this.hmac = hmac == null ? "" : hmac;
            this.tokenHash = tokenHash == null ? "" : tokenHash;
            this.jarSha = jarSha == null ? "" : jarSha;
        }

        static void encode(ProofC2S msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.keyId, 64);
            buf.writeUtf(msg.hmac, 128);
            buf.writeUtf(msg.tokenHash, 128);
            buf.writeUtf(msg.jarSha, 128);
        }

        static ProofC2S decode(FriendlyByteBuf buf) {
            return new ProofC2S(buf.readUtf(64), buf.readUtf(128), buf.readUtf(128), buf.readUtf(128));
        }

        static void handle(ProofC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!consumeValidProof(player, msg)) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new FailS2C("没有与本服匹配的管理模组，或密钥/哈希核对失败"));
                    return;
                }
                markAuthorized(player.getUUID());
                sendState(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class OpenGuiS2C {
        public final String json;

        public OpenGuiS2C(String json) {
            this.json = json == null ? "{}" : json;
        }

        static void encode(OpenGuiS2C msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.json, 200000);
        }

        static OpenGuiS2C decode(FriendlyByteBuf buf) {
            return new OpenGuiS2C(buf.readUtf(200000));
        }

        static void handle(OpenGuiS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.openScreen(msg.json)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class FailS2C {
        public final String reason;

        public FailS2C(String reason) {
            this.reason = reason == null ? "" : reason;
        }

        static void encode(FailS2C msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.reason, 256);
        }

        static FailS2C decode(FriendlyByteBuf buf) {
            return new FailS2C(buf.readUtf(256));
        }

        static void handle(FailS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.fail(msg.reason)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class ActionC2S {
        public final String op;
        public final String payload;

        public ActionC2S(String op, String payload) {
            this.op = op == null ? "" : op;
            this.payload = payload == null ? "" : payload;
        }

        static void encode(ActionC2S msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.op, 32);
            buf.writeUtf(msg.payload, 8000);
        }

        static ActionC2S decode(FriendlyByteBuf buf) {
            return new ActionC2S(buf.readUtf(32), buf.readUtf(8000));
        }

        static void handle(ActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !BanPermission.hasBan(player)) {
                    return;
                }
                // 操作包仍要求最近一次成功的密钥核对：重新发挑战会打断 UI。
                // 打开 GUI 时已核对；此处再要求权限节点，密钥在打开时锁定本会话不够严格。
                // 用短时授权：成功 Proof 后 5 分钟内允许操作。
                if (!AdminSessions.isAuthorized(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c管理会话已过期，请重新输入 /itemban gui"));
                    return;
                }
                String err = apply(player, msg.op, msg.payload);
                if (err != null) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FailS2C(err));
                    return;
                }
                sendState(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    static void markAuthorized(UUID uuid) {
        AdminSessions.authorize(uuid);
    }

    public static void onPlayerLeave(UUID uuid) {
        if (uuid == null) {
            return;
        }
        PENDING.remove(uuid);
        AdminSessions.clear(uuid);
    }

    private static String apply(ServerPlayer player, String op, String payload) {
        String value = payload == null ? "" : payload.trim();
        switch (op) {
            case "add_item" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                String err = ConfigHandler.validateNewRule(parsed.id, parsed.nbt, false);
                if (err != null) {
                    return err;
                }
                ConfigHandler.addToBlacklist(parsed.id, parsed.nbt);
                RecipeStripper.applyFromSnapshot(player.getServer());
            }
            case "remove_item" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                ConfigHandler.removeFromBlacklist(parsed.id, parsed.nbt);
                RecipeStripper.applyFromSnapshot(player.getServer());
            }
            case "add_block" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                String err = ConfigHandler.validateNewRule(parsed.id, parsed.nbt, true);
                if (err != null) {
                    return err;
                }
                ConfigHandler.addToBlockBlacklist(parsed.id, parsed.nbt);
            }
            case "remove_block" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                ConfigHandler.removeFromBlockBlacklist(parsed.id, parsed.nbt);
            }
            case "add_exclude" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                String err = ConfigHandler.validateNewRule(parsed.id, null, false);
                if (err != null) {
                    return err;
                }
                ConfigHandler.addExcludeFromLog(parsed.id);
            }
            case "remove_exclude" -> ConfigHandler.removeExcludeFromLog(CommandHandler.parseIdAndNbt(value).id);
            case "announce" -> ConfigHandler.setPublicAnnounce("1".equals(value) || "true".equalsIgnoreCase(value));
            case "autoban" -> ConfigHandler.setAutoBanOnViolation("1".equals(value) || "true".equalsIgnoreCase(value));
            case "dropdetect" -> ConfigHandler.setDetectDroppedItems("1".equals(value) || "true".equalsIgnoreCase(value));
            case "blockscan" -> ConfigHandler.setDetectWorldBlocks("1".equals(value) || "true".equalsIgnoreCase(value));
            case "reload" -> {
                ConfigHandler.loadBlacklist();
                ConfigHandler.loadBlockBlacklist();
                ConfigHandler.loadConfig();
                AdminKeyManager.reloadFromDisk();
                RecipeStripper.applyFromSnapshot(player.getServer());
            }
            default -> {
            }
        }
        return null;
    }

    public static void sendProof(String keyId, String hmac, String tokenHash, String jarSha) {
        CHANNEL.sendToServer(new ProofC2S(keyId, hmac, tokenHash, jarSha));
    }

    public static void sendAction(String op, String payload) {
        CHANNEL.sendToServer(new ActionC2S(op, payload));
    }
}
