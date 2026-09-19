package luisafk.echoai.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;

public class Config {

    public String baseUrl;
    public String apiKey;

    // Accepts either a single model name or an array of names; when an array is
    // given, the first entry is used. Handy for keeping alternatives in the
    // config file and switching by reordering.
    @JsonAdapter(FirstStringDeserializer.class)
    public String model;

    public String exaApiKey;

    /**
     * Optional TypeSafe (https://typesafe.ai) API key. When set, a Jev
     * "System One" pre-filter judges each debounced chat burst before the
     * LLM is invoked: if Jev concludes a reply is probably not warranted,
     * the LLM request is skipped entirely. When the key is missing or Jev
     * fails for any reason, the mod falls back to the plain LLM path.
     */
    public String jevApiKey;

    /**
     * Probability (0-1) above which the Jev pre-filter passes a message on
     * to the LLM; only used when {@code jevApiKey} is set. Lower values make
     * Echo reply more readily (fewer wrongly-ignored messages), higher
     * values make it stay silent more often. The default is deliberately
     * conservative, since wrongly staying silent is more visible than an
     * unnecessary LLM request.
     */
    public double jevResponseThreshold = 0.3;

    /**
     * How long to wait, in milliseconds, after a triggering message before
     * starting an AI request. Each new message resets the timer, so a rapid
     * burst of chat is coalesced into a single request once the chatter
     * settles rather than kicking off (and then discarding) an early run.
     * Set to 0 to respond immediately with no debounce.
     */
    public int debounceMs = 300;

    /**
     * Deserializes a JSON string as-is, or a JSON array by taking its first
     * element. Lets the {@code model} field accept either form.
     */
    static class FirstStringDeserializer implements JsonDeserializer<String> {

        @Override
        public String deserialize(
            JsonElement json,
            Type type,
            JsonDeserializationContext context
        ) {
            if (json.isJsonArray()) {
                JsonArray array = json.getAsJsonArray();
                if (array.isEmpty()) {
                    return null;
                }
                JsonElement first = array.get(0);
                return first.isJsonNull() ? null : first.getAsString();
            }
            return json.isJsonNull() ? null : json.getAsString();
        }
    }
}
