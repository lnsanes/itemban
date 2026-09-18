package com.itemban;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(ItemBan.MODID)
public class ItemBan {
    public static final String MODID = "itemban";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemBan(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(AdminNetwork::registerPayloads);
        ConfigHandler.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        AdminNetwork.register();
        LOGGER.info("ItemBan (NeoForge) initialized");
    }
}
