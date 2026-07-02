package com.goopygamemaster.stillhere.director;

import java.util.HashSet;
import java.util.Set;

public class PlayerProfile {
    private int guilt;
    private int remorse;
    private int villageThreat;
    private int recentViolence;
    private int historicViolence;
    private int passiveMobAttacks;

    private long secondsPlayed;
    private long secondsUnderground;
    private long secondsInDarkUnderground;
    private long secondsNearVillage;

    private long blocksBroken;
    private long blocksBrokenUnderground;
    private long blocksBrokenInDarkness;
    private long blocksBrokenNearVillage;

    private double distanceTravelled;

    private final Set<String> deliveredMessages = new HashSet<>();

    public int guilt() {
        return guilt;
    }

    public int remorse() {
        return remorse;
    }

    public int villageThreat() {
        return villageThreat;
    }

    public int recentViolence() {
        return recentViolence;
    }

    public int historicViolence() {
        return historicViolence;
    }

    public int passiveMobAttacks() {
        return passiveMobAttacks;
    }

    public long secondsPlayed() {
        return secondsPlayed;
    }

    public long secondsUnderground() {
        return secondsUnderground;
    }

    public long secondsInDarkUnderground() {
        return secondsInDarkUnderground;
    }

    public long secondsNearVillage() {
        return secondsNearVillage;
    }

    public long blocksBroken() {
        return blocksBroken;
    }

    public long blocksBrokenUnderground() {
        return blocksBrokenUnderground;
    }

    public long blocksBrokenInDarkness() {
        return blocksBrokenInDarkness;
    }

    public long blocksBrokenNearVillage() {
        return blocksBrokenNearVillage;
    }

    public double distanceTravelled() {
        return distanceTravelled;
    }

    public boolean markMessageDelivered(String key) {
        return deliveredMessages.add(key);
    }

    public void recordPassiveMobAttack() {
        passiveMobAttacks++;

        /*
         * Killing animals is normal Minecraft survival.
         * A single attack should barely move the Director.
         * Patterned violence matters more than individual hits.
         */
        addGuilt(1);
        addRecentViolence(2);

        if (passiveMobAttacks % 5 == 0) {
            addHistoricViolence(1);
        }
    }

    public void recordPeacefulVillageTrade() {
        addRemorse(2);
        addVillageThreat(-4);
        addRecentViolence(-3);
    }

    public void recordVillageBlockBroken() {
        addVillageThreat(6);
        addGuilt(2);
    }

    public void recordVillagerAttacked() {
        addGuilt(25);
        addVillageThreat(35);
        addRecentViolence(30);
        addHistoricViolence(20);
    }

    public void recordGolemAttacked() {
        addGuilt(15);
        addVillageThreat(25);
        addRecentViolence(20);
        addHistoricViolence(15);
    }

    public void recordSecond(boolean underground, boolean darkUnderground, boolean nearVillage, double distanceThisSecond) {
        secondsPlayed++;

        if (underground) {
            secondsUnderground++;
        }

        if (darkUnderground) {
            secondsInDarkUnderground++;
        }

        if (nearVillage) {
            secondsNearVillage++;
        }

        if (distanceThisSecond > 0.0D) {
            distanceTravelled += distanceThisSecond;
        }

        if (secondsPlayed % 60 == 0) {
            tickSlowDecay();
        }
    }

    public void recordBlockBroken(boolean underground, boolean darkUnderground, boolean nearVillage) {
        blocksBroken++;

        if (underground) {
            blocksBrokenUnderground++;
        }

        if (darkUnderground) {
            blocksBrokenInDarkness++;
        }

        if (nearVillage) {
            blocksBrokenNearVillage++;
            recordVillageBlockBroken();
        }
    }

    public void tickSlowDecay() {
        if (recentViolence > 0) {
            recentViolence--;
        }

        if (villageThreat > 0 && recentViolence == 0) {
            villageThreat--;
        }
    }

    private void addGuilt(int amount) {
        guilt = clamp(guilt + amount);
    }

    private void addRemorse(int amount) {
        remorse = clamp(remorse + amount);
    }

    private void addVillageThreat(int amount) {
        villageThreat = clamp(villageThreat + amount);
    }

    private void addRecentViolence(int amount) {
        recentViolence = clamp(recentViolence + amount);
    }

    private void addHistoricViolence(int amount) {
        historicViolence = clamp(historicViolence + amount);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}