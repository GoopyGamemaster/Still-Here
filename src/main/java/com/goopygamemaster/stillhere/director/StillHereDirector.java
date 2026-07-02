package com.goopygamemaster.stillhere.director;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class StillHereDirector {
    public static final StillHereDirector INSTANCE = new StillHereDirector();

    private static final long TICKS_PER_DAY = 24000L;

    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    private StillHereDirector() {
    }

    public long getWorldDay(ServerLevel level) {
        return Math.max(0L, level.getDayTime() / TICKS_PER_DAY);
    }

    public HorrorPhase getPhase(ServerLevel level) {
        return HorrorPhase.fromWorldDay(getWorldDay(level));
    }

    public PlayerProfile getProfile(ServerPlayer player) {
        return profiles.computeIfAbsent(player.getUUID(), ignored -> new PlayerProfile());
    }

    public void recordPassiveMobAttack(ServerPlayer player) {
        getProfile(player).recordPassiveMobAttack();
    }

    public void recordVillagerAttack(ServerPlayer player) {
        getProfile(player).recordVillagerAttacked();
    }

    public void recordGolemAttack(ServerPlayer player) {
        getProfile(player).recordGolemAttacked();
    }

    public void recordPeacefulVillageTrade(ServerPlayer player) {
        getProfile(player).recordPeacefulVillageTrade();
    }

    public void recordVillageBlockBroken(ServerPlayer player) {
        getProfile(player).recordVillageBlockBroken();
    }

    public void recordSecond(ServerPlayer player, boolean underground, boolean darkUnderground, boolean nearVillage, double distanceThisSecond) {
        getProfile(player).recordSecond(underground, darkUnderground, nearVillage, distanceThisSecond);
    }

    public void recordBlockBroken(ServerPlayer player, boolean underground, boolean darkUnderground, boolean nearVillage) {
        getProfile(player).recordBlockBroken(underground, darkUnderground, nearVillage);
    }

    public boolean canVillagerTrade(ServerPlayer player, ServerLevel level) {
        HorrorPhase phase = getPhase(level);
        PlayerProfile profile = getProfile(player);

        if (phase == HorrorPhase.VANILLA_MASK || phase == HorrorPhase.RECOGNITION) {
            return true;
        }

        if (phase == HorrorPhase.THE_WATCHER) {
            return profile.recentViolence() <= 5
                    && profile.villageThreat() <= 20
                    && (profile.historicViolence() <= 25 || profile.remorse() >= 15);
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE) {
            return profile.recentViolence() == 0
                    && profile.villageThreat() <= 10
                    && profile.guilt() <= 35
                    && profile.remorse() >= 25;
        }

        return false;
    }
}