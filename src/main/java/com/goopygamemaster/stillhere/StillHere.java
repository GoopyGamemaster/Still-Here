package com.goopygamemaster.stillhere;

import com.goopygamemaster.stillhere.command.StillHereCommands;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(StillHere.MODID)
public class StillHere {
    public static final String MODID = "stillhere";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StillHere(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[Still Here] The world remembers.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Still Here] Server starting. The world remembers.");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        StillHereCommands.register(event.getDispatcher());
    }
}
