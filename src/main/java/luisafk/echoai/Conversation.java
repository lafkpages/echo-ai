package luisafk.echoai;

import static luisafk.echoai.EchoAI.LOGGER;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
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
 * Coalescing happens at two points. Before a run starts, a triggering message
 * schedules the run {@code debounceMs} in the future; each further message
 * resets that timer, so a quick burst of chat collapses into one request once
 * the chatter settles (set {@code debounceMs} to 0 to disable and start
 * immediately). Once a run is in flight, supersede-and-discard takes over: new
 * messages are still appended to the history and bump the epoch, and when the
 * request finishes, if the epoch changed we discard its (now stale) reply and
 * run again against the latest history; otherwise we broadcast and store it.
 *
 * The debounce timer fires on a background scheduler, but its callback is
 * marshalled back onto the server thread before touching any state, preserving
 * the lock-free invariant. {@link #shutdown()} tears the scheduler down when
 * the server session ends.
 *
 * <p>When a {@link JevClient} is configured, each run is first gated by that
 * cheap pre-filter: if Jev concludes a reply is probably not warranted, the
 * LLM is never invoked. Any Jev failure falls back to the LLM path, so the
 * pre-filter can only ever skip work, never break the conversation.
 */
public class Conversation {

    // Improve later: smarter context compaction instead of a flat message cap.
    private static final int MAX_HISTORY = 50;

    // Name shown in chat for Echo's disguised messages, e.g. "<Echo> hello".
    private static final String SENDER_NAME = "Echo";

    // How a player message is rendered into the shared history. This exact
    // format is also described to the model in the system prompt, and
    // JevClient parses it back out — this is the only place that knows it.
    private static final Pattern PLAYER_LINE = Pattern.compile(
        "^<(.+?)> (.*)$",
        Pattern.DOTALL
    );

    private final ChatAgent agent;
    private final JevClient jev; // nullable: no pre-filter when absent
    private final MinecraftServer server;
    private final OptOutRegistry optOut;
    private final List<OpenAIClient.Message> history = new ArrayList<>();

    // Debounce window before a run starts. When <= 0, runs start immediately
    // and the scheduler is never created.
    private final int debounceMs;
    private final ScheduledExecutorService scheduler;

    // Handle to the pending debounce task (if any) so it can be cancelled and
    // rescheduled, plus a generation counter to ignore a timer that fires after
    // being superseded (cancellation can race with an already-firing task).
    private ScheduledFuture<?> pending;
    private int debounceGen = 0;

    private boolean running = false;
    private int epoch = 0;

    public Conversation(
        ChatAgent agent,
        JevClient jev,
        MinecraftServer server,
        OptOutRegistry optOut,
        int debounceMs
    ) {
        this.agent = agent;
        this.jev = jev;
        this.server = server;
        this.optOut = optOut;
        this.debounceMs = debounceMs;
        this.scheduler =
            debounceMs > 0
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                      Thread thread = new Thread(runnable, "echo-ai-debounce");
                      thread.setDaemon(true);
                      return thread;
                  })
                : null;
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
        history.add(
            OpenAIClient.Message.user(formatPlayerLine(playerName, text))
        );
        trim();

        // Opted-out players are still recorded as context above, but never
        // start a run nor supersede one already in flight.
        if (!triggersAi) {
            return;
        }

        epoch++;

        // A run already in flight will pick up this message via
        // supersede-and-discard when it completes, so there's nothing to
        // schedule. Otherwise (re)start the debounce window.
        if (!running) {
            scheduleRun();
        }
    }

    /** A player message split back out of its formatted history line. */
    record PlayerLine(String name, String text) {}

    static String formatPlayerLine(String playerName, String text) {
        return "<" + playerName + "> " + text;
    }

    /** Undoes {@link #formatPlayerLine}, or null if {@code content} isn't one. */
    static PlayerLine parsePlayerLine(String content) {
        if (content == null) {
            return null;
        }
        Matcher matcher = PLAYER_LINE.matcher(content);
        return matcher.matches()
            ? new PlayerLine(matcher.group(1), matcher.group(2))
            : null;
    }

    // (Re)starts the debounce window: cancels any pending start and schedules a
    // fresh one, so the last message in a burst is the one that fires the run.
    // With the debounce disabled, starts immediately.
    private void scheduleRun() {
        if (scheduler == null) {
            running = true;
            startRun();
            return;
        }

        if (pending != null) {
            pending.cancel(false);
        }

        int gen = ++debounceGen;
        pending = scheduler.schedule(
            () -> server.execute(() -> onDebounceElapsed(gen)),
            debounceMs,
            TimeUnit.MILLISECONDS
        );
    }

    // Runs on the server thread once the debounce window elapses. Ignored if a
    // newer message superseded this timer, or a run somehow already started.
    private void onDebounceElapsed(int gen) {
        if (gen != debounceGen || running) {
            return;
        }

        pending = null;
        running = true;
        startRun();
    }

    /**
     * Stops the debounce scheduler. Called when the server session ends so the
     * background thread doesn't outlive the conversation.
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void startRun() {
        int startEpoch = epoch;
        List<OpenAIClient.Message> snapshot = new ArrayList<>(history);

        run(snapshot).whenComplete((reply, error) ->
            server.execute(() -> onRunComplete(startEpoch, reply, error))
        );
    }

    /**
     * Produces this run's reply, optionally gated behind the Jev pre-filter.
     * Jev failures fall back to running the LLM, so the pre-filter can only
     * ever skip work, never break the conversation.
     */
    private CompletableFuture<Optional<String>> run(
        List<OpenAIClient.Message> snapshot
    ) {
        if (jev == null) {
            return agent.respond(snapshot);
        }

        return jev
            .decide(snapshot)
            .thenApply(decision -> {
                LOGGER.debug(
                    "Jev pre-filter: probability a response is warranted = {}",
                    decision.probability()
                );
                return decision.shouldRespond();
            })
            // Fail open: if Jev is unavailable or misconfigured, run the LLM
            // exactly as before the pre-filter existed.
            .exceptionally(error -> {
                LOGGER.warn(
                    "Jev pre-filter failed, falling back to LLM",
                    error
                );
                return true;
            })
            .thenCompose(allowed -> {
                if (!allowed) {
                    LOGGER.debug(
                        "Jev pre-filter: staying silent, skipping the LLM."
                    );
                    return CompletableFuture.completedFuture(
                        Optional.<String>empty()
                    );
                }
                return agent.respond(snapshot);
            });
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
    // Uses a disguised chat message so it renders like a normal player line
    // ("<Echo> ...") via the vanilla chat decoration, without being a signed
    // player message or a plain system message.
    private void broadcast(String text) {
        OutgoingChatMessage message = new OutgoingChatMessage.Disguised(
            Component.literal(text)
        );
        ChatType.Bound bound = ChatType.bind(
            ChatType.CHAT,
            server.registryAccess(),
            Component.literal(SENDER_NAME)
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!optOut.isOptedOut(player.getUUID())) {
                message.sendToPlayer(player, false, bound);
            }
        }
    }

    private void trim() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
