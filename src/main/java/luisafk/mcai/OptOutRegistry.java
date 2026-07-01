package luisafk.mcai;

import static luisafk.mcai.MCAI.LOGGER;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Tracks which players have opted out of the AI. Opted-out players still have
 * their chat recorded as context, but their messages never trigger the AI and
 * they are excluded from its replies.
 *
 * Persisted to {@code <configDir>/mc-ai-optout.json} as a JSON array of player
 * UUIDs: loaded once on construction, and rewritten whenever the set changes.
 * It lives in its own file rather than the main config so admins don't have to
 * scroll past machine-managed data.
 *
 * Like {@link Conversation}, this is confined to the server thread (commands,
 * chat events, and broadcasts all run there), so it needs no synchronization.
 * The save is small and happens only on the rare opt-out/opt-in, so doing it
 * synchronously on that thread is fine.
 */
public class OptOutRegistry {

    private static final File FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("mc-ai-optout.json")
        .toFile();
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private final Set<UUID> optedOut = load();

    public boolean isOptedOut(UUID playerId) {
        return optedOut.contains(playerId);
    }

    /**
     * Opts a player out of (or back into) the AI, persisting the change.
     *
     * @return true if this actually changed the player's state, false if they
     *     were already in the requested state.
     */
    public boolean setOptedOut(UUID playerId, boolean optOut) {
        boolean changed = optOut
            ? optedOut.add(playerId)
            : optedOut.remove(playerId);
        if (changed) {
            save();
        }
        return changed;
    }

    private static Set<UUID> load() {
        try (FileReader reader = new FileReader(FILE)) {
            UUID[] ids = GSON.fromJson(reader, UUID[].class);
            Set<UUID> set = new HashSet<>();
            if (ids != null) {
                for (UUID id : ids) {
                    if (id != null) {
                        set.add(id);
                    }
                }
            }
            return set;
        } catch (IOException e) {
            // No file yet (first run) is normal; start with an empty set.
            return new HashSet<>();
        } catch (RuntimeException e) {
            // Malformed file: don't crash startup, just start empty.
            LOGGER.error(
                "Failed to read opt-out file, starting empty: {}",
                e.getMessage()
            );
            return new HashSet<>();
        }
    }

    private void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            GSON.toJson(optedOut, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save opt-out file: {}", e.getMessage());
        }
    }
}
