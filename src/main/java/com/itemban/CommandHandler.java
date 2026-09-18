package com.itemban;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
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
            .requires(BanPermission::hasBan)
            .then(Commands.literal("add")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "item"));
                        ConfigHandler.addToBlacklist(parsed.id, parsed.nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        String msg = parsed.nbt != null 
                            ? "已添加黑名单物品: " + parsed.id + " (带 NBT)，已更新配方表（移除 " + stripped + " 条）"
                            : "已添加黑名单物品: " + parsed.id + "，已更新配方表（移除 " + stripped + " 条）";
                        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                        return 1;
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "item"));
                        ConfigHandler.removeFromBlacklist(parsed.id, parsed.nbt);
                        int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                        String msg = parsed.nbt != null 
                            ? "已移除黑名单物品: " + parsed.id + " (指定 NBT)，已恢复对应配方（当前仍过滤 " + stripped + " 条）"
                            : "已移除黑名单物品: " + parsed.id + " (所有规则)，已恢复对应配方（当前仍过滤 " + stripped + " 条）";
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
                    ConfigHandler.loadBlockBlacklist();
                    ConfigHandler.loadConfig();
                    AdminKeyManager.reloadFromDisk();
                    int stripped = RecipeStripper.applyFromSnapshot(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("黑名单与管理密钥已热重载，已更新配方表（移除 " + stripped + " 条）"), true);
                    return 1;
                }))
            .then(Commands.literal("gui")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§c请在游戏内使用 /itemban gui"), false);
                        return 0;
                    }
                    AdminNetwork.requestOpen(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("§7正在核对管理模组密钥与哈希…"), false);
                    return 1;
                }))
            .then(Commands.literal("adminmod")
                .executes(ctx -> {
                    Path jar = AdminKeyManager.writeModForCurrentKey();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a已生成当前密钥的管理模组:\n§f" + jar.toAbsolutePath()
                                    + "\n§7SHA-256: " + AdminKeyManager.jarSha256()
                                    + "\n§7keyId=" + AdminKeyManager.keyId()
                                    + " 把该 jar 放进客户端 mods，可与其它服的管理模组共存"), true);
                    return 1;
                })
                .then(Commands.literal("regen")
                    .executes(ctx -> {
                        Path jar = AdminKeyManager.rotateAndWriteMod();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§a已热更换密钥并生成新管理模组:\n§f" + jar.toAbsolutePath()
                                        + "\n§7SHA-256: " + AdminKeyManager.jarSha256()
                                        + "\n§7keyId=" + AdminKeyManager.keyId()
                                        + " 旧管理模组立即失效"), true);
                        return 1;
                    })))
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
                            IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "block"));
                            ConfigHandler.addToBlockBlacklist(parsed.id, parsed.nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将方块 " + StringArgumentType.getString(ctx, "block") + " 加入方块黑名单"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            IdAndNbt parsed = parseIdAndNbt(StringArgumentType.getString(ctx, "block"));
                            ConfigHandler.removeFromBlockBlacklist(parsed.id, parsed.nbt);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c已将方块 " + StringArgumentType.getString(ctx, "block") + " 从方块黑名单移除"), true);
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
                            String itemId = parseIdAndNbt(StringArgumentType.getString(ctx, "item")).id;
                            ConfigHandler.addExcludeFromLog(itemId);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已将 " + itemId + " 加入审计排除列表"), true);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String itemId = parseIdAndNbt(StringArgumentType.getString(ctx, "item")).id;
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
            .then(Commands.literal("web")
                .executes(ctx -> {
                    if (!ConfigHandler.webEnabled) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§c网页管理已关闭，请在 config/ItemBan/config.json 将 webEnabled 设为 true 后重启"), false);
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(ConfigHandler.webInfoMessage()), false);
                    return 1;
                })
                .then(Commands.literal("password")
                    .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String error = ConfigHandler.setWebPassword(StringArgumentType.getString(ctx, "password"));
                            if (error != null) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§c" + error), false);
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§a已设置 admin 正式密码（已哈希存储）"), true);
                            return 1;
                        }))))
        );
    }
}
