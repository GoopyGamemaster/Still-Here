package com.goopygamemaster.stillhere.director;

public class PlayerProfile {
    private int guilt;
    private int remorse;
    private int villageThreat;
    private int recentViolence;
    private int historicViolence;
    private int passiveMobAttacks;

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

    public void recordPassiveMobAttack() {
        passiveMobAttacks++;

        addGuilt(4);
        addRecentViolence(6);
        addHistoricViolence(2);
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