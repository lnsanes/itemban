package com.itemban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminNetwork {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<UUID, Challenge> PENDING = new ConcurrentHashMap<>();

    private record Challenge(byte[] nonce, long expiresAt) {}

    private AdminNetwork() {}

    public static void register() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(ChallengeS2C.TYPE, ChallengeS2C.STREAM_CODEC, ChallengeS2C::handle);
        registrar.playToServer(ProofC2S.TYPE, ProofC2S.STREAM_CODEC, ProofC2S::handle);
        registrar.playToClient(OpenGuiS2C.TYPE, OpenGuiS2C.STREAM_CODEC, OpenGuiS2C::handle);
        registrar.playToClient(FailS2C.TYPE, FailS2C.STREAM_CODEC, FailS2C::handle);
        registrar.playToServer(ActionC2S.TYPE, ActionC2S.STREAM_CODEC, ActionC2S::handle);
    }

    public static void requestOpen(ServerPlayer player) {
        if (!BanPermission.hasBan(player)) {
            player.sendSystemMessage(Component.literal("§c没有 itemban.ban 权限"));
            return;
        }
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        PENDING.put(player.getUUID(), new Challenge(nonce, System.currentTimeMillis() + 30_000L));
        PacketDistributor.sendToPlayer(player, new ChallengeS2C(AdminKeyManager.keyId(), nonce));
    }

    private static boolean consumeValidProof(ServerPlayer player, ProofC2S proof) {
        Challenge challenge = PENDING.remove(player.getUUID());
        if (challenge == null || System.currentTimeMillis() > challenge.expiresAt) {
            return false;
        }
        if (!BanPermission.hasBan(player)) {
            return false;
        }
        return AdminKeyManager.matchesProof(proof.keyId(), proof.hmac(), proof.tokenHash(), proof.jarSha(), challenge.nonce);
    }

    private static void sendState(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new OpenGuiS2C(GSON.toJson(ConfigHandler.adminGuiState())));
    }

    public record ChallengeS2C(String keyId, byte[] nonce) implements CustomPacketPayload {
        public static final Type<ChallengeS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin_challenge"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.keyId() == null ? "" : msg.keyId(), 64);
                    buf.writeByteArray(msg.nonce() == null ? new byte[0] : msg.nonce());
                },
                buf -> new ChallengeS2C(buf.readUtf(64), buf.readByteArray(64))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(ChallengeS2C msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.itemban.client.ClientAdminHandler.onChallenge(msg.keyId(), msg.nonce()));
        }
    }

    public record ProofC2S(String keyId, String hmac, String tokenHash, String jarSha) implements CustomPacketPayload {
        public static final Type<ProofC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin_proof"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProofC2S> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.keyId() == null ? "" : msg.keyId(), 64);
                    buf.writeUtf(msg.hmac() == null ? "" : msg.hmac(), 128);
                    buf.writeUtf(msg.tokenHash() == null ? "" : msg.tokenHash(), 128);
                    buf.writeUtf(msg.jarSha() == null ? "" : msg.jarSha(), 128);
                },
                buf -> new ProofC2S(buf.readUtf(64), buf.readUtf(128), buf.readUtf(128), buf.readUtf(128))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(ProofC2S msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                if (!consumeValidProof(player, msg)) {
                    PacketDistributor.sendToPlayer(player, new FailS2C("没有与本服匹配的管理模组，或密钥/哈希核对失败"));
                    return;
                }
                AdminSessions.authorize(player.getUUID());
                sendState(player);
            });
        }
    }

    public record OpenGuiS2C(String json) implements CustomPacketPayload {
        public static final Type<OpenGuiS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> buf.writeUtf(msg.json() == null ? "{}" : msg.json(), 200000),
                buf -> new OpenGuiS2C(buf.readUtf(200000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(OpenGuiS2C msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.itemban.client.ClientAdminHandler.openScreen(msg.json()));
        }
    }

    public record FailS2C(String reason) implements CustomPacketPayload {
        public static final Type<FailS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin_fail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FailS2C> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> buf.writeUtf(msg.reason() == null ? "" : msg.reason(), 256),
                buf -> new FailS2C(buf.readUtf(256))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(FailS2C msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.itemban.client.ClientAdminHandler.fail(msg.reason()));
        }
    }

    public record ActionC2S(String op, String payload) implements CustomPacketPayload {
        public static final Type<ActionC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ItemBan.MODID, "admin_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionC2S> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeUtf(msg.op() == null ? "" : msg.op(), 32);
                    buf.writeUtf(msg.payload() == null ? "" : msg.payload(), 8000);
                },
                buf -> new ActionC2S(buf.readUtf(32), buf.readUtf(8000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(ActionC2S msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                if (!BanPermission.hasBan(player)) {
                    return;
                }
                if (!AdminSessions.isAuthorized(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c管理会话已过期，请重新输入 /itemban gui"));
                    return;
                }
                String err = apply(player, msg.op(), msg.payload());
                if (err != null) {
                    PacketDistributor.sendToPlayer(player, new FailS2C(err));
                    return;
                }
                sendState(player);
            });
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
        sendToServer(new ProofC2S(keyId, hmac, tokenHash, jarSha));
    }

    public static void sendAction(String op, String payload) {
        sendToServer(new ActionC2S(op, payload));
    }

    private static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(payload);
        }
    }
}
