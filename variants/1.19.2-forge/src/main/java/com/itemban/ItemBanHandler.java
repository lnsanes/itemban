package com.itemban;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public class ItemBanHandler {

    /*
     * 性能优化说明（针对低性能服务器）：
     * - 所有周期扫描间隔不超过 12 ticks，且同一玩家同时只运行一种扫描
     * - 玩家背包扫描每 10 ticks（0.5秒）
     * - 展示框 / 世界方块扫描每 12 ticks（0.6秒）
     * - 掉落物范围扫描每 5 ticks（250ms）
     * - 主线程只做快照，黑名单匹配在后台线程，删除/公示回到主线程
     * - 方块黑名单为空时跳过体素扫描
     * - 模组存在检测缓存为静态布尔值，仅初始化一次
     * - 容器扫描仅在打开事件触发；dropdetect off 时掉落拦截为事件驱动
     * - 创造/OP玩家早期返回，避免不必要检查
     */

    private static final int INV_SCAN_INTERVAL = 10;
    private static final int ENV_SCAN_INTERVAL = 12;
    private static final int DROP_SCAN_INTERVAL = 5;

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

    private static volatile ExecutorService scanExecutor = newScanExecutor();
    private static final ConcurrentHashMap<UUID, PlayerScanState> scanStates = new ConcurrentHashMap<>();

    private enum ScanKind { DROP, INV, ENV }

    private static final class PlayerScanState {
        volatile boolean busy;
        int lastDrop = Integer.MIN_VALUE / 4;
        int lastInv = Integer.MIN_VALUE / 4;
        int lastEnv = Integer.MIN_VALUE / 4;
    }

    private record DropSnapshot(int entityId, ItemStack stack, String loc) {}
    private record InvSnapshot(int slot, boolean carried, ItemStack stack) {}
    private record FrameSnapshot(int entityId, ItemStack stack, String loc) {}
    private record BlockSnapshot(BlockPos pos, String blockId, CompoundTag nbt) {}

    private static ExecutorService newScanExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ItemBan-Scan");
            t.setDaemon(true);
            return t;
        });
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ItemBan.LOGGER.info("ItemBan: 服务器启动，黑名单加载完成");
        scanStates.clear();
        if (scanExecutor == null || scanExecutor.isShutdown()) {
            scanExecutor = newScanExecutor();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        scanStates.clear();
        ExecutorService executor = scanExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.getLevel().isClientSide) return;
        if (!(event.player instanceof ServerPlayer player) || isOpOrCreative(player)) return;

        if (!modsDetected) {
            detectLoadedMods();
        }
        scheduleNextScan(player);
    }

    @SubscribeEvent
    public static void onItemEntitySpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ItemEntity itemEntity)) return;

        if (ConfigHandler.detectDroppedItems) return;

        var throwerId = itemEntity.getThrower();
        if (throwerId != null) {
            var owner = itemEntity.getLevel().getPlayerByUUID(throwerId);
            if (owner != null && isOpOrCreative(owner)) return;
        }

        ItemStack stack = itemEntity.getItem();
        if (isBlacklisted(stack)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity().getLevel().isClientSide) return;
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
        String id = stack.getItem().builtInRegistryHolder().key().location().toString();
        CompoundTag nbt = stack.getTag();
        return ConfigHandler.isBlacklisted(id, nbt);
    }

    private static boolean isOpOrCreative(Player player) {
        if (player.isCreative()) return true;
        if (player instanceof ServerPlayer sp) {
            return sp.hasPermissions(2); // OP level
        }
        return false;
    }

    /** 格式化实体所在维度与方块坐标。 */
    private static String formatEntityPos(Entity entity) {
        if (entity == null) return "未知位置";
        return formatPos(entity.getLevel(), entity.blockPosition());
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
        return key.location().toString();
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
        String itemId = sampleViolation.getItem().builtInRegistryHolder().key().location().toString();
        if (ConfigHandler.isExcludedFromLog(itemId)) {
            return;
        }
        if (player.getServer() == null || player.hasDisconnected()) {
            return;
        }

        // 再扫一遍，确保踢人前背包干净
        if (inventoryHasBlacklisted(player)) {
            ItemBan.LOGGER.warn("封禁前仍检测到违禁物品，进行二次清除 | 玩家 {}", player.getGameProfile().getName());
            purgeInventoryItems(player, formatEntityPos(player), "二次清除", true);
        }

        if (inventoryHasBlacklisted(player)) {
            ItemBan.LOGGER.error("违禁物品未能清除干净，取消踢出以避免物品残留 | 玩家 {}", player.getGameProfile().getName());
            return;
        }

        ItemBan.LOGGER.info("已确认背包无违禁物品，执行封禁踢出 | 玩家 {} | 物品 {}",
                player.getGameProfile().getName(), itemId);
        String banReason = String.format("你因获取违禁物品（%s）已被永久封禁 如需申诉 请联系服务器管理员。", itemId);
        BanHelper.banAndKick(player, banReason);
    }

    /**
     * 记录/公示违禁物品。封禁必须在物品删除并校验之后单独调用 {@link #banAfterVerifiedClean}。
     *
     * @param allowBan 兼容旧调用；为 true 时仍会走“校验后再封禁”（调用方须已先删除物品）
     */
    private static void logObtained(Player player, ItemStack stack, String location, String source, boolean allowBan) {
        String itemId = stack.getItem().builtInRegistryHolder().key().location().toString();
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

            if (stack.hasTag()) {
                String nbtStr = stack.getTag().toString();
                // 限制 NBT 长度，避免消息过长
                if (nbtStr.length() > 60) {
                    nbtStr = nbtStr.substring(0, 57) + "...";
                }
                nbtInfo = " " + nbtStr;
            }

            String message = String.format("§c[ItemBan] §f玩家 §e%s §f因持有违禁物品 §c%s%s §f已被系统清除！ §7[%s | %s]",
                player.getName().getString(), itemId, nbtInfo, src, loc);

            if (player.getLevel().getServer() != null) {
                player.getLevel().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false);
            }
        }

        if (allowBan && player instanceof ServerPlayer serverPlayer) {
            banAfterVerifiedClean(serverPlayer, stack);
        }
    }

    private static void detectLoadedMods() {
        var modList = net.minecraftforge.fml.ModList.get();
        hasSophisticatedBackpacks = modList.isLoaded("sophisticatedbackpacks");
        hasAE2 = modList.isLoaded("ae2");
        hasCreate = modList.isLoaded("create");
        modsDetected = true;

        if (hasSophisticatedBackpacks || hasAE2 || hasCreate) {
            ItemBan.LOGGER.info("ItemBan: 检测到存储模组 - 精妙背包:{}, AE2:{}, Create:{}", 
                hasSophisticatedBackpacks, hasAE2, hasCreate);
        }
    }

    private static void scheduleNextScan(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PlayerScanState state = scanStates.computeIfAbsent(player.getUUID(), id -> new PlayerScanState());
        if (state.busy) {
            return;
        }
        ScanKind kind = nextDueScan(state, player.tickCount);
        if (kind == null) {
            return;
        }
        state.busy = true;
        switch (kind) {
            case DROP -> {
                state.lastDrop = player.tickCount;
                startDroppedItemScan(player, state);
            }
            case INV -> {
                state.lastInv = player.tickCount;
                startInventoryScan(player, state);
            }
            case ENV -> {
                state.lastEnv = player.tickCount;
                startEnvironmentScan(player, state);
            }
        }
    }

    private static ScanKind nextDueScan(PlayerScanState state, int tick) {
        boolean hasItemRules = !ConfigHandler.getBlacklistRules().isEmpty();
        ScanKind best = null;
        int bestOverdue = -1;
        if (ConfigHandler.detectDroppedItems && hasItemRules) {
            int overdue = tick - state.lastDrop;
            if (overdue >= DROP_SCAN_INTERVAL && overdue > bestOverdue) {
                best = ScanKind.DROP;
                bestOverdue = overdue;
            }
        }
        if (hasItemRules) {
            int overdue = tick - state.lastInv;
            if (overdue >= INV_SCAN_INTERVAL && overdue > bestOverdue) {
                best = ScanKind.INV;
                bestOverdue = overdue;
            }
        }
        boolean envNeeded = hasItemRules || (ConfigHandler.detectWorldBlocks && ConfigHandler.hasBlockBlacklist());
        if (envNeeded) {
            int overdue = tick - state.lastEnv;
            if (overdue >= ENV_SCAN_INTERVAL && overdue > bestOverdue) {
                best = ScanKind.ENV;
            }
        }
        return best;
    }

    private static void finishScan(PlayerScanState state) {
        if (state != null) {
            state.busy = false;
        }
    }

    private static void submitMatch(MinecraftServer server, PlayerScanState state, Runnable matchWork) {
        ExecutorService executor = scanExecutor;
        if (executor == null || executor.isShutdown()) {
            finishScan(state);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    matchWork.run();
                } catch (Throwable t) {
                    ItemBan.LOGGER.warn("ItemBan: 后台扫描失败: {}", t.toString());
                    server.execute(() -> finishScan(state));
                }
            });
        } catch (RejectedExecutionException ignored) {
            finishScan(state);
        }
    }

    private static void startInventoryScan(ServerPlayer player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        List<InvSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                snapshots.add(new InvSnapshot(i, false, stack.copy()));
            }
        }
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (!carried.isEmpty()) {
                snapshots.add(new InvSnapshot(-1, true, carried.copy()));
            }
        }
        if (snapshots.isEmpty()) {
            finishScan(state);
            return;
        }
        UUID playerId = player.getUUID();
        List<ConfigHandler.BlacklistRule> rules = List.copyOf(ConfigHandler.getBlacklistRules());
        submitMatch(server, state, () -> {
            List<InvSnapshot> hits = new ArrayList<>();
            for (InvSnapshot shot : snapshots) {
                if (matchesRules(shot.stack(), rules)) {
                    hits.add(shot);
                }
            }
            if (hits.isEmpty()) {
                finishScan(state);
                return;
            }
            server.execute(() -> {
                try {
                    applyInventoryHits(server, playerId, hits);
                } finally {
                    finishScan(state);
                }
            });
        });
    }

    private static void applyInventoryHits(MinecraftServer server, UUID playerId, List<InvSnapshot> hits) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        ItemStack firstViolation = ItemStack.EMPTY;
        for (InvSnapshot hit : hits) {
            ItemStack live;
            if (hit.carried()) {
                if (player.containerMenu == null) {
                    continue;
                }
                live = player.containerMenu.getCarried();
                if (live.isEmpty() || !isBlacklisted(live)) {
                    continue;
                }
                if (firstViolation.isEmpty()) {
                    firstViolation = live.copy();
                }
                ItemStack removed = live.copy();
                player.containerMenu.setCarried(ItemStack.EMPTY);
                logObtained(player, removed, formatEntityPos(player), "背包", false);
            } else {
                live = player.getInventory().getItem(hit.slot());
                if (live.isEmpty() || !isBlacklisted(live)) {
                    continue;
                }
                if (firstViolation.isEmpty()) {
                    firstViolation = live.copy();
                }
                ItemStack removed = live.copy();
                player.getInventory().setItem(hit.slot(), ItemStack.EMPTY);
                logObtained(player, removed, formatEntityPos(player), "背包", false);
            }
        }
        if (!firstViolation.isEmpty()) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            banAfterVerifiedClean(player, firstViolation);
        }
    }

    /**
     * 扫描玩家周围掉落物（仅在 detectDroppedItems = true 时调用）。
     * 主线程只做实体快照；黑名单匹配在后台线程；删除/公示/封禁回到主线程。
     */
    private static void startDroppedItemScan(ServerPlayer player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        var level = player.getLevel();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        var aabb = new net.minecraft.world.phys.AABB(
            (centerChunkX - 2) << 4, level.getMinBuildHeight(), (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, level.getMaxBuildHeight(), ((centerChunkZ + 2) << 4) + 16
        );

        List<DropSnapshot> snapshots = new ArrayList<>();
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, aabb)) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                snapshots.add(new DropSnapshot(itemEntity.getId(), stack.copy(), formatEntityPos(itemEntity)));
            }
        }
        if (snapshots.isEmpty()) {
            finishScan(state);
            return;
        }

        List<ConfigHandler.BlacklistRule> rules = List.copyOf(ConfigHandler.getBlacklistRules());
        UUID playerId = player.getUUID();
        submitMatch(server, state, () -> {
            List<DropSnapshot> hits = new ArrayList<>();
            for (DropSnapshot shot : snapshots) {
                if (matchesRules(shot.stack(), rules)) {
                    hits.add(shot);
                }
            }
            if (hits.isEmpty()) {
                finishScan(state);
                return;
            }
            server.execute(() -> {
                try {
                    applyDroppedItemHits(server, playerId, hits);
                } finally {
                    finishScan(state);
                }
            });
        });
    }

    private static boolean matchesRules(ItemStack stack, List<ConfigHandler.BlacklistRule> rules) {
        String id = stack.getItem().builtInRegistryHolder().key().location().toString();
        CompoundTag nbt = stack.getTag();
        for (ConfigHandler.BlacklistRule rule : rules) {
            if (rule.matches(id, nbt)) {
                return true;
            }
        }
        return false;
    }

    private static void applyDroppedItemHits(MinecraftServer server, UUID playerId, List<DropSnapshot> hits) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        var level = player.getLevel();
        ItemStack firstViolation = ItemStack.EMPTY;
        for (DropSnapshot hit : hits) {
            Entity entity = level.getEntity(hit.entityId());
            if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive()) {
                continue;
            }
            ItemStack live = itemEntity.getItem();
            if (live.isEmpty() || !isBlacklisted(live)) {
                continue;
            }
            if (firstViolation.isEmpty()) {
                firstViolation = live.copy();
            }
            ItemStack removed = live.copy();
            itemEntity.discard();
            logObtained(player, removed, hit.loc(), "掉落物", false);
        }
        if (!firstViolation.isEmpty()) {
            purgeInventoryItems(player, formatEntityPos(player), "掉落物联动清包", true);
            banAfterVerifiedClean(player, firstViolation);
        }
    }

    /**
     * 扫描玩家周围 2 个 chunk 范围内的物品展示框和违禁方块。
     * 主线程快照，后台匹配，主线程清除。
     */
    private static void startEnvironmentScan(ServerPlayer player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        var level = player.getLevel();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int playerY = player.getBlockY();
        var aabb = new net.minecraft.world.phys.AABB(
            (centerChunkX - 2) << 4, level.getMinBuildHeight(), (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, level.getMaxBuildHeight(), ((centerChunkZ + 2) << 4) + 16
        );

        List<FrameSnapshot> frames = new ArrayList<>();
        if (!ConfigHandler.getBlacklistRules().isEmpty()) {
            for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, aabb)) {
                ItemStack displayed = frame.getItem();
                if (!displayed.isEmpty()) {
                    frames.add(new FrameSnapshot(frame.getId(), displayed.copy(), formatEntityPos(frame)));
                }
            }
        }

        List<BlockSnapshot> blocks = new ArrayList<>();
        if (ConfigHandler.detectWorldBlocks && ConfigHandler.hasBlockBlacklist()) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                        for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                            for (int y = Math.max(playerY - 16, level.getMinBuildHeight());
                                 y <= Math.min(playerY + 16, level.getMaxBuildHeight()); y++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                BlockState blockState = level.getBlockState(pos);
                                if (blockState.isAir()) {
                                    continue;
                                }
                                String blockId = blockState.getBlock().builtInRegistryHolder().key().location().toString();
                                if (!ConfigHandler.isBlockIdTracked(blockId)) {
                                    continue;
                                }
                                CompoundTag blockNbt = null;
                                var blockEntity = level.getBlockEntity(pos);
                                if (blockEntity != null && ConfigHandler.blockIdNeedsNbt(blockId)) {
                                    blockNbt = blockEntity.saveWithFullMetadata();
                                }
                                blocks.add(new BlockSnapshot(pos, blockId, blockNbt == null ? null : blockNbt.copy()));
                            }
                        }
                    }
                }
            }
        }

        if (frames.isEmpty() && blocks.isEmpty()) {
            finishScan(state);
            return;
        }

        List<ConfigHandler.BlacklistRule> itemRules = List.copyOf(ConfigHandler.getBlacklistRules());
        List<ConfigHandler.BlacklistRule> blockRules = List.copyOf(ConfigHandler.getBlockBlacklistRules());
        UUID playerId = player.getUUID();
        submitMatch(server, state, () -> {
            List<FrameSnapshot> frameHits = new ArrayList<>();
            for (FrameSnapshot shot : frames) {
                if (matchesRules(shot.stack(), itemRules)) {
                    frameHits.add(shot);
                }
            }
            List<BlockSnapshot> blockHits = new ArrayList<>();
            for (BlockSnapshot shot : blocks) {
                if (matchesBlockRules(shot.blockId(), shot.nbt(), blockRules)) {
                    blockHits.add(shot);
                }
            }
            if (frameHits.isEmpty() && blockHits.isEmpty()) {
                finishScan(state);
                return;
            }
            server.execute(() -> {
                try {
                    applyEnvironmentHits(server, playerId, frameHits, blockHits);
                } finally {
                    finishScan(state);
                }
            });
        });
    }

    private static boolean matchesBlockRules(String blockId, CompoundTag nbt, List<ConfigHandler.BlacklistRule> rules) {
        for (ConfigHandler.BlacklistRule rule : rules) {
            if (rule.matches(blockId, nbt)) {
                return true;
            }
        }
        return false;
    }

    private static void applyEnvironmentHits(MinecraftServer server, UUID playerId,
                                             List<FrameSnapshot> frames, List<BlockSnapshot> blocks) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        var level = player.getLevel();
        ItemStack firstItem = ItemStack.EMPTY;
        for (FrameSnapshot hit : frames) {
            Entity entity = level.getEntity(hit.entityId());
            if (!(entity instanceof ItemFrame frame) || !frame.isAlive()) {
                continue;
            }
            ItemStack live = frame.getItem();
            if (live.isEmpty() || !isBlacklisted(live)) {
                continue;
            }
            if (firstItem.isEmpty()) {
                firstItem = live.copy();
            }
            ItemStack removed = live.copy();
            frame.setItem(ItemStack.EMPTY);
            logObtained(player, removed, hit.loc(), "展示框", false);
        }
        for (BlockSnapshot hit : blocks) {
            BlockState liveState = level.getBlockState(hit.pos());
            if (liveState.isAir()) {
                continue;
            }
            String liveId = liveState.getBlock().builtInRegistryHolder().key().location().toString();
            if (!hit.blockId().equals(liveId)) {
                continue;
            }
            CompoundTag liveNbt = null;
            var blockEntity = level.getBlockEntity(hit.pos());
            if (blockEntity != null && ConfigHandler.blockIdNeedsNbt(liveId)) {
                liveNbt = blockEntity.saveWithFullMetadata();
            }
            if (!ConfigHandler.isBlockBlacklisted(liveId, liveNbt)) {
                continue;
            }
            level.setBlock(hit.pos(), Blocks.AIR.defaultBlockState(), 3);
            logBlockViolation(player, liveId, liveNbt, formatPos(level, hit.pos()));
            if (player.hasDisconnected()) {
                return;
            }
        }
        if (!firstItem.isEmpty()) {
            purgeInventoryItems(player, formatEntityPos(player), "展示框联动清包", true);
            banAfterVerifiedClean(player, firstItem);
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

            if (player.getLevel().getServer() != null) {
                player.getLevel().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false);
            }
        }

        // 自动封禁功能（与 LnsanesBan 共存时走 BanHelper，避免 /ban 命令被劫持后静默失败）
        if (ConfigHandler.autoBanOnViolation && !ConfigHandler.isExcludedFromLog(blockId) && player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.getServer() != null) {
                String banReason = String.format("你因破坏违禁方块（%s）已被永久封禁 如需申诉 请联系服务器管理员。", blockId);
                BanHelper.banAndKick(serverPlayer, banReason);
            }
        }
    }
}
