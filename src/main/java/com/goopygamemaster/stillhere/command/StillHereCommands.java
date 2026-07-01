package com.goopygamemaster.stillhere.command;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public final class StillHereCommands {
    private StillHereCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("stillhere")
                        .then(Commands.literal("phase")
                                .executes(context -> showPhase(context.getSource())))
        );
    }

    private static int showPhase(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        StillHereDirector director = StillHereDirector.INSTANCE;

        long worldDay = director.getWorldDay(level);
        HorrorPhase phase = director.getPhase(level);

        source.sendSuccess(
                () -> Component.literal("Still Here Phase: " + phase.id() + " - " + phase.displayName()
                        + " | World Day: " + (worldDay + 1)),
                false
        );

        return 1;
    }
}
