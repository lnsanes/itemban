package com.itemban;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ItemBan.MODID)
public class ItemBan {
    public static final String MODID = "itemban";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemBan() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        // Register config, commands, events in other classes
        ConfigHandler.register();
        MinecraftForge.EVENT_BUS.register(new ItemBanHandler());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        AdminNetwork.register();
        LOGGER.info("ItemBan mod initialized for 1.18.2");
    }
}
