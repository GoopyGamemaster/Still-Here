package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.PlayerProfile;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
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

    private static final double PROTECTOR_WALK_SPEED = 0.85D;
    private static final double WARNING_CHASE_SPEED = 1.10D;

    private static final double PHASE_1_GUARD_DISTANCE = 9.0D;
    private static final double PHASE_2_WARNING_DISTANCE = 6.5D;
    private static final double PHASE_3_WARNING_DISTANCE = 9.0D;

    private static final int PROTECTOR_TIMEOUT_TICKS = 1200;
    private static final int WARNING_TARGET_TICKS = 70;

    private final Map<UUID, GolemProtectorTarget> activeProtectors = new HashMap<>();
    private final Map<UUID, Long> nextGolemScan = new HashMap<>();
    private final Map<UUID, Long> nextWarningAllowed = new HashMap<>();

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

        /*
         * Attacking a golem is an immediate village threat signal.
         */
        StillHereDirector.INSTANCE.recordGolemAttack(player);

        if (!shouldControlGolem(golem, level)) {
            return;
        }

        activateGolem(player, level, golem, true);
    }

    public int forceDebugGolems(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
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
            activateGolem(player, level, golem, false);
            golem.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
            affected++;
        }

        return affected;
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

        List<Villager> villagers = findNearbyVillagers(level, player.position(), VILLAGE_SCAN_RADIUS);

        if (villagers.isEmpty()) {
            return;
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
            activateGolem(player, level, golem, false);
        }
    }

    private void activateGolem(ServerPlayer player, ServerLevel level, IronGolem golem, boolean forceHostile) {
        long gameTime = level.getGameTime();

        activeProtectors.put(
                golem.getUUID(),
                new GolemProtectorTarget(
                        player.getUUID(),
                        gameTime + PROTECTOR_TIMEOUT_TICKS,
                        0L,
                        forceHostile ? gameTime + 200 : 0L
                )
        );

        facePlayer(golem, player);

        if (forceHostile) {
            golem.setTarget(player);
        }
    }

    private void updateProtector(IronGolem golem, ServerLevel level, GolemProtectorTarget target) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(target.playerId());

        if (player == null || !player.isAlive() || !golem.isAlive()) {
            endProtector(golem);
            return;
        }

        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);

        if (phase == HorrorPhase.VANILLA_MASK) {
            endProtector(golem);
            return;
        }

        long gameTime = level.getGameTime();

        if (gameTime > target.endGameTime() || golem.distanceToSqr(player) > PLAYER_LEAVE_RADIUS * PLAYER_LEAVE_RADIUS) {
            endProtector(golem);
            return;
        }

        List<Villager> villagers = findNearbyVillagers(level, player.position(), VILLAGE_SCAN_RADIUS);

        if (villagers.isEmpty()) {
            endProtector(golem);
            return;
        }

        Villager protectedVillager = findProtectedVillager(player, villagers);

        if (protectedVillager == null) {
            endProtector(golem);
            return;
        }

        PlayerProfile profile = StillHereDirector.INSTANCE.getProfile(player);

        boolean playerCloseToVillager = isPlayerTooCloseToVillager(player, protectedVillager, phase);
        boolean highThreat = profile.villageThreat() >= 60 || profile.recentViolence() >= 60;
        boolean extremeThreat = profile.villageThreat() >= 85 || profile.recentViolence() >= 85;

        /*
         * If the golem is currently in a warning/hostile window, let vanilla attack AI handle movement.
         */
        if (target.hostileUntilGameTime() > 0L && gameTime < target.hostileUntilGameTime()) {
            golem.setTarget(player);
            facePlayer(golem, player);
            return;
        }

        if (target.hostileUntilGameTime() > 0L) {
            golem.setTarget(null);

            GolemProtectorTarget updated = target.withHostileUntil(0L);
            activeProtectors.put(golem.getUUID(), updated);
            target = updated;
        }

        /*
         * Phase 2: warning aggression only if player gets too close or has threat history.
         * Phase 3: much lower tolerance.
         */
        if (shouldWarnOrAttack(player, level, phase, playerCloseToVillager, highThreat, extremeThreat)) {
            long hostileUntil = gameTime + WARNING_TARGET_TICKS;

            activeProtectors.put(
                    golem.getUUID(),
                    target.withHostileUntil(hostileUntil)
            );

            golem.getNavigation().moveTo(player, WARNING_CHASE_SPEED);
            golem.setTarget(player);
            facePlayer(golem, player);
            return;
        }

        /*
         * Otherwise, bodyguard mode:
         * move between the player and the protected villager, then stare.
         */
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

    private boolean shouldWarnOrAttack(
            ServerPlayer player,
            ServerLevel level,
            HorrorPhase phase,
            boolean playerCloseToVillager,
            boolean highThreat,
            boolean extremeThreat
    ) {
        if (phase == HorrorPhase.RECOGNITION) {
            return false;
        }

        UUID playerId = player.getUUID();
        long gameTime = level.getGameTime();

        long nextWarning = nextWarningAllowed.getOrDefault(playerId, 0L);

        if (gameTime < nextWarning) {
            return false;
        }

        if (phase == HorrorPhase.THE_WATCHER) {
            if (playerCloseToVillager || highThreat) {
                nextWarningAllowed.put(playerId, gameTime + 240);
                return true;
            }

            return false;
        }

        if (phase == HorrorPhase.MEMORY_LEAKAGE) {
            if (playerCloseToVillager || highThreat || extremeThreat) {
                nextWarningAllowed.put(playerId, gameTime + 180);
                return true;
            }
        }

        return false;
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

        /*
         * Stand between the player and the villager, slightly closer to the villager.
         */
        Vec3 rawGuardPoint = villagerPos.subtract(normal.scale(3.0D));

        BlockPos ground = findNearbyGround(level, BlockPos.containing(rawGuardPoint.x, golem.getY(), rawGuardPoint.z));

        if (ground == null) {
            return null;
        }

        return Vec3.atBottomCenterOf(ground);
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
        /*
         * Player-created golems should mostly remain normal.
         * Reflection avoids compile problems if mappings differ.
         */
        if (isPlayerCreatedGolem(golem)) {
            return false;
        }

        /*
         * Also require nearby villagers so random/player-made golems away from villages
         * are not pulled into village protector behaviour.
         */
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
            /*
             * If the method is not available under this mapping, fall back to village proximity.
             */
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
        activeProtectors.remove(golem.getUUID());
    }

    private record GolemProtectorTarget(
            UUID playerId,
            long endGameTime,
            long nextRepathGameTime,
            long hostileUntilGameTime
    ) {
        GolemProtectorTarget withNextRepath(long nextRepathGameTime) {
            return new GolemProtectorTarget(playerId, endGameTime, nextRepathGameTime, hostileUntilGameTime);
        }

        GolemProtectorTarget withHostileUntil(long hostileUntilGameTime) {
            return new GolemProtectorTarget(playerId, endGameTime, nextRepathGameTime, hostileUntilGameTime);
        }
    }
}