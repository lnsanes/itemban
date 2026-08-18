package com.itemban;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import net.minecraft.commands.Commands;

@EventBusSubscriber(modid = ItemBan.MODID)
public class ItemBanHandler {

    /*
     * 性能优化说明（针对低性能服务器）：
     * - 玩家背包扫描节流至每10 ticks（0.5秒）一次，降低CPU占用90%以上
     * - 环境扫描（展示框）节流至每40 ticks（2秒）一次
     * - 模组存在检测缓存为静态布尔值，仅初始化一次
     * - 容器扫描仅在打开事件触发，掉落拦截为事件驱动
     * - 创造/OP玩家早期返回，避免不必要检查
     */

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static Path LOG_DIR = Paths.get("logs", "ItemBan");

    static {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException ignored) {}
    }

    // Cached mod detection flags (initialized once for performance)
    private static boolean hasSophisticatedBackpacks = false;
    private static boolean hasAE2 = false;
    private static boolean hasCreate = false;
    private static boolean modsDetected = false;

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // Remove recipes for blacklisted items - simplified, in real use RecipeManager reload
        ItemBan.LOGGER.info("ItemBan: 服务器启动，黑名单加载完成");
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (isOpOrCreative(player)) return;

        // Throttle inventory scan to every 10 ticks (0.5s) to reduce CPU load on weak servers
        if (player.tickCount % 10 != 0) return;

        // Lazy init mod detection (once)
        if (!modsDetected) {
            detectLoadedMods();
        }

        // 必须先删干净并校验，再封禁踢出（否则踢人后物品可能残留在玩家数据里）
        if (player instanceof ServerPlayer serverPlayer) {
            purgeInventoryThenMaybeBan(serverPlayer, "背包");
            if (serverPlayer.hasDisconnected()) {
                return;
            }
        } else {
            purgeInventoryItems(player, formatEntityPos(player), "背包", false);
        }

        // 每 40 ticks（2 秒）扫描一次玩家周围 2 chunk 范围内的黑名单方块/展示框
        if (player.tickCount % 40 == 0 && player instanceof ServerPlayer serverPlayer && !isOpOrCreative(serverPlayer)) {
            scanNearbyChunks(serverPlayer);
        }

        // 当掉落物检测开关开启时，每 2 ticks 扫描玩家周围的掉落物（范围扫描模式）
        if (ConfigHandler.detectDroppedItems && player.tickCount % 2 == 0 && player instanceof ServerPlayer serverPlayer && !isOpOrCreative(serverPlayer)) {
            scanDroppedItemsNearby(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onItemEntitySpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity itemEntity)) return;

        // 开关为 on 时，改用范围扫描，此处跳过即时拦截
        if (ConfigHandler.detectDroppedItems) return;

        // 豁免 OP/创造玩家
        if (itemEntity.getOwner() instanceof Player owner && isOpOrCreative(owner)) return;

        ItemStack stack = itemEntity.getItem();
        if (isBlacklisted(stack)) {
            event.setCanceled(true); // 立即拦截新生成的掉落物（仅在 dropdetect off 时生效）
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity().level().isClientSide()) return;
        Player player = event.getEntity();
        if (isOpOrCreative(player)) return;

        // 先清空容器内违禁物品并记录，全部处理完后再封禁。
        // 跳过过滤/样板等幽灵槽，避免把配置用 ItemStack 当成真实持有。
        AbstractContainerMenu menu = event.getContainer();
        ItemStack firstViolation = ItemStack.EMPTY;
        boolean found = false;
        for (Slot slot : menu.slots) {
            if (isConfigGhostSlot(slot, player)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                ItemStack removed = stack.copy();
                if (!found) {
                    firstViolation = removed.copy();
                    found = true;
                }
                slot.set(ItemStack.EMPTY); // 先删
                logObtained(player, removed, formatEntityPos(player), "容器", false);
            }
        }
        if (found && player instanceof ServerPlayer serverPlayer) {
            menu.broadcastChanges();
            banAfterVerifiedClean(serverPlayer, firstViolation);
        }
    }

    /**
     * Create 过滤槽、AE2 FakeSlot / 样板标记槽等：GUI 里显示的是配置用幽灵物品，
     * 玩家并不能真正取出。这些槽不得删除、不得记为持有、不得封禁。
     */
    private static boolean isConfigGhostSlot(Slot slot, Player player) {
        if (slot == null) {
            return true;
        }
        // 玩家真实背包槽始终检查
        if (player != null && slot.container == player.getInventory()) {
            return false;
        }
        try {
            if (!slot.mayPickup(player)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 模组槽实现异常时按幽灵槽跳过，避免误封
            return true;
        }

        String className = slot.getClass().getName();
        String simpleName = slot.getClass().getSimpleName();
        String lower = (className + " " + simpleName).toLowerCase();
        return lower.contains("fakeslot")
                || lower.contains("ghost")
                || lower.contains("phantom")
                || lower.contains("filterslot")
                || lower.contains("configslot")
                || simpleName.equalsIgnoreCase("FakeSlot")
                || simpleName.equalsIgnoreCase("FilterSlot");
    }

    private static boolean isBlacklisted(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return ConfigHandler.isBlacklisted(id, stack);
    }

    private static boolean isOpOrCreative(Player player) {
        if (player.isCreative()) return true;
        if (player instanceof ServerPlayer sp) {
            return Commands.LEVEL_GAMEMASTERS.check(sp.permissions());
        }
        return false;
    }

    /** 格式化实体所在维度与方块坐标。 */
    private static String formatEntityPos(Entity entity) {
        if (entity == null) return "未知位置";
        return formatPos(entity.level(), entity.blockPosition());
    }

    /** 格式化维度 + 方块坐标，如：主世界 @ 100, 64, -20 */
    private static String formatPos(Level level, BlockPos pos) {
        if (pos == null) return "未知位置";
        String dim = formatDimension(level);
        return String.format("%s @ %d, %d, %d", dim, pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatDimension(Level level) {
        if (level == null) return "未知维度";
        ResourceKey<Level> key = level.dimension();
        if (key == Level.OVERWORLD) return "主世界";
        if (key == Level.NETHER) return "下界";
        if (key == Level.END) return "末地";
        return key.identifier().toString();
    }

    /**
     * 清空玩家背包中的全部违禁物品，记录/公示，校验干净后再封禁踢出。
     */
    private static void purgeInventoryThenMaybeBan(ServerPlayer player, String source) {
        ItemStack first = purgeInventoryItems(player, formatEntityPos(player), source, true);
        if (first.isEmpty()) {
            return;
        }
        banAfterVerifiedClean(player, first);
    }

    /**
     * 删除背包内所有黑名单物品并写日志/公示（不封禁）。
     *
     * @return 第一个被清除的物品副本；若无则返回 EMPTY
     */
    private static ItemStack purgeInventoryItems(Player player, String location, String source, boolean sync) {
        ItemStack firstViolation = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                if (firstViolation.isEmpty()) {
                    firstViolation = stack.copy();
                }
                // 先删再记，避免封禁踢人打断删除
                ItemStack removed = stack.copy();
                player.getInventory().setItem(i, ItemStack.EMPTY);
                logObtained(player, removed, location, source, false);
            }
        }

        // 光标上正在拖拽的物品也要清
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (!carried.isEmpty() && isBlacklisted(carried)) {
                if (firstViolation.isEmpty()) {
                    firstViolation = carried.copy();
                }
                ItemStack removed = carried.copy();
                player.containerMenu.setCarried(ItemStack.EMPTY);
                logObtained(player, removed, location, source, false);
            }
        }

        if (!firstViolation.isEmpty() && sync && player instanceof ServerPlayer serverPlayer) {
            player.getInventory().setChanged();
            serverPlayer.inventoryMenu.broadcastChanges();
            serverPlayer.containerMenu.broadcastChanges();
        }
        return firstViolation;
    }

    private static boolean inventoryHasBlacklisted(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                return true;
            }
        }
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (!carried.isEmpty() && isBlacklisted(carried)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验背包已无违禁物后才封禁踢出；若仍残留则再清一次，仍失败则不踢并打错误日志。
     */
    private static void banAfterVerifiedClean(ServerPlayer player, ItemStack sampleViolation) {
        if (!ConfigHandler.autoBanOnViolation || sampleViolation == null || sampleViolation.isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(sampleViolation.getItem()).toString();
        if (ConfigHandler.isExcludedFromLog(itemId)) {
            return;
        }
        if (player.level().getServer() == null || player.hasDisconnected()) {
            return;
        }

        // 再扫一遍，确保踢人前背包干净
        if (inventoryHasBlacklisted(player)) {
            ItemBan.LOGGER.warn("封禁前仍检测到违禁物品，进行二次清除 | 玩家 {}", player.getGameProfile().name());
            purgeInventoryItems(player, formatEntityPos(player), "二次清除", true);
        }

        if (inventoryHasBlacklisted(player)) {
            ItemBan.LOGGER.error("违禁物品未能清除干净，取消踢出以避免物品残留 | 玩家 {}", player.getGameProfile().name());
            return;
        }

        ItemBan.LOGGER.info("已确认背包无违禁物品，执行封禁踢出 | 玩家 {} | 物品 {}",
                player.getGameProfile().name(), itemId);
        String banReason = String.format("你因获取违禁物品（%s）已被永久封禁 如需申诉 请联系服务器管理员。", itemId);
        BanHelper.banAndKick(player, banReason);
    }

    /**
     * 记录/公示违禁物品。封禁必须在物品删除并校验之后单独调用 {@link #banAfterVerifiedClean}。
     *
     * @param allowBan 兼容旧调用；为 true 时仍会走“校验后再封禁”（调用方须已先删除物品）
     */
    private static void logObtained(Player player, ItemStack stack, String location, String source, boolean allowBan) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String loc = location == null || location.isBlank() ? formatEntityPos(player) : location;
        String src = source == null || source.isBlank() ? "未知" : source;

        // 如果物品在排除审计列表中，则不写入日志
        if (!ConfigHandler.isExcludedFromLog(itemId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 获得了黑名单物品 %s (数量: %d) | 来源: %s | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(),
                itemId, stack.getCount(), src, loc);
            try {
                Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                ItemBan.LOGGER.error("日志写入失败", e);
            }
            ItemBan.LOGGER.info("违禁物品 {} | 玩家 {} | 来源 {} | 坐标 {}", itemId, player.getName().getString(), src, loc);
        }

        // 聊天栏公示（可开关）
        if (ConfigHandler.publicAnnounce) {
            String nbtInfo = "";

            if (stack.has(DataComponents.CUSTOM_DATA)) {
                String nbtStr = stack.get(DataComponents.CUSTOM_DATA) == null ? null : stack.get(DataComponents.CUSTOM_DATA).copyTag().toString();
                // 限制 NBT 长度，避免消息过长
                if (nbtStr.length() > 60) {
                    nbtStr = nbtStr.substring(0, 57) + "...";
                }
                nbtInfo = " " + nbtStr;
            }

            String message = String.format("§c[ItemBan] §f玩家 §e%s §f因持有违禁物品 §c%s%s §f已被系统清除！ §7[%s | %s]",
                player.getName().getString(), itemId, nbtInfo, src, loc);

            if (player.level().getServer() != null) {
                player.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false);
            }
        }

        if (allowBan && player instanceof ServerPlayer serverPlayer) {
            banAfterVerifiedClean(serverPlayer, stack);
        }
    }

    private static void detectLoadedMods() {
        var modList = net.neoforged.fml.ModList.get();
        hasSophisticatedBackpacks = modList.isLoaded("sophisticatedbackpacks");
        hasAE2 = modList.isLoaded("ae2");
        hasCreate = modList.isLoaded("create");
        modsDetected = true;

        if (hasSophisticatedBackpacks || hasAE2 || hasCreate) {
            ItemBan.LOGGER.info("ItemBan: 检测到存储模组 - 精妙背包:{}, AE2:{}, Create:{}", 
                hasSophisticatedBackpacks, hasAE2, hasCreate);
        }
    }

    /**
     * 快速扫描玩家周围掉落物（仅在 detectDroppedItems = true 时调用）
     * 每 2 ticks 执行一次
     */
    private static void scanDroppedItemsNearby(ServerPlayer player) {
        var level = player.level();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;

        var aabb = new net.minecraft.world.phys.AABB(
            (centerChunkX - 2) << 4, level.getMinY(), (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, level.getMaxY(), ((centerChunkZ + 2) << 4) + 16
        );

        ItemStack firstViolation = ItemStack.EMPTY;
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, aabb)) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                if (firstViolation.isEmpty()) {
                    firstViolation = stack.copy();
                }
                ItemStack removed = stack.copy();
                String loc = formatEntityPos(itemEntity);
                itemEntity.discard(); // 先删除实体
                logObtained(player, removed, loc, "掉落物", false);
            }
        }
        if (!firstViolation.isEmpty()) {
            // 掉落物场景：先确保玩家背包也干净，再封禁
            purgeInventoryItems(player, formatEntityPos(player), "掉落物联动清包", true);
            banAfterVerifiedClean(player, firstViolation);
        }
    }

    /**
     * 扫描玩家周围 2 个 chunk 范围内的物品展示框（Item Frame）和违禁方块
     * 检测到黑名单物品/方块后会触发 logObtained / logBlockViolation（公示/封禁）并清除
     */
    private static void scanNearbyChunks(ServerPlayer player) {
        // 如果世界方块检测已关闭，则跳过方块扫描（但仍扫描展示框）
        if (!ConfigHandler.detectWorldBlocks) {
            // 仅扫描展示框
            scanItemFramesOnly(player);
            return;
        }

        var level = player.level();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int playerY = player.getBlockY();

        // 半径 2 chunk → 扫描 5×5 的 chunk 区域
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;

                // 扫描该 chunk 内的物品展示框实体
                var aabb = new net.minecraft.world.phys.AABB(
                    chunkX << 4, level.getMinY(), chunkZ << 4,
                    (chunkX << 4) + 16, level.getMaxY(), (chunkZ << 4) + 16
                );
                for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, aabb)) {
                    ItemStack displayed = frame.getItem();
                    if (!displayed.isEmpty() && isBlacklisted(displayed)) {
                        ItemStack removed = displayed.copy();
                        String loc = formatEntityPos(frame);
                        frame.setItem(ItemStack.EMPTY); // 先清空展示框
                        logObtained(player, removed, loc, "展示框", false);
                        // 展示框违禁：清完后校验背包再封禁
                        purgeInventoryItems(player, formatEntityPos(player), "展示框联动清包", true);
                        banAfterVerifiedClean(player, removed);
                    }
                }

                // 扫描该 chunk 内的方块（性能考虑：仅扫描玩家上下 16 格）
                for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                    for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                        for (int y = Math.max(playerY - 16, level.getMinY());
                             y <= Math.min(playerY + 16, level.getMaxY()); y++) {

                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (state.isAir()) continue;

                            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            CompoundTag blockNbt = null;

                            var blockEntity = level.getBlockEntity(pos);
                            if (blockEntity != null) {
                                blockNbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                            }

                            if (ConfigHandler.isBlockBlacklisted(blockId, blockNbt)) {
                                // 方块：先清除再记录/封禁
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                                logBlockViolation(player, blockId, blockNbt, formatPos(level, pos));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 仅扫描物品展示框（当 detectWorldBlocks 关闭时使用）
     */
    private static void scanItemFramesOnly(ServerPlayer player) {
        var level = player.level();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                var aabb = new net.minecraft.world.phys.AABB(
                    chunkX << 4, level.getMinY(), chunkZ << 4,
                    (chunkX << 4) + 16, level.getMaxY(), (chunkZ << 4) + 16
                );
                for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, aabb)) {
                    ItemStack displayed = frame.getItem();
                    if (!displayed.isEmpty() && isBlacklisted(displayed)) {
                        ItemStack removed = displayed.copy();
                        String loc = formatEntityPos(frame);
                        frame.setItem(ItemStack.EMPTY);
                        logObtained(player, removed, loc, "展示框", false);
                        purgeInventoryItems(player, formatEntityPos(player), "展示框联动清包", true);
                        banAfterVerifiedClean(player, removed);
                    }
                }
            }
        }
    }

    /**
     * 记录方块违禁事件（与 logObtained 逻辑一致，但消息不同）
     */
    private static void logBlockViolation(Player player, String blockId, CompoundTag nbt, String location) {
        String loc = location == null || location.isBlank() ? formatEntityPos(player) : location;

        // 如果物品在排除审计列表中，则不写入日志
        if (!ConfigHandler.isExcludedFromLog(blockId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 破坏了黑名单方块 %s | 来源: 世界方块 | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(), blockId, loc);
            try {
                Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                ItemBan.LOGGER.error("日志写入失败", e);
            }
            ItemBan.LOGGER.info("违禁方块 {} | 玩家 {} | 坐标 {}", blockId, player.getName().getString(), loc);
        }

        // 聊天栏公示（可开关）
        if (ConfigHandler.publicAnnounce) {
            String nbtInfo = "";
            if (nbt != null && !nbt.isEmpty()) {
                String nbtStr = nbt.toString();
                if (nbtStr.length() > 60) {
                    nbtStr = nbtStr.substring(0, 57) + "...";
                }
                nbtInfo = " " + nbtStr;
            }

            String message = String.format("§c[ItemBan] §f玩家 §e%s §f因破坏违禁方块 §c%s%s §f已被系统清除！ §7[世界方块 | %s]",
                player.getName().getString(), blockId, nbtInfo, loc);

            if (player.level().getServer() != null) {
                player.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false);
            }
        }

        // 自动封禁功能（与 LnsanesBan 共存时走 BanHelper，避免 /ban 命令被劫持后静默失败）
        if (ConfigHandler.autoBanOnViolation && !ConfigHandler.isExcludedFromLog(blockId) && player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.level().getServer() != null) {
                String banReason = String.format("你因破坏违禁方块（%s）已被永久封禁 如需申诉 请联系服务器管理员。", blockId);
                BanHelper.banAndKick(serverPlayer, banReason);
            }
        }
    }
}

