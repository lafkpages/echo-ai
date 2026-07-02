package luisafk.echoai;

import luisafk.echoai.config.Config;
import luisafk.echoai.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EchoAI implements ModInitializer {

    public static final String MOD_ID = "echo-ai";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String MINECRAFT_FRIENDLY_VERSION =
        FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container ->
                container.getMetadata().getVersion().getFriendlyString()
            )
            .orElse("unknown");

    private static final String SYSTEM_PROMPT = """
        You are Echo, a helpful AI assistant that responds to player's chat messages in Minecraft (Java Edition).
        You are only present in chat. You do not exist outside of it.
        You may respond to requests not related to Minecraft, but default to assuming the player is talking about Minecraft or Minecraft-related content, and Java Edition in particular.
        The current Minecraft version is ${minecraftVersion}.

        Messages from players are prefixed with their name in angle brackets, e.g. "<Steve> hello". Do not prefix your own replies this way.

        Keep responses very short and concise as they will be broadcast to all players and shown in in-game chat.

        If a message is clearly not directed at you, is small talk between other players, or otherwise does not warrant a reply, call the skip_response tool to stay silent instead of replying.

        You have a web_search tool (powered by Exa). Your built-in knowledge may be incomplete or outdated, and you are unaware of recent updates, current events, or anything past your training cutoff. Whenever a player asks about specific game mechanics, version-specific details, recipes, numeric values, recent updates, or any current or factual information you are not fully certain of, call web_search first and base your answer on the results instead of guessing. Prefer searching over giving a possibly-wrong answer from memory.

        Do not use markdown in your responses. Instead, use plain text, optionally using Minecraft formatting codes. Do not overuse colours in general speech though.
        You should use colours for things like enchantments, rare items, etc.
        The formatting codes available are as follows:

        §0 - Black
        §1 - Dark Blue
        §2 - Dark Green
        §3 - Dark Aqua
        §4 - Dark Red
        §5 - Dark Purple
        §6 - Gold
        §7 - Gray
        §8 - Dark Gray
        §9 - Blue
        §a - Green
        §b - Aqua
        §c - Red
        §d - Light Purple
        §e - Yellow
        §f - White
        §k - Obfuscated
        §l - Bold
        §m - Strikethrough
        §n - Underline
        §o - Italic
        §r - Reset
        """.replace("${minecraftVersion}", MINECRAFT_FRIENDLY_VERSION);

    private static Config config;
    private static ChatAgent chatAgent;

    // Recreated per server session, so history is a clean start each time.
    private static Conversation conversation;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();

        if (
            config == null ||
            config.baseUrl == null ||
            config.baseUrl.isBlank() ||
            config.apiKey == null ||
            config.apiKey.isBlank() ||
            config.model == null ||
            config.model.isBlank()
        ) {
            LOGGER.warn(
                "No base URL or API key configured, Echo AI will not run."
            );
            return;
        }

        OpenAIClient openAiClient = new OpenAIClient(
            config.baseUrl,
            config.apiKey
        );

        ExaClient exaClient =
            config.exaApiKey != null && !config.exaApiKey.isBlank()
                ? new ExaClient(config.exaApiKey)
                : null;
        if (exaClient == null) {
            LOGGER.warn(
                "No Exa API key configured, the web_search tool will be disabled."
            );
        }

        chatAgent = new ChatAgent(
            openAiClient,
            exaClient,
            config.model,
            SYSTEM_PROMPT
        );

        OptOutRegistry optOut = new OptOutRegistry();
        AiCommand.register(optOut);

        // Fresh conversation per server session (not persisted across restarts).
        ServerLifecycleEvents.SERVER_STARTING.register(
            server ->
                conversation = new Conversation(
                    chatAgent,
                    server,
                    optOut,
                    config.debounceMs
                )
        );
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (conversation != null) {
                conversation.shutdown();
            }
            conversation = null;
        });

        // Fired on the server thread after a player's chat message is broadcast.
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            Conversation current = conversation;
            if (current != null) {
                // Opted-out players' messages still count as context, but don't
                // trigger the AI.
                boolean triggersAi = !optOut.isOptedOut(sender.getUUID());
                current.onPlayerMessage(
                    sender.getName().getString(),
                    message.signedContent(),
                    triggersAi
                );
            }
        });
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
