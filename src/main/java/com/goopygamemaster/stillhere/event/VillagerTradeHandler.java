package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VillagerTradeHandler {
    public static final VillagerTradeHandler INSTANCE = new VillagerTradeHandler();

    private static final long PEACEFUL_TRADE_RECORD_COOLDOWN_TICKS = 200L;

    private final Map<UUID, Long> nextPeacefulTradeRecord = new HashMap<>();

    private VillagerTradeHandler() {
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        if (!(event.getTarget() instanceof Villager villager)) {
            return;
        }

        StillHereDirector director = StillHereDirector.INSTANCE;
        HorrorPhase phase = director.getPhase(level);

        boolean canTrade = director.canVillagerTrade(player, level);

        if (!canTrade) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);

            if (phase == HorrorPhase.THE_WATCHER) {
                player.displayClientMessage(Component.literal("The villager steps back from you."), true);
            } else {
                player.displayClientMessage(Component.literal("The villager refuses to look at you."), true);
            }

            villager.getNavigation().stop();
            villager.setTarget(null);
            villager.getLookControl().setLookAt(player, 90.0F, 90.0F);

            return;
        }

        /*
         * Allowed trading is treated as a small peaceful signal.
         * This lets the Director slowly recognise non-violent village behaviour,
         * but the cooldown prevents the player farming trust by spam-clicking.
         */
        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();
        long nextAllowedRecord = nextPeacefulTradeRecord.getOrDefault(playerId, 0L);

        if (gameTime >= nextAllowedRecord) {
            director.recordPeacefulVillageTrade(player);
            nextPeacefulTradeRecord.put(playerId, gameTime + PEACEFUL_TRADE_RECORD_COOLDOWN_TICKS);
        }
    }
}