package com.itemban;

import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class BanPermission {
    public static final String NODE_NAME = "itemban.ban";

    private BanPermission() {}

    public static void registerNode() {
        PermissionAPI.registerNode(NODE_NAME, DefaultPermissionLevel.OP, "Manage ItemBan blacklist and web panel");
    }

    public static boolean hasBan(CommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity) {
            return hasBan((ServerPlayerEntity) source.getEntity());
        }
        return true;
    }

    public static boolean hasBan(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity)) {
            return false;
        }
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        try {
            return PermissionAPI.hasPermission(serverPlayer, NODE_NAME);
        } catch (Exception e) {
            return serverPlayer.hasPermissions(2);
        }
    }
}
