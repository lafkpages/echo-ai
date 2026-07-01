package luisafk.mcai.config;

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
