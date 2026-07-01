package luisafk.mcai;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal wrapper around Exa's {@code /search} endpoint. Returns results
 * formatted as plain text, ready to hand back to the model as a tool result.
 */
public class ExaClient {

    private record SearchRequest(
        String query,
        int numResults,
        Contents contents
    ) {
        private record Contents(Text text) {
            private record Text(int maxCharacters) {}
        }
    }

    private record SearchResponse(List<Result> results) {
        private record Result(String title, String url, String text) {}
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String apiKey;

    public ExaClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public CompletableFuture<String> search(String query) {
        SearchRequest body = new SearchRequest(
            query,
            5,
            new SearchRequest.Contents(new SearchRequest.Contents.Text(1000))
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.exa.ai/search"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "Exa error " +
                        response.statusCode() +
                        ": " +
                        response.body()
                );
            }
            return format(GSON.fromJson(response.body(), SearchResponse.class));
        });
    }

    private static String format(SearchResponse response) {
        if (
            response == null ||
            response.results() == null ||
            response.results().isEmpty()
        ) {
            return "No results found.";
        }

        StringBuilder sb = new StringBuilder();
        for (SearchResponse.Result result : response.results()) {
            sb.append("Title: ").append(result.title()).append("\n");
            sb.append("URL: ").append(result.url()).append("\n");
            if (result.text() != null && !result.text().isBlank()) {
                sb
                    .append("Content: ")
                    .append(result.text().strip())
                    .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }
}
