package com.goopygamemaster.stillhere.event;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
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

public class VillagerFearHandler {
    public static final VillagerFearHandler INSTANCE = new VillagerFearHandler();

    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int REPATH_INTERVAL_TICKS = 30;

    private static final int ALL_VILLAGERS_IN_RANGE = -1;

    private static final double DEBUG_RADIUS = 64.0D;
    private static final int DEBUG_TIMEOUT_TICKS = 700;

    private static final double ATTACK_PANIC_RADIUS = 70.0D;
    private static final int ATTACK_PANIC_TIMEOUT_TICKS = 900;
    private static final double ATTACK_PANIC_SPEED = 1.10D;
    private static final double ATTACK_SAFE_DISTANCE = 52.0D;

    private static final int SHELTER_SEARCH_RADIUS = 32;
    private static final int SHELTER_VERTICAL_SEARCH = 5;
    private static final int BELL_SEARCH_RADIUS = 48;

    private final Map<UUID, VillagerFearTarget> activeFears = new HashMap<>();
    private final Map<UUID, Long> nextVillageAlertCheck = new HashMap<>();

    private VillagerFearHandler() {
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

        tryVillageAlert(player, level);
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }

        VillagerFearTarget target = activeFears.get(villager.getUUID());

        if (target == null) {
            return;
        }

        updateVillagerFear(villager, level, target);
    }

    @SubscribeEvent
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Villager attackedVillager)) {
            return;
        }

        Entity attacker = event.getSource().getEntity();

        if (!(attacker instanceof ServerPlayer player)) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        StillHereDirector.INSTANCE.recordVillagerAttack(player);
        ringNearestBell(level, attackedVillager.blockPosition());

        startVillagerFear(
                player,
                level,
                ATTACK_PANIC_RADIUS,
                ATTACK_PANIC_TIMEOUT_TICKS,
                ALL_VILLAGERS_IN_RANGE,
                ATTACK_SAFE_DISTANCE,
                ATTACK_PANIC_SPEED,
                0,
                false,
                true
        );
    }

    public int forceDebugVillagerFear(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        ringNearestBell(level, player.blockPosition());

        return startVillagerFear(
                player,
                level,
                DEBUG_RADIUS,
                DEBUG_TIMEOUT_TICKS,
                ALL_VILLAGERS_IN_RANGE,
                48.0D,
                0.95D,
                20,
                true,
                false
        );
    }

    private void tryVillageAlert(ServerPlayer player, ServerLevel level) {
        HorrorPhase phase = StillHereDirector.INSTANCE.getPhase(level);

        if (phase == HorrorPhase.VANILLA_MASK) {
            return;
        }

        long gameTime = level.getGameTime();
        UUID playerId = player.getUUID();

        if (!nextVillageAlertCheck.containsKey(playerId)) {
            nextVillageAlertCheck.put(playerId, gameTime + getInitialDelayTicks(player, phase));
            return;
        }

        if (gameTime < nextVillageAlertCheck.get(playerId)) {
            return;
        }

        VillagerAlertSettings settings = getSettingsForPhase(phase);

        List<Villager> spotters = findSpottingVillagers(player, level, settings.detectionRadius());

        if (spotters.isEmpty()) {
            nextVillageAlertCheck.put(playerId, gameTime + randomBetween(player, 200, 500));
            return;
        }

        boolean closeSpot = spotters.stream().anyMatch(villager ->
                villager.distanceToSqr(player) <= settings.closePanicDistance() * settings.closePanicDistance()
        );

        double chance = closeSpot ? 1.0D : settings.alertChance();

        if (player.getRandom().nextDouble() > chance) {
            nextVillageAlertCheck.put(playerId, gameTime + randomBetween(player, 200, 500));
            return;
        }

        if (settings.ringBell()) {
            ringNearestBell(level, spotters.get(0).blockPosition());
        }

        /*
         * Phase 1 is personal fear: only villagers who see you react.
         * Phase 2/3 are village alert: villagers nearby react once the alarm is raised.
         */
        boolean requireLineOfSight = phase == HorrorPhase.RECOGNITION;

        int affected = startVillagerFear(
                player,
                level,
                settings.alertRadius(),
                settings.timeoutTicks(),
                settings.maxVillagers(),
                settings.safeDistance(),
                settings.speed(),
                settings.stareTicks(),
                false,
                requireLineOfSight
        );

        if (affected > 0) {
            nextVillageAlertCheck.put(playerId, gameTime + randomBetween(player, settings.minCooldownTicks(), settings.maxCooldownTicks()));
        } else {
            nextVillageAlertCheck.put(playerId, gameTime + randomBetween(player, 200, 500));
        }
    }

    private List<Villager> findSpottingVillagers(ServerPlayer player, ServerLevel level, double radius) {
        AABB searchBox = player.getBoundingBox().inflate(radius);

        return level.getEntitiesOfClass(
                Villager.class,
                searchBox,
                villager -> villager.isAlive()
                        && villager.hasLineOfSight(player)
        );
    }

    private VillagerAlertSettings getSettingsForPhase(HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> new VillagerAlertSettings(
                    0.0D,
                    0.0D,
                    0.0D,
                    0,
                    0,
                    0.0D,
                    0.0D,
                    0,
                    0,
                    0,
                    false,
                    0.0D
            );

            case RECOGNITION -> new VillagerAlertSettings(
                    0.40D,
                    28.0D,
                    32.0D,
                    550,
                    3,
                    28.0D,
                    0.70D,
                    35,
                    1000,
                    2200,
                    false,
                    10.0D
            );

            case THE_WATCHER -> new VillagerAlertSettings(
                    0.70D,
                    44.0D,
                    52.0D,
                    800,
                    10,
                    40.0D,
                    0.85D,
                    25,
                    700,
                    1600,
                    true,
                    18.0D
            );

            case MEMORY_LEAKAGE -> new VillagerAlertSettings(
                    0.95D,
                    64.0D,
                    70.0D,
                    1100,
                    ALL_VILLAGERS_IN_RANGE,
                    52.0D,
                    0.95D,
                    10,
                    400,
                    1200,
                    true,
                    28.0D
            );
        };
    }

    private int getInitialDelayTicks(ServerPlayer player, HorrorPhase phase) {
        return switch (phase) {
            case VANILLA_MASK -> randomBetween(player, 2400, 4800);
            case RECOGNITION -> randomBetween(player, 400, 1000);
            case THE_WATCHER -> randomBetween(player, 200, 700);
            case MEMORY_LEAKAGE -> randomBetween(player, 100, 400);
        };
    }

    private int startVillagerFear(
            ServerPlayer player,
            ServerLevel level,
            double radius,
            int timeoutTicks,
            int maxVillagers,
            double safeDistance,
            double speed,
            int stareTicks,
            boolean debugGlow,
            boolean requireLineOfSight
    ) {
        AABB searchBox = player.getBoundingBox().inflate(radius);

        List<Villager> villagers = level.getEntitiesOfClass(
                Villager.class,
                searchBox,
                villager -> villager.isAlive()
                        && !activeFears.containsKey(villager.getUUID())
                        && (!requireLineOfSight || villager.hasLineOfSight(player))
        );

        if (villagers.isEmpty()) {
            return 0;
        }

        Collections.shuffle(villagers);

        long hardEndTime = level.getGameTime() + timeoutTicks;
        long stareUntil = stareTicks > 0 ? level.getGameTime() + stareTicks : 0L;

        int affected = 0;

        for (Villager villager : villagers) {
            if (maxVillagers != ALL_VILLAGERS_IN_RANGE && affected >= maxVillagers) {
                break;
            }

            villager.getNavigation().stop();
            villager.setTarget(null);

            activeFears.put(
                    villager.getUUID(),
                    new VillagerFearTarget(
                            player.getUUID(),
                            hardEndTime,
                            radius,
                            safeDistance,
                            speed,
                            0L,
                            stareUntil
                    )
            );

            forceVillagerLookAtPlayer(villager, player);

            if (stareTicks <= 0) {
                pathVillagerToShelterOrAway(villager, player, level, speed);
            }

            if (debugGlow) {
                villager.addEffect(new MobEffectInstance(MobEffects.GLOWING, timeoutTicks, 0, false, false));
            }

            affected++;
        }

        return affected;
    }

    private void updateVillagerFear(Villager villager, ServerLevel level, VillagerFearTarget target) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(target.playerId());

        if (player == null) {
            activeFears.remove(villager.getUUID());
            return;
        }

        long gameTime = level.getGameTime();

        if (!villager.isAlive()
                || !player.isAlive()
                || gameTime > target.hardEndGameTime()
                || villager.distanceToSqr(player) >= target.safeDistance() * target.safeDistance()) {
            endVillagerFear(villager);
            return;
        }

        if (target.stareUntilGameTime() > 0L && gameTime < target.stareUntilGameTime()) {
            villager.getNavigation().stop();
            villager.setTarget(null);
            villager.setDeltaMovement(0.0D, villager.getDeltaMovement().y, 0.0D);
            forceVillagerLookAtPlayer(villager, player);
            return;
        }

        if (target.stareUntilGameTime() > 0L) {
            VillagerFearTarget updatedTarget = target.withStareFinished(gameTime + REPATH_INTERVAL_TICKS);
            activeFears.put(villager.getUUID(), updatedTarget);
            pathVillagerToShelterOrAway(villager, player, level, updatedTarget.speed());
            return;
        }

        forceVillagerHeadLookAtPlayer(villager, player);

        if (villager.getNavigation().isDone() || gameTime >= target.nextRepathGameTime()) {
            pathVillagerToShelterOrAway(villager, player, level, target.speed());

            activeFears.put(
                    villager.getUUID(),
                    target.withNextRepath(gameTime + REPATH_INTERVAL_TICKS)
            );
        }
    }

    private void endVillagerFear(Villager villager) {
        villager.getNavigation().stop();
        villager.setTarget(null);
        activeFears.remove(villager.getUUID());
    }

    private void forceVillagerLookAtPlayer(Villager villager, ServerPlayer player) {
        double dx = player.getX() - villager.getX();
        double dz = player.getZ() - villager.getZ();
        double dy = player.getEyeY() - villager.getEyeY();

        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        villager.setYRot(yaw);
        villager.setYHeadRot(yaw);
        villager.setYBodyRot(yaw);
        villager.setXRot(pitch);
        villager.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private void forceVillagerHeadLookAtPlayer(Villager villager, ServerPlayer player) {
        double dx = player.getX() - villager.getX();
        double dz = player.getZ() - villager.getZ();
        double dy = player.getEyeY() - villager.getEyeY();

        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        villager.setYHeadRot(yaw);
        villager.setXRot(pitch);
        villager.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private void pathVillagerToShelterOrAway(Villager villager, ServerPlayer player, ServerLevel level, double speed) {
        Vec3 shelter = findBestShelterPoint(villager, player, level);

        if (shelter != null) {
            villager.getNavigation().moveTo(shelter.x, shelter.y, shelter.z, speed);
            return;
        }

        Vec3 fallback = findBestEscapePoint(villager, player, level);

        if (fallback != null) {
            villager.getNavigation().moveTo(fallback.x, fallback.y, fallback.z, speed);
        }
    }

    private Vec3 findBestShelterPoint(Villager villager, ServerPlayer player, ServerLevel level) {
        BlockPos centre = villager.blockPosition();
        Vec3 playerPos = player.position();

        Vec3 bestPoint = null;
        double bestScore = -999999.0D;

        for (int dx = -SHELTER_SEARCH_RADIUS; dx <= SHELTER_SEARCH_RADIUS; dx++) {
            for (int dz = -SHELTER_SEARCH_RADIUS; dz <= SHELTER_SEARCH_RADIUS; dz++) {
                for (int dy = -SHELTER_VERTICAL_SEARCH; dy <= SHELTER_VERTICAL_SEARCH; dy++) {
                    BlockPos pos = centre.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    boolean isDoor = state.getBlock() instanceof DoorBlock;
                    boolean isBed = state.getBlock() instanceof BedBlock;

                    if (!isDoor && !isBed) {
                        continue;
                    }

                    Vec3 walkPoint = findWalkablePointNearShelter(level, pos);

                    if (walkPoint == null) {
                        continue;
                    }

                    double distanceFromPlayer = walkPoint.distanceTo(playerPos);
                    double distanceFromVillager = walkPoint.distanceTo(villager.position());
                    boolean breaksSight = !hasClearSightFromPlayerToPoint(player, level, walkPoint.add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));

                    double score = 0.0D;

                    if (isBed) {
                        score += 35.0D;
                    }

                    if (isDoor) {
                        score += 25.0D;
                    }

                    score += distanceFromPlayer * 1.5D;

                    if (breaksSight) {
                        score += 80.0D;
                    }

                    score -= distanceFromVillager * 0.7D;

                    if (score > bestScore) {
                        bestScore = score;
                        bestPoint = walkPoint;
                    }
                }
            }
        }

        return bestPoint;
    }

    private Vec3 findWalkablePointNearShelter(ServerLevel level, BlockPos shelterBlock) {
        Direction[] directions = new Direction[]{
                Direction.NORTH,
                Direction.SOUTH,
                Direction.EAST,
                Direction.WEST
        };

        for (Direction direction : directions) {
            BlockPos candidate = shelterBlock.relative(direction);

            BlockPos ground = findNearbyGround(level, candidate);

            if (ground != null) {
                return Vec3.atBottomCenterOf(ground);
            }
        }

        BlockPos ground = findNearbyGround(level, shelterBlock);

        if (ground != null) {
            return Vec3.atBottomCenterOf(ground);
        }

        return null;
    }

    private Vec3 findBestEscapePoint(Villager villager, ServerPlayer player, ServerLevel level) {
        Vec3 villagerPos = villager.position();
        Vec3 playerPos = player.position();

        double awayX = villagerPos.x - playerPos.x;
        double awayZ = villagerPos.z - playerPos.z;

        double length = Math.sqrt((awayX * awayX) + (awayZ * awayZ));

        if (length < 0.001D) {
            return null;
        }

        awayX /= length;
        awayZ /= length;

        double sideX = -awayZ;
        double sideZ = awayX;

        Vec3 bestPoint = null;
        double bestScore = -999999.0D;

        double[] forwardDistances = new double[]{10.0D, 14.0D, 20.0D};
        double[] sideOffsets = new double[]{0.0D, 6.0D, -6.0D, 12.0D, -12.0D};

        for (double forward : forwardDistances) {
            for (double side : sideOffsets) {
                double targetX = villagerPos.x + (awayX * forward) + (sideX * side);
                double targetZ = villagerPos.z + (awayZ * forward) + (sideZ * side);

                BlockPos ground = findNearbyGround(level, BlockPos.containing(targetX, villager.getY(), targetZ));

                if (ground == null) {
                    continue;
                }

                Vec3 candidate = Vec3.atBottomCenterOf(ground);

                double distanceFromPlayer = candidate.distanceTo(playerPos);
                boolean breaksSight = !hasClearSightFromPlayerToPoint(player, level, candidate.add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));

                double score = distanceFromPlayer;

                if (breaksSight) {
                    score += 60.0D;
                }

                if (candidate.y < villager.getY() - 3.0D || candidate.y > villager.getY() + 2.0D) {
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

    private void ringNearestBell(ServerLevel level, BlockPos centre) {
        BlockPos nearestBell = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int dx = -BELL_SEARCH_RADIUS; dx <= BELL_SEARCH_RADIUS; dx++) {
            for (int dz = -BELL_SEARCH_RADIUS; dz <= BELL_SEARCH_RADIUS; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = centre.offset(dx, dy, dz);

                    if (!(level.getBlockState(pos).getBlock() instanceof BellBlock)) {
                        continue;
                    }

                    double distance = pos.distSqr(centre);

                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestBell = pos;
                    }
                }
            }
        }

        if (nearestBell != null) {
            level.playSound(null, nearestBell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.4F, 1.0F);
        }
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

    private int randomBetween(ServerPlayer player, int minTicks, int maxTicks) {
        int range = Math.max(1, maxTicks - minTicks + 1);
        return minTicks + player.getRandom().nextInt(range);
    }

    private record VillagerFearTarget(
            UUID playerId,
            long hardEndGameTime,
            double radius,
            double safeDistance,
            double speed,
            long nextRepathGameTime,
            long stareUntilGameTime
    ) {
        VillagerFearTarget withNextRepath(long nextRepathGameTime) {
            return new VillagerFearTarget(playerId, hardEndGameTime, radius, safeDistance, speed, nextRepathGameTime, stareUntilGameTime);
        }

        VillagerFearTarget withStareFinished(long nextRepathGameTime) {
            return new VillagerFearTarget(playerId, hardEndGameTime, radius, safeDistance, speed, nextRepathGameTime, 0L);
        }
    }

    private record VillagerAlertSettings(
            double alertChance,
            double detectionRadius,
            double alertRadius,
            int timeoutTicks,
            int maxVillagers,
            double safeDistance,
            double speed,
            int stareTicks,
            int minCooldownTicks,
            int maxCooldownTicks,
            boolean ringBell,
            double closePanicDistance
    ) {
    }
}