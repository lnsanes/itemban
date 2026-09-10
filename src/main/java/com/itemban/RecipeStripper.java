package com.itemban;

import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 从 RecipeManager 中移除「产出为物品黑名单」的配方。
 * <p>
 * 只按产出判断，不碰以黑名单物品为材料的配方，避免误删大量原版/模组配方。
 * datapack 重载后会重新快照完整配方表；{@code /itemban add/remove/reload} 只从快照再过滤，
 * 因此取消封禁后配方能恢复。单条配方读取失败时保留该配方，绝不中断加载。
 * 写回走原版 {@link RecipeManager#replaceRecipes}，避免反射字段名在 SRG 运行时对不上。
 */
@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class RecipeStripper {
    /** datapack 加载完成后、过滤前的完整配方（副本）。 */
    private static List<Recipe<?>> snapshot = List.of();
    private static MinecraftServer current;
    private static boolean serverLive;

    private RecipeStripper() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        RecipeManager reloading = event.getServerResources().getRecipeManager();
        event.addListener((preparationBarrier, resourceManager, prepareProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
            preparationBarrier.wait(net.minecraft.util.Unit.INSTANCE).thenRunAsync(() -> {
                MinecraftServer server = current;
                if (server != null && serverLive) {
                    recaptureAndApply(server, reloading);
                }
            }, gameExecutor));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        current = event.getServer();
        serverLive = true;
        if (snapshot.isEmpty()) {
            recaptureAndApply(current);
        } else {
            applyFromSnapshot(current);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        serverLive = false;
        current = null;
        snapshot = List.of();
    }

    /** {@code /itemban add/remove/reload} 之后调用：按当前黑名单从快照重建，不重新抓 datapack。 */
    public static int applyFromSnapshot(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        if (snapshot.isEmpty()) {
            return recaptureAndApply(server);
        }
        return replaceFrom(server, server.getRecipeManager(), snapshot, true);
    }

    static int recaptureAndApply(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        return recaptureAndApply(server, server.getRecipeManager());
    }

    static int recaptureAndApply(MinecraftServer server, RecipeManager manager) {
        if (server == null || manager == null) {
            return 0;
        }
        Collection<Recipe<?>> currentRecipes = manager.getRecipes();
        if (currentRecipes.isEmpty()) {
            ItemBan.LOGGER.error("ItemBan: 配方表为空，跳过过滤以免清掉配方");
            return 0;
        }
        if (snapshot.isEmpty() || currentRecipes.size() > snapshot.size()) {
            snapshot = List.copyOf(new ArrayList<>(currentRecipes));
        }
        return replaceFrom(server, manager, snapshot, true);
    }

    private static int replaceFrom(MinecraftServer server, RecipeManager manager, List<Recipe<?>> source, boolean syncPlayers) {
        List<Recipe<?>> keep = new ArrayList<>(source.size());
        List<ResourceLocation> removedIds = new ArrayList<>();
        var registryAccess = server.registryAccess();

        for (Recipe<?> recipe : source) {
            try {
                ItemStack result = recipe.getResultItem(registryAccess);
                if (!result.isEmpty() && isOutputBanned(result)) {
                    removedIds.add(recipe.getId());
                    continue;
                }
                keep.add(recipe);
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 读取配方 {} 失败，已保留该配方: {}", safeId(recipe), t.toString());
                keep.add(recipe);
            }
        }

        try {
            manager.replaceRecipes(keep);
        } catch (Throwable t) {
            ItemBan.LOGGER.error("ItemBan: 写回 RecipeManager 失败，配方表未改动", t);
            return 0;
        }

        int removed = removedIds.size();
        if (removed == 0) {
            ItemBan.LOGGER.info("ItemBan: 配方过滤完成，无需移除（快照 {} 条）", source.size());
        } else {
            String examples = removedIds.stream().limit(8).map(ResourceLocation::toString).reduce((a, b) -> a + ", " + b).orElse("");
            ItemBan.LOGGER.info("ItemBan: 配方过滤完成，移除 {} 条，剩余 {} 条。例如: {}", removed, keep.size(), examples);
        }

        if (!verifyNoBannedOutputsRemain(server, manager)) {
            ItemBan.LOGGER.error("ItemBan: 过滤后仍发现黑名单产出配方，请检查日志");
        }

        if (syncPlayers) {
            syncToPlayers(server, manager);
        }
        return removed;
    }

    private static boolean isOutputBanned(ItemStack result) {
        String id = result.getItem().builtInRegistryHolder().key().location().toString();
        return ConfigHandler.isBlacklisted(id, result.getTag());
    }

    /** 过滤后再扫一遍：若还有黑名单产出，说明写回失败或特殊配方漏网。 */
    private static boolean verifyNoBannedOutputsRemain(MinecraftServer server, RecipeManager manager) {
        var registryAccess = server.registryAccess();
        boolean clean = true;
        for (Recipe<?> recipe : manager.getRecipes()) {
            try {
                ItemStack result = recipe.getResultItem(registryAccess);
                if (!result.isEmpty() && isOutputBanned(result)) {
                    ItemBan.LOGGER.error("ItemBan: 过滤后仍残留配方 {} -> {}", recipe.getId(), result);
                    clean = false;
                }
            } catch (Throwable ignored) {
                // 与主循环一致：读失败的配方本来就会被保留
            }
        }
        return clean;
    }

    private static void syncToPlayers(MinecraftServer server, RecipeManager manager) {
        Collection<Recipe<?>> recipes = manager.getRecipes();
        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(recipes);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                player.connection.send(packet);
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 向 {} 同步配方失败: {}", player.getGameProfile().getName(), t.toString());
            }
        }
    }

    private static String safeId(Recipe<?> recipe) {
        try {
            return String.valueOf(recipe.getId());
        } catch (Throwable t) {
            return "<unknown>";
        }
    }
}
