package luisafk.echoai;

import static luisafk.echoai.EchoAI.LOGGER;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal client for TypeSafe's System One API, used as a cheap pre-filter in
 * front of the LLM: a single Noul question asks whether the latest chat
 * warrants a reply at all, so obvious small talk never reaches the
 * chat-completions model. Any failure (bad key, network error, rate limit,
 * unexpected response) surfaces as a failed future; the caller is responsible
 * for falling back to the normal LLM path.
 */
public class JevClient {

    /**
     * Jev's answer: the probability that a response is warranted, and whether
     * that probability clears the configured threshold.
     */
    public record Decision(double probability, boolean shouldRespond) {}

    private record NoulAnswer(String type, Double noul) {}

    private record SystemOneResponse(Map<String, NoulAnswer> answers) {}

    private static final URI ENDPOINT = URI.create(
        "https://api.typesafe.ai/v1/systemone"
    );
    private static final String MODEL = "jev-latest";
    private static final String QUESTION_ID = "should_respond";

    private static final String INSTRUCTIONS = """
    Echo is an AI assistant that watches the public chat of a Minecraft \
    server and replies to players when appropriate. The chat log is in \
    chronological order; the last entry is the most recent message, and \
    the one being considered for a reply. Entries with speaker "Echo" are \
    the assistant's own previous replies.

    Should Echo reply to that latest message? Echo should reply when the \
    message is directed at it (e.g. addressing Echo or the assistant, \
    asking it a question, requesting help), or when a short reply would \
    clearly be warranted and welcome. Echo should stay silent when \
    players are just talking to each other and the message is not \
    directed at the assistant.""";

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String apiKey;
    private final double responseThreshold;

    public JevClient(String apiKey, double responseThreshold) {
        this.apiKey = apiKey;
        if (responseThreshold < 0 || responseThreshold > 1) {
            LOGGER.warn(
                "jevResponseThreshold {} is outside 0-1, clamping.",
                responseThreshold
            );
        }
        this.responseThreshold = Math.clamp(responseThreshold, 0.0, 1.0);
    }

    /**
     * Asks Jev whether the given chat history warrants a response. The
     * returned future fails if Jev is unavailable or returns something
     * unexpected; callers should treat any failure as "run the LLM".
     */
    public CompletableFuture<Decision> decide(
        List<OpenAIClient.Message> history
    ) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(ENDPOINT)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    GSON.toJson(payload(history))
                )
            )
            .build();

        return HTTP.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> parse(response));
    }

    private JsonObject payload(List<OpenAIClient.Message> history) {
        JsonObject question = new JsonObject();
        question.addProperty("type", "noul");
        question.addProperty("instructions", INSTRUCTIONS);

        JsonObject criteria = new JsonObject();
        criteria.addProperty(
            "true",
            "Echo should reply: the latest message is directed at Echo, " +
                "asks it something, or clearly warrants its input"
        );
        criteria.addProperty(
            "false",
            "Echo should stay silent: the latest message is small talk " +
                "between players or otherwise does not warrant a reply"
        );
        question.add("criteria", criteria);

        JsonObject questions = new JsonObject();
        questions.add(QUESTION_ID, question);

        JsonObject payload = new JsonObject();
        payload.add("state", state(history));
        payload.addProperty("model", MODEL);
        payload.add("questions", questions);
        return payload;
    }

    // Turns the chat history into structured state: one {speaker, text}
    // entry per message, in order. Player messages are stored in the shared
    // history in their OpenAI wire format ("<Steve> hello"); the prefix is
    // split back out so Jev sees named fields instead of chat decoration.
    private static JsonObject state(List<OpenAIClient.Message> history) {
        JsonArray chatLog = new JsonArray();
        for (OpenAIClient.Message message : history) {
            JsonObject entry = new JsonObject();

            if ("assistant".equals(message.role())) {
                entry.addProperty("speaker", "Echo");
                entry.addProperty("text", message.content());
            } else {
                Conversation.PlayerLine player = Conversation.parsePlayerLine(
                    message.content()
                );
                if (player != null) {
                    entry.addProperty("speaker", player.name());
                    entry.addProperty("text", player.text());
                } else {
                    // Defensive: history normally holds only player and Echo
                    // messages, but keep the state well-formed regardless.
                    entry.addProperty("speaker", message.role());
                    entry.addProperty("text", message.content());
                }
            }

            chatLog.add(entry);
        }

        JsonObject state = new JsonObject();
        state.add("chat_log", chatLog);
        return state;
    }

    private Decision parse(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException(
                "TypeSafe error " +
                    response.statusCode() +
                    ": " +
                    response.body()
            );
        }

        SystemOneResponse parsed = GSON.fromJson(
            response.body(),
            SystemOneResponse.class
        );
        NoulAnswer answer =
            parsed != null && parsed.answers() != null
                ? parsed.answers().get(QUESTION_ID)
                : null;
        if (answer == null || answer.noul() == null) {
            throw new RuntimeException(
                "TypeSafe response missing '" +
                    QUESTION_ID +
                    "' answer: " +
                    response.body()
            );
        }

        double probability = answer.noul();
        return new Decision(probability, probability >= responseThreshold);
    }
}
