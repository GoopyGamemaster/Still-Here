package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerTelemetryHandler {
    public static final PlayerTelemetryHandler INSTANCE = new PlayerTelemetryHandler();

    private static final int TELEMETRY_INTERVAL_TICKS = 20;
    private static final double VILLAGE_CHECK_RADIUS = 48.0D;

    private final Map<UUID, Vec3> lastPlayerPositions = new HashMap<>();

    private PlayerTelemetryHandler() {
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        if (player.tickCount % TELEMETRY_INTERVAL_TICKS != 0) {
            return;
        }

        boolean underground = isUnderground(player);
        boolean darkUnderground = underground && isDark(level, player.blockPosition());
        boolean nearVillage = isNearVillage(level, player);

        Vec3 currentPosition = player.position();
        Vec3 previousPosition = lastPlayerPositions.put(player.getUUID(), currentPosition);

        double distanceThisSecond = 0.0D;

        if (previousPosition != null) {
            distanceThisSecond = Math.min(previousPosition.distanceTo(currentPosition), 40.0D);
        }

        StillHereDirector.INSTANCE.recordSecond(
                player,
                underground,
                darkUnderground,
                nearVillage,
                distanceThisSecond
        );


    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        boolean underground = isUnderground(player);
        boolean darkUnderground = underground && isDark(level, player.blockPosition());
        boolean nearVillage = isNearVillage(level, player);
        boolean sensitiveVillageBlock = nearVillage && isSensitiveVillageBlock(level, event.getPos());

        StillHereDirector.INSTANCE.recordBlockBroken(
                player,
                underground,
                darkUnderground,
                sensitiveVillageBlock
        );
    }

    private boolean isSensitiveVillageBlock(ServerLevel level, BlockPos pos) {
        String blockName = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();

        return blockName.contains("bed")
                || blockName.contains("door")
                || blockName.contains("bell")
                || blockName.contains("chest")
                || blockName.contains("barrel")
                || blockName.contains("lectern")
                || blockName.contains("composter")
                || blockName.contains("loom")
                || blockName.contains("cartography_table")
                || blockName.contains("fletching_table")
                || blockName.contains("smithing_table")
                || blockName.contains("grindstone")
                || blockName.contains("stonecutter")
                || blockName.contains("blast_furnace")
                || blockName.contains("smoker")
                || blockName.contains("brewing_stand")
                || blockName.contains("cauldron");
    }

    private boolean isUnderground(ServerPlayer player) {
        return player.getY() < 60.0D;
    }

    private boolean isDark(ServerLevel level, BlockPos pos) {
        return level.getMaxLocalRawBrightness(pos) <= 7;
    }

    private boolean isNearVillage(ServerLevel level, ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(VILLAGE_CHECK_RADIUS);

        return !level.getEntitiesOfClass(
                Villager.class,
                searchBox,
                villager -> villager.isAlive()
        ).isEmpty();
    }
}