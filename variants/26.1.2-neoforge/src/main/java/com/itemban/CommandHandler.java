package com.itemban;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = ItemBan.MODID)
public class CommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("itemban")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("add")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String input = StringArgumentType.getString(ctx, "item");
                        String id = input;
                        String nbt = null;
                        int i = input.indexOf('{');
                        if (i > 0) { id = input.substring(0, i); nbt = input.substring(i); }
                        ConfigHandler.addToBlacklist(id, nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("已添加黑名单物品: " + input + "，已更新配方表（移除 " + stripped + " 条）"), true);
                        return 1;
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String input = StringArgumentType.getString(ctx, "item");
                        String id = input;
                        String nbt = null;
                        int i = input.indexOf('{');
                        if (i > 0) { id = input.substring(0, i); nbt = input.substring(i); }
                        ConfigHandler.removeFromBlacklist(id, nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("已移除黑名单物品: " + input + "，已恢复对应配方（当前仍过滤 " + stripped + " 条）"), true);
                        return 1;
                    })))
            .then(Commands.literal("list")
                .executes(ctx -> {
                    StringBuilder sb = new StringBuilder("当前物品黑名单:\n");
                    for (var r : ConfigHandler.getBlacklistRules()) {
                        sb.append("  - ").append(r.id);
                        if (r.nbtString != null && !r.nbtString.isEmpty()) sb.append(r.nbtString);
                        sb.append("\n");
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                    return 1;
                }))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ConfigHandler.loadBlacklist();
                    ConfigHandler.loadBlockBlacklist();
                    ConfigHandler.loadConfig();
                    int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("配置已重载，已更新配方表（移除 " + stripped + " 条）"), true);
                    return 1;
                }))
            .then(Commands.literal("announce")
                .then(Commands.literal("on").executes(ctx -> { ConfigHandler.setPublicAnnounce(true); ctx.getSource().sendSuccess(() -> Component.literal("§a已开启公示"), true); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { ConfigHandler.setPublicAnnounce(false); ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭公示"), true); return 1; })))
            .then(Commands.literal("autoban")
                .then(Commands.literal("on").executes(ctx -> { ConfigHandler.setAutoBanOnViolation(true); ctx.getSource().sendSuccess(() -> Component.literal("§a已开启自动封禁"), true); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { ConfigHandler.setAutoBanOnViolation(false); ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭自动封禁"), true); return 1; })))
            .then(Commands.literal("dropdetect")
                .then(Commands.literal("on").executes(ctx -> { ConfigHandler.setDetectDroppedItems(true); ctx.getSource().sendSuccess(() -> Component.literal("§a掉落物检测：范围扫描"), true); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { ConfigHandler.setDetectDroppedItems(false); ctx.getSource().sendSuccess(() -> Component.literal("§c掉落物检测：即时拦截"), true); return 1; })))
            .then(Commands.literal("blockscan")
                .then(Commands.literal("on").executes(ctx -> { ConfigHandler.setDetectWorldBlocks(true); ctx.getSource().sendSuccess(() -> Component.literal("§a已开启世界方块检测"), true); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { ConfigHandler.setDetectWorldBlocks(false); ctx.getSource().sendSuccess(() -> Component.literal("§c已关闭世界方块检测"), true); return 1; })))
            .then(Commands.literal("block")
                .then(Commands.literal("add")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "block");
                            String id = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            String nbt = input.contains("{") ? input.substring(input.indexOf("{")) : null;
                            ConfigHandler.addToBlockBlacklist(id, nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将方块 " + input + " 加入方块黑名单"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "block");
                            String id = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            String nbt = input.contains("{") ? input.substring(input.indexOf("{")) : null;
                            ConfigHandler.removeFromBlockBlacklist(id, nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已将方块 " + input + " 从方块黑名单移除"), true);
                            return 1;
                        })))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§6方块黑名单:\n");
                        for (var r : ConfigHandler.getBlockBlacklistRules()) {
                            sb.append("  - ").append(r.id);
                            if (r.nbtString != null && !r.nbtString.isEmpty()) sb.append(r.nbtString);
                            sb.append("\n");
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                        return 1;
                    })))
            .then(Commands.literal("logexclude")
                .then(Commands.literal("add")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "item");
                            String id = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            ConfigHandler.addExcludeFromLog(id);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已加入审计排除: " + id), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "item");
                            String id = input.contains("{") ? input.substring(0, input.indexOf("{")) : input;
                            ConfigHandler.removeExcludeFromLog(id);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已移出审计排除: " + id), true);
                            return 1;
                        })))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§6审计排除列表:\n");
                        for (String id : ConfigHandler.getExcludeFromLog()) sb.append("  - ").append(id).append("\n");
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
                        return 1;
                    })))
        );
    }
}
