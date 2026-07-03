package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Set;

public class HostileMobSuppressionHandler {
    private static final int CLEANUP_INTERVAL_TICKS = 80;
    private static final double CLEANUP_RADIUS = 160.0D;

    /*
     * Do not remove visible mobs right in front of the player.
     * That looks gamey. Existing mobs are removed quietly when out of sight.
     */
    private static final double VISIBLE_GRACE_RADIUS = 72.0D;

    private static final Set<String> EXPLICITLY_SUPPRESSED_ENTITY_IDS = Set.of(
            "zombie",
            "zombie_villager",
            "skeleton",
            "creeper",
            "spider",
            "cave_spider",
            "slime",
            "enderman",
            "witch",
            "phantom",
            "drowned",
            "husk",
            "stray",
            "pillager",
            "vindicator",
            "evoker",
            "ravager",
            "vex",
            "warden",
            "silverfish",
            "endermite",
            "guardian",
            "elder_guardian",
            "shulker",
            "breeze",
            "bogged",

            /*
             * Nether native entities.
             * After Phase 0, the Nether should also feel emptied out.
             */
            "blaze",
            "ghast",
            "magma_cube",
            "wither_skeleton",
            "piglin",
            "piglin_brute",
            "zombified_piglin",
            "hoglin",
            "zoglin",
            "strider",

            /*
             * Bosses are suppressed for now.
             * Later we can replace these moments with Kintsugi-specific events.
             */
            "wither",
            "ender_dragon"
    );

    /*
     * Main suppression layer.
     * After Phase 0, hostile entities should not properly enter the world.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (!shouldSuppressAfterPhaseZero(level)) {
            return;
        }

        Entity entity = event.getEntity();

        if (shouldSuppressEntity(level, entity)) {
            event.setCanceled(true);
            entity.discard();
        }
    }

    /*
     * Cleanup layer.
     * This only handles mobs that already existed before the phase changed.
     * It avoids deleting mobs the player is directly looking at.
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        if (player.tickCount % CLEANUP_INTERVAL_TICKS != 0) {
            return;
        }

        if (!shouldSuppressAfterPhaseZero(level)) {
            return;
        }

        for (Entity entity : level.getEntities(
                player,
                player.getBoundingBox().inflate(CLEANUP_RADIUS),
                entity -> shouldSuppressEntity(level, entity)
        )) {
            if (isVisibleToAnyPlayer(level, entity)) {
                continue;
            }

            entity.discard();
        }
    }

    private boolean shouldSuppressAfterPhaseZero(ServerLevel currentLevel) {
        /*
         * Use the Overworld as the global horror clock.
         * This keeps Nether/End suppression in sync with the main world phase.
         */
        ServerLevel overworld = currentLevel.getServer().overworld();
        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(overworld);

        return phase != HorrorPhase.VANILLA_MASK;
    }

    private boolean shouldSuppressEntity(ServerLevel level, Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }

        if (entity instanceof ServerPlayer) {
            return false;
        }

        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();

        if (EXPLICITLY_SUPPRESSED_ENTITY_IDS.contains(id)) {
            return true;
        }

        /*
         * Catch normal hostile mobs, including modded hostile mobs where possible.
         */
        if (entity.getType().getCategory() == MobCategory.MONSTER) {
            return true;
        }

        if (entity instanceof Enemy) {
            return true;
        }

        /*
         * Extra Nether safety: remove native Nether mobs after Phase 0.
         * Do not remove imported animals/villagers just because they are in the Nether.
         */
        if (level.dimension() == Level.NETHER) {
            return EXPLICITLY_SUPPRESSED_ENTITY_IDS.contains(id);
        }

        return false;
    }

    private boolean isVisibleToAnyPlayer(ServerLevel level, Entity entity) {
        double visibleGraceRadiusSqr = VISIBLE_GRACE_RADIUS * VISIBLE_GRACE_RADIUS;

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entity) > visibleGraceRadiusSqr) {
                continue;
            }

            if (player.hasLineOfSight(entity)) {
                return true;
            }
        }

        return false;
    }
}