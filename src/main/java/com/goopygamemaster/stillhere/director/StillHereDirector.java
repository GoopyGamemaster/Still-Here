package com.goopygamemaster.stillhere.director;

import net.minecraft.server.level.ServerLevel;

public final class StillHereDirector {
    public static final StillHereDirector INSTANCE = new StillHereDirector();

    private static final long TICKS_PER_DAY = 24000L;

    private StillHereDirector() {
    }

    public long getWorldDay(ServerLevel level) {
        return Math.max(0L, level.getDayTime() / TICKS_PER_DAY);
    }

    public HorrorPhase getPhase(ServerLevel level) {
        return HorrorPhase.fromWorldDay(getWorldDay(level));
    }
}
