package com.itemban;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ItemBan.MODID)
public class ItemBan {
    public static final String MODID = "itemban";
    public static final Logger LOGGER = LogManager.getLogger();

    public ItemBan() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        ConfigHandler.register();
        MinecraftForge.EVENT_BUS.register(new ItemBanHandler());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("ItemBan mod initialized for 1.16.5");
    }
}
