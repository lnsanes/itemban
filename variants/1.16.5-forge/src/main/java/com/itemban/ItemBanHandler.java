package com.itemban;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.item.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.IChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final class DropSnapshot {
        final int entityId;
        final ItemStack stack;
        final String loc;
        DropSnapshot(int entityId, ItemStack stack, String loc) {
            this.entityId = entityId;
            this.stack = stack;
            this.loc = loc;
        }
    }
    private static final class InvSnapshot {
        final int slot;
        final boolean carried;
        final ItemStack stack;
        InvSnapshot(int slot, boolean carried, ItemStack stack) {
            this.slot = slot;
            this.carried = carried;
            this.stack = stack;
        }
    }
    private static final class FrameSnapshot {
        final int entityId;
        final ItemStack stack;
        final String loc;
        FrameSnapshot(int entityId, ItemStack stack, String loc) {
            this.entityId = entityId;
            this.stack = stack;
            this.loc = loc;
        }
    }
    private static final class BlockSnapshot {
        final BlockPos pos;
        final String blockId;
        final CompoundNBT nbt;
        BlockSnapshot(BlockPos pos, String blockId, CompoundNBT nbt) {
            this.pos = pos;
            this.blockId = blockId;
            this.nbt = nbt;
        }
    }

    private static ExecutorService newScanExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ItemBan-Scan");
            t.setDaemon(true);
            return t;
        });
    }

    @SubscribeEvent
    public static void onServerStarting(FMLServerStartingEvent event) {
        ItemBan.LOGGER.info("ItemBan: 服务器启动，黑名单加载完成");
        scanStates.clear();
        if (scanExecutor == null || scanExecutor.isShutdown()) {
            scanExecutor = newScanExecutor();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        scanStates.clear();
        ExecutorService executor = scanExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level.isClientSide) return;
        if (!(event.player instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.player;
        if (isOpOrCreative(player)) return;

        if (!modsDetected) {
            detectLoadedMods();
        }
        scheduleNextScan(player);
    }

    @SubscribeEvent
    public static void onItemEntitySpawn(EntityJoinWorldEvent event) {
        if (event.getWorld().isClientSide || !(event.getEntity() instanceof ItemEntity)) return;

        ItemEntity itemEntity = (ItemEntity) event.getEntity();
        if (ConfigHandler.detectDroppedItems) return;

        java.util.UUID throwerId = itemEntity.getThrower();
        if (throwerId != null) {
            PlayerEntity owner = itemEntity.level.getPlayerByUUID(throwerId);
            if (owner != null && isOpOrCreative(owner)) return;
        }

        ItemStack stack = itemEntity.getItem();
        if (isBlacklisted(stack)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getPlayer().level.isClientSide) return;
        PlayerEntity player = event.getPlayer();
        if (isOpOrCreative(player)) return;

        // 先清空容器内违禁物品并记录，全部处理完后再封禁。
        // 跳过过滤/样板等幽灵槽，避免把配置用 ItemStack 当成真实持有。
        Container menu = event.getContainer();
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
        if (found && player instanceof ServerPlayerEntity) {
            menu.broadcastChanges();
            banAfterVerifiedClean((ServerPlayerEntity) player, firstViolation);
        }
    }

    /**
     * Create 过滤槽、AE2 FakeSlot / 样板标记槽等：GUI 里显示的是配置用幽灵物品，
     * 玩家并不能真正取出。这些槽不得删除、不得记为持有、不得封禁。
     */
    private static boolean isConfigGhostSlot(Slot slot, PlayerEntity player) {
        if (slot == null) {
            return true;
        }
        // 玩家真实背包槽始终检查
        if (player != null && slot.container == player.inventory) {
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
        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        CompoundNBT nbt = stack.getTag();
        return ConfigHandler.isBlacklisted(id, nbt);
    }

    private static boolean isOpOrCreative(PlayerEntity player) {
        if (player.isCreative()) return true;
        if (player instanceof ServerPlayerEntity) {
            return player.hasPermissions(2);
        }
        return false;
    }

    /** 格式化实体所在维度与方块坐标。 */
    private static String formatEntityPos(Entity entity) {
        if (entity == null) return "未知位置";
        return formatPos(entity.level, entity.blockPosition());
    }

    /** 格式化维度 + 方块坐标，如：主世界 @ 100, 64, -20 */
    private static String formatPos(World level, BlockPos pos) {
        if (pos == null) return "未知位置";
        String dim = formatDimension(level);
        return String.format("%s @ %d, %d, %d", dim, pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatDimension(World level) {
        if (level == null) return "未知维度";
        RegistryKey<World> key = level.dimension();
        if (key == World.OVERWORLD) return "主世界";
        if (key == World.NETHER) return "下界";
        if (key == World.END) return "末地";
        return key.location().toString();
    }

    /**
     * 删除背包内所有黑名单物品并写日志/公示（不封禁）。
     *
     * @return 第一个被清除的物品副本；若无则返回 EMPTY
     */
    private static ItemStack purgeInventoryItems(PlayerEntity player, String location, String source, boolean sync) {
        ItemStack firstViolation = ItemStack.EMPTY;
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            ItemStack stack = player.inventory.getItem(i);
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                if (firstViolation.isEmpty()) {
                    firstViolation = stack.copy();
                }
                // 先删再记，避免封禁踢人打断删除
                ItemStack removed = stack.copy();
                player.inventory.setItem(i, ItemStack.EMPTY);
                logObtained(player, removed, location, source, false);
            }
        }

        // 光标上正在拖拽的物品也要清
        ItemStack carried = player.inventory.getCarried();
        if (!carried.isEmpty() && isBlacklisted(carried)) {
            if (firstViolation.isEmpty()) {
                firstViolation = carried.copy();
            }
            ItemStack removed = carried.copy();
            player.inventory.setCarried(ItemStack.EMPTY);
            logObtained(player, removed, location, source, false);
        }

        if (!firstViolation.isEmpty() && sync && player instanceof ServerPlayerEntity) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            player.inventory.setChanged();
            serverPlayer.inventoryMenu.broadcastChanges();
            serverPlayer.containerMenu.broadcastChanges();
        }
        return firstViolation;
    }

    private static boolean inventoryHasBlacklisted(PlayerEntity player) {
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            ItemStack stack = player.inventory.getItem(i);
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                return true;
            }
        }
        ItemStack carried = player.inventory.getCarried();
        if (!carried.isEmpty() && isBlacklisted(carried)) {
            return true;
        }
        return false;
    }

    /**
     * 校验背包已无违禁物后才封禁踢出；若仍残留则再清一次，仍失败则不踢并打错误日志。
     */
    private static void banAfterVerifiedClean(ServerPlayerEntity player, ItemStack sampleViolation) {
        if (!ConfigHandler.autoBanOnViolation || sampleViolation == null || sampleViolation.isEmpty()) {
            return;
        }
        String itemId = ForgeRegistries.ITEMS.getKey(sampleViolation.getItem()).toString();
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
    private static void logObtained(PlayerEntity player, ItemStack stack, String location, String source, boolean allowBan) {
        String itemId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        String loc = location == null || location.trim().isEmpty() ? formatEntityPos(player) : location;
        String src = source == null || source.trim().isEmpty() ? "未知" : source;

        // 如果物品在排除审计列表中，则不写入日志
        if (!ConfigHandler.isExcludedFromLog(itemId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 获得了黑名单物品 %s (数量: %d) | 来源: %s | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(),
                itemId, stack.getCount(), src, loc);
            try {
                Files.write(logFile, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

            if (player.level.getServer() != null) {
                player.level.getServer().getPlayerList().broadcastMessage(
                    new StringTextComponent(message), ChatType.SYSTEM, Util.NIL_UUID);
            }
        }

        if (allowBan && player instanceof ServerPlayerEntity) {
            banAfterVerifiedClean((ServerPlayerEntity) player, stack);
        }
    }

    private static void detectLoadedMods() {
        net.minecraftforge.fml.ModList modList = net.minecraftforge.fml.ModList.get();
        modsDetected = true;
        ItemBan.LOGGER.info("ItemBan: 检测到存储模组 - 精妙背包:{}, AE2:{}, Create:{}",
            modList.isLoaded("sophisticatedbackpacks"),
            modList.isLoaded("ae2"),
            modList.isLoaded("create"));
    }

    private static void scheduleNextScan(ServerPlayerEntity player) {
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
            case DROP:
                state.lastDrop = player.tickCount;
                startDroppedItemScan(player, state);
                break;
            case INV:
                state.lastInv = player.tickCount;
                startInventoryScan(player, state);
                break;
            case ENV:
                state.lastEnv = player.tickCount;
                startEnvironmentScan(player, state);
                break;
        }
    }

    private static ScanKind nextDueScan(PlayerScanState state, int tick) {
        boolean hasItemRules = ConfigHandler.hasItemBlacklist();
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

    private static void startInventoryScan(ServerPlayerEntity player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        List<InvSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            ItemStack stack = player.inventory.getItem(i);
            if (!stack.isEmpty()) {
                snapshots.add(new InvSnapshot(i, false, stack.copy()));
            }
        }
        ItemStack carried = player.inventory.getCarried();
        if (!carried.isEmpty()) {
            snapshots.add(new InvSnapshot(-1, true, carried.copy()));
        }
        if (snapshots.isEmpty()) {
            finishScan(state);
            return;
        }
        UUID playerId = player.getUUID();
        List<ConfigHandler.BlacklistRule> rules = ConfigHandler.getBlacklistRules();
        submitMatch(server, state, () -> {
            List<InvSnapshot> hits = new ArrayList<>();
            for (InvSnapshot shot : snapshots) {
                if (matchesRules(shot.stack, rules)) {
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
        ServerPlayerEntity player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        ItemStack firstViolation = ItemStack.EMPTY;
        for (InvSnapshot hit : hits) {
            ItemStack live;
            if (hit.carried) {
                live = player.inventory.getCarried();
                if (live.isEmpty() || !isBlacklisted(live)) {
                    continue;
                }
                if (firstViolation.isEmpty()) {
                    firstViolation = live.copy();
                }
                ItemStack removed = live.copy();
                player.inventory.setCarried(ItemStack.EMPTY);
                logObtained(player, removed, formatEntityPos(player), "背包", false);
            } else {
                live = player.inventory.getItem(hit.slot);
                if (live.isEmpty() || !isBlacklisted(live)) {
                    continue;
                }
                if (firstViolation.isEmpty()) {
                    firstViolation = live.copy();
                }
                ItemStack removed = live.copy();
                player.inventory.setItem(hit.slot, ItemStack.EMPTY);
                logObtained(player, removed, formatEntityPos(player), "背包", false);
            }
        }
        if (!firstViolation.isEmpty()) {
            player.inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
            ((ServerPlayerEntity) player).containerMenu.broadcastChanges();
            banAfterVerifiedClean(player, firstViolation);
        }
    }

    /**
     * 扫描玩家周围掉落物（仅在 detectDroppedItems = true 时调用）。
     * 主线程只做实体快照；黑名单匹配在后台线程；删除/公示/封禁回到主线程。
     */
    private static void startDroppedItemScan(ServerPlayerEntity player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        World level = player.level;
        int centerChunkX = player.xChunk;
        int centerChunkZ = player.zChunk;
        AxisAlignedBB aabb = new AxisAlignedBB(
            (centerChunkX - 2) << 4, 0, (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, 256, ((centerChunkZ + 2) << 4) + 16
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

        List<ConfigHandler.BlacklistRule> rules = ConfigHandler.getBlacklistRules();
        UUID playerId = player.getUUID();
        submitMatch(server, state, () -> {
            List<DropSnapshot> hits = new ArrayList<>();
            for (DropSnapshot shot : snapshots) {
                if (matchesRules(shot.stack, rules)) {
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
        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        CompoundNBT nbt = stack.getTag();
        for (ConfigHandler.BlacklistRule rule : rules) {
            if (rule.matches(id, nbt)) {
                return true;
            }
        }
        return false;
    }

    private static void applyDroppedItemHits(MinecraftServer server, UUID playerId, List<DropSnapshot> hits) {
        ServerPlayerEntity player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        World level = player.level;
        ItemStack firstViolation = ItemStack.EMPTY;
        for (DropSnapshot hit : hits) {
            Entity entity = level.getEntity(hit.entityId);
            if (!(entity instanceof ItemEntity)) {
                continue;
            }
            ItemEntity itemEntity = (ItemEntity) entity;
            if (!itemEntity.isAlive()) {
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
            itemEntity.remove();
            logObtained(player, removed, hit.loc, "掉落物", false);
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
    private static void startEnvironmentScan(ServerPlayerEntity player, PlayerScanState state) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            finishScan(state);
            return;
        }
        World level = player.level;
        int centerChunkX = player.xChunk;
        int centerChunkZ = player.zChunk;
        int playerY = player.blockPosition().getY();
        AxisAlignedBB aabb = new AxisAlignedBB(
            (centerChunkX - 2) << 4, 0, (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, 256, ((centerChunkZ + 2) << 4) + 16
        );

        List<FrameSnapshot> frames = new ArrayList<>();
        if (ConfigHandler.hasItemBlacklist()) {
            for (ItemFrameEntity frame : level.getEntitiesOfClass(ItemFrameEntity.class, aabb)) {
                ItemStack displayed = frame.getItem();
                if (!displayed.isEmpty()) {
                    frames.add(new FrameSnapshot(frame.getId(), displayed.copy(), formatEntityPos(frame)));
                }
            }
        }

        List<BlockSnapshot> blocks = new ArrayList<>();
        if (ConfigHandler.detectWorldBlocks && ConfigHandler.hasBlockBlacklist()) {
            BlockPos.Mutable cursor = new BlockPos.Mutable();
            int minY = Math.max(playerY - 16, 0);
            int maxY = Math.min(playerY + 16, 255);
            int minSection = minY >> 4;
            int maxSection = maxY >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    IChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                    if (chunk == null) {
                        continue;
                    }
                    ChunkSection[] sections = chunk.getSections();
                    for (int sec = minSection; sec <= maxSection; sec++) {
                        if (sec < 0 || sec >= sections.length) {
                            continue;
                        }
                        ChunkSection section = sections[sec];
                        if (section == null || section.isEmpty()) {
                            continue;
                        }
                        int baseY = sec << 4;
                        int yStart = Math.max(minY, baseY);
                        int yEnd = Math.min(maxY, baseY + 15);
                        int originX = chunkX << 4;
                        int originZ = chunkZ << 4;
                        for (int x = originX; x < originX + 16; x++) {
                            for (int z = originZ; z < originZ + 16; z++) {
                                for (int y = yStart; y <= yEnd; y++) {
                                    BlockState blockState = chunk.getBlockState(cursor.set(x, y, z));
                                    if (blockState.isAir()) {
                                        continue;
                                    }
                                    if (!ConfigHandler.isTrackedBlock(blockState.getBlock())) {
                                        continue;
                                    }
                                    String blockId = ForgeRegistries.BLOCKS.getKey(blockState.getBlock()).toString();
                                    CompoundNBT blockNbt = null;
                                    TileEntity blockEntity = level.getBlockEntity(cursor);
                                    if (blockEntity != null && ConfigHandler.blockIdNeedsNbt(blockId)) {
                                        blockNbt = blockEntity.save(new CompoundNBT());
                                    }
                                    blocks.add(new BlockSnapshot(new BlockPos(cursor), blockId, blockNbt == null ? null : blockNbt.copy()));
                                }
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

        List<ConfigHandler.BlacklistRule> itemRules = ConfigHandler.getBlacklistRules();
        List<ConfigHandler.BlacklistRule> blockRules = ConfigHandler.getBlockBlacklistRules();
        UUID playerId = player.getUUID();
        submitMatch(server, state, () -> {
            List<FrameSnapshot> frameHits = new ArrayList<>();
            for (FrameSnapshot shot : frames) {
                if (matchesRules(shot.stack, itemRules)) {
                    frameHits.add(shot);
                }
            }
            List<BlockSnapshot> blockHits = new ArrayList<>();
            for (BlockSnapshot shot : blocks) {
                if (matchesBlockRules(shot.blockId, shot.nbt, blockRules)) {
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

    private static boolean matchesBlockRules(String blockId, CompoundNBT nbt, List<ConfigHandler.BlacklistRule> rules) {
        for (ConfigHandler.BlacklistRule rule : rules) {
            if (rule.matches(blockId, nbt)) {
                return true;
            }
        }
        return false;
    }

    private static void applyEnvironmentHits(MinecraftServer server, UUID playerId,
                                             List<FrameSnapshot> frames, List<BlockSnapshot> blocks) {
        ServerPlayerEntity player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            return;
        }
        World level = player.level;
        ItemStack firstItem = ItemStack.EMPTY;
        for (FrameSnapshot hit : frames) {
            Entity entity = level.getEntity(hit.entityId);
            if (!(entity instanceof ItemFrameEntity)) {
                continue;
            }
            ItemFrameEntity frame = (ItemFrameEntity) entity;
            if (!frame.isAlive()) {
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
            logObtained(player, removed, hit.loc, "展示框", false);
        }
        for (BlockSnapshot hit : blocks) {
            BlockState liveState = level.getBlockState(hit.pos);
            if (liveState.isAir()) {
                continue;
            }
            String liveId = ForgeRegistries.BLOCKS.getKey(liveState.getBlock()).toString();
            if (!hit.blockId.equals(liveId)) {
                continue;
            }
            CompoundNBT liveNbt = null;
            TileEntity blockEntity = level.getBlockEntity(hit.pos);
            if (blockEntity != null && ConfigHandler.blockIdNeedsNbt(liveId)) {
                liveNbt = blockEntity.save(new CompoundNBT());
            }
            if (!ConfigHandler.isBlockBlacklisted(liveId, liveNbt)) {
                continue;
            }
            level.setBlock(hit.pos, Blocks.AIR.defaultBlockState(), 3);
            logBlockViolation(player, liveId, liveNbt, formatPos(level, hit.pos));
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
    private static void logBlockViolation(PlayerEntity player, String blockId, CompoundNBT nbt, String location) {
        String loc = location == null || location.trim().isEmpty() ? formatEntityPos(player) : location;

        // 如果物品在排除审计列表中，则不写入日志
        if (!ConfigHandler.isExcludedFromLog(blockId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 破坏了黑名单方块 %s | 来源: 世界方块 | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(), blockId, loc);
            try {
                Files.write(logFile, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

            if (player.level.getServer() != null) {
                player.level.getServer().getPlayerList().broadcastMessage(
                    new StringTextComponent(message), ChatType.SYSTEM, Util.NIL_UUID);
            }
        }

        // 自动封禁功能（与 LnsanesBan 共存时走 BanHelper，避免 /ban 命令被劫持后静默失败）
        if (ConfigHandler.autoBanOnViolation && !ConfigHandler.isExcludedFromLog(blockId) && player instanceof ServerPlayerEntity) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            if (serverPlayer.getServer() != null) {
                String banReason = String.format("你因破坏违禁方块（%s）已被永久封禁 如需申诉 请联系服务器管理员。", blockId);
                BanHelper.banAndKick(serverPlayer, banReason);
            }
        }
    }
}
