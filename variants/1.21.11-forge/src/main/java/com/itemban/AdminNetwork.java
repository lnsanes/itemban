package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminNetwork {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<UUID, Challenge> PENDING = new ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin"))
            .networkProtocolVersion(1)
            .optional()
            .simpleChannel();

    private record Challenge(byte[] nonce, long expiresAt) {}

    private AdminNetwork() {}

    public static void register() {
        CHANNEL.play()
                .clientbound()
                .addMain(ChallengeS2C.class, ChallengeS2C.STREAM_CODEC, ChallengeS2C::handle)
                .addMain(OpenGuiS2C.class, OpenGuiS2C.STREAM_CODEC, OpenGuiS2C::handle)
                .addMain(FailS2C.class, FailS2C.STREAM_CODEC, FailS2C::handle)
                .serverbound()
                .addMain(ProofC2S.class, ProofC2S.STREAM_CODEC, ProofC2S::handle)
                .addMain(ActionC2S.class, ActionC2S.STREAM_CODEC, ActionC2S::handle)
                .build();
    }

    public static void requestOpen(ServerPlayer player) {
        if (!BanPermission.hasBan(player)) {
            player.sendSystemMessage(Component.literal("§c没有 itemban.ban 权限"));
            return;
        }
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        PENDING.put(player.getUUID(), new Challenge(nonce, System.currentTimeMillis() + 30_000L));
        CHANNEL.send(new ChallengeS2C(AdminKeyManager.keyId(), nonce), PacketDistributor.PLAYER.with(player));
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
        CHANNEL.send(new OpenGuiS2C(GSON.toJson(ConfigHandler.adminGuiState())), PacketDistributor.PLAYER.with(player));
    }

    public static final class ChallengeS2C {
        public final String keyId;
        public final byte[] nonce;
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.keyId, 64);
                    buf.writeByteArray(msg.nonce);
                },
                buf -> new ChallengeS2C(buf.readUtf(64), buf.readByteArray(64))
        );

        public ChallengeS2C(String keyId, byte[] nonce) {
            this.keyId = keyId == null ? "" : keyId;
            this.nonce = nonce == null ? new byte[0] : nonce;
        }

        static void handle(ChallengeS2C msg, CustomPayloadEvent.Context ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.onChallenge(msg.keyId, msg.nonce));
        }
    }

    public static final class ProofC2S {
        public final String keyId;
        public final String hmac;
        public final String tokenHash;
        public final String jarSha;
        public static final StreamCodec<RegistryFriendlyByteBuf, ProofC2S> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.keyId, 64);
                    buf.writeUtf(msg.hmac, 128);
                    buf.writeUtf(msg.tokenHash, 128);
                    buf.writeUtf(msg.jarSha, 128);
                },
                buf -> new ProofC2S(buf.readUtf(64), buf.readUtf(128), buf.readUtf(128), buf.readUtf(128))
        );

        public ProofC2S(String keyId, String hmac, String tokenHash, String jarSha) {
            this.keyId = keyId == null ? "" : keyId;
            this.hmac = hmac == null ? "" : hmac;
            this.tokenHash = tokenHash == null ? "" : tokenHash;
            this.jarSha = jarSha == null ? "" : jarSha;
        }

        static void handle(ProofC2S msg, CustomPayloadEvent.Context ctx) {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!consumeValidProof(player, msg)) {
                CHANNEL.send(new FailS2C("没有与本服匹配的管理模组，或密钥/哈希核对失败"),
                        PacketDistributor.PLAYER.with(player));
                return;
            }
            AdminSessions.authorize(player.getUUID());
            sendState(player);
        }
    }

    public static final class OpenGuiS2C {
        public final String json;
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> buf.writeUtf(msg.json, 200000),
                buf -> new OpenGuiS2C(buf.readUtf(200000))
        );

        public OpenGuiS2C(String json) {
            this.json = json == null ? "{}" : json;
        }

        static void handle(OpenGuiS2C msg, CustomPayloadEvent.Context ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.openScreen(msg.json));
        }
    }

    public static final class FailS2C {
        public final String reason;
        public static final StreamCodec<RegistryFriendlyByteBuf, FailS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> buf.writeUtf(msg.reason, 256),
                buf -> new FailS2C(buf.readUtf(256))
        );

        public FailS2C(String reason) {
            this.reason = reason == null ? "" : reason;
        }

        static void handle(FailS2C msg, CustomPayloadEvent.Context ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.itemban.client.ClientAdminHandler.fail(msg.reason));
        }
    }

    public static final class ActionC2S {
        public final String op;
        public final String payload;
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionC2S> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.op, 32);
                    buf.writeUtf(msg.payload, 8000);
                },
                buf -> new ActionC2S(buf.readUtf(32), buf.readUtf(8000))
        );

        public ActionC2S(String op, String payload) {
            this.op = op == null ? "" : op;
            this.payload = payload == null ? "" : payload;
        }

        static void handle(ActionC2S msg, CustomPayloadEvent.Context ctx) {
            ServerPlayer player = ctx.getSender();
            if (player == null || !BanPermission.hasBan(player)) {
                return;
            }
            if (!AdminSessions.isAuthorized(player.getUUID())) {
                player.sendSystemMessage(Component.literal("§c管理会话已过期，请重新输入 /itemban gui"));
                return;
            }
            String err = apply(player, msg.op, msg.payload);
            if (err != null) {
                CHANNEL.send(new FailS2C(err), PacketDistributor.PLAYER.with(player));
                return;
            }
            sendState(player);
        }
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
                RecipeStripper.applyFromSnapshot(player.level().getServer());
            }
            case "remove_item" -> {
                CommandHandler.IdAndNbt parsed = CommandHandler.parseIdAndNbt(value);
                ConfigHandler.removeFromBlacklist(parsed.id, parsed.nbt);
                RecipeStripper.applyFromSnapshot(player.level().getServer());
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
                RecipeStripper.applyFromSnapshot(player.level().getServer());
            }
            default -> {
            }
        }
        return null;
    }

    public static void sendProof(String keyId, String hmac, String tokenHash, String jarSha) {
        CHANNEL.send(new ProofC2S(keyId, hmac, tokenHash, jarSha), PacketDistributor.SERVER.noArg());
    }

    public static void sendAction(String op, String payload) {
        CHANNEL.send(new ActionC2S(op, payload), PacketDistributor.SERVER.noArg());
    }
}
