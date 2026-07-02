package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.PlayerProfile;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DirectorWhisperHandler {
    public static final DirectorWhisperHandler INSTANCE = new DirectorWhisperHandler();

    private static final int CHECK_INTERVAL_TICKS = 600; // 30 seconds
    private static final int MIN_SECONDS_PLAYED_BEFORE_WHISPERS = 600; // 10 minutes

    private final Map<UUID, Long> nextCheckGameTime = new HashMap<>();
    private final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private final Map<UUID, Integer> stillSeconds = new HashMap<>();

    private DirectorWhisperHandler() {
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        trackStillness(player);

        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        tryNaturalWhisper(player, level);
    }

    public boolean forceDebugWhisper(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        String message = chooseWhisper(player, level, true);

        if (message == null || message.isBlank()) {
            return false;
        }

        sendWhisper(player, message);
        return true;
    }

    private void tryNaturalWhisper(ServerPlayer player, ServerLevel level) {
        StillHereDirector director = StillHereDirector.INSTANCE;
        PlayerProfile profile = director.getProfile(player);
        HorrorPhase phase = director.getPhase(level);

        if (profile.secondsPlayed() < MIN_SECONDS_PLAYED_BEFORE_WHISPERS) {
            return;
        }

        if (phase == HorrorPhase.VANILLA_MASK) {
            return;
        }

        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();

        long nextCheck = nextCheckGameTime.getOrDefault(playerId, 0L);

        if (gameTime < nextCheck) {
            return;
        }

        nextCheckGameTime.put(playerId, gameTime + randomBetween(player, 1800, 4800));

        double chance = getWhisperChance(player, profile, phase);

        if (player.getRandom().nextDouble() > chance) {
            return;
        }

        String message = chooseWhisper(player, level, false);

        if (message != null && !message.isBlank()) {
            sendWhisper(player, message);
        }
    }

    private double getWhisperChance(ServerPlayer player, PlayerProfile profile, HorrorPhase phase) {
        double chance = switch (phase) {
            case VANILLA_MASK -> 0.0D;
            case RECOGNITION -> 0.006D;
            case THE_WATCHER -> 0.014D;
            case MEMORY_LEAKAGE -> 0.028D;
        };

        /*
         * These only slightly increase the chance.
         * The entity should feel like it interrupts the game rarely.
         */
        if (profile.recentViolence() >= 80) {
            chance += 0.012D;
        }

        if (profile.villageThreat() >= 80) {
            chance += 0.012D;
        }

        if (profile.secondsInDarkUnderground() >= 1200) {
            chance += 0.010D;
        }

        if (stillSeconds.getOrDefault(player.getUUID(), 0) >= 120) {
            chance += 0.012D;
        }

        return Math.min(chance, 0.055D);
    }

    private String chooseWhisper(ServerPlayer player, ServerLevel level, boolean debug) {
        StillHereDirector director = StillHereDirector.INSTANCE;
        PlayerProfile profile = director.getProfile(player);
        HorrorPhase phase = director.getPhase(level);

        List<String> messages = new ArrayList<>();

        /*
         * These are not narration.
         * They are the entity / Steve's subconscious pushing through.
         */

        if (phase == HorrorPhase.RECOGNITION) {
            messages.add("Not yet.");
            messages.add("You came back.");
            messages.add("This again.");
            messages.add("Too quiet.");
            messages.add("Stay.");
        }

        if (phase == HorrorPhase.THE_WATCHER) {
            messages.add("Do not turn around.");
            messages.add("Keep going.");
            messages.add("I heard that.");
            messages.add("Wrong way.");
            messages.add("You always come back.");
            messages.add("It was better before.");
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE) {
            messages.add("Stop hiding.");
            messages.add("There you are.");
            messages.add("Still here.");
            messages.add("You remember.");
            messages.add("I remember.");
            messages.add("Not alone.");
            messages.add("You left them first.");
        }

        if (profile.recentViolence() >= 90) {
            messages.add("Again.");
            messages.add("I saw.");
            messages.add("You did not stop.");
        }

        if (profile.passiveMobAttacks() >= 80) {
            messages.add("Quiet now.");
            messages.add("Nothing stays.");
        }

        if (profile.villageThreat() >= 85) {
            messages.add("They saw you.");
            messages.add("The doors know.");
            messages.add("No one is coming out.");
        }

        if (profile.blocksBrokenNearVillage() >= 30) {
            messages.add("Not yours.");
            messages.add("Put it back.");
        }

        if (profile.secondsInDarkUnderground() >= 1200) {
            messages.add("Keep digging.");
            messages.add("The stone heard you.");
            messages.add("Lower.");
        }

        if (stillSeconds.getOrDefault(player.getUUID(), 0) >= 120) {
            messages.add("Stop hiding.");
            messages.add("Move.");
            messages.add("I can wait.");
        }

        if (messages.isEmpty()) {
            return debug ? "Nothing answers." : null;
        }

        return messages.get(player.getRandom().nextInt(messages.size()));
    }

    private void trackStillness(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Vec3 current = player.position();
        Vec3 previous = lastPositions.put(playerId, current);

        if (previous == null) {
            return;
        }

        double distance = previous.distanceTo(current);

        if (distance < 0.05D) {
            stillSeconds.put(playerId, stillSeconds.getOrDefault(playerId, 0) + 1);
        } else {
            stillSeconds.put(playerId, 0);
        }
    }

    private void sendWhisper(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    private int randomBetween(ServerPlayer player, int minTicks, int maxTicks) {
        int range = Math.max(1, maxTicks - minTicks + 1);
        return minTicks + player.getRandom().nextInt(range);
    }
}