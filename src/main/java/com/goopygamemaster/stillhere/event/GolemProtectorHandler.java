package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.PlayerProfile;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GolemProtectorHandler {
    public static final GolemProtectorHandler INSTANCE = new GolemProtectorHandler();

    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int REPATH_INTERVAL_TICKS = 30;

    private static final double VILLAGE_SCAN_RADIUS = 56.0D;
    private static final double GOLEM_SCAN_RADIUS = 72.0D;
    private static final double PLAYER_LEAVE_RADIUS = 90.0D;
    private static final int BELL_SEARCH_RADIUS = 48;

    private static final double PROTECTOR_WALK_SPEED = 0.85D;
    private static final double WARNING_APPROACH_SPEED = 0.95D;
    private static final double CARRY_SPEED = 1.45D;

    private static final double PHASE_1_GUARD_DISTANCE = 9.0D;
    private static final double PHASE_2_WARNING_DISTANCE = 6.5D;
    private static final double PHASE_3_WARNING_DISTANCE = 13.0D;

    private static final int PROTECTOR_TIMEOUT_TICKS = 1200;

    private static final int WARNING_APPROACH_TICKS = 140;
    private static final double WARNING_HIT_DISTANCE = 4.0D;
    private static final float WARNING_HIT_DAMAGE = 2.0F;

    private static final int POST_WARNING_GRACE_TICKS = 20 * 8;
    private static final int CARRY_TIMEOUT_TICKS = 20 * 4;
    private static final double CARRY_FINISH_DISTANCE = 8.0D;

    private static final int PHASE_2_WARNING_PRESSURE_TICKS = 20 * 85;
    private static final int PHASE_3_WARNING_PRESSURE_TICKS = 20 * 35;

    private static final int PHASE_2_EJECTION_PRESSURE_TICKS = 20 * 40;
    private static final int PHASE_3_EJECTION_PRESSURE_TICKS = 20 * 8;

    private static final int MAX_PRESSURE_TICKS = 20 * 240;

    private static final int POST_EJECTION_LEAVE_GRACE_TICKS = 20 * 12;
    private static final int EXILE_DURATION_TICKS = 20 * 60 * 30;

    private static final int TRUE_HOSTILE_WINDOW_TICKS = 20 * 30;

    private final Map<UUID, GolemProtectorTarget> activeProtectors = new HashMap<>();
    private final Map<UUID, CarryTarget> activeCarries = new HashMap<>();
    private final Map<UUID, ForcedThrow> activeForcedThrows = new HashMap<>();
    private final Map<UUID, Long> nextGolemScan = new HashMap<>();
    private final Map<UUID, Map<String, VillageMemory>> villageMemoriesByPlayer = new HashMap<>();
    private final Map<UUID, Long> fullyHostileGolems = new HashMap<>();

    private GolemProtectorHandler() {
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        updateForcedThrow(player);

        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        tryActivateProtectors(player, level);
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof IronGolem golem)) {
            return;
        }

        if (!(golem.level() instanceof ServerLevel level)) {
            return;
        }

        GolemProtectorTarget target = activeProtectors.get(golem.getUUID());

        if (target == null) {
            return;
        }

        updateProtector(golem, level, target);
    }

    @SubscribeEvent
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)) {
            return;
        }

        Entity attacker = event.getSource().getEntity();

        if (!(attacker instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        StillHereDirector.INSTANCE.recordGolemAttack(player);

        if (!shouldControlGolem(golem, level)) {
            return;
        }

        VillageKey villageKey = resolveVillageKey(level, golem.position());

        if (villageKey == null) {
            return;
        }

        activateGolem(player, level, golem, villageKey, true);
    }

    public int forceDebugGolems(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        VillageKey villageKey = resolveVillageKey(level, player.position());

        if (villageKey == null) {
            return 0;
        }

        AABB box = player.getBoundingBox().inflate(GOLEM_SCAN_RADIUS);

        List<IronGolem> golems = level.getEntitiesOfClass(
                IronGolem.class,
                box,
                golem -> golem.isAlive() && shouldControlGolem(golem, level)
        );

        int affected = 0;

        for (IronGolem golem : golems) {
            activateGolem(player, level, golem, villageKey, false);
            golem.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
            affected++;
        }

        return affected;
    }

    public boolean forceDebugEject(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        VillageKey villageKey = resolveVillageKey(level, player.position());

        if (villageKey == null) {
            return false;
        }

        AABB box = player.getBoundingBox().inflate(GOLEM_SCAN_RADIUS);

        IronGolem nearestGolem = level.getEntitiesOfClass(
                        IronGolem.class,
                        box,
                        golem -> golem.isAlive() && shouldControlGolem(golem, level)
                )
                .stream()
                .min(Comparator.comparingDouble(golem -> golem.distanceToSqr(player)))
                .orElse(null);

        if (nearestGolem == null) {
            return false;
        }

        VillageMemory memory = getVillageMemory(player.getUUID(), villageKey);

        activateGolem(player, level, nearestGolem, villageKey, false);
        startCarryEjection(nearestGolem, player, level, villageKey, memory);

        return true;
    }
    private void tryActivateProtectors(ServerPlayer player, ServerLevel level) {
        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);

        if (phase == HorrorPhase.VANILLA_MASK) {
            return;
        }

        UUID playerId = player.getUUID();
        long gameTime = level.getGameTime();

        long nextScan = nextGolemScan.getOrDefault(playerId, 0L);

        if (gameTime < nextScan) {
            return;
        }

        nextGolemScan.put(playerId, gameTime + CHECK_INTERVAL_TICKS);

        VillageKey villageKey = resolveVillageKey(level, player.position());

        if (villageKey == null) {
            return;
        }

        VillageMemory memory = getVillageMemory(playerId, villageKey);
        memory.clearExpiredExile(gameTime);

        List<Villager> villagers = findNearbyVillagers(level, Vec3.atBottomCenterOf(villageKey.anchorPos()), VILLAGE_SCAN_RADIUS);

        if (villagers.isEmpty()) {
            reduceVillagePressure(memory);
            return;
        }

        PlayerProfile profile = StillHereDirector.INSTANCE.getProfile(player);

        if (!memory.isExiled(gameTime)) {
            increaseVillagePressure(player, phase, profile, villagers, memory);
        }

        AABB golemBox = player.getBoundingBox().inflate(GOLEM_SCAN_RADIUS);

        List<IronGolem> golems = level.getEntitiesOfClass(
                IronGolem.class,
                golemBox,
                golem -> golem.isAlive()
                        && shouldControlGolem(golem, level)
                        && !activeProtectors.containsKey(golem.getUUID())
        );

        for (IronGolem golem : golems) {
            activateGolem(player, level, golem, villageKey, false);
        }
    }

    private void activateGolem(ServerPlayer player, ServerLevel level, IronGolem golem, VillageKey villageKey, boolean forceHostile) {
        long gameTime = level.getGameTime();

        activeProtectors.put(
                golem.getUUID(),
                new GolemProtectorTarget(
                        player.getUUID(),
                        villageKey,
                        gameTime + PROTECTOR_TIMEOUT_TICKS,
                        0L,
                        0L
                )
        );

        facePlayer(golem, player);

        if (forceHostile) {
            fullyHostileGolems.put(golem.getUUID(), gameTime + TRUE_HOSTILE_WINDOW_TICKS);
            golem.setTarget(player);
        }
    }

    private void updateProtector(IronGolem golem, ServerLevel level, GolemProtectorTarget target) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(target.playerId());

        if (player == null || !player.isAlive() || !golem.isAlive()) {
            endProtector(golem);
            return;
        }

        CarryTarget carryTarget = activeCarries.get(golem.getUUID());

        if (carryTarget != null) {
            updateCarry(golem, level, carryTarget);
            return;
        }

        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);

        if (phase == HorrorPhase.VANILLA_MASK) {
            endProtector(golem);
            return;
        }

        long gameTime = level.getGameTime();

        if (gameTime > target.endGameTime()) {
            returnGolemHomeOrEnd(golem, level, target.villageKey());
            return;
        }

        if (target.villageKey().anchorPos().distSqr(player.blockPosition()) > PLAYER_LEAVE_RADIUS * PLAYER_LEAVE_RADIUS) {
            returnGolemHomeOrEnd(golem, level, target.villageKey());
            return;
        }

        VillageMemory memory = getVillageMemory(player.getUUID(), target.villageKey());
        memory.clearExpiredExile(gameTime);

        long fullyHostileUntil = fullyHostileGolems.getOrDefault(golem.getUUID(), 0L);

        if (fullyHostileUntil > gameTime) {
            golem.setTarget(player);
            golem.getNavigation().moveTo(player, CARRY_SPEED);
            facePlayer(golem, player);
            return;
        }

        if (fullyHostileUntil > 0L) {
            fullyHostileGolems.remove(golem.getUUID());
            golem.setTarget(null);
        }

        if (memory.isExiled(gameTime) && gameTime >= memory.postEjectionGraceUntilGameTime) {
            golem.setTarget(player);
            golem.getNavigation().moveTo(player, CARRY_SPEED);
            facePlayer(golem, player);
            return;
        }

        List<Villager> villagers = findNearbyVillagers(level, Vec3.atBottomCenterOf(target.villageKey().anchorPos()), VILLAGE_SCAN_RADIUS);

        if (villagers.isEmpty()) {
            returnGolemHomeOrEnd(golem, level, target.villageKey());
            return;
        }

        Villager protectedVillager = findProtectedVillager(player, villagers);

        if (protectedVillager == null) {
            returnGolemHomeOrEnd(golem, level, target.villageKey());
            return;
        }

        PlayerProfile profile = StillHereDirector.INSTANCE.getProfile(player);

        boolean playerCloseToVillager = isPlayerTooCloseToVillager(player, protectedVillager, phase);
        boolean highThreat = profile.villageThreat() >= 70 || profile.recentViolence() >= 75;

        if (target.warningUntilGameTime() > 0L && gameTime < target.warningUntilGameTime()) {
            if (memory.warningIssuedGameTime > 0L || memory.warningApproachUntilGameTime < gameTime) {
                activeProtectors.put(golem.getUUID(), target.withWarningFinished());
                golem.getNavigation().stop();
                golem.setTarget(null);
                facePlayer(golem, player);
                return;
            }

            golem.setTarget(null);
            facePlayer(golem, player);

            if (golem.distanceToSqr(player) <= WARNING_HIT_DISTANCE * WARNING_HIT_DISTANCE) {
                performWarningHit(golem, player, level, memory);
                activeProtectors.put(golem.getUUID(), target.withWarningFinished());
                returnGolemHomeOrEnd(golem, level, target.villageKey());
                return;
            }

            golem.getNavigation().moveTo(player, WARNING_APPROACH_SPEED);
            return;
        }

        if (target.warningUntilGameTime() > 0L) {
            activeProtectors.put(golem.getUUID(), target.withWarningFinished());
        }

        if (shouldStartCarryEjection(player, phase, playerCloseToVillager, highThreat, memory, gameTime)) {
            startCarryEjection(golem, player, level, target.villageKey(), memory);
            return;
        }

        if (shouldStartWarning(player, phase, playerCloseToVillager, highThreat, memory, gameTime)) {
            memory.warningApproachUntilGameTime = gameTime + WARNING_APPROACH_TICKS;

            activeProtectors.put(
                    golem.getUUID(),
                    target.withWarningUntil(gameTime + WARNING_APPROACH_TICKS)
            );

            golem.setTarget(null);
            facePlayer(golem, player);
            golem.getNavigation().moveTo(player, WARNING_APPROACH_SPEED);
            return;
        }

        golem.setTarget(null);
        facePlayer(golem, player);

        if (gameTime >= target.nextRepathGameTime() || golem.getNavigation().isDone()) {
            Vec3 guardPoint = findGuardPoint(level, player, protectedVillager, golem);

            if (guardPoint != null) {
                golem.getNavigation().moveTo(guardPoint.x, guardPoint.y, guardPoint.z, PROTECTOR_WALK_SPEED);
            }

            activeProtectors.put(
                    golem.getUUID(),
                    target.withNextRepath(gameTime + REPATH_INTERVAL_TICKS)
            );
        }
    }

    private boolean shouldStartWarning(
            ServerPlayer player,
            HorrorPhase phase,
            boolean playerCloseToVillager,
            boolean highThreat,
            VillageMemory memory,
            long gameTime
    ) {
        if (phase == HorrorPhase.RECOGNITION) {
            return false;
        }

        if (memory.isExiled(gameTime)) {
            return false;
        }

        if (memory.warningIssuedGameTime > 0L) {
            return false;
        }

        if (memory.warningApproachUntilGameTime > gameTime || memory.carryActiveUntilGameTime > gameTime) {
            return false;
        }

        int threshold = phase == HorrorPhase.THE_WATCHER
                ? PHASE_2_WARNING_PRESSURE_TICKS
                : PHASE_3_WARNING_PRESSURE_TICKS;

        if (memory.pressureTicks < threshold) {
            return false;
        }

        if (phase == HorrorPhase.THE_WATCHER) {
            return playerCloseToVillager || highThreat;
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE) {
            return true;
        }

        return false;
    }

    private boolean shouldStartCarryEjection(
            ServerPlayer player,
            HorrorPhase phase,
            boolean playerCloseToVillager,
            boolean highThreat,
            VillageMemory memory,
            long gameTime
    ) {
        /*
         * Ejection is the consequence of ignoring the warning.
         * After the warning hit, if the player remains in the same village past
         * the grace period, the golem should forcibly remove them.
         */
        if (phase == HorrorPhase.RECOGNITION) {
            return false;
        }

        if (memory.isExiled(gameTime)) {
            return false;
        }

        if (memory.warningIssuedGameTime <= 0L) {
            return false;
        }

        if (gameTime < memory.warningIssuedGameTime + POST_WARNING_GRACE_TICKS) {
            return false;
        }

        if (memory.carryActiveUntilGameTime > gameTime) {
            return false;
        }

        if (phase == HorrorPhase.THE_WATCHER) {
            return playerCloseToVillager || highThreat || memory.pressureTicks >= PHASE_2_EJECTION_PRESSURE_TICKS;
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE) {
            return true;
        }

        return false;
    }

    private void performWarningHit(IronGolem golem, ServerPlayer player, ServerLevel level, VillageMemory memory) {
        player.displayClientMessage(Component.literal("Leave"), true);
        player.hurt(golem.damageSources().mobAttack(golem), WARNING_HIT_DAMAGE);

        long gameTime = level.getGameTime();

        memory.pressureTicks = 0;
        memory.warningIssuedGameTime = gameTime;
        memory.warningApproachUntilGameTime = 0L;
        memory.carryActiveUntilGameTime = 0L;
    }

    private void startCarryEjection(IronGolem golem, ServerPlayer player, ServerLevel level, VillageKey villageKey, VillageMemory memory) {
        long gameTime = level.getGameTime();
        Vec3 destination = findEjectionDestination(level, player, villageKey);

        memory.carryActiveUntilGameTime = gameTime + CARRY_TIMEOUT_TICKS;

        activeCarries.put(
                golem.getUUID(),
                new CarryTarget(
                        player.getUUID(),
                        villageKey,
                        destination.x,
                        destination.y,
                        destination.z,
                        gameTime + CARRY_TIMEOUT_TICKS
                )
        );

        /*
         * Carry mode must not use vanilla targeting.
         * If the golem has a target, it will try to attack instead of carrying.
         */
        fullyHostileGolems.remove(golem.getUUID());
        golem.setTarget(null);
        golem.getNavigation().stop();

        /*
         * Proper physical grab:
         * mount the player onto the selected golem instead of teleporting them every tick.
         * This should visibly attach the player to that exact golem.
         */
        if (player.getVehicle() != golem) {
            player.startRiding(golem, true);
        }

        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 10, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 140, 4, false, false, true));

        golem.getNavigation().moveTo(destination.x, destination.y, destination.z, CARRY_SPEED);

        level.playSound(null, player.blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.2F, 0.7F);
    }

    private void updateCarry(IronGolem golem, ServerLevel level, CarryTarget carryTarget) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(carryTarget.playerId());

        if (player == null || !player.isAlive() || !golem.isAlive()) {
            activeCarries.remove(golem.getUUID());
            endProtector(golem);
            return;
        }

        long gameTime = level.getGameTime();

        /*
         * Keep this golem in carry mode, not attack mode.
         */
        fullyHostileGolems.remove(golem.getUUID());
        golem.setTarget(null);

        /*
         * Keep the player physically attached to the chosen golem.
         * If they dismount or get desynced, remount them while carry is active.
         */
        if (player.getVehicle() != golem) {
            player.startRiding(golem, true);
        }

        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 40, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 10, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, 4, false, false, true));

        Vec3 destination = new Vec3(carryTarget.destinationX(), carryTarget.destinationY(), carryTarget.destinationZ());

        if (golem.getNavigation().isDone() || golem.tickCount % 20 == 0) {
            golem.getNavigation().moveTo(destination.x, destination.y, destination.z, CARRY_SPEED);
        }

        faceAwayFromVillage(golem, carryTarget.villageKey());

        double distanceToDestination = golem.position().distanceToSqr(destination);

        boolean arrived = distanceToDestination <= CARRY_FINISH_DISTANCE * CARRY_FINISH_DISTANCE;
        boolean timedOut = gameTime >= carryTarget.endGameTime();

        if (arrived || timedOut) {
            VillageMemory memory = getVillageMemory(player.getUUID(), carryTarget.villageKey());
            performFinalEjection(golem, player, level, carryTarget.villageKey(), memory);

            activeCarries.remove(golem.getUUID());
            returnGolemHomeOrEnd(golem, level, carryTarget.villageKey());
        }
    }

    private void performFinalEjection(IronGolem golem, ServerPlayer player, ServerLevel level, VillageKey villageKey, VillageMemory memory) {
        long gameTime = level.getGameTime();

        Vec3 villageCentre = Vec3.atBottomCenterOf(villageKey.anchorPos());
        Vec3 away = player.position().subtract(villageCentre);

        if (away.lengthSqr() < 0.001D) {
            away = player.position().subtract(golem.position());
        }

        if (away.lengthSqr() < 0.001D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }

        away = away.normalize();

        /*
         * Dismount first. The actual throw is applied over the next few ticks
         * because Minecraft often clears velocity immediately after dismounting.
         */
        if (player.getVehicle() == golem) {
            player.stopRiding();
        }

        player.teleportTo(
                golem.getX() + away.x * 2.0D,
                golem.getY() + 1.3D,
                golem.getZ() + away.z * 2.0D
        );

        player.displayClientMessage(Component.literal("GET OUT"), true);

        level.playSound(null, player.blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.4F, 0.45F);
        level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.45F, 1.4F);

        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 90, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 35, 2, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 180, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 140, 4, false, false, true));

        activeForcedThrows.put(
                player.getUUID(),
                new ForcedThrow(
                        away.x * 7.5D,
                        1.85D,
                        away.z * 7.5D,
                        5
                )
        );

        updateForcedThrow(player);

        memory.pressureTicks = 0;
        memory.warningIssuedGameTime = 0L;
        memory.warningApproachUntilGameTime = 0L;
        memory.carryActiveUntilGameTime = 0L;
        memory.exileUntilGameTime = gameTime + EXILE_DURATION_TICKS;
        memory.postEjectionGraceUntilGameTime = gameTime + POST_EJECTION_LEAVE_GRACE_TICKS;
    }

    private void updateForcedThrow(ServerPlayer player) {
        ForcedThrow forcedThrow = activeForcedThrows.get(player.getUUID());

        if (forcedThrow == null) {
            return;
        }

        if (player.getVehicle() != null) {
            player.stopRiding();
        }

        player.setDeltaMovement(forcedThrow.xVelocity(), forcedThrow.yVelocity(), forcedThrow.zVelocity());
        player.push(forcedThrow.xVelocity() * 0.15D, 0.05D, forcedThrow.zVelocity() * 0.15D);
        player.fallDistance = 0.0F;
        player.hasImpulse = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        int remainingTicks = forcedThrow.remainingTicks() - 1;

        if (remainingTicks <= 0) {
            activeForcedThrows.remove(player.getUUID());
            return;
        }

        activeForcedThrows.put(
                player.getUUID(),
                new ForcedThrow(
                        forcedThrow.xVelocity(),
                        forcedThrow.yVelocity(),
                        forcedThrow.zVelocity(),
                        remainingTicks
                )
        );
    }

    private Vec3 findEjectionDestination(ServerLevel level, ServerPlayer player, VillageKey villageKey) {
        Vec3 villageCentre = Vec3.atBottomCenterOf(villageKey.anchorPos());
        Vec3 away = player.position().subtract(villageCentre);

        if (away.lengthSqr() < 0.001D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }

        away = away.normalize();

        double[] distances = new double[]{14.0D, 18.0D, 24.0D};

        for (double distance : distances) {
            double x = villageCentre.x + away.x * distance;
            double z = villageCentre.z + away.z * distance;

            BlockPos ground = findNearbyGround(level, BlockPos.containing(x, player.getY(), z));

            if (ground != null) {
                return Vec3.atBottomCenterOf(ground);
            }
        }

        return player.position().add(away.x * 16.0D, 0.0D, away.z * 16.0D);
    }

    private void increaseVillagePressure(
            ServerPlayer player,
            HorrorPhase phase,
            PlayerProfile profile,
            List<Villager> villagers,
            VillageMemory memory
    ) {
        int add = CHECK_INTERVAL_TICKS;

        boolean closeToVillager = villagers.stream().anyMatch(villager ->
                isPlayerTooCloseToVillager(player, villager, phase)
        );

        if (closeToVillager) {
            add += CHECK_INTERVAL_TICKS;
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE && closeToVillager) {
            add += CHECK_INTERVAL_TICKS;
        }

        if (profile.villageThreat() >= 70 || profile.recentViolence() >= 75) {
            add += CHECK_INTERVAL_TICKS;
        }

        memory.pressureTicks = Math.min(memory.pressureTicks + add, MAX_PRESSURE_TICKS);
    }

    private void reduceVillagePressure(VillageMemory memory) {
        if (memory.pressureTicks <= 0) {
            memory.pressureTicks = 0;
            return;
        }

        memory.pressureTicks = Math.max(0, memory.pressureTicks - CHECK_INTERVAL_TICKS * 2);
    }

    private VillageMemory getVillageMemory(UUID playerId, VillageKey villageKey) {
        Map<String, VillageMemory> byVillage = villageMemoriesByPlayer.computeIfAbsent(playerId, ignored -> new HashMap<>());
        return byVillage.computeIfAbsent(villageKey.id(), ignored -> new VillageMemory());
    }

    private VillageKey resolveVillageKey(ServerLevel level, Vec3 centreVec) {
        BlockPos centre = BlockPos.containing(centreVec);

        BlockPos nearestBell = null;
        double nearestBellDistance = Double.MAX_VALUE;

        for (int dx = -BELL_SEARCH_RADIUS; dx <= BELL_SEARCH_RADIUS; dx++) {
            for (int dz = -BELL_SEARCH_RADIUS; dz <= BELL_SEARCH_RADIUS; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = centre.offset(dx, dy, dz);

                    if (!(level.getBlockState(pos).getBlock() instanceof BellBlock)) {
                        continue;
                    }

                    double distance = pos.distSqr(centre);

                    if (distance < nearestBellDistance) {
                        nearestBellDistance = distance;
                        nearestBell = pos.immutable();
                    }
                }
            }
        }

        if (nearestBell != null) {
            return new VillageKey(
                    "bell:" + nearestBell.getX() + ":" + nearestBell.getY() + ":" + nearestBell.getZ(),
                    nearestBell
            );
        }

        List<Villager> villagers = findNearbyVillagers(level, centreVec, VILLAGE_SCAN_RADIUS);

        if (villagers.isEmpty()) {
            return null;
        }

        Villager nearestVillager = villagers.stream()
                .min(Comparator.comparingDouble(villager -> villager.position().distanceToSqr(centreVec)))
                .orElse(null);

        if (nearestVillager == null) {
            return null;
        }

        BlockPos fallback = nearestVillager.blockPosition().immutable();

        return new VillageKey(
                "fallback:" + fallback.getX() + ":" + fallback.getY() + ":" + fallback.getZ(),
                fallback
        );
    }

    private boolean isPlayerTooCloseToVillager(ServerPlayer player, Villager villager, HorrorPhase phase) {
        double distance = switch (phase) {
            case VANILLA_MASK -> 0.0D;
            case RECOGNITION -> PHASE_1_GUARD_DISTANCE;
            case THE_WATCHER -> PHASE_2_WARNING_DISTANCE;
            case MEMORY_LEAKAGE -> PHASE_3_WARNING_DISTANCE;
        };

        return villager.distanceToSqr(player) <= distance * distance;
    }

    private Villager findProtectedVillager(ServerPlayer player, List<Villager> villagers) {
        return villagers.stream()
                .filter(Villager::isAlive)
                .min(Comparator.comparingDouble(villager -> villager.distanceToSqr(player)))
                .orElse(null);
    }

    private List<Villager> findNearbyVillagers(ServerLevel level, Vec3 centre, double radius) {
        AABB box = new AABB(
                centre.x - radius,
                centre.y - 16.0D,
                centre.z - radius,
                centre.x + radius,
                centre.y + 16.0D,
                centre.z + radius
        );

        return level.getEntitiesOfClass(
                Villager.class,
                box,
                villager -> villager.isAlive()
        );
    }

    private Vec3 findGuardPoint(ServerLevel level, ServerPlayer player, Villager villager, IronGolem golem) {
        Vec3 playerPos = player.position();
        Vec3 villagerPos = villager.position();

        Vec3 directionFromPlayerToVillager = villagerPos.subtract(playerPos);

        if (directionFromPlayerToVillager.lengthSqr() < 0.001D) {
            return null;
        }

        Vec3 normal = directionFromPlayerToVillager.normalize();
        Vec3 rawGuardPoint = villagerPos.subtract(normal.scale(3.0D));

        BlockPos ground = findNearbyGround(level, BlockPos.containing(rawGuardPoint.x, golem.getY(), rawGuardPoint.z));

        if (ground == null) {
            return null;
        }

        return Vec3.atBottomCenterOf(ground);
    }

    private void faceAwayFromVillage(IronGolem golem, VillageKey villageKey) {
        Vec3 villageCentre = Vec3.atBottomCenterOf(villageKey.anchorPos());
        Vec3 away = golem.position().subtract(villageCentre);

        if (away.lengthSqr() < 0.001D) {
            return;
        }

        away = away.normalize();

        float yaw = (float) (Mth.atan2(away.z, away.x) * (180F / Math.PI)) - 90.0F;

        golem.setYHeadRot(yaw);
        golem.setYBodyRot(yaw);
        golem.setYRot(yaw);
    }

    private void returnGolemHomeOrEnd(IronGolem golem, ServerLevel level, VillageKey villageKey) {
        golem.setTarget(null);

        BlockPos ground = findNearbyGround(level, villageKey.anchorPos());

        Vec3 home = ground != null
                ? Vec3.atBottomCenterOf(ground)
                : Vec3.atBottomCenterOf(villageKey.anchorPos());

        if (golem.position().distanceToSqr(home) > 16.0D) {
            golem.getNavigation().moveTo(home.x, home.y, home.z, PROTECTOR_WALK_SPEED);
            return;
        }

        endProtector(golem);
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

    private boolean shouldControlGolem(IronGolem golem, ServerLevel level) {
        if (isPlayerCreatedGolem(golem)) {
            return false;
        }

        List<Villager> villagers = findNearbyVillagers(level, golem.position(), VILLAGE_SCAN_RADIUS);

        return !villagers.isEmpty();
    }

    private boolean isPlayerCreatedGolem(IronGolem golem) {
        try {
            Method method = golem.getClass().getMethod("isPlayerCreated");
            Object result = method.invoke(golem);

            if (result instanceof Boolean value) {
                return value;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        return false;
    }

    private void facePlayer(IronGolem golem, ServerPlayer player) {
        double dx = player.getX() - golem.getX();
        double dz = player.getZ() - golem.getZ();
        double dy = player.getEyeY() - golem.getEyeY();

        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        golem.setYHeadRot(yaw);
        golem.setXRot(pitch);
        golem.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private void endProtector(IronGolem golem) {
        golem.setTarget(null);
        activeCarries.remove(golem.getUUID());
        activeProtectors.remove(golem.getUUID());
    }

    private record VillageKey(
            String id,
            BlockPos anchorPos
    ) {
    }

    private static final class VillageMemory {
        private int pressureTicks;
        private long warningIssuedGameTime;
        private long exileUntilGameTime;
        private long postEjectionGraceUntilGameTime;
        private long warningApproachUntilGameTime;
        private long carryActiveUntilGameTime;

        private boolean isExiled(long gameTime) {
            return exileUntilGameTime > gameTime;
        }

        private void clearExpiredExile(long gameTime) {
            if (exileUntilGameTime > 0L && gameTime >= exileUntilGameTime) {
                exileUntilGameTime = 0L;
                postEjectionGraceUntilGameTime = 0L;
                warningIssuedGameTime = 0L;
                warningApproachUntilGameTime = 0L;
                carryActiveUntilGameTime = 0L;
                pressureTicks = 0;
            }
        }
    }

    private record CarryTarget(
            UUID playerId,
            VillageKey villageKey,
            double destinationX,
            double destinationY,
            double destinationZ,
            long endGameTime
    ) {
    }

    private record ForcedThrow(
            double xVelocity,
            double yVelocity,
            double zVelocity,
            int remainingTicks
    ) {
    }

    private record GolemProtectorTarget(
            UUID playerId,
            VillageKey villageKey,
            long endGameTime,
            long nextRepathGameTime,
            long warningUntilGameTime
    ) {
        GolemProtectorTarget withNextRepath(long nextRepathGameTime) {
            return new GolemProtectorTarget(playerId, villageKey, endGameTime, nextRepathGameTime, warningUntilGameTime);
        }

        GolemProtectorTarget withWarningUntil(long warningUntilGameTime) {
            return new GolemProtectorTarget(playerId, villageKey, endGameTime, nextRepathGameTime, warningUntilGameTime);
        }

        GolemProtectorTarget withWarningFinished() {
            return new GolemProtectorTarget(playerId, villageKey, endGameTime, nextRepathGameTime, 0L);
        }
    }
}