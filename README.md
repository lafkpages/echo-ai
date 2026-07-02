# Echo AI

A [Fabric](https://fabricmc.net/) mod that drops an AI assistant straight into Minecraft chat. It reads what players say, decides whether a reply is warranted, and answers in-line, with optional web search for questions it can't confidently answer from memory.

The AI talks to any OpenAI-compatible chat completions endpoint, so you can point it at OpenAI, a local server (Ollama, LM Studio, llama.cpp, etc.), or any other compatible provider.

## Features

- **In-chat assistant** — watches all chat messages and replies inline. Everyone sees the same conversation; there's no per-player session.
- **Knows when to stay quiet** — the model can call a `skip_response` tool to ignore small talk or messages that aren't directed at it, instead of replying to everything.
- **Web search (optional)** — a `web_search` tool powered by [Exa](https://exa.ai/) lets the model look up current or version-specific facts instead of guessing. Enabled automatically when an Exa API key is configured.
- **Minecraft-aware** — the system prompt is primed for Minecraft Java Edition, injects the running game version, and encourages short replies using Minecraft `§` formatting codes.
- **Per-player opt-out** — players can remove themselves from the AI with a command. Opted-out players' messages still count as context for others, but never trigger the AI and never receive its replies.
- **Reasoning-model friendly** — inline `<think>…</think>` blocks are stripped from replies so chain-of-thought never leaks into chat.
- **Concurrency-safe** — while a reply is generating, new messages supersede the in-flight request so the AI always answers against the latest chat, one request at a time.
- **Burst coalescing** — a short, configurable debounce collapses rapid-fire messages into a single AI request, so spam or a quick back-and-forth doesn't waste API calls.

## Requirements

|               |          |
| ------------- | -------- |
| Minecraft     | 26.1.2   |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API    | required |
| Java          | ≥ 25     |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods/` folder.
3. Place the `echo-ai` jar in your `mods/` folder.
4. Launch the game or server once so the mod generates its config file, then fill in your credentials (see below).

The mod can run on the client (singleplayer / integrated server) or on a dedicated server — chat handling and commands are all server-side.

## Configuration

On first launch the mod writes an empty config to `<config>/echo-ai.json` (typically `.minecraft/config/echo-ai.json`, or the server's `config/` directory). Fill it in like so:

```json
{
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "sk-...",
  "model": "gpt-4o-mini",
  "exaApiKey": "your-exa-api-key",
  "debounceMs": 300
}
```

| Field        | Required | Description                                                                                                                                             |
| ------------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `baseUrl`    | ✅       | Base URL of an OpenAI-compatible API. The mod appends `/chat/completions`. A trailing slash is tolerated.                                               |
| `apiKey`     | ✅       | API key sent as a `Bearer` token.                                                                                                                       |
| `model`      | ✅       | Model name. May also be a JSON array of names — the first entry is used, which is handy for keeping alternatives on hand and switching by reordering.   |
| `exaApiKey`  | ⬜       | [Exa](https://exa.ai/) API key. When omitted, the `web_search` tool is disabled and the mod still runs.                                                 |
| `debounceMs` | ⬜       | Delay (ms) before the AI reacts to a message; each new message resets it, coalescing bursts into one request. Defaults to `300`. Set to `0` to disable. |

If `baseUrl`, `apiKey`, or `model` is missing, the mod logs a warning and stays inactive.

## Commands

Players manage their own participation with `/echo`:

| Command        | Description                                                               |
| -------------- | ------------------------------------------------------------------------- |
| `/echo optout` | Stop the AI from reacting to your messages and hide its replies from you. |
| `/echo optin`  | Rejoin the AI.                                                            |
| `/echo status` | Show whether you're currently opted in or out.                            |

Opt-out state is persisted to `<config>/echo-ai-optout.json`.

## How it works

1. Every chat message is appended to a shared, in-memory history.
2. Messages from opted-out players are kept as context but don't trigger the AI.
3. A triggering message starts a short debounce timer (`debounceMs`, default 300ms); each further message resets it, so a burst collapses into one request once chat settles.
4. When the timer elapses the AI runs one request at a time. New messages that arrive mid-request supersede the in-flight run, so the AI always answers against the latest chat.
5. The model may call tools (e.g. `web_search`) and loop with their results, reply with text, or call `skip_response` to stay silent.
6. Text replies have any `<think>` reasoning stripped and are broadcast to every opted-in player.

- Conversation history is **in-memory and per server session** — it starts fresh on every server start and is capped at the most recent 50 messages.
- The tool-calling loop is bounded (max 5 round-trips) so a misbehaving model can't loop forever.
- All conversation state is confined to the server thread; async API calls are marshalled back onto it, so the design is lock-free.

## Building

The project uses Gradle with [Fabric Loom](https://github.com/FabricMC/fabric-loom) and requires JDK 25.

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

Every push and pull request is built in CI via the [`build` workflow](.github/workflows/build.yml).

## Roadmap

Ideas and improvements on the horizon (contributions welcome):

- **Smarter context handling** — replace the flat 50-message history cap with proper context compaction (e.g. summarizing older messages) so long conversations aren't abruptly truncated.
- **In-game powers** — optional tools that let the AI _act_ in the world, not just talk: running commands, giving items, teleporting, placing blocks, etc. These would be off by default and gated behind config and permissions.

## Using in modpacks

You're welcome to include Echo AI in modpacks, as long as appropriate credit is given (a link back to this project). Please don't rehost the jar as your own.

## License

All Rights Reserved.

Author: [LuisAFK](https://github.com/lafkpages) · Source: [github.com/lafkpages/echo-ai](https://github.com/lafkpages/echo-ai)
