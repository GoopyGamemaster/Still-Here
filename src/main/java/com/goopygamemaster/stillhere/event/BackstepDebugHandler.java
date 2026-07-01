package com.goopygamemaster.stillhere.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BackstepDebugHandler {
    public static final BackstepDebugHandler INSTANCE = new BackstepDebugHandler();

    private static final double DEBUG_RADIUS = 32.0D;
    private static final int BACKSTEP_DURATION_TICKS = 80;
    private static final double BACKSTEP_SPEED = 0.055D;

    private final Map<UUID, BackstepTarget> activeBacksteps = new HashMap<>();

    private BackstepDebugHandler() {
    }

    public int forceDebugBackstep(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }

        AABB searchBox = player.getBoundingBox().inflate(DEBUG_RADIUS);

        List<Mob> mobs = level.getEntitiesOfClass(
                Mob.class,
                searchBox,
                mob -> mob.isAlive()
                        && !(mob instanceof Enemy)
                        && !(mob instanceof IronGolem)
                        && mob.hasLineOfSight(player)
        );

        long endTime = level.getGameTime() + BACKSTEP_DURATION_TICKS;
        int affected = 0;

        for (Mob mob : mobs) {
            boolean wasNoAi = mob.isNoAi();

            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            mob.setNoAi(true);

            activeBacksteps.put(
                    mob.getUUID(),
                    new BackstepTarget(player.getUUID(), endTime, wasNoAi)
            );

            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, BACKSTEP_DURATION_TICKS, 0, false, false));
            affected++;
        }

        return affected;
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }

        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }

        BackstepTarget target = activeBacksteps.get(mob.getUUID());

        if (target == null) {
            return;
        }

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(target.playerId());

        if (player == null || !player.isAlive() || !mob.isAlive() || level.getGameTime() > target.endGameTime()) {
            endBackstep(mob, target);
            activeBacksteps.remove(mob.getUUID());
            return;
        }

        facePlayer(mob, player);

        if (!forcedBackstep(mob, player, level)) {
            endBackstep(mob, target);
            activeBacksteps.remove(mob.getUUID());
        }
    }

    private boolean forcedBackstep(Mob mob, ServerPlayer player, ServerLevel level) {
        double awayX = mob.getX() - player.getX();
        double awayZ = mob.getZ() - player.getZ();

        double length = Math.sqrt((awayX * awayX) + (awayZ * awayZ));

        if (length < 0.001D) {
            return false;
        }

        awayX /= length;
        awayZ /= length;

        double nextX = mob.getX() + (awayX * BACKSTEP_SPEED);
        double nextZ = mob.getZ() + (awayZ * BACKSTEP_SPEED);

        SafeStep safeStep = findSafeStep(level, mob, nextX, mob.getY(), nextZ);

        if (safeStep == null) {
            return false;
        }

        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);

        /*
         * Directly set position for this debug test.
         * This preserves the original "back away while staring" feeling,
         * but only allows the move if the destination has solid ground and
         * enough space for the mob.
         */
        mob.setPos(nextX, safeStep.y(), nextZ);
        mob.hasImpulse = true;

        return true;
    }

    private SafeStep findSafeStep(ServerLevel level, Mob mob, double x, double y, double z) {
        /*
         * Try current height first, then one block down, then one block up.
         * This lets mobs handle tiny terrain changes without floating off cliffs.
         */
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

    private void facePlayer(Mob mob, ServerPlayer player) {
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double dy = player.getEyeY() - mob.getEyeY();

        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI));

        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setXRot(pitch);
        mob.getLookControl().setLookAt(player, 90.0F, 90.0F);
    }

    private void endBackstep(Mob mob, BackstepTarget target) {
        if (!target.wasNoAi()) {
            mob.setNoAi(false);
        }

        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    private record BackstepTarget(UUID playerId, long endGameTime, boolean wasNoAi) {
    }

    private record SafeStep(double y) {
    }
}