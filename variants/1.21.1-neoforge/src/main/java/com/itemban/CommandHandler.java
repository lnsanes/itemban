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

    static final class IdAndNbt {
        final String id;
        final String nbt;
        IdAndNbt(String id, String nbt) {
            this.id = id;
            this.nbt = nbt;
        }
    }

    static IdAndNbt parseIdAndNbt(String input) {
        int braceIndex = input.indexOf('{');
        if (braceIndex > 0) {
            return new IdAndNbt(input.substring(0, braceIndex), input.substring(braceIndex));
        }
        return new IdAndNbt(input, null);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("itemban")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("add")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        CommandHandler.IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "item"));
                        ConfigHandler.addToBlacklist(parsed.id, parsed.nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("已添加黑名单物品: " + parsed.id + (parsed.nbt == null ? "" : parsed.nbt) + "，已更新配方表（移除 " + stripped + " 条）"), true);
                        return 1;
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        CommandHandler.IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "item"));
                        ConfigHandler.removeFromBlacklist(parsed.id, parsed.nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("已移除黑名单物品: " + parsed.id + (parsed.nbt == null ? "" : parsed.nbt) + "，已恢复对应配方（当前仍过滤 " + stripped + " 条）"), true);
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
                            CommandHandler.IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "block"));
                            ConfigHandler.addToBlockBlacklist(parsed.id, parsed.nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将方块 " + parsed.id + (parsed.nbt == null ? "" : parsed.nbt) + " 加入方块黑名单"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandHandler.IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "block"));
                            ConfigHandler.removeFromBlockBlacklist(parsed.id, parsed.nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已将方块 " + parsed.id + (parsed.nbt == null ? "" : parsed.nbt) + " 从方块黑名单移除"), true);
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
                            String itemId = parseIdAndNbt(StringArgumentType.getString(ctx, "item")).id;
                            ConfigHandler.addExcludeFromLog(itemId);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已加入审计排除: " + itemId), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String itemId = parseIdAndNbt(StringArgumentType.getString(ctx, "item")).id;
                            ConfigHandler.removeExcludeFromLog(itemId);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已移出审计排除: " + itemId), true);
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
