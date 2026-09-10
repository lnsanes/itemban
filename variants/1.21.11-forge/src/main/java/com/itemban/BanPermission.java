package com.itemban;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class BanPermission {
    public static final String NODE_NAME = "itemban.ban";

    public static final PermissionNode<Boolean> BAN = new PermissionNode<>(
            ItemBan.MODID,
            "ban",
            PermissionTypes.BOOLEAN,
            (player, uuid, context) -> isGameMaster(player)
    );

    private BanPermission() {}

    @SubscribeEvent
    public static void gatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(BAN);
    }

    public static boolean hasBan(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return hasBan(player);
    }

    public static boolean hasBan(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(serverPlayer, BAN));
        } catch (Exception e) {
            return isGameMaster(serverPlayer);
        }
    }

    private static boolean isGameMaster(Player player) {
        return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
