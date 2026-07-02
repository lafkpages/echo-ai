package luisafk.echoai;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers the {@code /echo} command, which lets players opt out of (or back
 * into) Echo AI. Opted-out players are still recorded as context for everyone
 * else, but their messages never trigger Echo and they don't receive its
 * replies.
 */
public final class AiCommand {

    private AiCommand() {}

    public static void register(OptOutRegistry optOut) {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                dispatcher.register(
                    Commands.literal("echo")
                        .then(
                            Commands.literal("optout").executes(ctx ->
                                setOptOut(ctx.getSource(), optOut, true)
                            )
                        )
                        .then(
                            Commands.literal("optin").executes(ctx ->
                                setOptOut(ctx.getSource(), optOut, false)
                            )
                        )
                        .then(
                            Commands.literal("status").executes(ctx ->
                                status(ctx.getSource(), optOut)
                            )
                        )
                )
        );
    }

    private static int setOptOut(
        CommandSourceStack source,
        OptOutRegistry optOut,
        boolean optedOut
    ) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(
                Component.literal("Only players can use this command.")
            );
            return 0;
        }

        boolean changed = optOut.setOptedOut(player.getUUID(), optedOut);
        String message;
        if (optedOut) {
            message = changed
                ? "You've opted out of Echo AI. It will ignore your messages and you won't see its replies."
                : "You're already opted out of Echo AI.";
        } else {
            message = changed
                ? "You've opted back into Echo AI."
                : "You're already opted into Echo AI.";
        }

        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int status(
        CommandSourceStack source,
        OptOutRegistry optOut
    ) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(
                Component.literal("Only players can use this command.")
            );
            return 0;
        }

        String message = optOut.isOptedOut(player.getUUID())
            ? "You're opted out of Echo AI. Use /echo optin to rejoin."
            : "You're opted into Echo AI. Use /echo optout to opt out.";

        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
