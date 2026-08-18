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
                        String input = StringArgumentType.getString(ctx, "item");
                        String itemId;
                        String nbtString = null;

                        int braceIndex = input.indexOf('{');
                        if (braceIndex > 0) {
                            itemId = input.substring(0, braceIndex);
                            nbtString = input.substring(braceIndex);
                        } else {
                            itemId = input;
                        }

                        ConfigHandler.addToBlacklist(itemId, nbtString);
                        String msg = nbtString != null 
                            ? "已添加黑名单物品: " + itemId + " (带 NBT)"
                            : "已添加黑名单物品: " + itemId;
                        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                        return 1;
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String input = StringArgumentType.getString(ctx, "item");
                        String itemId;
                        String nbtString = null;

                        int braceIndex = input.indexOf('{');
                        if (braceIndex > 0) {
                            itemId = input.substring(0, braceIndex);
                            nbtString = input.substring(braceIndex);
                        } else {
                            itemId = input;
                        }

                        ConfigHandler.removeFromBlacklist(itemId, nbtString);
                        String msg = nbtString != null 
                            ? "已移除黑名单物品: " + itemId + " (指定 NBT)"
                            : "已移除黑名单物品: " + itemId;
                        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                        return 1;
                    })))
            .then(Commands.literal("list")
                .executes(ctx -> {
                    StringBuilder sb = new StringBuilder("当前黑名单:\n");
                    for (ConfigHandler.BlacklistRule rule : ConfigHandler.getBlacklistRules()) {
                        if (rule.nbtString != null) {
                            sb.append("  - ").append(rule.id).append(rule.nbtString).append("\n");
                        } else if (rule.nbt != null) {
                            sb.append("  - ").append(rule.id).append(" (带NBT)\n");
                        } else {
                            sb.append("  - ").append(rule.id).append("\n");
                        }
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                    return 1;
                }))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ConfigHandler.loadBlacklist();
                    ctx.getSource().sendSuccess(() -> Component.literal("黑名单已重载"), true);
                    return 1;
                }))
            .then(Commands.literal("announce")
                .then(Commands.literal("on")
                    .executes(ctx -> {
                        ConfigHandler.setPublicAnnounce(true);
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已开启聊天栏公示功能"), true);
                        return 1;
                    }))
                .then(Commands.literal("off")
                    .executes(ctx -> {
                        ConfigHandler.setPublicAnnounce(false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭聊天栏公示功能"), true);
                        return 1;
                    })))
            .then(Commands.literal("autoban")
                .then(Commands.literal("on")
                    .executes(ctx -> {
                        ConfigHandler.setAutoBanOnViolation(true);
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已开启自动踢出功能"), true);
                        return 1;
                    }))
                .then(Commands.literal("off")
                    .executes(ctx -> {
                        ConfigHandler.setAutoBanOnViolation(false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭自动踢出功能"), true);
                        return 1;
                    })))
            .then(Commands.literal("dropdetect")
                .then(Commands.literal("on")
                    .executes(ctx -> {
                        ConfigHandler.setDetectDroppedItems(true);
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已开启掉落物检测"), true);
                        return 1;
                    }))
                .then(Commands.literal("off")
                    .executes(ctx -> {
                        ConfigHandler.setDetectDroppedItems(false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭掉落物检测"), true);
                        return 1;
                    })))
            .then(Commands.literal("blockscan")
                .then(Commands.literal("on")
                    .executes(ctx -> {
                        ConfigHandler.setDetectWorldBlocks(true);
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已开启世界方块检测"), true);
                        return 1;
                    }))
                .then(Commands.literal("off")
                    .executes(ctx -> {
                        ConfigHandler.setDetectWorldBlocks(false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭世界方块检测"), true);
                        return 1;
                    })))
            .then(Commands.literal("block")
                .then(Commands.literal("add")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "block");
                            String blockId = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            String nbt = input.contains("{") ? input.substring(input.indexOf("{")) : null;
                            ConfigHandler.addToBlockBlacklist(blockId, nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将方块 " + input + " 加入方块黑名单"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "block");
                            String blockId = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            String nbt = input.contains("{") ? input.substring(input.indexOf("{")) : null;
                            ConfigHandler.removeFromBlockBlacklist(blockId, nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已将方块 " + input + " 从方块黑名单移除"), true);
                            return 1;
                        })))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§6方块黑名单:\n");
                        for (var rule : ConfigHandler.getBlockBlacklistRules()) {
                            if (rule.nbtString != null && !rule.nbtString.isEmpty()) {
                                sb.append("  - ").append(rule.id).append(rule.nbtString).append("\n");
                            } else {
                                sb.append("  - ").append(rule.id).append("\n");
                            }
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                        return 1;
                    })))
            .then(Commands.literal("logexclude")
                .then(Commands.literal("add")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "item");
                            String itemId = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            ConfigHandler.addExcludeFromLog(itemId);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将 " + itemId + " 加入审计排除列表"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "item");
                            String itemId = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            ConfigHandler.removeExcludeFromLog(itemId);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已将 " + itemId + " 从审计排除列表移除"), true);
                            return 1;
                        })))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§6审计排除列表:\n");
                        for (String id : ConfigHandler.getExcludeFromLog()) {
                            sb.append("  - ").append(id).append("\n");
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                        return 1;
                    })))
        );
    }
}
