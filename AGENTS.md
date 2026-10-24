# Claude Chat: notes for coding agents

Client-side Fabric mod for Minecraft **26.3**. `@claude <question>` in chat runs the local Claude Code CLI (`claude -p`) and renders the answer in chat. User-facing overview: `README.md`.

## Build and run (Windows, PowerShell 5.1)

- **Java:** JDK 25 is a portable install at `C:\Users\jackson\.jdks\jdk-25.0.4.1+1`. The build declares a Java toolchain (`java_version` in `gradle.properties`) and the JVM running Gradle counts as a candidate, so pointing `JAVA_HOME` at it is enough, and the wrapper needs `JAVA_HOME` anyway:
  `$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.4.1+1"; .\gradlew.bat build`
  Running Gradle on a different JDK needs a JDK 25 that toolchain detection can find, or one registered as `org.gradle.java.installations.paths=<jdk folder>` in the user-level `~\.gradle\gradle.properties` (never the project's).
- **Versions:** Java, Minecraft, Fabric Loader and Fabric API versions are set only in `gradle.properties`; `processResources` fills them into `fabric.mod.json` and the mixin config.
- **Output:** the jar is `build\libs\claudechat-<version>.jar` (Vineflower is bundled inside it).
- **Git:** not on PATH in agent shells. Call `& "C:\Program Files\Git\cmd\git.exe"`. The author is set repo-locally.
- **Commit messages:** write them to a file and use `git commit -F <file>`. PowerShell 5.1 passes here-strings to native commands as arguments, which breaks `-F -`.

## Test

`.\gradlew.bat test` runs the fast unit tests in `src/test/java` (JUnit 5, no game client). `fabric-loader-junit` loads Minecraft classes, but nothing is bootstrapped and there is no client: a test that needs registries or the command tree calls `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()` itself (see `CommandRulesTest`).

`.\gradlew.bat runClientGameTest` starts the real client, creates a world, types into chat, and saves screenshots to `build\run\clientGameTest\screenshots\`. Read them to check rendering. The test is `src/gametest/java/claudechat/test/ClaudeChatClientGameTest.java`; optional steps are switched on with environment variables:

| Env var | Step |
| --- | --- |
| `CLAUDECHAT_LIVE_TEST=1` | Real `@claude` questions (uses the user's Claude subscription) |
| `CLAUDECHAT_MOD_QUESTION="..."` | Ask about a mod (on-demand decompile) |
| `CLAUDECHAT_PACK_TEST=1` | Writes a folder and a zip data pack, runs `/reload`, asks about them |
| `CLAUDECHAT_TEMPLATE_TEST=1` | Calls the structure template tools with checks; `CLAUDECHAT_TEMPLATE_QUESTION="..."` also asks Claude |
| `CLAUDECHAT_SCHEMATIC_TEST="<folder>"` | Copies the schematics there into the test, checks material lists and a chest comparison; `CLAUDECHAT_SCHEMATIC_QUESTION="..."` also asks Claude |
| `CLAUDECHAT_WAYPOINT_TEST=1` | Needs Xaero's Minimap via `-PextraMods`. Runs `[[waypoint:…]]` button commands, confirms Xaero's screen, checks `waypoints.txt` per dimension; `CLAUDECHAT_WAYPOINT_QUESTION="..."` also asks Claude |
| `CLAUDECHAT_PERMISSION_TEST=1` | World with cheats off. Calls `run_command` in each tier with cheats off and on, clicks the chat Allow/Deny buttons (screenshots `claudechat-approval-*`), calls the `approve` tool over MCP; with `CLAUDECHAT_LIVE_TEST=1` Claude also creates a file and runs a command, and the test clicks Allow |
| `CLAUDECHAT_LOOK_TEST=1` | Builds a wall with a glass window in front of the player, calls `look` and checks what it sees (diamonds through the glass, sign text, the near pig) and doesn't (a gold block and a pig behind the wall); saves the tool's screenshot as `claudechat-look-tool.png`. `CLAUDECHAT_LOOK_QUESTION="..."` also asks Claude |
| `CLAUDECHAT_WORLD_TEST=1` | Normal world (not superflat), stashes items, calls every world tool directly (logged as `tool <name> <args> -> <json>`), checks structure pieces against real generation, visits a village and a far chunk (checks the visited filter and `claudechat-visits.txt`), asks Claude |

- **Test with real mods:** `-PextraMods="<folder of jars>"`. The user's mods are in `%APPDATA%\ModrinthApp\profiles\Fabric 26.3\mods`.
- **Hang on exit:** with those mods, the client hangs after "Xaero hud session finalized". Run the test in the background, wait for the last screenshot, then stop only the java process whose command line matches `clientGameTest`. Other `claude` and `java` processes belong to the user.
- **Logs:** pipe the run to a file in `$env:TEMP` and `Select-String` it. Delete the file afterwards.

## Layout (`src/client/java/claudechat/`)

| File | Role |
| --- | --- |
| `ClaudeChatClient` | Entry point: `/claude` commands, `@claude` chat hook, lifecycle events |
| `ClaudeCli` | Runs `claude -p`: system prompt, flags, MCP config, timeout (kills `claude` and its child processes) |
| `ClaudeStream`, `ClaudeResult` | Reads the stream-json output: progress notes, and the final `result` event parsed once (retry only on a missing session) |
| `ClaudeConfig` | `config/claudechat.properties`; `configVersion` drives migrations |
| `KnowledgeBase` | `config/claudechat/knowledge/`: decompiles Minecraft (always) and mods (on demand) with Vineflower in a separate JVM; extracts data and lang files; world data packs; the prompt's file map |
| `ChatMarkup`, `ChatFormatter`, `ItemIcons`, `RecipeRenderer` | Markdown subset parsed into lines (pure, unit-tested) → chat components; `[[item]]` icons; `[[recipe:id]]` grids from data JSON, with tags from the game's merged tag registry |
| `Waypoints` | `[[waypoint:Name\|x y z\|dimension]]` → a button running Xaero's `xaero_waypoint_add:` share command (only while a known-good version of Xaero's Minimap is loaded: `KNOWN_GOOD_VERSIONS`) |
| `StatusLine` + `mixin/ChatComponentAccessor` | The live "Thinking… 3s" line, replaced in place |
| `WorldIndex` | Reads region files (read-only) plus live loaded chunks: containers, entities, players, per-chunk `InhabitedTime` (with its own record of what the save lacks) and block palettes |
| `StructureFinder` | Multi-result `/locate` with visited filtering; builds a structure's pieces in memory (`Structure.generate`) to name them (template id, or class for hand-coded pieces, via `mixin/*Accessor`) and sample the biomes they occupy |
| `BlockFinder` | Nearest blocks by id/tag, state properties and block entity text; re-reads only saved chunks whose palette has the block |
| `BiomeFinder` | Multi-result `/locate biome` from the seed, off the server thread |
| `LocationInspector` | Block and biome at a position: live, from the save, or predicted from the seed (base terrain only) |
| `RegistryQuery` | Id, `#tag` or word → registry entries, for blocks and biomes |
| `StructureTemplates` | Structure `.nbt` templates read through the server's resource manager (data-fixed), indexed per reload for block search; traces template → pools → structures |
| `Schematics`, `Materials` | Schematic files (`.litematic`, `.schem`, `.nbt`) from Litematica's folder; material lists, recipe breakdown and container comparison |
| `McpServer`, `ModTools`, `WorldTools` | MCP on 127.0.0.1 with a random port and bearer token, for the whole game session: `decompile_mod` and `approve` always, the eleven world tools and `run_command` only while a single-player world is open |
| `ViewSnapshot` | `look`, in any world (multiplayer too): rays through the screen grid (refined on edges) plus an entity line-of-sight pass, reporting what's visible with image positions, and a screenshot of the same frame as MCP image content |
| `GameCommands`, `CommandRules` | `run_command`: parses with the server's dispatcher; `CommandRules` walks the parse's contexts (each `execute` modifier, `run`, alias) and allows a command unasked only if every modifier is a non-writing vanilla one and the final command is vanilla and on its read-only list. Runs as the host player's own source, or GAMEMASTER level with cheats on |
| `Approvals`, `ChatActions` | Ask for approval: chat request with [Allow]/[Deny], the `approve` tool for `--permission-prompt-tool`. Buttons run `/claude action <random id>` so no chat message can carry a working copy |

## Conventions

- Tabs for indentation. Javadoc on classes and on non-obvious methods; comments explain *why*.
- No new runtime dependencies. Vineflower is the only bundled jar. The MCP server uses plain sockets on purpose: some launchers' trimmed Java runtimes lack `jdk.httpserver`.
- Everything is client-side (`"environment": "client"`). World access goes through the integrated server only, so multiplayer gets no world tools.
- **Read only by default.** `Read,Grep,Glob,WebSearch,WebFetch` plus `mcp__minecraft`. Edit, Write, Bash and PowerShell come only from `/claude permissions ask` or `full` (`ClaudeCli.permissionFlags`), never by default and never from a migration. Anything that changes the world goes through `run_command`'s own rules; don't pre-approve it in Claude Code.
- **Never commit decompiled Minecraft or mod code.** It lives under `run/` and `build/`, which are gitignored.

## Looking up Minecraft APIs

Minecraft 26.x ships **unobfuscated** with Mojang's names, so there are no mappings. Loom is the `net.fabricmc.fabric-loom` plugin and doesn't remap; use `implementation`, not `modImplementation`.

- **Search the decompiled source; don't guess API names.** Many classes and methods were renamed in 26.x (`Identifier`, `GuiGraphicsExtractor`, `ChunkPos.x()`, `mc.gui.hud.getChat()`…). The source is at `run/config/claudechat/knowledge/minecraft-26.3/src/`, created by any dev or test client run.
- **For Fabric API or other jars:** `javap -cp <jar> <class>` (in the JDK's `bin`), or decompile with `run/config/claudechat/knowledge/vineflower.jar`.

## Gotchas (each cost real debugging time)

**Gradle and Loom**
- **Stale build after moving the project:** Gradle's configuration cache stores absolute paths. After moving the folder, the build "succeeds" with old classes. Delete `.gradle\` and `build\`.
- **Test source set config order:** `fabricApi { configureTests { … } }` must come *after* `loom { splitEnvironmentSourceSets() }`, or Gradle fails with "value … is final".
- **Test client's mods folder:** `runClientGameTest` empties `build/run/clientGameTest/mods` on every run, which is why `-PextraMods` passes jars via `-Dfabric.addMods`.
- **Installed copy of this mod:** the user's mods folder has a released `claudechat-*.jar`. Loaded via `-PextraMods`, it silently replaced the code under test, so `build.gradle` skips any jar whose `fabric.mod.json` id is `claudechat`, whatever its file name.
- **BOM breaks javac:** PowerShell 5.1's `Set-Content -Encoding utf8` writes a BOM, and javac then fails with "illegal character '\ufeff'". Edit Java files with the editor tools, or write with `UTF8Encoding($false)`.

**Client game tests**
- **Key codes:** 26.x input is **SDL**, not GLFW. Use `InputConstants.KEY_RETURN` / `KEY_ESCAPE` (scancodes). LWJGL isn't on the gametest compile classpath anyway.
- **Escape with suggestions open:** the first Escape only closes command suggestions; press it twice to close chat.
- **Saving:** `save-all` doesn't exist in single-player. Use `server.runOnServer(s -> s.saveEverything(true, true, true))`.
- **Test world settings:** the default test world is superflat with structures off. Structure tests need `worldBuilder().adjustSettings(...)`.
- **Waiting on world tools:** they block on the server thread, which the test thread drives. Call them from another thread and `context.waitFor(...)` (see `logTool`).

**Chat rendering**
- **Style inheritance:** child components inherit, and override, the parent's style. Append siblings to `Component.empty()` rather than to a styled prefix, or answer text turns gold.
- **Replacing lines:** vanilla can only delete *signed* chat messages, and leaves a marker. `StatusLine` edits `allMessages` through the accessor mixin and calls `refreshTrimmedMessages()`.
- **Inline icons:** these are `Component.object(new AtlasSprite(atlas, sprite))`, 8 px wide with no gap. Check that a sprite exists with `atlas.getSprite(id) != atlas.missingSprite()`. Item textures are in the `items` atlas (`item/<id>`), blocks in `blocks`, and the empty slot is `gui` / `container/slot`.

**claude CLI**
- **Install and sign-in:** the Claude desktop app's bundled `claude.exe` isn't signed in for standalone use. The real CLI is in `~/.local/bin`, which is often not on the game's PATH, so `ClaudeCli.findExecutable` checks it explicitly.
- **stream-json events:** `--output-format stream-json` needs `--verbose`. Each content block is its own `assistant` event, so text is only known to be a progress note once a later tool call arrives (`ClaudeStream`).
- **Missing session:** `--resume <id>` for a conversation Claude Code no longer has prints a `result` event with `is_error`, `num_turns: 0` and `errors: ["No conversation found with session ID: …"]`. Only that is retried as a new conversation; any other error may come after tools ran.
- **Windows argument quoting:** this breaks JSON and long strings. The system prompt and MCP config go in files (`--append-system-prompt-file`, `--mcp-config <file>`), and the question goes over stdin.
- **Tool flags:** `--tools` limits built-in tools. MCP tools are allowed with `--allowedTools mcp__minecraft`, and `--strict-mcp-config` keeps the user's other MCP servers out.
- **Permission prompts under `-p`:** there's no terminal to ask in. `--permission-prompt-tool mcp__minecraft__approve` sends each prompt to our MCP tool as `{tool_name, input, tool_use_id}`. It answers `{"behavior":"allow","updatedInput":…}` or `{"behavior":"deny","message":…}`. `--disallowedTools` hides the tool from Claude but it still handles prompts. Shell commands that only read never prompt. A 150 s wait in the tool was fine.
- **Auto mode doesn't judge game commands:** `--permission-mode auto` works under `-p`, but its classifier let a 41×38×41 `/fill … air` and `/clear` through when the player asked vaguely. That's why there's no "Approve for me" tier and why `run_command` classifies commands itself.
- **Empty arguments in PowerShell 5.1:** `--tools ""` is dropped when calling a native command, which shifts every flag after it. Pass a real value.
- **Model choice:** Haiku made up recipe details around correct grids, so the default is Sonnet. Keep the prompt rule that a `[[recipe:…]]` grid stands alone.

**World data**
- **Region files mid-game:** they aren't padded to whole sectors until closed. Read a chunk by its own length field, not `sectors * 4096`.
- **Files lag the game:** saved chunks are up to an autosave (5 min) behind, so loaded chunks are captured live on the server thread and override the saved copy.
- **"Generated" chunks:** structure searches (ours and `/locate`'s) leave `structure_starts` chunks on disk. Only `Status: minecraft:full` counts as generated.
- **InhabitedTime:**
  - It advances only while a player is within 128 blocks of the chunk and the chunk is within simulation distance (`ServerChunkCache.tickSpawningChunk`). So a structure's time is the most of any chunk its pieces cover: an end city in the user's world had 0 s at its start chunk and 101 s in its pieces.
  - It doesn't mark the chunk unsaved, so the game writes it only when something else in the chunk changes. `WorldIndex` records it on chunk unload (and every 10 s near players) and keeps what the save lacks in `<world>/claudechat-visits.txt`.
  - "Visited" is ≥ 1 s.
- **Section palettes:** in 26.x a palette entry is a bare string (`"minecraft:spawner"`) when the block is in its default state, otherwise `{id, properties}`, no longer `{Name, Properties}`. Use `WorldIndex.paletteName`.
- **26.x paths:** player data is in `players/data`, and dimensions are under `dimensions/<ns>/<path>/{region,entities}`. Use `DimensionType.getStorageFolder`.

**Game commands**
- **Raising permissions:** `CommandSourceStack.withMaximumPermission` doesn't raise anything in 26.3: `LevelBasedPermissionSet.union` keeps the *lower* level. `GameCommands.atLeastGamemaster` compares levels itself and uses `withPermission`.

**Xaero waypoints**
- **No Xaero dependency:** the button's `RunCommand` goes through `sendUnattendedCommand`, which Xaero's mixin cancels at HEAD for `xaero_waypoint_add:` and answers with its Add Waypoint screen. Without Xaero the command would reach the server, so `Waypoints.button` renders nothing then.
- **Destination parsing:** Xaero splits the dimension folder off at the *last* `-`, and its escaping turns `_` into `-` (`-` itself becomes `^min^`, `:` in names `^col^`). Going by the decompiled parser, its own shares (`Internal-the-end`) lose the dimension (untested). We send `Internal-<escaped dim%… folder>-waypoints`, which the game test checks for the Overworld, Nether and End.

**Mods and data packs**
- **Nested jars:** many mods keep their real code in nested jar-in-jar (Vein Miner's outer jar has 5 classes). Use the loader's `getContainedMods()` / `getRootPaths()`, and skip nested jars with Mod Menu's `library` badge.
- **Polymer:** Universal Graves depends on modules *inside* `polymer-bundled`, not on `polymer-bundled` itself.
- **Override order:** world data packs, then mods, then vanilla. `KnowledgeBase.dataRoots()` returns them in that order, and anything looking up data files must respect it.
