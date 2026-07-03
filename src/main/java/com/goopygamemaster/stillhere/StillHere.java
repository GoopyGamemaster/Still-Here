package com.goopygamemaster.stillhere;

import com.goopygamemaster.stillhere.command.StillHereCommands;
import com.goopygamemaster.stillhere.event.BackstepDebugHandler;
import com.goopygamemaster.stillhere.event.DirectorWhisperHandler;
import com.goopygamemaster.stillhere.event.GolemProtectorHandler;
import com.goopygamemaster.stillhere.event.MobStaringHandler;
import com.goopygamemaster.stillhere.event.PlayerTelemetryHandler;
import com.goopygamemaster.stillhere.event.VillagerFearHandler;
import com.goopygamemaster.stillhere.event.VillagerTradeHandler;
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
        NeoForge.EVENT_BUS.register(MobStaringHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(BackstepDebugHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(PlayerTelemetryHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(VillagerFearHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(VillagerTradeHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(DirectorWhisperHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(GolemProtectorHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(new com.goopygamemaster.stillhere.event.HostileMobSuppressionHandler());

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