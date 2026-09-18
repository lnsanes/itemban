package com.itemban;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ItemBan.MODID)
public class ItemBan {
    public static final String MODID = "itemban";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemBan(FMLJavaModLoadingContext context) {
        FMLCommonSetupEvent.getBus(context.getModBusGroup()).addListener(this::commonSetup);
        ConfigHandler.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        AdminNetwork.register();
        LOGGER.info("ItemBan mod initialized for 26.1.2");
    }
}
