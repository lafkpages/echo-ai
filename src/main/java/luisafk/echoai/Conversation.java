package luisafk.echoai;

import static luisafk.echoai.EchoAI.LOGGER;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owns the shared, in-memory chat history for one server session and drives
 * the AI a single request at a time.
 *
 * All state ({@link #history}, {@link #running}, {@link #epoch}) is confined to
 * the server thread: player messages arrive there, and async AI completions are
 * marshalled back onto it before touching anything. That keeps it lock-free.
 *
 * Coalescing with supersede-and-discard: while a request is in flight, new
 * messages are still appended to the history and bump the epoch. When the
 * request finishes, if the epoch changed we discard its (now stale) reply and
 * run again against the latest history; otherwise we broadcast and store it.
 */
public class Conversation {

    // Improve later: smarter context compaction instead of a flat message cap.
    private static final int MAX_HISTORY = 50;

    private final ChatAgent agent;
    private final MinecraftServer server;
    private final OptOutRegistry optOut;
    private final List<OpenAIClient.Message> history = new ArrayList<>();

    private boolean running = false;
    private int epoch = 0;

    public Conversation(
        ChatAgent agent,
        MinecraftServer server,
        OptOutRegistry optOut
    ) {
        this.agent = agent;
        this.server = server;
        this.optOut = optOut;
    }

    /**
     * Records a player's chat message. It always becomes part of the shared
     * context, but only triggers the AI when {@code triggersAi} is true (false
     * for players who have opted out). Must be called on the server thread.
     */
    public void onPlayerMessage(
        String playerName,
        String text,
        boolean triggersAi
    ) {
        history.add(OpenAIClient.Message.user("<" + playerName + "> " + text));
        trim();

        // Opted-out players are still recorded as context above, but never
        // start a run nor supersede one already in flight.
        if (!triggersAi) {
            return;
        }

        epoch++;

        if (!running) {
            running = true;
            startRun();
        }
    }

    private void startRun() {
        int startEpoch = epoch;
        List<OpenAIClient.Message> snapshot = new ArrayList<>(history);

        agent
            .respond(snapshot)
            .whenComplete((reply, error) ->
                server.execute(() -> onRunComplete(startEpoch, reply, error))
            );
    }

    private void onRunComplete(
        int startEpoch,
        Optional<String> reply,
        Throwable error
    ) {
        if (error != null) {
            LOGGER.error("AI request failed", error);
        }

        // New messages arrived while generating: discard this reply and rerun
        // against the latest history.
        if (epoch != startEpoch) {
            startRun();
            return;
        }

        running = false;

        if (error == null && reply != null && reply.isPresent()) {
            String text = reply.get();
            history.add(OpenAIClient.Message.assistant(text));
            trim();
            broadcast(text);
        }
    }

    // Sends the AI reply to every online player except those who opted out.
    private void broadcast(String text) {
        Component message = Component.literal(text);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!optOut.isOptedOut(player.getUUID())) {
                player.sendSystemMessage(message);
            }
        }
    }

    private void trim() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
