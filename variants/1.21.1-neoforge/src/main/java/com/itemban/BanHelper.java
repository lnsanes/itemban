package com.itemban;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.UUID;

/**
 * Ban helpers compatible with LnsanesBan (which overrides vanilla {@code /ban}).
 * <p>
 * Order: LnsanesBan API (reflection) → vanilla {@link UserBanList} → server-side
 * {@code /ban} as last resort. Always disconnects afterwards if still connected.
 */
public final class BanHelper {
    private static final String LNSANESBAN_MODID = "lnsanesban";
    private static final String LNSANESBAN_API = "com.lnsanes.lnsanesban.api.LnsanesBanApi";
    private static final String LNSANESBAN_SERVICE = "com.lnsanes.lnsanesban.server.BanService";

    private static Boolean lnsanesbanLoaded;

    private BanHelper() {
    }

    public static void banAndKick(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        String banReason = reason == null || reason.isBlank()
                ? "Banned by ItemBan."
                : reason.replace('\n', ' ').replace('\r', ' ');

        // 先落盘当前背包（调用方应已清空违禁物），再封禁/踢出
        try {
            if (!player.hasDisconnected()) {
                player.getInventory().setChanged();
                server.getPlayerList().saveAll();
            }
        } catch (Exception e) {
            ItemBan.LOGGER.warn("封禁前保存玩家数据失败: {}", e.toString());
        }

        boolean persisted = false;
        if (isLnsanesBanLoaded()) {
            persisted = banViaLnsanesBan(player, banReason);
            if (persisted) {
                ItemBan.LOGGER.info("已通过 LnsanesBan 级联封禁玩家 {}", player.getGameProfile().getName());
            } else {
                ItemBan.LOGGER.warn("LnsanesBan 调用失败，回退到原版封禁列表");
            }
        }

        if (!persisted) {
            persisted = banViaVanillaList(player, banReason);
            if (persisted) {
                ItemBan.LOGGER.info("已写入原版封禁列表: {}", player.getGameProfile().getName());
            }
        }

        if (!persisted) {
            // Last resort: run /ban as the *server* (not the victim), so command source survives.
            try {
                String name = player.getGameProfile().getName();
                String cmd = "ban " + name + " " + banReason;
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPermission(4),
                        cmd
                );
                ItemBan.LOGGER.info("执行 /ban 回退命令 player={}", name);
                persisted = true;
            } catch (Exception e) {
                ItemBan.LOGGER.warn("执行 /ban 回退命令失败", e);
            }
        }

        if (!persisted) {
            ItemBan.LOGGER.error("未能持久化封禁记录，仅踢出玩家 {}", player.getGameProfile().getName());
        }

        // BanService already kicks; disconnect again if the session is still open.
        try {
            if (player.connection != null && !player.hasDisconnected()) {
                player.connection.disconnect(Component.literal(banReason));
            }
        } catch (Exception e) {
            ItemBan.LOGGER.debug("disconnect after ban ignored: {}", e.toString());
        }
    }

    private static boolean isLnsanesBanLoaded() {
        if (lnsanesbanLoaded == null) {
            try {
                lnsanesbanLoaded = ModList.get().isLoaded(LNSANESBAN_MODID);
            } catch (Throwable t) {
                lnsanesbanLoaded = false;
            }
        }
        return lnsanesbanLoaded;
    }

    private static boolean banViaLnsanesBan(ServerPlayer player, String reason) {
        // Prefer public API (3.1.2+)
        try {
            Class<?> api = Class.forName(LNSANESBAN_API);
            Method banPlayer = api.getMethod("banPlayer", ServerPlayer.class, String.class, String.class);
            banPlayer.invoke(null, player, reason, "ItemBan");
            return true;
        } catch (ClassNotFoundException ignored) {
            // Older LnsanesBan without API — fall through to BanService
        } catch (Throwable e) {
            ItemBan.LOGGER.warn("调用 LnsanesBanApi.banPlayer 失败", e);
        }

        try {
            Class<?> service = Class.forName(LNSANESBAN_SERVICE);
            Method banCascade = service.getMethod(
                    "banCascade",
                    String.class, UUID.class, String.class, String.class, String.class, MinecraftServer.class
            );
            banCascade.invoke(
                    null,
                    player.getGameProfile().getName(),
                    player.getUUID(),
                    null,
                    reason,
                    "ItemBan",
                    player.getServer()
            );
            return true;
        } catch (Throwable e) {
            ItemBan.LOGGER.warn("调用 BanService.banCascade 失败", e);
            return false;
        }
    }

    private static boolean banViaVanillaList(ServerPlayer player, String reason) {
        try {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return false;
            }
            UserBanList banList = server.getPlayerList().getBans();
            GameProfile profile = player.getGameProfile();
            if (!banList.isBanned(profile)) {
                banList.add(new UserBanListEntry(
                        profile,
                        new Date(),
                        "ItemBan",
                        null,
                        reason
                ));
            }
            return true;
        } catch (Throwable e) {
            ItemBan.LOGGER.warn("写入原版封禁列表失败", e);
            return false;
        }
    }
}

