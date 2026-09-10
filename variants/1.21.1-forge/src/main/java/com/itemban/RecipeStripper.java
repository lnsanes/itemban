package com.itemban;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
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

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class RecipeStripper {
    private static List<RecipeHolder<?>> snapshot = List.of();
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
        int n = manager.getRecipes().size();
        if (n == 0) {
            ItemBan.LOGGER.error("ItemBan: 配方表为空，跳过过滤以免清掉配方");
            return 0;
        }
        if (snapshot.isEmpty() || n > snapshot.size()) {
            snapshot = List.copyOf(new ArrayList<>(manager.getRecipes()));
        }
        return replaceFrom(server, manager, snapshot, true);
    }

    private static int replaceFrom(MinecraftServer server, RecipeManager manager, List<RecipeHolder<?>> source, boolean syncPlayers) {
        List<RecipeHolder<?>> keep = new ArrayList<>(source.size());
        List<ResourceLocation> removedIds = new ArrayList<>();
        var registryAccess = server.registryAccess();
        for (RecipeHolder<?> holder : source) {
            try {
                ItemStack result = holder.value().getResultItem(registryAccess);
                if (!result.isEmpty() && isOutputBanned(result)) {
                    removedIds.add(holder.id());
                    continue;
                }
                keep.add(holder);
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 读取配方 {} 失败，已保留: {}", safeId(holder), t.toString());
                keep.add(holder);
            }
        }
        try {
            manager.replaceRecipes(keep);
        } catch (Throwable t) {
            ItemBan.LOGGER.error("ItemBan: 写回 RecipeManager 失败", t);
            return 0;
        }
        ItemBan.LOGGER.info("ItemBan: 配方过滤完成，移除 {} 条，剩余 {} 条", removedIds.size(), keep.size());
        if (syncPlayers) {
            Collection<RecipeHolder<?>> recipes = manager.getRecipes();
            ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(recipes);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    player.connection.send(packet);
                } catch (Throwable ignored) {
                }
            }
        }
        return removedIds.size();
    }

    private static boolean isOutputBanned(ItemStack result) {
        String id = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        CompoundTag nbt = result.get(DataComponents.CUSTOM_DATA) == null ? null : result.get(DataComponents.CUSTOM_DATA).copyTag();
        return ConfigHandler.isBlacklisted(id, nbt);
    }

    private static String safeId(RecipeHolder<?> holder) {
        try {
            return String.valueOf(holder.id());
        } catch (Throwable t) {
            return "<unknown>";
        }
    }
}
