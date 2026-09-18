package com.itemban;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.BanList;
import net.minecraft.server.management.ProfileBanEntry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.UUID;

/**
 * Ban helpers compatible with LnsanesBan (which overrides vanilla {@code /ban}).
 * <p>
 * Order: LnsanesBan API (reflection) → vanilla {@link BanList} → server-side
 * {@code /ban} as last resort. Always disconnects afterwards if still connected.
 */
public final class BanHelper {
    private static final String LNSANESBAN_MODID = "lnsanesban";
    private static final String LNSANESBAN_API = "com.lnsanes.lnsanesban.api.LnsanesBanApi";
    private static final String LNSANESBAN_SERVICE = "com.lnsanes.lnsanesban.server.BanService";

    private static Boolean lnsanesbanLoaded;

    private BanHelper() {
    }

    public static void banAndKick(ServerPlayerEntity player, String reason) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        String banReason = reason == null || reason.trim().isEmpty()
                ? "Banned by ItemBan."
                : reason.replace('\n', ' ').replace('\r', ' ');

        try {
            if (!player.hasDisconnected()) {
                player.inventory.setChanged();
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
            try {
                String name = player.getGameProfile().getName();
                String cmd = "ban " + name + " " + banReason;
                int result = server.getCommands().performCommand(
                        server.createCommandSourceStack().withPermission(4),
                        cmd
                );
                ItemBan.LOGGER.info("执行 /ban 回退命令 result={} player={}", result, name);
                persisted = result > 0;
            } catch (Exception e) {
                ItemBan.LOGGER.warn("执行 /ban 回退命令失败", e);
            }
        }

        if (!persisted) {
            ItemBan.LOGGER.error("未能持久化封禁记录，仅踢出玩家 {}", player.getGameProfile().getName());
        }

        try {
            if (player.connection != null && !player.hasDisconnected()) {
                player.connection.disconnect(new StringTextComponent(banReason));
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

    private static boolean banViaLnsanesBan(ServerPlayerEntity player, String reason) {
        try {
            Class<?> api = Class.forName(LNSANESBAN_API);
            Method banPlayer = api.getMethod("banPlayer", ServerPlayerEntity.class, String.class, String.class);
            banPlayer.invoke(null, player, reason, "ItemBan");
            return true;
        } catch (ClassNotFoundException ignored) {
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

    private static boolean banViaVanillaList(ServerPlayerEntity player, String reason) {
        try {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return false;
            }
            BanList banList = server.getPlayerList().getBans();
            GameProfile profile = player.getGameProfile();
            if (!banList.isBanned(profile)) {
                banList.add(new ProfileBanEntry(
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
