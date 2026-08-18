package com.itemban;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.network.play.server.SUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class RecipeStripper {
    private static List<IRecipe<?>> snapshot = Collections.emptyList();
    private static MinecraftServer current;
    private static boolean serverLive;

    private RecipeStripper() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        RecipeManager reloading = event.getDataPackRegistries().getRecipeManager();
        event.addListener((preparationBarrier, resourceManager, prepareProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
            preparationBarrier.wait(net.minecraft.util.Unit.INSTANCE).thenRunAsync(new Runnable() {
                @Override
                public void run() {
                    MinecraftServer server = current;
                    if (server != null && serverLive) {
                        recaptureAndApply(server, reloading);
                    }
                }
            }, gameExecutor));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(FMLServerStartedEvent event) {
        current = event.getServer();
        serverLive = true;
        recaptureAndApply(current);
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        serverLive = false;
        current = null;
        snapshot = Collections.emptyList();
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
        snapshot = Collections.unmodifiableList(new ArrayList<IRecipe<?>>(manager.getRecipes()));
        return replaceFrom(server, manager, snapshot, true);
    }

    private static int replaceFrom(MinecraftServer server, RecipeManager manager, List<IRecipe<?>> source, boolean syncPlayers) {
        List<IRecipe<?>> keep = new ArrayList<IRecipe<?>>(source.size());
        List<ResourceLocation> removedIds = new ArrayList<ResourceLocation>();
        for (IRecipe<?> recipe : source) {
            try {
                ItemStack result = recipe.getResultItem();
                if (!result.isEmpty() && isOutputBanned(result)) {
                    removedIds.add(recipe.getId());
                    continue;
                }
                keep.add(recipe);
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 读取配方失败，已保留: {}", t.toString());
                keep.add(recipe);
            }
        }
        try {
            writeRecipes(manager, keep);
        } catch (Throwable t) {
            ItemBan.LOGGER.error("ItemBan: 写回 RecipeManager 失败", t);
            return 0;
        }
        ItemBan.LOGGER.info("ItemBan: 配方过滤完成，移除 {} 条，剩余 {} 条", Integer.valueOf(removedIds.size()), Integer.valueOf(keep.size()));
        if (syncPlayers) {
            SUpdateRecipesPacket packet = new SUpdateRecipesPacket(manager.getRecipes());
            for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
                try {
                    player.connection.send(packet);
                } catch (Throwable ignored) {
                }
            }
        }
        return removedIds.size();
    }

    private static boolean isOutputBanned(ItemStack result) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(result.getItem());
        if (key == null) {
            return false;
        }
        return ConfigHandler.isBlacklisted(key.toString(), result.getTag());
    }

    private static void writeRecipes(RecipeManager manager, List<IRecipe<?>> keep) throws Exception {
        Field field = findField(RecipeManager.class, new String[] {"recipes", "field_199522_d"});
        if (field == null) {
            throw new IllegalStateException("未找到 RecipeManager 配方表字段");
        }
        Map<IRecipeType<?>, Map<ResourceLocation, IRecipe<?>>> byType =
            new HashMap<IRecipeType<?>, Map<ResourceLocation, IRecipe<?>>>();
        for (IRecipe<?> recipe : keep) {
            IRecipeType<?> type = recipe.getType();
            Map<ResourceLocation, IRecipe<?>> inner = byType.get(type);
            if (inner == null) {
                inner = new HashMap<ResourceLocation, IRecipe<?>>();
                byType.put(type, inner);
            }
            inner.put(recipe.getId(), recipe);
        }
        field.set(manager, byType);
    }

    private static Field findField(Class<?> owner, String[] names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Field field = owner.getDeclaredField(names[i]);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        ItemBan.LOGGER.error("ItemBan: 未找到字段");
        return null;
    }
}
