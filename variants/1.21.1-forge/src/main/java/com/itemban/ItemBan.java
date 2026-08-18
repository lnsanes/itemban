package com.itemban;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ItemBan.MODID)
public class ItemBan {
    public static final String MODID = "itemban";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemBan(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ConfigHandler.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("ItemBan mod initialized for 1.21.1");
    }
}
