package com.goopygamemaster.stillhere.director;

import net.minecraft.network.chat.Component;
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

    public void evaluateMilestones(ServerPlayer player, ServerLevel level) {
        PlayerProfile profile = getProfile(player);
        HorrorPhase phase = getPhase(level);

        /*
         * Director messages should be rare.
         * They should feel like the world has noticed a pattern,
         * not like the mod is explaining the story.
         */

        if (phase == HorrorPhase.THE_WATCHER && profile.secondsPlayed() >= 900 && profile.markMessageDelivered("phase_the_watcher_late")) {
            sendActionMessage(player, "Something shifts in the quiet.");
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE && profile.secondsPlayed() >= 1500 && profile.markMessageDelivered("phase_memory_late")) {
            sendActionMessage(player, "The quiet has changed.");
        }

        if (profile.passiveMobAttacks() >= 25 && profile.markMessageDelivered("passive_attacks_25")) {
            sendActionMessage(player, "The field is quieter now.");
        }

        if (profile.passiveMobAttacks() >= 60 && profile.markMessageDelivered("passive_attacks_60")) {
            sendActionMessage(player, "Nothing lingers nearby.");
        }

        if (profile.recentViolence() >= 70 && profile.markMessageDelivered("recent_violence_70")) {
            sendActionMessage(player, "Something noticed the pattern.");
        }

        if (profile.villageThreat() >= 65 && profile.markMessageDelivered("village_threat_65")) {
            sendActionMessage(player, "The bell has gone still.");
        }

        if (profile.villageThreat() >= 90 && profile.markMessageDelivered("village_threat_90")) {
            sendActionMessage(player, "Windows darken before you arrive.");
        }

        if (profile.blocksBrokenNearVillage() >= 20 && profile.markMessageDelivered("village_blocks_20")) {
            sendActionMessage(player, "These walls were not waiting for you.");
        }

        if (profile.secondsInDarkUnderground() >= 600 && profile.markMessageDelivered("dark_underground_600")) {
            sendActionMessage(player, "The stone listens.");
        }

        if (profile.secondsInDarkUnderground() >= 1800 && profile.markMessageDelivered("dark_underground_1800")) {
            sendActionMessage(player, "Something below keeps count.");
        }

        if (profile.guilt() >= 95 && profile.markMessageDelivered("guilt_95")) {
            sendActionMessage(player, "Still here.");
        }
    }

    private void sendActionMessage(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }
}