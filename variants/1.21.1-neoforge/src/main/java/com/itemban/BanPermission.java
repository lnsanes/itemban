package com.itemban;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

@EventBusSubscriber(modid = ItemBan.MODID)
public final class BanPermission {
    public static final String NODE_NAME = "itemban.ban";

    public static final PermissionNode<Boolean> BAN = new PermissionNode<>(
            ItemBan.MODID,
            "ban",
            PermissionTypes.BOOLEAN,
            (player, uuid, context) -> player != null && player.hasPermissions(2)
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
            return serverPlayer.hasPermissions(2);
        }
    }
}
