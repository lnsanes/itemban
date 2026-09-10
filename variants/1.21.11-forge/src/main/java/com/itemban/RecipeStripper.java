package com.itemban;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public final class RecipeStripper {
    private static final Field RECIPES_FIELD = findField(RecipeManager.class, "recipes");
    private static List<RecipeHolder<?>> snapshot = List.of();
    private static MinecraftServer current;
    private static boolean serverLive;

    private RecipeStripper() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((sharedState, backgroundExecutor, barrier, gameExecutor) ->
            barrier.wait(net.minecraft.util.Unit.INSTANCE).thenRunAsync(() -> {
                MinecraftServer server = current;
                if (server != null && serverLive) {
                    // 1.21.11+ 在 reload apply 阶段 item tag 尚未绑定，
                    // 此时 finalizeRecipeLoading 会抛 unbound tag。延到下一 tick。
                    scheduleRecapture(server);
                }
            }, gameExecutor));
    }

    @SubscribeEvent
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
        if (RECIPES_FIELD == null) {
            ItemBan.LOGGER.error("ItemBan: 无法访问 RecipeManager.recipes，配方过滤已跳过");
            return 0;
        }
        List<RecipeHolder<?>> keep = new ArrayList<>(source.size());
        List<String> removedIds = new ArrayList<>();
        for (RecipeHolder<?> holder : source) {
            try {
                ItemStack result = extractResult(holder.value());
                if (!result.isEmpty() && isOutputBanned(result)) {
                    removedIds.add(String.valueOf(holder.id()));
                    continue;
                }
                keep.add(holder);
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 读取配方 {} 失败，已保留: {}", safeId(holder), t.toString());
                keep.add(holder);
            }
        }
        try {
            RECIPES_FIELD.set(manager, RecipeMap.create(keep));
            manager.finalizeRecipeLoading(enabledFeatures(server));
            unboundRetries = 0;
        } catch (Throwable t) {
            if (isUnboundTag(t)) {
                if (unboundRetries++ < 8) {
                    ItemBan.LOGGER.info("ItemBan: item tag 尚未绑定，延后写回配方表 ({})", unboundRetries);
                    scheduleRecapture(server);
                } else {
                    unboundRetries = 0;
                    ItemBan.LOGGER.error("ItemBan: 写回 RecipeManager 失败", t);
                }
                return 0;
            }
            ItemBan.LOGGER.error("ItemBan: 写回 RecipeManager 失败", t);
            return 0;
        }
        ItemBan.LOGGER.info("ItemBan: 配方过滤完成，移除 {} 条，剩余 {} 条", removedIds.size(), keep.size());
        if (syncPlayers) {
            try {
                ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(
                    manager.getSynchronizedItemProperties(),
                    manager.getSynchronizedStonecutterRecipes()
                );
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    try {
                        player.connection.send(packet);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                ItemBan.LOGGER.warn("ItemBan: 同步配方到客户端失败: {}", t.toString());
            }
        }
        return removedIds.size();
    }

    private static volatile int unboundRetries;

    private static void scheduleRecapture(MinecraftServer server) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            server.execute(() -> {
                if (serverLive && current == server) {
                    recaptureAndApply(server);
                }
            });
        }, "itemban-recipe-reload");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isUnboundTag(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("unbound tag")) {
                return true;
            }
        }
        return false;
    }

    private static net.minecraft.world.flag.FeatureFlagSet enabledFeatures(MinecraftServer server) {
        try {
            Method m = server.getClass().getMethod("getWorldData");
            Object data = m.invoke(server);
            return (net.minecraft.world.flag.FeatureFlagSet) data.getClass().getMethod("enabledFeatures").invoke(data);
        } catch (Throwable ignored) {
        }
        try {
            Field f = server.getClass().getField("worldData");
            Object data = f.get(server);
            return (net.minecraft.world.flag.FeatureFlagSet) data.getClass().getMethod("enabledFeatures").invoke(data);
        } catch (Throwable t) {
            throw new IllegalStateException("无法读取 FeatureFlagSet", t);
        }
    }

    private static ItemStack extractResult(Recipe<?> recipe) {
        try {
            Method method = recipe.getClass().getMethod("getResultItem", net.minecraft.core.HolderLookup.Provider.class);
            Object value = method.invoke(recipe, current == null ? null : current.registryAccess());
            if (value instanceof ItemStack stack) {
                return stack;
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> type = recipe.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(recipe);
                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        return stack;
                    }
                    if (value != null && value.getClass().getName().endsWith("ItemStackTemplate")) {
                        Object created = value.getClass().getMethod("create").invoke(value);
                        if (created instanceof ItemStack stack) {
                            return stack;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return ItemStack.EMPTY;
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

    private static Field findField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        ItemBan.LOGGER.error("ItemBan: 未找到字段 {} 于 {}", String.join("/", names), owner.getName());
        return null;
    }
}
