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
}