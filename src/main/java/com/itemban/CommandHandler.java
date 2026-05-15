package com.itemban;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public class CommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("itemban")
            .requires(source -> source.hasPermission(2)) // OP level 2
            .then(Commands.literal("add")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String item = StringArgumentType.getString(ctx, "item");
                        ConfigHandler.addToBlacklist(item);
                        ctx.getSource().sendSuccess(() -> Component.literal("已添加黑名单物品: " + item), true);
                        return 1;
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String item = StringArgumentType.getString(ctx, "item");
                        ConfigHandler.removeFromBlacklist(item);
                        ctx.getSource().sendSuccess(() -> Component.literal("已移除黑名单物品: " + item), true);
                        return 1;
                    })))
            .then(Commands.literal("list")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("当前黑名单: " + ConfigHandler.getBlacklist()), true);
                    return 1;
                }))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ConfigHandler.loadBlacklist();
                    ctx.getSource().sendSuccess(() -> Component.literal("黑名单已重载"), true);
                    return 1;
                }))
        );
    }
}
