package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MobStaringHandler {
    public static final MobStaringHandler INSTANCE = new MobStaringHandler();

    private static final double DEBUG_RADIUS = 96.0D;
    private static final int DEBUG_DURATION_TICKS = 200;

    private static final int NATURAL_CHECK_INTERVAL_TICKS = 100;
    private static final boolean ENABLE_NATURAL_STARES = true;
    private static final boolean ENABLE_NATURAL_AVOIDANCE = true;
    private static final boolean ENABLE_ATTACK_REACTION = true;

    private static final int ALL_MOBS_IN_RANGE = -1;
    private static final double PLAYER_VIEW_DOT_THRESHOLD = 0.25D;
    private static final double NATURAL_AVOIDANCE_CLOSE_NOTICE_DISTANCE = 14.0D;

    /*
     * Attack witness behaviour:
     * - Close witnesses back up while staring, then flee.
     * - Far witnesses freeze and stare.
     */
    private static final double ATTACK_CLOSE_WITNESS_RADIUS_FROM_ATTACKED_MOB = 18.0D;
    private static final double ATTACK_MEDIUM_WITNESS_RADIUS_FROM_ATTACKED_MOB = 42.0D;
    private static final double ATTACK_FAR_WITNESS_RADIUS_FROM_ATTACKED_MOB = 70.0D;
    private static final double ATTACK_BACK_AWAY_SAFE_DISTANCE_FROM_PLAYER = 38.0D;
    private static final int ATTACK_BACK_AWAY_SAFETY_TIMEOUT_TICKS = 700;
    private static final int ATTACK_FAR_STARE_DURATION_TICKS = 140;
    private static final int ATTACK_REACTION_COOLDOWN_TICKS = 100;

    /*
     * Avoidance uses a slower escape speed.
     * Attack-panic uses a faster escape speed.
     */
    private static final double AVOIDANCE_ESCAPE_SPEED = 0.80D;
    private static final double ATTACK_ESCAPE_SPEED = 1.35D;
    private static final int AVOIDANCE_REPATH_INTERVAL_TICKS = 30;

    /*
     * Backing-away stage.
     * This deliberately uses manual movement because vanilla pathfinding cannot
     * convincingly make mobs walk backwards while staring at the player.
     */
    private static final int BACK_AWAY_STAGE_TICKS = 80;
    private static final double BACK_AWAY_SPEED = 0.055D;

    private final Map<UUID, StareTarget> activeStares = new HashMap<>();
    private final Map<UUID, AvoidanceTarget> activeAvoidanceStares = new HashMap<>();

    private final Map<UUID, Long> nextNaturalStareCheck = new HashMap<>();
    private final Map<UUID, Long> nextNaturalAvoidanceCheck = new HashMap<>();
    private final Map<UUID, Long> nextAttackReactionAllowed = new HashMap<>();

    private MobStaringHandler() {
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        if (player.tickCount % NATURAL_CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        if (ENABLE_NATURAL_STARES) {
            tryNaturalStare(player, level);
        }

        if (ENABLE_NATURAL_AVOIDANCE) {
            tryNaturalAvoidance(player, level);
        }
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }

        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }

        StareTarget stareTarget = activeStares.get(mob.getUUID());

        if (stareTarget != null) {
            updateSingleStare(mob, level, stareTarget);
        }

        AvoidanceTarget avoidanceTarget = activeAvoidanceStares.get(mob.getUUID());

        if (avoidanceTarget != null) {
            updateSingleAvoidance(mob, level, avoidanceTarget);
        }
    }

    @SubscribeEvent
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!ENABLE_ATTACK_REACTION) {
            return;
        }

        if (!(event.getEntity() instanceof Mob attackedMob)) {
            return;
        }

        if (!isValidFearMob(attackedMob)) {
            return;
        }

        Entity attacker = event.getSource().getEntity();

        if (!(attacker instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        StillHereDirector.INSTANCE.recordPassiveMobAttack(player);

        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();

        long nextAllowed = nextAttackReactionAllowed.getOrDefault(playerId, 0L);

        if (gameTime < nextAllowed) {
            return;
        }

        int affected = startAttackWitnessReaction(player, level, attackedMob);

        if (affected > 0) {
            nextAttackReactionAllowed.put(playerId, gameTime + ATTACK_REACTION_COOLDOWN_TICKS);
        }
    }

    public int forceDebugStare(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        return startGroupStare(
                player,
                level,
                DEBUG_RADIUS,
                DEBUG_DURATION_TICKS,
                ALL_MOBS_IN_RANGE,
                true
        );
    }

    public int forceDebugAvoidance(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        return startAvoidanceStare(
                player,
                level,
                DEBUG_RADIUS,
                DEBUG_DURATION_TICKS * 4,
                ALL_MOBS_IN_RANGE,
                48.0D,
                AVOIDANCE_ESCAPE_SPEED,
                true,
                true,
                true,
                null
        );
    }

    public int forceDebugFlee(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        return startAvoidanceStare(
                player,
                level,
                DEBUG_RADIUS,
                700,
                ALL_MOBS_IN_RANGE,
                48.0D,
                ATTACK_ESCAPE_SPEED,
                false,
                false,
                true,
                null
        );
    }

    private void tryNaturalStare(ServerPlayer player, ServerLevel level) {
        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);
        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();

        if (!nextNaturalStareCheck.containsKey(playerId)) {
            nextNaturalStareCheck.put(playerId, gameTime + getInitialStareDelayTicks(player, phase));
            return;
        }

        long nextCheckTime = nextNaturalStareCheck.get(playerId);

        if (gameTime < nextCheckTime) {
            return;
        }

        NaturalStareSettings settings = getStareSettingsForPhase(phase);

        if (player.getRandom().nextDouble() > settings.chance()) {
            nextNaturalStareCheck.put(playerId, gameTime + randomBetween(player, 400, 800));
            return;
        }

        int affected = startGroupStare(
                player,
                level,
                settings.radius(),
                settings.durationTicks(),
                settings.maxMobs(),
                false
        );

        if (affected > 0) {
            nextNaturalStareCheck.put(playerId, gameTime + randomBetween(player, settings.minCooldownTicks(), settings.maxCooldownTicks()));
        } else {
            nextNaturalStareCheck.put(playerId, gameTime + randomBetween(player, 400, 800));
        }
    }

    private void tryNaturalAvoidance(ServerPlayer player, ServerLevel level) {
        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);
        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();

        AvoidanceSettings settings = getAvoidanceSettingsForPhase(phase);

        if (settings.chance() <= 0.0D) {
            return;
        }

        if (!nextNaturalAvoidanceCheck.containsKey(playerId)) {
            nextNaturalAvoidanceCheck.put(playerId, gameTime + getInitialAvoidanceDelayTicks(player, phase));
            return;
        }

        long nextCheckTime = nextNaturalAvoidanceCheck.get(playerId);

        if (gameTime < nextCheckTime) {
            return;
        }

        if (player.getRandom().nextDouble() > settings.chance()) {
            nextNaturalAvoidanceCheck.put(playerId, gameTime + randomBetween(player, 200, 500));
            return;
        }

        int affected = startAvoidanceStare(
                player,
                level,
                settings.radius(),
                settings.safetyTimeoutTicks(),
                settings.maxMobs(),
                settings.safeDistance(),
                AVOIDANCE_ESCAPE_SPEED,
                true,
                true,
                false,
                null
        );

        if (affected > 0) {
            nextNaturalAvoidanceCheck.put(playerId, gameTime + randomBetween(player, settings.minCooldownTicks(), settings.maxCooldownTicks()));
        } else {
            nextNaturalAvoidanceCheck.put(playerId, gameTime + randomBetween(player, 200, 500));
        }
    }

    private int startAttackWitnessReaction(ServerPlayer player, ServerLevel level, Mob attackedMob) {
        AABB closeBox = attackedMob.getBoundingBox().inflate(ATTACK_CLOSE_WITNESS_RADIUS_FROM_ATTACKED_MOB);
        AABB mediumBox = attackedMob.getBoundingBox().inflate(ATTACK_MEDIUM_WITNESS_RADIUS_FROM_ATTACKED_MOB);
        AABB farBox = attackedMob.getBoundingBox().inflate(ATTACK_FAR_WITNESS_RADIUS_FROM_ATTACKED_MOB);

        List<Mob> closeWitnesses = level.getEntitiesOfClass(
                Mob.class,
                closeBox,
                mob -> isAttackWitnessCandidate(mob, player, attackedMob)
        );

        List<Mob> mediumWitnesses = level.getEntitiesOfClass(
                Mob.class,
                mediumBox,
                mob -> isAttackWitnessCandidate(mob, player, attackedMob)
                        && !closeWitnesses.contains(mob)
        );

        List<Mob> farWitnesses = level.getEntitiesOfClass(
                Mob.class,
                farBox,
                mob -> isAttackWitnessCandidate(mob, player, attackedMob)
                        && !closeWitnesses.contains(mob)
                        && !mediumWitnesses.contains(mob)
        );

        int affected = 0;
        long reactionEndTime = level.getGameTime() + ATTACK_BACK_AWAY_SAFETY_TIMEOUT_TICKS;
        long farStareEndTime = level.getGameTime() + ATTACK_FAR_STARE_DURATION_TICKS;

        /*
         * Close witnesses panic. No backstep.
         */
        for (Mob mob : closeWitnesses) {
            if (beginAvoidanceForMob(
                    player,
                    level,
                    mob,
                    reactionEndTime,
                    ATTACK_FAR_WITNESS_RADIUS_FROM_ATTACKED_MOB,
                    ATTACK_BACK_AWAY_SAFE_DISTANCE_FROM_PLAYER,
                    ATTACK_ESCAPE_SPEED,
                    false
            )) {
                affected++;
            }
        }

        /*
         * Medium witnesses avoid. They saw enough to be scared, but they are not
         * in immediate panic range, so they backstep first.
         */
        for (Mob mob : mediumWitnesses) {
            if (beginAvoidanceForMob(
                    player,
                    level,
                    mob,
                    reactionEndTime,
                    ATTACK_FAR_WITNESS_RADIUS_FROM_ATTACKED_MOB,
                    ATTACK_BACK_AWAY_SAFE_DISTANCE_FROM_PLAYER,
                    AVOIDANCE_ESCAPE_SPEED,
                    true
            )) {
                affected++;
            }
        }

        /*
         * Far witnesses freeze and stare.
         */
        for (Mob mob : farWitnesses) {
            boolean wasNoAi = mob.isNoAi();

            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            mob.setNoAi(true);

            activeStares.put(
                    mob.getUUID(),
                    new StareTarget(
                            player.getUUID(),
                            farStareEndTime,
                            ATTACK_FAR_WITNESS_RADIUS_FROM_ATTACKED_MOB,
                            wasNoAi
                    )
            );

            forceMobToStare(mob, player);
            affected++;
        }

        return affected;
    }

    private boolean isAttackWitnessCandidate(Mob mob, ServerPlayer player, Mob attackedMob) {
        return isValidFearMob(mob)
                && !mob.getUUID().equals(attackedMob.getUUID())
                && !activeStares.containsKey(mob.getUUID())
                && !activeAvoidanceStares.containsKey(mob.getUUID())
                && mob.hasLineOfSight(player);
    }

    private NaturalStareSettings getStareSettingsForPhase(HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> new NaturalStareSettings(0.15D, 18.0D, 60, 1, 4800, 7200);
            case RECOGNITION -> new NaturalStareSettings(0.23D, 28.0D, 90, 3, 3000, 4800);
            case THE_WATCHER -> new NaturalStareSettings(0.29D, 48.0D, 120, ALL_MOBS_IN_RANGE, 1800, 3600);
            case MEMORY_LEAKAGE -> new NaturalStareSettings(0.37D, 48.0D, 160, ALL_MOBS_IN_RANGE, 1200, 2400);
        };
    }

    private AvoidanceSettings getAvoidanceSettingsForPhase(HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> new AvoidanceSettings(0.00D, 0.0D, 0, 0, 0.0D, 0, 0);
            case RECOGNITION -> new AvoidanceSettings(0.30D, 32.0D, 550, 3, 30.0D, 1200, 2400);
            case THE_WATCHER -> new AvoidanceSettings(0.45D, 48.0D, 700, 6, 40.0D, 800, 1800);
            case MEMORY_LEAKAGE -> new AvoidanceSettings(0.60D, 56.0D, 900, 10, 48.0D, 600, 1400);
        };
    }

    private int getInitialStareDelayTicks(ServerPlayer player, HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> randomBetween(player, 2400, 4800);
            case RECOGNITION -> randomBetween(player, 1200, 2400);
            case THE_WATCHER -> randomBetween(player, 600, 1200);
            case MEMORY_LEAKAGE -> randomBetween(player, 400, 800);
        };
    }

    private int getInitialAvoidanceDelayTicks(ServerPlayer player, HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> randomBetween(player, 2400, 4800);
            case RECOGNITION -> randomBetween(player, 400, 1000);
            case THE_WATCHER -> randomBetween(player, 300, 800);
            case MEMORY_LEAKAGE -> randomBetween(player, 200, 600);
        };
    }

    private int randomBetween(ServerPlayer player, int minTicks, int maxTicks) {
        int range = Math.max(1, maxTicks - minTicks + 1);
        return minTicks + player.getRandom().nextInt(range);
    }

    private int startGroupStare(ServerPlayer player, ServerLevel level, double radius, int durationTicks, int maxMobs, boolean debugGlow) {
        AABB searchBox = player.getBoundingBox().inflate(radius);

        List<Mob> nearbyMobs = level.getEntitiesOfClass(
                Mob.class,
                searchBox,
                mob -> isValidStareMob(mob)
                        && !activeStares.containsKey(mob.getUUID())
                        && !activeAvoidanceStares.containsKey(mob.getUUID())
        );

        if (nearbyMobs.isEmpty()) {
            return 0;
        }

        Collections.shuffle(nearbyMobs);

        long endTime = level.getGameTime() + durationTicks;
        int affected = 0;

        for (Mob mob : nearbyMobs) {
            if (maxMobs != ALL_MOBS_IN_RANGE && affected >= maxMobs) {
                break;
            }

            boolean wasNoAi = mob.isNoAi();

            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            mob.setNoAi(true);

            activeStares.put(mob.getUUID(), new StareTarget(player.getUUID(), endTime, radius, wasNoAi));

            forceMobToStare(mob, player);

            if (debugGlow) {
                mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, durationTicks, 0, false, false));
            }

            affected++;
        }

        return affected;
    }

    private int startAvoidanceStare(
            ServerPlayer player,
            ServerLevel level,
            double radius,
            int safetyTimeoutTicks,
            int maxMobs,
            double safeDistance,
            double escapeSpeed,
            boolean startWithBackstep,
            boolean requirePlayerVision,
            boolean debugGlow,
            UUID excludedMobId
    ) {
        AABB searchBox = player.getBoundingBox().inflate(radius);

        List<Mob> visibleMobs = level.getEntitiesOfClass(
                Mob.class,
                searchBox,
                mob -> isValidFearMob(mob)
                        && (excludedMobId == null || !mob.getUUID().equals(excludedMobId))
                        && !activeStares.containsKey(mob.getUUID())
                        && !activeAvoidanceStares.containsKey(mob.getUUID())
                        && mob.hasLineOfSight(player)
                        && (!requirePlayerVision || isNaturalAvoidanceCandidate(player, mob))
        );

        if (visibleMobs.isEmpty()) {
            return 0;
        }

        Collections.shuffle(visibleMobs);

        long hardEndTime = level.getGameTime() + safetyTimeoutTicks;
        int affected = 0;

        for (Mob mob : visibleMobs) {
            if (maxMobs != ALL_MOBS_IN_RANGE && affected >= maxMobs) {
                break;
            }

            if (beginAvoidanceForMob(player, level, mob, hardEndTime, radius, safeDistance, escapeSpeed, startWithBackstep)) {
                if (debugGlow) {
                    mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, safetyTimeoutTicks, 0, false, false));
                }

                affected++;
            }
        }

        return affected;
    }

    private boolean beginAvoidanceForMob(
            ServerPlayer player,
            ServerLevel level,
            Mob mob,
            long hardEndTime,
            double radius,
            double safeDistance,
            double escapeSpeed,
            boolean startWithBackstep
    ) {
        boolean wasNoAi = mob.isNoAi();

        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);

        long backingUntil = 0L;

        if (startWithBackstep) {
            /*
             * Cautious avoidance starts with the proven backstep stage.
             * Panic fleeing skips this and goes straight to pathfinding.
             */
            backingUntil = level.getGameTime() + BACK_AWAY_STAGE_TICKS;
            mob.setNoAi(true);
            forceMobToFacePlayer(mob, player);
        } else {
            pathMobTowardBestAvoidancePoint(mob, player, level, escapeSpeed);
        }

        activeAvoidanceStares.put(
                mob.getUUID(),
                new AvoidanceTarget(
                        player.getUUID(),
                        hardEndTime,
                        radius,
                        safeDistance,
                        escapeSpeed,
                        0L,
                        backingUntil,
                        wasNoAi
                )
        );

        return true;
    }

    private void updateSingleStare(Mob mob, ServerLevel level, StareTarget stareTarget) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(stareTarget.playerId());

        if (player == null) {
            endStare(mob, stareTarget);
            activeStares.remove(mob.getUUID());
            return;
        }

        long gameTime = level.getGameTime();

        if (gameTime > stareTarget.endGameTime()
                || !mob.isAlive()
                || !player.isAlive()
                || mob.distanceToSqr(player) > stareTarget.radius() * stareTarget.radius() * 1.5D) {
            endStare(mob, stareTarget);
            activeStares.remove(mob.getUUID());
            return;
        }

        forceMobToStare(mob, player);
    }

    private void updateSingleAvoidance(Mob mob, ServerLevel level, AvoidanceTarget avoidanceTarget) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(avoidanceTarget.playerId());

        if (player == null) {
            endAvoidance(mob, avoidanceTarget);
            activeAvoidanceStares.remove(mob.getUUID());
            return;
        }

        long gameTime = level.getGameTime();

        if (!mob.isAlive() || !player.isAlive() || gameTime > avoidanceTarget.hardEndGameTime()) {
            endAvoidance(mob, avoidanceTarget);
            activeAvoidanceStares.remove(mob.getUUID());
            return;
        }

        /*
         * Stage 1: forced backwards movement while staring.
         * This intentionally uses manual movement, but only while the next tiny
         * step is safe. If terrain becomes unsafe, it switches to pathfinding.
         */
        if (avoidanceTarget.backingUntilGameTime() > 0L && gameTime < avoidanceTarget.backingUntilGameTime()) {
            forceMobToFacePlayer(mob, player);

            if (moveMobBackwardsSafely(mob, player, level)) {
                return;
            }

            restoreAiIfNeeded(mob, avoidanceTarget.wasNoAi());

            AvoidanceTarget updatedTarget = avoidanceTarget.withBackingFinished(gameTime + AVOIDANCE_REPATH_INTERVAL_TICKS);
            activeAvoidanceStares.put(mob.getUUID(), updatedTarget);

            pathMobTowardBestAvoidancePoint(mob, player, level, updatedTarget.escapeSpeed());
            return;
        }

        /*
         * Stage 1 has just finished. Restore AI, then Stage 2 begins:
         * turn around and pathfind away.
         */
        if (avoidanceTarget.backingUntilGameTime() > 0L) {
            restoreAiIfNeeded(mob, avoidanceTarget.wasNoAi());

            AvoidanceTarget updatedTarget = avoidanceTarget.withBackingFinished(gameTime + AVOIDANCE_REPATH_INTERVAL_TICKS);
            activeAvoidanceStares.put(mob.getUUID(), updatedTarget);

            pathMobTowardBestAvoidancePoint(mob, player, level, updatedTarget.escapeSpeed());
            return;
        }

        boolean safeDistanceReached = mob.distanceToSqr(player) >= avoidanceTarget.safeDistance() * avoidanceTarget.safeDistance();
        boolean lineOfSightBroken = !mob.hasLineOfSight(player) || !player.hasLineOfSight(mob);

        if (safeDistanceReached || lineOfSightBroken) {
            endAvoidance(mob, avoidanceTarget);
            activeAvoidanceStares.remove(mob.getUUID());
            return;
        }

        /*
         * During escape, let the body move naturally, but keep the head unsettled.
         */
        forceMobHeadToLookAtPlayer(mob, player);

        if (mob.getNavigation().isDone() || gameTime >= avoidanceTarget.nextRepathGameTime()) {
            pathMobTowardBestAvoidancePoint(mob, player, level, avoidanceTarget.escapeSpeed());

            activeAvoidanceStares.put(
                    mob.getUUID(),
                    avoidanceTarget.withNextRepath(gameTime + AVOIDANCE_REPATH_INTERVAL_TICKS)
            );
        }
    }

    private boolean isValidStareMob(Mob mob) {
        return mob.isAlive() && !(mob instanceof Enemy);
    }

    private boolean isValidFearMob(Mob mob) {
        return isValidStareMob(mob) && !(mob instanceof IronGolem);
    }

    private boolean isNaturalAvoidanceCandidate(ServerPlayer player, Mob mob) {
        if (!player.hasLineOfSight(mob)) {
            return false;
        }

        if (mob.distanceToSqr(player) <= NATURAL_AVOIDANCE_CLOSE_NOTICE_DISTANCE * NATURAL_AVOIDANCE_CLOSE_NOTICE_DISTANCE) {
            return true;
        }

        return isWithinPlayerVision(player, mob);
    }

    private boolean isWithinPlayerVision(ServerPlayer player, Mob mob) {
        Vec3 playerLook = player.getLookAngle().normalize();
        Vec3 playerEye = player.getEyePosition();

        Vec3 mobCentre = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        Vec3 directionToMob = mobCentre.subtract(playerEye).normalize();

        return playerLook.dot(directionToMob) >= PLAYER_VIEW_DOT_THRESHOLD;
    }

    private void forceMobToStare(Mob mob, ServerPlayer player) {
        forceMobToFacePlayer(mob, player);

        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
        mob.hasImpulse = true;
    }

    private void forceMobToFacePlayer(Mob mob, ServerPlayer player) {
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double dy = player.getEyeY() - mob.getEyeY();

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setXRot(pitch);

        mob.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private void forceMobHeadToLookAtPlayer(Mob mob, ServerPlayer player) {
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double dy = player.getEyeY() - mob.getEyeY();

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        mob.setYHeadRot(yaw);
        mob.setXRot(pitch);
        mob.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private boolean moveMobBackwardsSafely(Mob mob, ServerPlayer player, ServerLevel level) {
        double awayX = mob.getX() - player.getX();
        double awayZ = mob.getZ() - player.getZ();

        double length = Math.sqrt((awayX * awayX) + (awayZ * awayZ));

        if (length < 0.001D) {
            return false;
        }

        awayX /= length;
        awayZ /= length;

        double nextX = mob.getX() + (awayX * BACK_AWAY_SPEED);
        double nextZ = mob.getZ() + (awayZ * BACK_AWAY_SPEED);

        SafeStep safeStep = findSafeStep(level, mob, nextX, mob.getY(), nextZ);

        if (safeStep == null) {
            return false;
        }

        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);

        /*
         * Same method as /stillhere backstep:
         * directly move the mob while the target position is safe.
         */
        mob.setPos(nextX, safeStep.y(), nextZ);
        mob.hasImpulse = true;

        return true;
    }

    private SafeStep findSafeStep(ServerLevel level, Mob mob, double x, double y, double z) {
        int baseY = Mth.floor(y);
        int[] yOffsets = new int[]{0, -1, 1};

        for (int offset : yOffsets) {
            int feetY = baseY + offset;

            BlockPos feet = BlockPos.containing(x, feetY, z);
            BlockPos below = feet.below();
            BlockPos head = feet.above();

            boolean belowSolid = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
            boolean feetClear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
            boolean headClear = level.getBlockState(head).getCollisionShape(level, head).isEmpty();

            if (belowSolid && feetClear && headClear) {
                return new SafeStep(feetY);
            }
        }

        return null;
    }

    private boolean canBackStepSafely(Mob mob, ServerPlayer player, ServerLevel level) {
        if (!mob.onGround()) {
            return false;
        }

        double awayX = mob.getX() - player.getX();
        double awayZ = mob.getZ() - player.getZ();

        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);

        if (length < 0.001D) {
            return false;
        }

        awayX /= length;
        awayZ /= length;

        double nextX = mob.getX() + awayX * BACK_AWAY_SPEED;
        double nextZ = mob.getZ() + awayZ * BACK_AWAY_SPEED;

        return isBackStepSafe(mob, level, nextX, mob.getY(), nextZ);
    }

    private boolean isBackStepSafe(Mob mob, ServerLevel level, double nextX, double nextY, double nextZ) {
        BlockPos feet = BlockPos.containing(nextX, nextY, nextZ);
        BlockPos below = feet.below();
        BlockPos head = feet.above();

        boolean belowSolid = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        boolean feetClear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headClear = level.getBlockState(head).getCollisionShape(level, head).isEmpty();

        return belowSolid && feetClear && headClear;
    }

    private void pathMobTowardBestAvoidancePoint(Mob mob, ServerPlayer player, ServerLevel level, double speed) {
        Vec3 best = findBestAvoidancePoint(mob, player, level);

        if (best == null) {
            moveMobDirectlyAwayWithGravitySafeVelocity(mob, player);
            return;
        }

        mob.getNavigation().moveTo(best.x, best.y, best.z, speed);
    }

    private Vec3 findBestAvoidancePoint(Mob mob, ServerPlayer player, ServerLevel level) {
        Vec3 mobPos = mob.position();
        Vec3 playerPos = player.position();

        double awayX = mobPos.x - playerPos.x;
        double awayZ = mobPos.z - playerPos.z;

        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);

        if (length < 0.001D) {
            return null;
        }

        awayX /= length;
        awayZ /= length;

        double sideX = -awayZ;
        double sideZ = awayX;

        Vec3 bestPoint = null;
        double bestScore = -999999.0D;

        double[] forwardDistances = new double[]{10.0D, 14.0D, 18.0D};
        double[] sideOffsets = new double[]{0.0D, 5.0D, -5.0D, 9.0D, -9.0D, 13.0D, -13.0D};

        for (double forward : forwardDistances) {
            for (double side : sideOffsets) {
                double targetX = mobPos.x + (awayX * forward) + (sideX * side);
                double targetZ = mobPos.z + (awayZ * forward) + (sideZ * side);

                BlockPos ground = findNearbyGround(level, BlockPos.containing(targetX, mob.getY(), targetZ));

                if (ground == null) {
                    continue;
                }

                Vec3 candidate = Vec3.atBottomCenterOf(ground);

                double distanceFromPlayer = candidate.distanceTo(playerPos);
                boolean blocksSight = !hasClearSightFromPlayerToPoint(player, level, candidate.add(0.0D, mob.getBbHeight() * 0.5D, 0.0D));

                double score = distanceFromPlayer;

                if (blocksSight) {
                    score += 50.0D;
                }

                if (candidate.y < mob.getY() - 3.0D || candidate.y > mob.getY() + 2.0D) {
                    score -= 20.0D;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestPoint = candidate;
                }
            }
        }

        return bestPoint;
    }

    private BlockPos findNearbyGround(ServerLevel level, BlockPos start) {
        for (int dy = 3; dy >= -5; dy--) {
            BlockPos feet = start.offset(0, dy, 0);
            BlockPos below = feet.below();
            BlockPos head = feet.above();

            boolean solidBelow = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
            boolean feetClear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
            boolean headClear = level.getBlockState(head).getCollisionShape(level, head).isEmpty();

            if (solidBelow && feetClear && headClear) {
                return feet;
            }
        }

        return null;
    }

    private boolean hasClearSightFromPlayerToPoint(ServerPlayer player, ServerLevel level, Vec3 target) {
        Vec3 start = player.getEyePosition();

        HitResult result = level.clip(new ClipContext(
                start,
                target,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));

        return result.getType() == HitResult.Type.MISS;
    }

    private void moveMobDirectlyAwayWithGravitySafeVelocity(Mob mob, ServerPlayer player) {
        double awayX = mob.getX() - player.getX();
        double awayZ = mob.getZ() - player.getZ();

        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);

        if (length < 0.001D) {
            return;
        }

        awayX /= length;
        awayZ /= length;

        Vec3 current = mob.getDeltaMovement();

        mob.setDeltaMovement(awayX * 0.05D, current.y, awayZ * 0.05D);
        mob.hasImpulse = true;
    }

    private void restoreAiIfNeeded(Mob mob, boolean wasNoAi) {
        if (!wasNoAi) {
            mob.setNoAi(false);
        }
    }

    private void endStare(Mob mob, StareTarget stareTarget) {
        restoreAiIfNeeded(mob, stareTarget.wasNoAi());
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
    }

    private void endAvoidance(Mob mob, AvoidanceTarget avoidanceTarget) {
        restoreAiIfNeeded(mob, avoidanceTarget.wasNoAi());
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    private record SafeStep(double y) {
    }

    private record StareTarget(UUID playerId, long endGameTime, double radius, boolean wasNoAi) {
    }

    private record AvoidanceTarget(
            UUID playerId,
            long hardEndGameTime,
            double radius,
            double safeDistance,
            double escapeSpeed,
            long nextRepathGameTime,
            long backingUntilGameTime,
            boolean wasNoAi
    ) {
        AvoidanceTarget withNextRepath(long nextRepathGameTime) {
            return new AvoidanceTarget(playerId, hardEndGameTime, radius, safeDistance, escapeSpeed, nextRepathGameTime, backingUntilGameTime, wasNoAi);
        }

        AvoidanceTarget withBackingFinished(long nextRepathGameTime) {
            return new AvoidanceTarget(playerId, hardEndGameTime, radius, safeDistance, escapeSpeed, nextRepathGameTime, 0L, wasNoAi);
        }
    }

    private record NaturalStareSettings(
            double chance,
            double radius,
            int durationTicks,
            int maxMobs,
            int minCooldownTicks,
            int maxCooldownTicks
    ) {
    }

    private record AvoidanceSettings(
            double chance,
            double radius,
            int safetyTimeoutTicks,
            int maxMobs,
            double safeDistance,
            int minCooldownTicks,
            int maxCooldownTicks
    ) {
    }
}