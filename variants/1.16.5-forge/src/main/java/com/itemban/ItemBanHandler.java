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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ItemBan.MODID)
public class ItemBanHandler {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static Path LOG_DIR = Paths.get("logs", "ItemBan");

    static {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException ignored) {}
    }

    private static boolean hasSophisticatedBackpacks = false;
    private static boolean hasAE2 = false;
    private static boolean hasCreate = false;
    private static boolean modsDetected = false;

    @SubscribeEvent
    public static void onServerStarting(FMLServerStartingEvent event) {
        ItemBan.LOGGER.info("ItemBan: 服务器启动，黑名单加载完成");
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level.isClientSide) return;

        PlayerEntity player = event.player;
        if (isOpOrCreative(player)) return;

        if (player.tickCount % 10 != 0) return;

        if (!modsDetected) {
            detectLoadedMods();
        }

        if (player instanceof ServerPlayerEntity) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            purgeInventoryThenMaybeBan(serverPlayer, "背包");
            if (serverPlayer.hasDisconnected()) {
                return;
            }
        } else {
            purgeInventoryItems(player, formatEntityPos(player), "背包", false);
        }

        if (player.tickCount % 40 == 0 && player instanceof ServerPlayerEntity && !isOpOrCreative(player)) {
            scanNearbyChunks((ServerPlayerEntity) player);
        }

        if (ConfigHandler.detectDroppedItems && player.tickCount % 2 == 0
                && player instanceof ServerPlayerEntity && !isOpOrCreative(player)) {
            scanDroppedItemsNearby((ServerPlayerEntity) player);
        }
    }

    @SubscribeEvent
    public static void onItemEntitySpawn(EntityJoinWorldEvent event) {
        if (event.getWorld().isClientSide || !(event.getEntity() instanceof ItemEntity)) return;

        ItemEntity itemEntity = (ItemEntity) event.getEntity();
        if (ConfigHandler.detectDroppedItems) return;

        UUID throwerId = itemEntity.getThrower();
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
                slot.set(ItemStack.EMPTY);
                logObtained(player, removed, formatEntityPos(player), "容器", false);
            }
        }
        if (found && player instanceof ServerPlayerEntity) {
            menu.broadcastChanges();
            banAfterVerifiedClean((ServerPlayerEntity) player, firstViolation);
        }
    }

    private static boolean isConfigGhostSlot(Slot slot, PlayerEntity player) {
        if (slot == null) {
            return true;
        }
        if (player != null && slot.container == player.inventory) {
            return false;
        }
        try {
            if (!slot.mayPickup(player)) {
                return true;
            }
        } catch (Throwable ignored) {
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

    private static String formatEntityPos(Entity entity) {
        if (entity == null) return "未知位置";
        return formatPos(entity.level, entity.blockPosition());
    }

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

    private static void purgeInventoryThenMaybeBan(ServerPlayerEntity player, String source) {
        ItemStack first = purgeInventoryItems(player, formatEntityPos(player), source, true);
        if (first.isEmpty()) {
            return;
        }
        banAfterVerifiedClean(player, first);
    }

    private static ItemStack purgeInventoryItems(PlayerEntity player, String location, String source, boolean sync) {
        ItemStack firstViolation = ItemStack.EMPTY;
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            ItemStack stack = player.inventory.getItem(i);
            if (!stack.isEmpty() && isBlacklisted(stack)) {
                if (firstViolation.isEmpty()) {
                    firstViolation = stack.copy();
                }
                ItemStack removed = stack.copy();
                player.inventory.setItem(i, ItemStack.EMPTY);
                logObtained(player, removed, location, source, false);
            }
        }

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
        return !carried.isEmpty() && isBlacklisted(carried);
    }

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

    private static void logObtained(PlayerEntity player, ItemStack stack, String location, String source, boolean allowBan) {
        String itemId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        String loc = location == null || location.trim().isEmpty() ? formatEntityPos(player) : location;
        String src = source == null || source.trim().isEmpty() ? "未知" : source;

        if (!ConfigHandler.isExcludedFromLog(itemId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 获得了黑名单物品 %s (数量: %d) | 来源: %s | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(),
                itemId, stack.getCount(), src, loc);
            try {
                Files.write(logFile, entry.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                ItemBan.LOGGER.error("日志写入失败", e);
            }
            ItemBan.LOGGER.info("违禁物品 {} | 玩家 {} | 来源 {} | 坐标 {}", itemId, player.getName().getString(), src, loc);
        }

        if (ConfigHandler.publicAnnounce) {
            String nbtInfo = "";

            if (stack.hasTag()) {
                String nbtStr = stack.getTag().toString();
                if (nbtStr.length() > 60) {
                    nbtStr = nbtStr.substring(0, 57) + "...";
                }
                nbtInfo = " " + nbtStr;
            }

            String message = String.format("§c[ItemBan] §f玩家 §e%s §f因持有违禁物品 §c%s%s §f已被系统清除！ §7[%s | %s]",
                player.getName().getString(), itemId, nbtInfo, src, loc);

            if (player.level.getServer() != null) {
                player.level.getServer().getPlayerList().broadcastMessage(
                    new StringTextComponent(message),
                    ChatType.SYSTEM,
                    Util.NIL_UUID);
            }
        }

        if (allowBan && player instanceof ServerPlayerEntity) {
            banAfterVerifiedClean((ServerPlayerEntity) player, stack);
        }
    }

    private static void detectLoadedMods() {
        net.minecraftforge.fml.ModList modList = net.minecraftforge.fml.ModList.get();
        hasSophisticatedBackpacks = modList.isLoaded("sophisticatedbackpacks");
        hasAE2 = modList.isLoaded("ae2");
        hasCreate = modList.isLoaded("create");
        modsDetected = true;

        if (hasSophisticatedBackpacks || hasAE2 || hasCreate) {
            ItemBan.LOGGER.info("ItemBan: 检测到存储模组 - 精妙背包:{}, AE2:{}, Create:{}",
                hasSophisticatedBackpacks, hasAE2, hasCreate);
        }
    }

    private static void scanDroppedItemsNearby(ServerPlayerEntity player) {
        World level = player.level;
        int centerChunkX = player.xChunk;
        int centerChunkZ = player.zChunk;

        AxisAlignedBB aabb = new AxisAlignedBB(
            (centerChunkX - 2) << 4, 0, (centerChunkZ - 2) << 4,
            ((centerChunkX + 2) << 4) + 16, 256, ((centerChunkZ + 2) << 4) + 16
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
                itemEntity.remove();
                logObtained(player, removed, loc, "掉落物", false);
            }
        }
        if (!firstViolation.isEmpty()) {
            purgeInventoryItems(player, formatEntityPos(player), "掉落物联动清包", true);
            banAfterVerifiedClean(player, firstViolation);
        }
    }

    private static void scanNearbyChunks(ServerPlayerEntity player) {
        if (!ConfigHandler.detectWorldBlocks) {
            scanItemFramesOnly(player);
            return;
        }

        World level = player.level;
        int centerChunkX = player.xChunk;
        int centerChunkZ = player.zChunk;
        int playerY = player.blockPosition().getY();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                IChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;

                AxisAlignedBB aabb = new AxisAlignedBB(
                    chunkX << 4, 0, chunkZ << 4,
                    (chunkX << 4) + 16, 256, (chunkZ << 4) + 16
                );
                for (ItemFrameEntity frame : level.getEntitiesOfClass(ItemFrameEntity.class, aabb)) {
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

                for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                    for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                        for (int y = Math.max(playerY - 16, 0); y <= Math.min(playerY + 16, 255); y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (state.isAir()) continue;

                            String blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
                            CompoundNBT blockNbt = null;

                            TileEntity blockEntity = level.getBlockEntity(pos);
                            if (blockEntity != null) {
                                blockNbt = blockEntity.save(new CompoundNBT());
                            }

                            if (ConfigHandler.isBlockBlacklisted(blockId, blockNbt)) {
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                                logBlockViolation(player, blockId, blockNbt, formatPos(level, pos));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void scanItemFramesOnly(ServerPlayerEntity player) {
        World level = player.level;
        int centerChunkX = player.xChunk;
        int centerChunkZ = player.zChunk;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                AxisAlignedBB aabb = new AxisAlignedBB(
                    chunkX << 4, 0, chunkZ << 4,
                    (chunkX << 4) + 16, 256, (chunkZ << 4) + 16
                );
                for (ItemFrameEntity frame : level.getEntitiesOfClass(ItemFrameEntity.class, aabb)) {
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

    private static void logBlockViolation(PlayerEntity player, String blockId, CompoundNBT nbt, String location) {
        String loc = location == null || location.trim().isEmpty() ? formatEntityPos(player) : location;

        if (!ConfigHandler.isExcludedFromLog(blockId)) {
            String date = LocalDate.now().format(DATE_FORMAT);
            Path logFile = LOG_DIR.resolve(date + ".log");
            String entry = String.format("[%s] 玩家 %s 破坏了黑名单方块 %s | 来源: 世界方块 | 坐标: %s\n",
                java.time.LocalDateTime.now(), player.getName().getString(), blockId, loc);
            try {
                Files.write(logFile, entry.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                ItemBan.LOGGER.error("日志写入失败", e);
            }
            ItemBan.LOGGER.info("违禁方块 {} | 玩家 {} | 坐标 {}", blockId, player.getName().getString(), loc);
        }

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
                    new StringTextComponent(message),
                    ChatType.SYSTEM,
                    Util.NIL_UUID);
            }
        }

        if (ConfigHandler.autoBanOnViolation && !ConfigHandler.isExcludedFromLog(blockId)
                && player instanceof ServerPlayerEntity) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            if (serverPlayer.getServer() != null) {
                String banReason = String.format("你因破坏违禁方块（%s）已被永久封禁 如需申诉 请联系服务器管理员。", blockId);
                BanHelper.banAndKick(serverPlayer, banReason);
            }
        }
    }
}
