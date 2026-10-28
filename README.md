# ClaudeChat

Ask Claude about Minecraft without leaving the game. ClaudeChat is a client-side Fabric mod for **Minecraft Java Edition 26.3**. Type a question in chat and Claude can use your game version, installed mods, and what you are looking at to answer. In single-player, it can also search your world.

The mod runs the Claude Code terminal app on your computer. It prepares reference files from Minecraft and your mods, including recipes and decompiled code, so Claude can investigate how your game works. Answers appear in chat and can include item icons, recipe grids, clickable commands, and waypoint buttons.

<img width="499" height="205" alt="image" src="https://github.com/user-attachments/assets/fa46cac7-3b12-4be6-83ac-8806afef4bfd" />

## Why

Claude's training data can be incomplete or out of date, and wikis can contain mistakes or block access by agents. ClaudeChat decompiles Minecraft and your mods so Claude can read their code and data directly when answering questions. This gives it information specific to your game version and installed mods, though its answers can still contain mistakes.

## Installation

1. Install **Claude Code for Windows**. Open PowerShell and run the [official installer](https://code.claude.com/docs/en/setup):

   ```powershell
   irm https://claude.ai/install.ps1 | iex
   ```

2. Open a new PowerShell window, run `claude`, and follow the sign-in prompts. You need an account with access to Claude Code. ClaudeChat uses the CLI’s authentication: usage can count against your Claude subscription or incur API charges, depending on how you sign in. If you have a subscription, we recommend using it. You do not need to enter an API key in the mod. See the [Claude Code quickstart](https://code.claude.com/docs/en/quickstart) for help.
3. Install [Fabric Loader](https://docs.fabricmc.net/players/installing-fabric/windows) for Minecraft **26.3**. Use Loader **0.19.5 or newer**.
4. Download Fabric API **0.163.0+26.3 or newer** for Minecraft 26.3 and the ClaudeChat release jar. Put both jars in your instance's `mods` folder. For the standard Minecraft Launcher on Windows, this is `%appdata%\.minecraft\mods`. Other launchers may use a different folder.
5. Start Minecraft with the Fabric profile. ClaudeChat prepares Minecraft's reference files in the background on first launch. You can ask questions while it works.

ClaudeChat looks for `claude` on your PATH and in `~/.local/bin`. If it is installed elsewhere, set `claudePath` in `config/claudechat.properties` to its full path.

## Basic Usage

Open Minecraft chat and start a message with `@claude`:

```text
@claude how do I craft a crafter?
@claude what does the block I'm looking at do?
@claude where did I put my diamonds?
```

The question stays out of server chat. A status line shows progress until the answer appears. Ask a follow-up in the same world to continue the conversation; use `/claude reset` to start over.

Claude can answer questions about Minecraft and your installed mods in single-player or multiplayer. World searches, such as finding your chests or unvisited structures, require a single-player world. With a supported version of Xaero's Minimap, Claude can offer a waypoint button that opens Xaero's confirmation screen.

ClaudeChat is **read-only by default**. Claude can search the reference files and use the available world tools, but cannot change your world or edit files unless you change the permissions. Web search is off by default. Your question, game context, and information Claude reads, including screenshots requested through `look`, are sent to Claude's service.

## MCP Tools

Claude chooses these tools as needed; you do not have to call them yourself. MCP is the connection ClaudeChat uses to give Claude access to game information.

| Tool | What it does | Availability |
| --- | --- | --- |
| `decompile_mod` | Makes an installed mod's code searchable when Claude needs it. | Any world |
| `look` | Describes what is visible on screen and can provide a screenshot. | Single-player and multiplayer |
| `search_containers` | Finds items in containers, nested shulker boxes and bundles, your inventory, and your ender chest. | Single-player |
| `search_entities` | Finds named or tamed entities and entities holding items, such as pets and item frames. | Single-player |
| `get_player` | Gets your position, dimension, inventory, and ender chest. | Single-player |
| `find_structures` | Finds nearby structures, with filters for visits, biomes, and parts such as an end city ship. | Single-player |
| `find_blocks` | Finds placed blocks by type, state, or data, such as a skeleton spawner. | Single-player |
| `find_biomes` | Finds nearby biome patches and can filter by visited status. | Single-player |
| `inspect_location` | Checks the block and biome at coordinates, or predicts terrain in ungenerated chunks. | Single-player |
| `search_structure_templates` | Searches structure pieces by name or blocks to help identify a building. | Single-player |
| `inspect_structure_template` | Inspects a structure piece, including its blocks, contents, and layers. | Single-player |
| `list_schematics` | Lists Litematica, WorldEdit, and structure schematic files in your schematics folder. | Single-player |
| `inspect_schematic` | Lists materials and crafting ingredients, and can compare them with a chest or your inventory. | Single-player |
| `run_command` | Runs a game command under your selected permissions and cheats settings. | Single-player |
| `approve` | Handles Claude Code permission requests through chat buttons. | Internal |

Loaded chunks are read live; other chunks use the last save and may be out of date. A place counts as visited after a player has spent at least one second within 128 blocks. The mod keeps a visit record in the world folder; older visits only count where Minecraft saved them. Structure layouts are predictions of what generated, not proof that loot remains. Predictions for ungenerated blocks cover base terrain only.

## Game Commands

All commands support tab completion. Use `/claude` to see the current model, effort, and help.

| Command | What it does |
| --- | --- |
| `/claude model <haiku\|sonnet\|opus\|fable\|id>` | Choose a model. The default is Sonnet. |
| `/claude effort <low\|medium\|high\|xhigh\|max\|default>` | Set how much effort Claude uses. |
| `/claude reset` | Stop the current question and start a new conversation. |
| `/claude permissions <read_only\|ask\|full>` | Set what Claude may do beyond reading. |
| `/claude cheats <on\|off>` | Allow or prevent Claude's game commands from using elevated game permissions. |
| `/claude web <on\|off>` | Allow or prevent web searches and page fetches. Default: off. |
| `/claude knowledge` | Show the status of Minecraft and mod reference files. |
| `/claude knowledge include <mod>` | Always decompile the named mod. |
| `/claude knowledge exclude <mod>` | Do not decompile the named mod; its data remains searchable. |
| `/claude knowledge rebuild` | Rebuild the reference files. |
| `/claude preview` | Show examples of chat formatting. |

The `read_only` permission tier allows reference-file reads and read-only game commands. `ask` asks you to allow or deny file changes, shell commands, and game commands that may change the world. `full` allows them without asking. Your own Claude Code settings can grant additional file access. Turning on `full`, cheats, or web access requires confirmation in chat. A question keeps the settings it had when you asked it; changes apply to the next question. With `full`, shell commands may reach the network even when web access is off.

Other settings, including the chat prefix, timeout, and extra instructions, are in `config/claudechat.properties` in your game folder.

## Privacy

ClaudeChat runs the Claude Code CLI in non-interactive mode and does not make API requests or access the internet itself. Its MCP tools listen only on the local computer (`127.0.0.1`). Claude Code handles the network requests needed to answer your question.

ClaudeChat starts Claude Code but does not directly access your Claude authentication details. Your question and the game information Claude needs to answer, such as installed mods, world data, file contents, or a screenshot, are sent through Claude Code to its configured service. ClaudeChat does not send this information to a separate service. It stores reference files and visit records locally; game logs can also contain diagnostic details such as mod names and errors.

With `full` permissions, Claude can run shell commands without asking. Instructions hidden in content it reads (prompt injection) could cause it to send personal information elsewhere, even with web access off. Enabling this mode means accepting that risk.

## Showcase

Try asking:

- **Find your things:** `@claude where did I put my diamonds?`
- **Plan an expedition:** `@claude where's the nearest bastion I haven't been to yet?`
- **Explore a structure:** `@claude find an unvisited end city with a ship and make a waypoint`
- **Understand your surroundings:** `@claude what am I looking at?`
- **Investigate a build:** `@claude what is this mossy stone tower with a spawner?`
- **Prepare a project:** `@claude what materials do I need for my witch farm schematic, and does my Witch Farm chest have them?`
- **Learn the game:** `@claude how does this spawner work?`
- **Use your mods:** `@claude how do I craft the item I'm holding?`

<img width="499" height="225" alt="image" src="https://github.com/user-attachments/assets/d79a075e-a79f-431d-8c20-00f4d0d17296" />

<img width="495" height="143" alt="image" src="https://github.com/user-attachments/assets/d1f15d8d-c272-4be7-b487-9c676d40ba88" />


Claude can make mistakes, so check the answer before acting on it.

Licensed under CC0-1.0.
