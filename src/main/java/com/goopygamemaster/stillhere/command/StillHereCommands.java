package com.goopygamemaster.stillhere.command;

import com.goopygamemaster.stillhere.director.HorrorPhase;
import com.goopygamemaster.stillhere.director.PlayerProfile;
import com.goopygamemaster.stillhere.director.StillHereDirector;
import com.goopygamemaster.stillhere.event.BackstepDebugHandler;
import com.goopygamemaster.stillhere.event.MobStaringHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class StillHereCommands {
    private StillHereCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("stillhere")
                        .then(Commands.literal("phase")
                                .executes(context -> showPhase(context.getSource())))
                        .then(Commands.literal("profile")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> showProfile(context.getSource())))
                        .then(Commands.literal("stare")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> forceStare(context.getSource())))
                        .then(Commands.literal("avoid")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> forceAvoidance(context.getSource())))
                        .then(Commands.literal("flee")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> forceFlee(context.getSource())))
                        .then(Commands.literal("backstep")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> forceBackstep(context.getSource())))
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

    private static int showProfile(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerProfile profile = StillHereDirector.INSTANCE.getProfile(player);

        source.sendSuccess(
                () -> Component.literal(
                        "Still Here Profile"
                                + " | Guilt: " + profile.guilt()
                                + " | Remorse: " + profile.remorse()
                                + " | Village Threat: " + profile.villageThreat()
                                + " | Recent Violence: " + profile.recentViolence()
                                + " | Historic Violence: " + profile.historicViolence()
                                + " | Passive Attacks: " + profile.passiveMobAttacks()
                                + " | Seconds Played: " + profile.secondsPlayed()
                                + " | Underground: " + profile.secondsUnderground() + "s"
                                + " | Dark Underground: " + profile.secondsInDarkUnderground() + "s"
                                + " | Near Village: " + profile.secondsNearVillage() + "s"
                                + " | Blocks Broken: " + profile.blocksBroken()
                                + " | Underground Blocks: " + profile.blocksBrokenUnderground()
                                + " | Village Blocks: " + profile.blocksBrokenNearVillage()
                                + " | Distance: " + Math.round(profile.distanceTravelled())
                ),
                false
        );

        return 1;
    }

    private static int forceStare(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        int affectedMobs = MobStaringHandler.INSTANCE.forceDebugStare(player);

        source.sendSuccess(
                () -> Component.literal("Still Here debug stare triggered. Mobs affected: " + affectedMobs),
                false
        );

        return affectedMobs;
    }

    private static int forceAvoidance(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        int affectedMobs = MobStaringHandler.INSTANCE.forceDebugAvoidance(player);

        source.sendSuccess(
                () -> Component.literal("Still Here debug avoidance triggered. Mobs affected: " + affectedMobs),
                false
        );

        return affectedMobs;
    }

    private static int forceFlee(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        int affectedMobs = MobStaringHandler.INSTANCE.forceDebugFlee(player);

        source.sendSuccess(
                () -> Component.literal("Still Here debug flee triggered. Mobs affected: " + affectedMobs),
                false
        );

        return affectedMobs;
    }

    private static int forceBackstep(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        int affectedMobs = BackstepDebugHandler.INSTANCE.forceDebugBackstep(player);

        source.sendSuccess(
                () -> Component.literal("Still Here debug backstep triggered. Mobs affected: " + affectedMobs),
                false
        );

        return affectedMobs;
    }
}