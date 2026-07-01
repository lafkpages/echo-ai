package luisafk.mcai;

import static luisafk.mcai.MCAI.LOGGER;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the tool-calling loop: sends the conversation to the model, runs any
 * tools it asks for, feeds the results back, and repeats until the model
 * produces a final reply (or decides to stay silent via {@code skip_response}).
 */
public class ChatAgent {

    private static final String SEARCH_TOOL = "web_search";
    private static final String SKIP_TOOL = "skip_response";

    // Matches a well-formed <think>...</think> (or <thinking>...</thinking>)
    // reasoning block. DOTALL so it spans newlines; reluctant so multiple
    // blocks are each removed.
    private static final Pattern THINK_BLOCK = Pattern.compile(
        "(?is)<think(?:ing)?>.*?</think(?:ing)?>"
    );
    private static final Pattern THINK_CLOSE = Pattern.compile(
        "(?is)</think(?:ing)?>"
    );

    // Hard cap on round-trips so a misbehaving model can't loop forever.
    private static final int MAX_TURNS = 5;

    private final OpenAIClient openAiClient;
    private final ExaClient exaClient; // nullable: no web search when absent
    private final String model;
    private final String systemPrompt;
    private final List<OpenAIClient.Tool> tools;

    public ChatAgent(
        OpenAIClient openAiClient,
        ExaClient exaClient,
        String model,
        String systemPrompt
    ) {
        this.openAiClient = openAiClient;
        this.exaClient = exaClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = buildTools(exaClient != null);
    }

    /**
     * Produces the assistant's reply given the running conversation history,
     * or an empty Optional if the model chose not to respond. The system
     * prompt is prepended here; the caller's history is not mutated.
     */
    public CompletableFuture<Optional<String>> respond(
        List<OpenAIClient.Message> history
    ) {
        List<OpenAIClient.Message> messages = new ArrayList<>();
        messages.add(OpenAIClient.Message.system(systemPrompt));
        messages.addAll(history);
        return loop(messages, 0);
    }

    private CompletableFuture<Optional<String>> loop(
        List<OpenAIClient.Message> messages,
        int turn
    ) {
        if (turn >= MAX_TURNS) {
            LOGGER.warn("Tool loop hit MAX_TURNS ({}), giving up.", MAX_TURNS);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return openAiClient
            .complete(model, messages, tools)
            .thenCompose(assistant -> {
                List<OpenAIClient.ToolCall> calls = assistant.tool_calls();

                // No tool calls -> this is the final answer.
                if (calls == null || calls.isEmpty()) {
                    return CompletableFuture.completedFuture(
                        Optional.ofNullable(assistant.content())
                            .map(ChatAgent::stripThinking)
                            .filter(content -> !content.isBlank())
                    );
                }

                LOGGER.info(
                    "Model requested {} tool call(s): {}",
                    calls.size(),
                    toolNames(calls)
                );

                // The model explicitly chose to stay silent.
                for (OpenAIClient.ToolCall call : calls) {
                    if (SKIP_TOOL.equals(call.function().name())) {
                        LOGGER.info(
                            "Model chose to stay silent (skip_response)."
                        );
                        return CompletableFuture.completedFuture(
                            Optional.empty()
                        );
                    }
                }

                // Record the assistant's tool request, then run each tool.
                messages.add(assistant);

                List<CompletableFuture<OpenAIClient.Message>> results =
                    new ArrayList<>();
                for (OpenAIClient.ToolCall call : calls) {
                    results.add(runTool(call));
                }

                return CompletableFuture.allOf(
                    results.toArray(CompletableFuture[]::new)
                ).thenCompose(ignored -> {
                    for (CompletableFuture<OpenAIClient.Message> result : results) {
                        messages.add(result.join());
                    }
                    return loop(messages, turn + 1);
                });
            });
    }

    private CompletableFuture<OpenAIClient.Message> runTool(
        OpenAIClient.ToolCall call
    ) {
        String name = call.function().name();
        JsonObject args = parseArguments(call.function().arguments());

        CompletableFuture<String> result;
        if (SEARCH_TOOL.equals(name) && exaClient != null) {
            String query = args.has("query")
                ? args.get("query").getAsString()
                : "";
            LOGGER.info("Running web_search for query: {}", query);
            result = exaClient.search(query);
        } else {
            result = CompletableFuture.completedFuture(
                "Unknown or unavailable tool: " + name
            );
        }

        return result
            .exceptionally(err -> {
                LOGGER.error("Tool '{}' failed", name, err);
                return "Tool '" + name + "' failed: " + err.getMessage();
            })
            .thenApply(content ->
                OpenAIClient.Message.tool(call.id(), content)
            );
    }

    /**
     * Removes inline chain-of-thought that some models emit in their content,
     * so it never reaches chat or the stored history. Handles both well-formed
     * {@code <think>...</think>} blocks and the case where the opening tag was
     * swallowed by the chat template, leaving only a trailing {@code </think>}.
     */
    private static String stripThinking(String content) {
        String stripped = THINK_BLOCK.matcher(content).replaceAll("");

        // A dangling close tag (no matching open) means everything before it was
        // reasoning; keep only what comes after the last one.
        Matcher matcher = THINK_CLOSE.matcher(stripped);
        int end = -1;
        while (matcher.find()) {
            end = matcher.end();
        }
        if (end >= 0) {
            stripped = stripped.substring(end);
        }

        return stripped.strip();
    }

    private static String toolNames(List<OpenAIClient.ToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (OpenAIClient.ToolCall call : calls) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(call.function().name());
        }
        return sb.toString();
    }

    private static JsonObject parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(arguments).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static List<OpenAIClient.Tool> buildTools(boolean includeSearch) {
        List<OpenAIClient.Tool> tools = new ArrayList<>();

        if (includeSearch) {
            tools.add(
                new OpenAIClient.Tool(
                    "function",
                    new OpenAIClient.Tool.Function(
                        SEARCH_TOOL,
                        "Search the web for current or factual information " +
                            "you are unsure about, using the Exa search engine.",
                        schema(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "query": {
                                  "type": "string",
                                  "description": "The search query."
                                }
                              },
                              "required": ["query"]
                            }
                            """
                        )
                    )
                )
            );
        }

        tools.add(
            new OpenAIClient.Tool(
                "function",
                new OpenAIClient.Tool.Function(
                    SKIP_TOOL,
                    "Call this to deliberately not respond at all, e.g. when " +
                        "the message is not directed at you or does not warrant a " +
                        "reply. Produces no chat output.",
                    schema("{ \"type\": \"object\", \"properties\": {} }")
                )
            )
        );

        return tools;
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
