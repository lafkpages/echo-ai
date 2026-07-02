package luisafk.echoai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OpenAIClient {

    /**
     * A chat message. The optional fields are null when unused; since Gson
     * omits null fields by default, they simply don't appear in the request.
     */
    public record Message(
        String role,
        String content,
        List<ToolCall> tool_calls,
        String tool_call_id
    ) {
        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, toolCallId);
        }
    }

    /** A tool call requested by the model on an assistant message. */
    public record ToolCall(String id, String type, FunctionCall function) {
        /** {@code arguments} is a JSON string, not a parsed object. */
        public record FunctionCall(String name, String arguments) {}
    }

    /** A tool definition advertised to the model. */
    public record Tool(String type, Function function) {
        public record Function(
            String name,
            String description,
            JsonObject parameters
        ) {}
    }

    private record ChatRequest(
        String model,
        List<Message> messages,
        List<Tool> tools,
        boolean stream
    ) {}

    private record ChatResponse(List<Choice> choices) {
        private record Choice(Message message) {}
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String baseUrl;
    private final String apiKey;

    public OpenAIClient(String baseUrl, String apiKey) {
        // tolerate a trailing slash in the configured base URL
        this.baseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Performs a single chat completion and returns the assistant's reply.
     * The returned message may contain tool calls instead of (or in addition
     * to) text content; the caller is responsible for running any tools and
     * continuing the conversation.
     */
    public CompletableFuture<Message> complete(
        String model,
        List<Message> messages,
        List<Tool> tools
    ) {
        ChatRequest payload = new ChatRequest(model, messages, tools, false);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();

        return HTTP.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "API error " +
                        response.statusCode() +
                        ": " +
                        response.body()
                );
            }
            ChatResponse parsed = GSON.fromJson(
                response.body(),
                ChatResponse.class
            );
            return parsed.choices().get(0).message();
        });
    }
}
