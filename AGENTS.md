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

`.\gradlew.bat runClientGameTest` starts the real client and plays through the mod. Its checks read chat history and tool results; it also saves screenshots to `build\run\clientGameTest\screenshots\` for checking rendering by eye. The test is in `src/gametest/java/claudechat/test/`:

- **Steps** are declared once, in `Step`: the switch, the question variable, other variables read, and the world. `TestOptions` parses the `CLAUDECHAT_*` variables against those declarations. An unknown name fails the run before the game starts, as does a switch other than `1`/`0`, or a question without its step's switch. Each step runs in a fresh world of its own and deletes the files it copies, so steps pass in any combination.
- **Every question goes to `FakeClaude`:** a JDK-only stand-in that plays the scenario the test writes to `claudechat-fake/scenario.txt`. `TestPreLaunch` (a `preLaunch` entrypoint) sets it as `claudePath` in `config/claudechat.properties` before the mod reads the file. It also resets permissions, cheats and the include/exclude lists, and sets the question timeout: 20 s, or 300 s when a real question is asked. A real question passes through the fake to the `claude` CLI (on PATH or in `~/.local/bin`). Each answer step checks that an answer line arrived and that no error line was posted.
- **`Game`** is the harness: chat history and buttons, questions (`ask`, `askClaude`, `hold`), and MCP calls as Claude makes them, logged as `tool <name> <args> (<ms> ms) -> <result>`. Calls that depend on permissions (`run_command`, `approve`) are made with the token of a question the fake holds open, after `/claude permissions` and `/claude cheats` with their confirm clicks. Other calls use `ClaudeChatClient.mcp()`, the one test hook in the mod.
- **Before the steps,** the test checks four things: `claudechat` is loaded from this build's `build/`, every mixin applies (`MixinEnvironment.audit`), the MCP tools offered with no world open are right, and Minecraft's code is decompiled (it waits through `decompile_mod`).

| Switch | Also reads | World | Step |
| --- | --- | --- | --- |
| (always) | | superflat | `BASICS`: world tools offered, `/claude preview`, recipe grids in an answer, tab completion, `/claude knowledge`, the status line updating in place until the answer replaces it |
| `CLAUDECHAT_MOD_TEST=1` | `CLAUDECHAT_MOD_QUESTION` | superflat | A question naming the test mod decompiles it first (status line, the prompt's file map) |
| `CLAUDECHAT_PACK_TEST=1` | `CLAUDECHAT_LIVE_TEST` | superflat | A folder and a zip data pack (a loot table, the piston recipe): recipe grids, a schematic's material list and the prompt's pack list follow them, and drop the zip once it's disabled |
| `CLAUDECHAT_TEMPLATE_TEST=1` | `CLAUDECHAT_TEMPLATE_QUESTION` | normal | Structure template search, tracing and layers; a structure block save showing up in search; after a `/reload`, a template too wide for the parallel pass and one refused by its memory limit |
| `CLAUDECHAT_SCHEMATIC_TEST="<folder>"` | `CLAUDECHAT_SCHEMATIC_QUESTION` | superflat | Copies the folder's schematics in (and deletes them after): material lists and a chest comparison, plus matching, upgrades and limits with the unit tests' fixtures. To make those, run `gradlew test --rerun --tests claudechat.ExportSchematicFixtures` with `CLAUDECHAT_EXPORT_SCHEMATICS="<folder>"` |
| `CLAUDECHAT_WAYPOINT_TEST=1` | `CLAUDECHAT_WAYPOINT_QUESTION` | normal | Needs Xaero's Minimap via `-PextraMods`. Clicks the `[[waypoint:…]]` buttons of an answer, confirms Xaero's screen, checks `waypoints.txt` per dimension |
| `CLAUDECHAT_PERMISSION_TEST=1` | `CLAUDECHAT_LIVE_TEST` | superflat, cheats off | `run_command` and `approve` in each tier, cheats off and on; Allow/Deny clicked through their own buttons (screenshots `claudechat-approval-*`); an Allow clicked after its question ended (by `/claude reset`, or by answering) is too late and runs nothing. Live: Claude creates a file and runs a command, and the test clicks Allow |
| `CLAUDECHAT_LOOK_TEST=1` | `CLAUDECHAT_LOOK_QUESTION` | superflat | `look` over MCP: what it must see and must not (a wall with a window, a sign's back, a chest's name), third person, darkness, Blindness, under water; saves each screenshot as `claudechat-look-*.png` |
| `CLAUDECHAT_WORLD_TEST=1` | `CLAUDECHAT_LIVE_TEST` | normal | Every world tool against the facts it placed (nested, ender chest, Nether and saved-only containers, a named wolf, live and saved spawners and anchors), structure pieces and footprints against generation, biomes against `/locate`, visits through the visited filter and a fresh `WorldIndex` |
| `CLAUDECHAT_RUN_TEST=1` | | superflat | Question lifecycles with the fake: an error, the missing-session retry, the tier a question keeps (and its token's 401 after), the timeout killing the fake and its child, and a disconnect whose late answer never reaches the next world |
| `CLAUDECHAT_LIVE_TEST=1` | | superflat | Real questions (uses the user's Claude subscription): a recipe, and research with progress notes |

The `*_QUESTION` variables and `CLAUDECHAT_LIVE_TEST` ask the real Claude. With either set, the timeout is 300 s, so the run step's timeout scenario waits that long.

- **Test with real mods:** `-PextraMods="<folder of jars>"`. The user's mods are in `%APPDATA%\ModrinthApp\profiles\Fabric 26.3\mods`.
- **Hang on exit:** with those mods, the client hangs after "Xaero hud session finalized". Run the test in the background, wait for the last screenshot, then stop only the java process whose command line matches `clientGameTest`. Other `claude` and `java` processes belong to the user.
- **Logs:** pipe the run to a file in `$env:TEMP` and `Select-String` it. Delete the file afterwards.

## Layout (`src/client/java/claudechat/`)

| File | Role |
| --- | --- |
| `ClaudeChatClient` | Entry point: `/claude` commands, `@claude` chat hook, lifecycle events. Owns the current `Conversation`: disconnect and `/claude reset` end it |
| `ClaudeCli` | Runs `claude -p` for a question: flags, per-question MCP config (owner-only, deleted after), timeout (kills `claude` and its child processes) |
| `SystemPrompt`, `PromptData`, `GameContext` | The system prompt, assembled from fragments their owners keep (`ChatMarkup.PROMPT`, `Waypoints.PROMPT`, `PermissionTier.prompt`, `KnowledgeBase.describeForPrompt`); `{tool:name}` references are checked against the MCP server's tools when it's built. Third-party text (game state, mod, pack and world names) goes in `<game-data>` blocks whose `<` can't be faked. `GameContext` is what's captured when the player asks: the game state text, held/targeted mod namespaces, the dimension |
| `ClaudeRun`, `Conversation` | One question: its captured `Policy`, MCP `Grant`, `Approvals` and process; `cancel()` kills, revokes and denies, and a cancelled run's outcome is never delivered. A conversation holds the session follow-ups resume and the running question; client thread |
| `PermissionTier`, `Policy` | One definition per tier: Claude Code flags, the prompt's permissions paragraph, what `run_command` does with a changing command, confirm text, help and tool-description text. A `Policy` is a question's tier and cheats, captured when asked; cheats only set the permission level |
| `ChatOutput` | The `[Claude] ` prefix and "send if a player exists", for every class that writes to chat |
| `ClaudeStream`, `ClaudeResult` | Reads the stream-json output: progress notes, and the final `result` event parsed once (retry only on a missing session) |
| `ClaudeConfig` | `config/claudechat.properties`; `configVersion` drives migrations |
| `KnowledgeBase` | `config/claudechat/knowledge/`: decompiles Minecraft (always) and mods (on demand); extracts data and lang files; world data packs; data roots in the server's pack order; each source's `Status`; the prompt's file map |
| `BuiltFolder`, `Decompiler`, `ModMentions`, `KnowledgeFiles` | One built folder (layout, marker holding the input key, progress, publish by rename, removing superseded folders, refusing a folder a link leads outside the root); Vineflower in a separate JVM; which mods a question names; contained-path, link-safe walk, copy and delete helpers |
| `DataPackCopy` | Copies files someone else made (a world pack's `data/` from a folder or zip, a mod's data and English names) within a `Budget`: entries counted as the walk meets them, bytes as they're read, depth, free disk space, interruption. Budgets nest: a mod's nested jars share one, a world's packs another (`KnowledgeBase.MAX_WORLD_PACKS` packs at most) |
| `NbtBudget`, `TooLargeException` | Reading imported gzipped NBT (schematics, world and pack templates) within a decompressed-size, memory and nesting budget, stopping on cancellation; the error for any limit on imported files |
| `ChatMarkup`, `ChatFormatter`, `ItemIcons`, `RecipeRenderer` | Markdown subset parsed into lines (pure, unit-tested) → chat components; `[[item]]` icons; `[[recipe:id]]` grids from `GameRecipes`. `ChatFormatter.parse` (any thread: markup and the recipe snapshot) then `build` (client thread: every component) |
| `GameRecipes`, `GameRecipe` | The one recipe model, for grids and material lists: the integrated server's `RecipeManager` while a single-player world is open, else the knowledge base's recipe files read with the game's codec; lookup by id and each item's main recipe; recipes read through the game's recipe-book displays into immutable records |
| `Waypoints` | `[[waypoint:Name\|x y z\|dimension]]` → a button running Xaero's `xaero_waypoint_add:` share command (only while a known-good version of Xaero's Minimap is loaded: `KNOWN_GOOD_VERSIONS`) |
| `StatusLine` + `mixin/ChatComponentAccessor` | The live "Thinking… 3s" line, replaced in place |
| `WorldIndex` | Reads region files (read-only) plus live loaded chunks: containers, entities, players (the host by UUID), per-chunk `InhabitedTime` (with its own record of what the save lacks) and block palettes. Per-world state is an object made on world start and dropped on stop. A `Snapshot` (`Detail.VISITS` or `HOLDERS`) also reads saved chunks |
| `RegionFiles`, `SavedChunk`, `HolderReader`, `VisitsFile` | Region file layout; the parsed chunk view (the only reader of chunk NBT keys); block entity/entity/player NBT → holders; `claudechat-visits.txt` |
| `LiveChunks` | The one rule for which chunks are read live rather than from the save |
| `Visit`, `Dimensions`, `Origin`, `Provenance` | Values the world tools parse arguments into: visit state and the `visited` filter, dimension names and which dimensions a search covers, the search origin (translated x/8 into the Nether), live/saved/predicted |
| `NearestFirst`, `ServerTasks` | Ring walk and top-k for the nearest-first searches; server-thread calls with timeouts and asserted thread rules |
| `StructureFinder` | Multi-result `/locate` with visited filtering, in short slices on the server thread with a timeout. Pieces come from the saved start where there is one, else are built in memory exactly as generation would (`Structure.generate`, including a structure set's weighted pick); never generates chunks. Names pieces (template id, or class for hand-coded pieces, via `mixin/*Accessor`) and samples the biomes they occupy |
| `BlockFinder` | Nearest blocks by id/tag, state properties and block entity text; re-reads only saved chunks whose palette has the block |
| `BiomeFinder` | Multi-result `/locate biome` from the seed, off the server thread |
| `LocationInspector` | Block and biome at a position: live, from the save, or predicted from the seed (base terrain only) |
| `RegistryQuery` | Id, `#tag` or word → registry entries, for blocks, biomes and structures (structures prefer the tag) |
| `StructureTemplates` | Structure `.nbt` templates, read from the game's sources in the game's order (world and pack files within `NbtBudget`, not through the manager's cache); for block search, all of them indexed per reload (small ones in parallel, large ones afterwards one at a time), with structure block saves re-read when their file changes; search ranking; traces template → pools → structures |
| `BlockVolume`, `StructureNbt` | The shared block model (block, air, structure void or absent per position; `counts` decides which blocks a build is made of; at most 2^26 positions) and the structure NBT layout, whose `upgrade` data-fixes every format's blocks, block entities and entities after checking their counts |
| `Schematics`, `LitematicaFormat`, `SpongeFormat`, `Materials` | Schematic files (`.litematic`, `.schem`, `.nbt`) from Litematica's folder: listing reads only each file's description, one matcher for list and inspect; material lists, recipe breakdown and container comparison |
| `McpServer`, `ModTools`, `WorldTools` | MCP on 127.0.0.1 with a random port, for the whole game session: `decompile_mod` and `approve` always, the eleven world tools and `run_command` only while a single-player world is open. Each question gets a `Grant` (its own bearer token → its `Tool.Context`); closing it refuses the token (401) and interrupts its calls in flight. McpServer serves a list of `Tool`s and names none of them; an unknown name is a JSON-RPC `-32602`, any other failure an `isError` result |
| `Tool`, `Param`, `Arguments`, `ToolFormat` | The tool contract: name, description (may depend on the context), a `Requirement` checked when listing *and* on every call, and a handler taking parsed `Arguments` plus a `Tool.Context` (the question's `Policy` and `Approvals`; never read live config for these). Each `Param` writes its schema (default, bounds) and parses its value; a wrong type is an error, never the default. `ToolFormat` is the output vocabulary: ids (`minecraft:` dropped, other namespaces kept, in every tool), block states, positions, `distance_blocks`, items, `searched`, and `more_not_shown` for anything left out |
| `ViewSnapshot` | `look`, in any world (multiplayer too): rays through the screen grid (refined on edges) plus an entity line-of-sight pass, reporting what's visible with image positions, and a screenshot of the same frame as MCP image content. Its Javadoc lists what it hides so it never shows more than the player sees |
| `ViewFrame` + `mixin/GameRendererMixin`, `SightTracer`, `BlockSurfaces` | For `look`: the projection the level was last drawn with (captured in `renderLevel`, so hurt tilt, bobbing and nausea are in it) and the fog distance from the game's render state; walking a ray block by block with the light rule; where a ray meets a block's baked model and whether that texel is a hole, translucent or opaque |
| `GameCommands`, `CommandRules` | `run_command`: parses with the server's dispatcher; `CommandRules` walks the parse's contexts (each `execute` modifier, `run`, alias) and allows a command unasked only if every modifier is a non-writing vanilla one and the final command is vanilla and on its read-only list. The question's `Policy` decides run/ask/refuse for the rest. Runs the approved parse itself (never reparsed), as the host player's own source, or at least GAMEMASTER level with cheats on |
| `Approvals`, `ChatActions` | One question's approvals: chat request with [Allow]/[Deny], the `approve` tool for `--permission-prompt-tool` (an Allow returns exactly the input shown, every field listed). Closed with its question: pending requests are denied, late clicks say too late. Buttons run `/claude action <random id>` so no chat message can carry a working copy |

## Conventions

- Tabs for indentation. Javadoc on classes and on non-obvious methods; comments explain *why*.
- No new runtime dependencies. Vineflower is the only bundled jar. The MCP server uses plain sockets on purpose: some launchers' trimmed Java runtimes lack `jdk.httpserver`.
- Everything is client-side (`"environment": "client"`). World access goes through the integrated server only, so multiplayer gets no world tools.
- **Read only by default.** `Read,Grep,Glob,WebSearch,WebFetch` plus `mcp__minecraft`. Edit, Write, Bash and PowerShell come only from `/claude permissions ask` or `full` (`PermissionTier.cliFlags`), never by default and never from a migration. Anything that changes the world goes through `run_command`'s own rules; don't pre-approve it in Claude Code.
- **Never commit decompiled Minecraft or mod code.** It lives under `run/` and `build/`, which are gitignored.
- **Prompt facts have one owner.** Text Claude reads names tools through `{tool:…}` or `McpServer.claudeName`, and numbers through their constants; `SystemPromptTest` fails if the prompt names a tool the server doesn't have. Anything the mod didn't write goes through `PromptData.block`.
- **Chat components on the client thread.** Atlas sprites, translations, items and `Minecraft.getInstance()` reads belong there; off-thread work produces plain data.

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
- **Windows argument quoting:** this breaks JSON and long strings. The system prompt and MCP config go in files (`--append-system-prompt-file`, `--mcp-config <file>`), and the question goes over stdin. The files are per question (`config/claudechat/runs/<pid>-<n>-*`), so a cancelled question's cleanup can't delete the next one's; the MCP config is owner-only (a single-entry ACL on Windows) and deleted when the question ends.
- **A fake `claude` for tests:** `claudePath` may name a `.cmd` file; Java runs it through cmd.exe and `%*` passes the mod's arguments on unchanged (they have no quotes or `& | ^ %`). The process the mod kills is then cmd.exe, so the fake and its child are its descendants.
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
  - It doesn't mark the chunk unsaved, so the game writes it only when something else in the chunk changes. `WorldIndex` records it on chunk unload (and every 10 s near players) and keeps what the save lacks in `<world>/claudechat-visits.txt` (lines start with the full dimension id). A recorded time counts only while the save shows the chunk fully generated (or it's live), and is pruned once the chunk is gone.
  - "Visited" is ≥ 1 s.
- **Section palettes:** in 26.x a palette entry is a bare string (`"minecraft:spawner"`) when the block is in its default state, otherwise `{id, properties}`, no longer `{Name, Properties}`. `SavedChunk` handles both.
- **26.x paths:** player data is in `players/data`, and dimensions are under `dimensions/<ns>/<path>/{region,entities}`. Use `DimensionType.getStorageFolder`.

**Game commands**
- **Raising permissions:** `CommandSourceStack.withMaximumPermission` doesn't raise anything in 26.3: `LevelBasedPermissionSet.union` keeps the *lower* level. `GameCommands.atLeastGamemaster` compares levels itself and uses `withPermission`.

**Xaero waypoints**
- **No Xaero dependency:** the button's `RunCommand` goes through `sendUnattendedCommand`, which Xaero's mixin cancels at HEAD for `xaero_waypoint_add:` and answers with its Add Waypoint screen. Without Xaero the command would reach the server, so `Waypoints.button` renders nothing then.
- **Destination parsing:** Xaero splits the dimension folder off at the *last* `-`, and its escaping turns `_` into `-` (`-` itself becomes `^min^`, `:` in names `^col^`). Going by the decompiled parser, its own shares (`Internal-the-end`) lose the dimension (untested). We send `Internal-<escaped dim%… folder>-waypoints`, which the game test checks for the Overworld, Nether and End.

**Mods and data packs**
- **Nested jars:** many mods keep their real code in nested jar-in-jar (Vein Miner's outer jar has 5 classes). Use the loader's `getContainedMods()` / `getRootPaths()`, and skip nested jars with Mod Menu's `library` badge.
- **Polymer:** Universal Graves depends on modules *inside* `polymer-bundled`, not on `polymer-bundled` itself.
- **Override order:** `KnowledgeBase.dataRoots()` is highest priority first. With a world open it follows the server's selected packs (`vanilla`, mod packs named by mod id, `file/…` world packs; disabled ones left out); without one it's mods, then Minecraft. Recipes and loot tables override by file; tags don't (the game merges every pack's tag file), so read tags from the game's tag registry, never from the first file found.
- **Recipes:** clients don't receive recipes in 26.x (`Registries.RECIPE` is a reloadable registry, not a synced one), so `GameRecipes` reads files only without a server. Its ingredients parse with `Recipe.DIRECT_CODEC`: `"id"`, `"#tag"` or a list of ids; the old `{"item": …}` object doesn't load in 26.x.

**Imported files (schematics, templates, packs)**
- **The game reads NBT unbounded:** `NbtAccounter.unlimitedHeap()` for templates, so a few KB of gzip can ask for gigabytes. Read imported NBT with `NbtBudget` (256 MiB of decompressed data and of the accounter's memory estimate, depth 512). The accounter's estimate runs about 8× the decompressed size for structure files (Terralith's largest template: 7 MB → 59 MB), and skipped data isn't accounted at all, hence the separate decompressed-byte count.
- **Wrapped parse errors:** the parser wraps a stream's `IOException` in `ReportedNbtException` (cause in `getCause()`), and unwinds the accounter's depth before its exception arrives; `NbtBudget.translated` handles both.
- **Limits are `TooLargeException`:** positions (`BlockVolume.MAX_POSITIONS`), block states, palettes (64), block entities (2^18), entities (2^16), and pack and mod entries and bytes (`DataPackCopy.Limits`, per copy and per world). A failed build deletes its partial folder. Check them before the data fixers run; the template index retries a template that's too large for its parallel pass.
- **Windows junctions aren't symbolic links to Java:** `Files.isSymbolicLink` is false, `isDirectory` true and `isOther` true, and `Files.walk` enters them, deleting through them too. `KnowledgeFiles.isLink` treats an "other" entry whose real path differs as a link (cloud-sync placeholders are "other" too, but real in place). Walk and delete with `KnowledgeFiles`; links inside the knowledge folder aren't followed, so to move it, link the whole folder. Unit tests create junctions with `mklink /J`; symbolic links need Developer Mode, so those tests skip without it.
