# Claude Chat

Ask Claude about Minecraft without leaving the game. Type `@claude` in chat, and the answer appears right there: recipes drawn as icon grids, items you can hover for tooltips, commands you can click.

```
> @claude how do I craft this?
[Claude] Thinking… 2s
[Claude] ▦▦▦
         ▦▣▦  → ▣ Crafter
         ▪▫▪
```

Claude doesn't answer from memory. It reads the game's own code and data for your exact version and mods, and in single-player it can look around your world: which chest has your diamonds, the nearest village you haven't explored yet.

Client-side Fabric mod for **Minecraft 26.3**.

## What it can do

- **Answers about your game.** Every question comes with your Minecraft version, installed mods, position, biome, held item and the block you're looking at, so "what does this do?" just works.
- **The real game, not guesses.**
  - The mod decompiles Minecraft in the background (about 30 seconds, once per version) and extracts every recipe, loot table, tag and structure file, from Minecraft, your mods and your world's data packs. Claude searches all of it.
  - For questions like "how does spawn potential work?" it reads the actual source.
- **Your mods too.**
  - Every mod's data (recipes, loot, structures) is searchable from the start.
  - A mod's code is decompiled only when it's needed: when you mention the mod, when you're holding or looking at something from it, when Claude decides it needs it (on servers too), or when you add it yourself. Mods that never come up are never decompiled.
- **Your world** (single-player):
  - **Find items:** "Where are my diamonds?" searches every chest, barrel and shulker box, including what's inside shulker boxes in chests, plus your inventory and ender chest.
  - **Find mobs and things:** "Where's Rex?" finds pets, named mobs, item frames and dropped items.
  - **Find blocks:** "Where did I put my respawn anchor?" or "Find the closest skeleton spawner" searches placed blocks, including their state (anchor charges, which way a block faces) and data (what a spawner spawns, sign text).
  - **Find structures:** "Where's the nearest village I haven't explored?" finds structures like `/locate` does, but returns several and can skip the ones you've already been to. "Find me a nether fortress in a warped forest" checks which biomes each structure's pieces reach into, worked out from the seed, so nothing gets generated. Add "at least 75% warped forest" to skip ones that only touch it. "Find me an end city with a ship" checks which rooms each one has, also from the seed: end city ships, village buildings, stronghold libraries and so on. That's what generates there, not whether it's been looted since.
  - **Find biomes:** "Where's the nearest cherry grove?" works like `/locate biome`, but lists several separate patches and can skip places you've been.
  - **Inspect a spot:** "What biome is 1200 64 -300?" reads the block and biome there, or predicts them from the seed if you haven't been there yet.
  - **Schematics:** "How much redstone do I need to build my witch farm, including what goes into the pistons?" reads Litematica (and WorldEdit) schematics from your schematics folder, makes the material list and breaks crafted parts down using the game's recipes. "Does the Witch Farm chest have everything?" checks a named chest (shulker boxes inside included) and says what's still missing.
  - **Identify structures:** "What's this little mossy stone brick tower with a spawner?" searches the structure template files (from Minecraft, your mods and data packs) by the blocks they contain, and says which structure uses them. Claude can also see a template's chests, spawners and layer-by-layer shape.
  - **Exact directions:** distances and directions are calculated for you, e.g. "2,832 blocks south-east, 160 up".
- **Rich chat.**
  - Item icons with real tooltips, and recipe grids drawn from the game's data.
  - Your actual key bindings ("press [Right Button]"), and commands you can click to put in the chat box.
  - **Waypoints** (with Xaero's Minimap): "Find me the nearest end city I haven't visited and add a waypoint" puts a **[+ Waypoint]** button next to the coordinates. Click it, and Xaero's Add Waypoint screen opens, already filled in for the right dimension; press Confirm. The waypoint shows on Xaero's World Map too.
  - Bold, italics, lists and links.
- **Live progress.** A status line appears as soon as you ask ("Thinking… 3s", "Checking the spawner code… 14s") and is replaced by the answer.

## Requirements

- Minecraft Java Edition **26.3** with **Fabric Loader** 0.19.5+ and **Fabric API**.
- **[Claude Code](https://claude.com/claude-code)** installed and signed in. The mod runs `claude -p` on your computer, so it uses your own Claude subscription (or API account) and needs no API key in the mod.
  - Install it, then run `claude` once in a terminal and sign in.
  - The mod finds `claude` on your PATH or in `~/.local/bin`. If it's somewhere else, set `claudePath` in the config.

## Install

1. Build the mod with a JDK 25 (point `JAVA_HOME` at it, then `./gradlew build`), or use a release jar.
2. Copy `claudechat-<version>.jar` into your instance's `mods` folder, next to Fabric API.
3. Start the game. On first launch the mod decompiles Minecraft in the background and posts a chat message when it's done. You can ask questions right away; answers just get better once that finishes.

## Using it

Ask anything in normal chat. It's never sent to the server:

```
@claude what's the best way to find netherite?
@claude where did I put my mending book?
@claude what is the loot table of a tavern?
```

Questions in the same world continue one conversation, so follow-ups work.

| Command | What it does |
| --- | --- |
| `/claude` | Current model, effort and help |
| `/claude model <haiku\|sonnet\|opus\|fable\|id>` | Choose the model (default: Sonnet) |
| `/claude effort <low\|medium\|high\|xhigh\|max\|default>` | How hard Claude thinks |
| `/claude reset` | Start a new conversation |
| `/claude permissions <read_only\|ask\|full>` | What Claude may do beyond reading (see [Permissions](#permissions)) |
| `/claude cheats <on\|off>` | Whether Claude's game commands may cheat where you can't |
| `/claude web <on\|off>` | Whether Claude may search the web and fetch pages (default: off) |
| `/claude knowledge` | What's decompiled and what's data-only (hover a mod for details) |
| `/claude knowledge include <mod>` | Always decompile a mod's code |
| `/claude knowledge exclude <mod>` | Never decompile a mod (its data stays searchable) |
| `/claude knowledge rebuild` | Rebuild everything |
| `/claude preview` | Show every kind of formatting, without asking Claude |

All of these tab-complete.

## Settings

`.minecraft/config/claudechat.properties` (also changed by the commands above):

| Setting | Default | |
| --- | --- | --- |
| `model` | `sonnet` | `haiku` is faster but less reliable |
| `effort` | (Claude Code's default) | |
| `chatPrefix` | `@claude` | |
| `timeoutSeconds` | `300` | |
| `claudePath` | (auto) | Full path to `claude` / `claude.exe` |
| `buildKnowledge` | `true` | Build the knowledge base at startup: decompile Minecraft and included mods, copy every mod's data. With `false`, that waits for `/claude knowledge rebuild` or `include`. Either way, a question that names a mod, or Claude's `decompile_mod`, still decompiles that mod |
| `knowledgeInclude`, `knowledgeExclude` | | Mod ids, comma-separated |
| `extraInstructions` | | Added to Claude's instructions, e.g. `Answer like a pirate.` |
| `permissions` | `read_only` | `read_only`, `ask` or `full` |
| `cheats` | `false` | |
| `web` | `false` | Web search and page fetches, in every permissions tier |

## Permissions

| `/claude permissions` | Files and shell | Game commands (single-player) |
| --- | --- | --- |
| `read_only` (default) | Read and search the reference files only. Files anywhere else are refused | Only ones that change nothing, like `time query`, `data get` or `gamerule <name>`. Every other one is refused, cheats or not |
| `ask` | Edits, new files and shell commands each wait for you to click **[Allow]** or **[Deny]** in chat, and so does reading a file outside the reference files and the game folder. Commands that only read run straight away | The same: read-only ones run, every other one asks. Destructive ones (`clear`, `kill`, `fill`…) are marked in red, with the block count for `fill` and `clone` |
| `full` | Anything, without asking | Anything, without asking |

- The mod decides which game commands need your OK; Claude Code's own judgment isn't used for them. Commands added by mods always ask.
- No answer within 2 minutes counts as Deny. A sound plays and the status line says so, since chat fades while closed.
- What runs after **[Allow]** is exactly what the request showed: the same parsed game command, or Claude Code's request with every field it carries. A request whose question has ended (left the world, `/claude reset`) is denied, and its buttons stop working.
- Every game command Claude runs is shown in chat, with the game's response on hover.
- `/claude cheats` decides *what* is possible, not whether Claude may change things. Off: Claude's commands run with your own permissions, so in a world without cheats it can't `/give` or `/gamemode` either. On: they run at command block level (or yours, if higher), always as you. The world list still shows cheats off.
- `/claude web` is separate from the permissions above: you can let Claude edit files without letting it reach the web. Off (the default), Claude isn't given web search or page fetching in any tier, so it answers from the game's files and your world. In `full`, shell commands can still reach the network either way.
- Turning on `full`, cheats or web access needs a click on a confirm button.
- A question keeps the permissions, cheats and web settings it was asked with; changes apply from your next question. Leaving the world or `/claude reset` stops a running question, along with anything it started.

## Privacy and safety

- **Read-only by default.** Claude can read the reference files (and no other files on your computer, unless your own Claude Code settings allow more) and use the world tools. Unless you change `/claude permissions`, it can't edit files, run shell commands, or change your world, and unless you turn on `/claude web`, it can't search the web or open pages. Waypoint buttons only open Xaero's own screen; nothing is added until you confirm it. The mod itself writes one file in your world folder, `claudechat-visits.txt` (where you've been), and never touches the world's own files.
- **Local storage and transport.**
  - The decompiled code and extracted data stay on your computer, in `.minecraft/config/claudechat/knowledge/`. Delete that folder any time; it's rebuilt as needed.
  - The mod's tools are served on `127.0.0.1` only. Each question gets a random token of its own, which works only until that question ends. The `claude` process gets it in a file only your account can read, deleted when the question ends. Anything that has the token can use the tools while the question runs, so another program running as you could too. The world tools are offered only while a single-player world is open.
- **Your own MCP servers stay out.** Other MCP servers you've set up for Claude Code aren't loaded for Minecraft questions.
- **What gets sent:** the processing isn't local. Claude Code sends your question, the game details above, and whatever Claude reads or gets from the mod's tools (files, world data, the `look` screenshot) to Claude's service, the same as any Claude Code session.

## Limitations

- **Multiplayer:** world questions only work in single-player. On a server, the world's contents and the server's data packs aren't available on your computer. Questions about the game and your mods still work.
- **Timing:**
  - **Saved vs. live:** chunks near you are read live. Everything else comes from the last save, which can be up to 5 minutes behind.
  - **Visited:** a structure counts as visited once you've spent a second within 128 blocks of any part of it. Minecraft doesn't always save how long you've spent somewhere, so the mod keeps its own record in `claudechat-visits.txt` in the world folder. Time spent before you installed this version only counts where the game happened to save it.
- **Predictions:** for places you've never been, biomes and structure layouts come from the seed and are exact, but blocks are only the base terrain (stone, water, air), without grass, caves cut by carvers, trees or ores. Worlds started in an older Minecraft version keep their old terrain and biomes where already generated.
- **Speed:** answers take a few seconds for simple questions and longer for deep ones, and each question starts a fresh `claude` process. `/claude model haiku` is faster but makes more mistakes.

## License

CC0-1.0
