package com.goopygamemaster.stillhere.director;

public enum HorrorPhase {
    VANILLA_MASK(0, "Vanilla Mask"),
    RECOGNITION(1, "Recognition"),
    THE_WATCHER(2, "The Watcher"),
    MEMORY_LEAKAGE(3, "Memory Leakage");

    private final int id;
    private final String displayName;

    HorrorPhase(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static HorrorPhase fromWorldDay(long worldDay) {
        if (worldDay >= 15) {
            return MEMORY_LEAKAGE;
        }

        if (worldDay >= 8) {
            return THE_WATCHER;
        }

        if (worldDay >= 5) {
            return RECOGNITION;
        }

        return VANILLA_MASK;
    }
}
