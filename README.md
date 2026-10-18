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
  - A mod's code is decompiled only when it's needed: when you mention the mod, when you're holding or looking at something from it, when Claude asks for it, or when you add it yourself. Mods that never come up are never decompiled.
- **Your world** (single-player):
  - **Find items:** "Where are my diamonds?" searches every chest, barrel and shulker box, including what's inside shulker boxes in chests, plus your inventory and ender chest.
  - **Find mobs and things:** "Where's Rex?" finds pets, named mobs, item frames and dropped items.
  - **Find blocks:** "Where did I put my respawn anchor?" or "Find the closest skeleton spawner" searches placed blocks, including their state (anchor charges, which way a block faces) and data (what a spawner spawns, sign text).
  - **Find structures:** "Where's the nearest village I haven't explored?" finds structures like `/locate` does, but returns several and can skip the ones you've already been to. "Find me a nether fortress in a warped forest" checks which biomes each structure's pieces reach into, worked out from the seed, so nothing gets generated. Add "at least 75% warped forest" to skip ones that only touch it.
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

1. Build the mod (`./gradlew build`), or use a release jar.
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
| `buildKnowledge` | `true` | Decompile Minecraft at startup |
| `knowledgeInclude`, `knowledgeExclude` | | Mod ids, comma-separated |
| `extraInstructions` | | Added to Claude's instructions, e.g. `Answer like a pirate.` |

## Privacy and safety

- **Read-only.** Claude can read the reference files, search the web, and use the world tools. It can't edit files, run commands, or change your world. Waypoint buttons only open Xaero's own screen; nothing is added until you confirm it.
- **Local only.**
  - The decompiled code and extracted data stay on your computer, in `.minecraft/config/claudechat/knowledge/`. Delete that folder any time; it's rebuilt as needed.
  - The world tools are served only to the `claude` process the mod starts, on `127.0.0.1` with a random token. They stop when you leave the world.
- **Your own MCP servers stay out.** Other MCP servers you've set up for Claude Code aren't loaded for Minecraft questions.
- **What gets sent:** your question and the game details above go to Claude, the same as any Claude Code session.

## Limitations

- **Multiplayer:** world questions only work in single-player. On a server, the world's contents and the server's data packs aren't available on your computer. Questions about the game and your mods still work.
- **Timing:**
  - **Saved vs. live:** chunks near you are read live. Everything else comes from the last save, which can be up to 5 minutes behind.
  - **Visited:** a structure counts as visited after about a minute within 128 blocks of it.
- **Predictions:** for places you've never been, biomes and structure layouts come from the seed and are exact, but blocks are only the base terrain (stone, water, air), without grass, caves cut by carvers, trees or ores. Worlds started in an older Minecraft version keep their old terrain and biomes where already generated.
- **Speed:** answers take a few seconds for simple questions and longer for deep ones, and each question starts a fresh `claude` process. `/claude model haiku` is faster but makes more mistakes.

## License

CC0-1.0
