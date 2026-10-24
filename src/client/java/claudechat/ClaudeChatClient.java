package claudechat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClaudeChatClient implements ClientModInitializer {
	public static final String MOD_ID = "claudechat";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Sample answer for /claude preview: shows every kind of formatting without calling Claude. */
	private static final String PREVIEW = """
		[[recipe:minecraft:crafter]]
		# Crafter basics
		A [[crafter]] crafts automatically when it gets a **redstone pulse**. Press {{key.use}} to open it.
		- Click slots to *disable* them; ~~hoppers~~ fill only enabled slots
		- Smelt [[raw_iron]] for ingots:
		[[recipe:minecraft:iron_ingot_from_smelting_raw_iron]]
		1. Try `/give @s minecraft:crafter` or see the [wiki page](https://minecraft.wiki/w/Crafter)
		2. Nearest end city: `1240 ~ -3380` [[waypoint:End City|1240 ~ -3380|the_end]]
		[[recipe:minecraft:torch]]
		""";

	private static ClaudeConfig config;
	private static ClaudeCli cli;
	private static KnowledgeBase knowledge;
	private static ChatFormatter formatter;
	private static WorldIndex worldIndex;
	private static McpServer mcp;
	private static WorldTools worldTools;
	private static final StatusLine status = new StatusLine();
	/** This world's (or server's) questions; replaced when the player leaves or resets. Client thread. */
	private static volatile Conversation conversation = new Conversation();

	/** For the client game test: is a question still being answered? */
	public static boolean isBusy() {
		return conversation.busy();
	}

	/** For the client game test: has the knowledge base finished all its background builds? */
	public static boolean isKnowledgeReady() {
		return !knowledge.dataRoots().isEmpty() && !knowledge.isBusy();
	}

	/** For the client game test. */
	public static KnowledgeBase knowledge() {
		return knowledge;
	}

	/** For the client game test: settings it can't reach through commands, such as claudePath and the timeout. */
	public static ClaudeConfig config() {
		return config;
	}

	/** For the client game test: the MCP server, to call tools with a policy of its own. */
	public static McpServer mcp() {
		return mcp;
	}

	@Override
	public void onInitializeClient() {
		config = ClaudeConfig.load();
		knowledge = new KnowledgeBase(config);
		worldIndex = new WorldIndex();
		worldTools = new WorldTools(worldIndex, knowledge);
		mcp = new McpServer(tools(knowledge, worldIndex, worldTools), worldTools::available);
		mcp.start();
		cli = new ClaudeCli(config, knowledge, mcp);
		formatter = new ChatFormatter(knowledge.recipes());

		// Start decompiling right away so it's usually done by the time a world has loaded.
		if (config.buildKnowledge) {
			knowledge.buildMissing(ClaudeChatClient::notifyFromBackground);
		}

		// /claude is for settings; questions go through "@claude ..." in chat.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal("claude")
			.executes(ctx -> {
				showStatus();
				return 1;
			})
			.then(ClientCommands.literal("model")
				.executes(ctx -> {
					ChatOutput.info("Model: " + config.model + ". Change it with /claude model <" + String.join("|", ClaudeConfig.MODEL_SUGGESTIONS) + ">.");
					return 1;
				})
				.then(ClientCommands.argument("model", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ClaudeConfig.MODEL_SUGGESTIONS, builder))
					.executes(ctx -> {
						config.model = StringArgumentType.getString(ctx, "model");
						config.save();
						ChatOutput.info("Model set to " + config.model + ".");
						return 1;
					})))
			.then(ClientCommands.literal("effort")
				.executes(ctx -> {
					ChatOutput.info("Effort: " + config.effort.id + ". Change it with /claude effort <" + String.join("|", effortIds()) + ">.");
					return 1;
				})
				.then(ClientCommands.argument("effort", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(effortIds(), builder))
					.executes(ctx -> {
						Optional<ClaudeConfig.Effort> effort = ClaudeConfig.Effort.parse(StringArgumentType.getString(ctx, "effort"));
						if (effort.isEmpty()) {
							ctx.getSource().sendError(Component.literal("Effort must be one of: " + String.join(", ", effortIds())));
							return 0;
						}
						config.effort = effort.get();
						config.save();
						ChatOutput.info("Effort set to " + config.effort.id + ".");
						return 1;
					})))
			.then(ClientCommands.literal("preview").executes(ctx -> {
				showAnswer(formatter.format(PREVIEW, GameContext.dimension(Minecraft.getInstance())));
				return 1;
			}))
			.then(ClientCommands.literal("reset").executes(ctx -> {
				boolean stopped = conversation.busy();
				endConversation();
				status.stop();
				ChatOutput.info(stopped ? "Stopped the question and started a new conversation." : "Started a new conversation.");
				return 1;
			}))
			.then(ClientCommands.literal("permissions")
				.executes(ctx -> {
					showPermissions();
					return 1;
				})
				.then(ClientCommands.argument("mode", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PermissionTier.ids(), builder))
					.executes(ctx -> {
						Optional<PermissionTier> mode = PermissionTier.parse(StringArgumentType.getString(ctx, "mode"));
						if (mode.isEmpty()) {
							ctx.getSource().sendError(Component.literal("Permissions must be one of: " + String.join(", ", PermissionTier.ids())));
							return 0;
						}
						setPermissions(mode.get());
						return 1;
					})))
			.then(ClientCommands.literal("cheats")
				.executes(ctx -> {
					showPermissions();
					return 1;
				})
				.then(ClientCommands.literal("on").executes(ctx -> {
					setCheats(true);
					return 1;
				}))
				.then(ClientCommands.literal("off").executes(ctx -> {
					setCheats(false);
					return 1;
				})))
			// Target of ChatActions buttons; not meant to be typed.
			.then(ClientCommands.literal("action")
				.then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
					if (!ChatActions.run(StringArgumentType.getString(ctx, "id"))) {
						ChatOutput.info("That button has expired.");
					}
					return 1;
				})))
			.then(ClientCommands.literal("knowledge")
				.executes(ctx -> {
					showKnowledgeStatus();
					return 1;
				})
				.then(ClientCommands.literal("rebuild").executes(ctx -> {
					knowledge.rebuildAll(ClaudeChatClient::notifyFromBackground);
					ChatOutput.info("Rebuilding the knowledge base in the background.");
					return 1;
				}))
				.then(ClientCommands.literal("include")
					.then(ClientCommands.argument("mod", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(modIds(KnowledgeBase.Status::includable), builder))
						.executes(ctx -> {
							withMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), mod -> {
								knowledge.include(mod, ClaudeChatClient::notifyFromBackground);
								ChatOutput.info("Decompiling " + mod.label() + " from now on" + (knowledge.status(mod).hasCode() ? "." : "; starting now."));
							});
							return 1;
						})))
				.then(ClientCommands.literal("exclude")
					.then(ClientCommands.argument("mod", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(modIds(KnowledgeBase.Status::excludable), builder))
						.executes(ctx -> {
							withMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), mod -> {
								knowledge.exclude(mod);
								ChatOutput.info(mod.label() + " won't be decompiled (its recipes and other data stay searchable).");
							});
							return 1;
						}))))));

		// "@claude how do I ..." in normal chat: answer it and never send it to the server.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			String prefix = config.chatPrefix;
			if (!message.regionMatches(true, 0, prefix, 0, prefix.length())) {
				return true;
			}
			String question = message.substring(prefix.length()).trim();
			if (question.isEmpty()) {
				showStatus();
			} else {
				ask(question);
			}
			return false;
		});

		// Each world (or server) gets a fresh conversation, and nothing of the last one keeps running.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			endConversation();
			status.reset();
			ChatActions.clear();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> status.tick());

		// Single-player worlds: read the world's enabled data packs when it opens and after /reload.
		// (On a multiplayer server its data packs live on the server and can't be read.)
		ServerLifecycleEvents.SERVER_STARTED.register(ClaudeChatClient::updateWorldPacks);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			updateWorldPacks(server);
			worldTools.prepare(server);
		});
		// ...and offer the world tools (containers, entities, player, structures) to Claude over MCP.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			worldIndex.start(server);
			worldTools.prepare(server);
		});
		// Time spent near chunks, which the game doesn't reliably save (see WorldIndex).
		ServerChunkEvents.CHUNK_UNLOAD.register(worldIndex::chunkUnloaded);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 200 == 0) {
				worldIndex.recordLoadedChunks(server);
			}
		});
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> worldIndex.saveVisits(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(worldIndex::saveVisits);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			knowledge.clearWorldPacks();
			knowledge.recipes().clearServer();
			worldTools.stopBackgroundWork();
			worldIndex.stop();
		});
	}

	/**
	 * Every tool the MCP server serves, for the whole session; each says when it's available (look in any world, the
	 * world tools and run_command only in a single-player one).
	 */
	static List<Tool> tools(KnowledgeBase knowledge, WorldIndex worldIndex, WorldTools worldTools) {
		return Stream.of(new ModTools(knowledge).tools(), List.of(Approvals.tool(), new ViewSnapshot().tool()),
			worldTools.tools(), List.of(new GameCommands(worldIndex).tool())).flatMap(List::stream).toList();
	}

	private static void updateWorldPacks(MinecraftServer server) {
		knowledge.setWorldPacks(server.getWorldData().getLevelName(), server.getWorldPath(LevelResource.DATAPACK_DIR),
			List.copyOf(server.getPackRepository().getSelectedIds()));
		knowledge.recipes().useServer(server.getRecipeManager());
	}

	/** Cancels the running question, if any, and starts a new conversation. Client thread. */
	private static void endConversation() {
		Conversation ended = conversation;
		conversation = new Conversation();
		ended.end();
	}

	private static void ask(String question) {
		Minecraft mc = Minecraft.getInstance();
		Conversation asked = conversation;
		if (asked.busy()) {
			ChatOutput.info("Still thinking about your last question...");
			return;
		}

		// Read game state and settings now, on the client thread; the CLI call runs in the background. Waypoints in the
		// answer default to the dimension the player asked in.
		ClaudeRun.Request request = new ClaudeRun.Request(question, GameContext.describe(mc), GameContext.modNamespaces(mc),
			asked.session(), Policy.of(config));
		Identifier dimension = GameContext.dimension(mc);
		ChatOutput.send(Component.literal("> " + question).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		// Immediate feedback; Claude's first progress note can take several seconds.
		StatusLine.Handle line = status.start();
		Consumer<String> showProgress = note -> mc.execute(() -> line.setNote(ChatFormatter.formatProgress(note, dimension)));
		Consumer<Component> showApproval = note -> mc.execute(() -> line.setNote(note));

		ClaudeRun run = cli.start(request, showProgress, showApproval);
		asked.started(run);
		run.outcome().whenComplete((result, error) -> {
			if (run.isCancelled()) {
				return;
			}

			// ClaudeCli always completes with an outcome; anything else is a bug, which mustn't leave the player stuck.
			ClaudeRun.Outcome outcome = error == null ? result : ClaudeRun.Outcome.failure(error.toString());
			// Parsed here, off the client thread, since the recipes may be read from disk; built into chat lines there.
			Optional<ChatFormatter.Parsed> parsed = outcome.failed() ? Optional.empty() : parse(outcome.text());
			mc.execute(() -> {
				// A question cancelled meanwhile belongs to a conversation that has ended: its answer is never shown.
				if (!asked.finished(run, outcome.sessionId())) {
					return;
				}

				line.remove();
				Optional<ChatFormatter.Answer> answer = parsed.flatMap(p -> build(p, dimension));
				answer.ifPresentOrElse(ClaudeChatClient::showAnswer, () -> showFailure(outcome.failed()
					? outcome.text() : "Couldn't show Claude's answer; the log has details."));
			});
		});
	}

	/** Any thread. Empty if the answer can't be read; logged, since the player only sees that it failed. */
	private static Optional<ChatFormatter.Parsed> parse(String answer) {
		try {
			return Optional.of(formatter.parse(answer));
		} catch (RuntimeException e) {
			LOGGER.warn("Couldn't read Claude's answer", e);
			return Optional.empty();
		}
	}

	/** Client thread. Empty if the answer can't be shown; logged, since the player only sees that it failed. */
	private static Optional<ChatFormatter.Answer> build(ChatFormatter.Parsed parsed, Identifier dimension) {
		try {
			return Optional.of(ChatFormatter.build(parsed, dimension));
		} catch (RuntimeException e) {
			LOGGER.warn("Couldn't format Claude's answer", e);
			return Optional.empty();
		}
	}

	private static void showFailure(String message) {
		ChatOutput.send(ChatOutput.line(Component.literal(message).withStyle(ChatFormatting.RED)));
	}

	private static void showAnswer(ChatFormatter.Answer answer) {
		List<Component> lines = answer.lines();
		String plain = String.join("\n", lines.stream().map(Component::getString).toList());
		// A recipe grid needs every row to start at the same x, so it can't share a line with the [Claude] prefix.
		boolean prefixOwnLine = answer.startsWithGrid();
		if (prefixOwnLine) {
			ChatOutput.send(ChatOutput.prefix());
		}
		for (int i = 0; i < lines.size(); i++) {
			// Siblings, not children of the prefix, so answer text doesn't inherit its gold color.
			MutableComponent line = i == 0 && !prefixOwnLine ? ChatOutput.line(lines.get(i)) : Component.empty().append(lines.get(i));
			if (i == lines.size() - 1) {
				line.append(Component.literal(" [copy]").withStyle(style -> style
					.withColor(ChatFormatting.DARK_GRAY)
					.withClickEvent(new ClickEvent.CopyToClipboard(plain))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy answer to clipboard")))));
			}
			ChatOutput.send(line);
		}
	}

	private static void showStatus() {
		ChatOutput.info("Ask in chat with " + config.chatPrefix + " <question>. Model: " + config.model + ", effort: " + config.effort.id + ".");
		ChatOutput.info("/claude model <name>, /claude effort <level>, /claude permissions, /claude reset (new conversation), /claude knowledge, /claude preview.");
	}

	private static void showPermissions() {
		ChatOutput.info("Permissions: " + config.permissions.label + ". Cheats: " + (config.cheats ? "on" : "off") + ".");
		String tiers = Stream.of(PermissionTier.values()).map(tier -> tier.id + " (" + tier.summary + ")")
			.collect(Collectors.joining(", "));
		ChatOutput.info("/claude permissions <" + String.join("|", PermissionTier.ids()) + ">: " + tiers + "."
			+ " /claude cheats <on|off>: whether Claude's game commands may cheat where you can't."
			+ " Changes apply from your next question.");
	}

	/** A tier that needs confirming is set only by a click on a confirm button, so neither a typo nor a link in chat can. */
	private static void setPermissions(PermissionTier mode) {
		if (mode == config.permissions) {
			ChatOutput.info("Permissions are already " + mode.label + ".");
			return;
		}
		Runnable apply = () -> {
			config.permissions = mode;
			config.save();
			ChatOutput.info("Permissions set to " + mode.label + ".");
		};
		mode.confirmation().ifPresentOrElse(warning -> ChatOutput.send(ChatOutput.line(Component.empty()
			.append(Component.literal(warning).withStyle(ChatFormatting.YELLOW))
			.append(ChatActions.button("Turn on " + mode.label, ChatFormatting.RED,
				Component.literal("Claude may " + mode.summary), apply)))), apply);
	}

	private static void setCheats(boolean on) {
		if (on == config.cheats) {
			ChatOutput.info("Cheats are already " + (on ? "on" : "off") + ".");
			return;
		}
		Runnable apply = () -> {
			config.cheats = on;
			config.save();
			ChatOutput.info("Cheats " + (on ? "on: Claude's game commands run at command block level at least."
				: "off: Claude's game commands run with your permissions."));
		};
		if (!on) {
			apply.run();
			return;
		}
		ChatOutput.send(ChatOutput.line(Component.empty()
			.append(Component.literal("With cheats on, Claude can run commands like /give, /gamemode and /fill even in"
				+ " worlds where you can't. The world list will still show cheats off. ").withStyle(ChatFormatting.YELLOW))
			.append(ChatActions.button("Turn on cheats", ChatFormatting.RED,
				Component.literal("Claude's game commands run at command block level"), apply))));
	}

	private static List<String> effortIds() {
		return Stream.of(ClaudeConfig.Effort.values()).map(effort -> effort.id).toList();
	}

	/** Two groups: decompiled code, and data only (with why). Hover a mod for its id and state. */
	private static void showKnowledgeStatus() {
		if (knowledge.sources().isEmpty()) {
			ChatOutput.info("No game or mod jars found.");
			return;
		}
		Map<Boolean, List<KnowledgeBase.Source>> byCode = knowledge.sources().stream()
			.collect(Collectors.partitioningBy(s -> wantsOrHasCode(knowledge.status(s))));
		ChatOutput.info("Knowledge base: decompiled code for these (green = ready):");
		ChatOutput.send(modList(byCode.get(true)));
		if (!byCode.get(false).isEmpty()) {
			ChatOutput.info("Data only; code is decompiled when a question needs it:");
			ChatOutput.send(modList(byCode.get(false)));
		}
		ChatOutput.info("Change with /claude knowledge include|exclude <mod>.");
		if (!config.buildKnowledge) {
			ChatOutput.info("Building at startup is off (buildKnowledge=false). /claude knowledge rebuild builds it now.");
		}
	}

	/** Listed with the decompiled code: code is there, or is wanted up front and still being built. */
	private static boolean wantsOrHasCode(KnowledgeBase.Status status) {
		return status.hasCode() || status.tier() == KnowledgeBase.Tier.CODE;
	}

	private static Component modList(List<KnowledgeBase.Source> sources) {
		MutableComponent line = Component.literal("  ");
		for (int i = 0; i < sources.size(); i++) {
			KnowledgeBase.Source source = sources.get(i);
			KnowledgeBase.Status status = knowledge.status(source);
			ChatFormatting color = switch (status.state()) {
				case READY, DATA_READY -> ChatFormatting.GREEN;
				case FAILED -> ChatFormatting.RED;
				default -> ChatFormatting.YELLOW;
			};
			line.append(Component.literal(source.label()).withStyle(style -> style.withColor(color)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(statusDetail(source, status))))));
			if (i < sources.size() - 1) {
				line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return line;
	}

	/** "create: data ready (decompiled when needed)", and why a build failed. */
	private static String statusDetail(KnowledgeBase.Source source, KnowledgeBase.Status status) {
		String detail = source.id() + ": " + status.state().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " (" + status.reason() + ")";
		return status.failure() != null ? detail + "\n" + status.failure() : detail;
	}

	/** Tab completion: the mods {@code /claude knowledge include} (or exclude) would change. */
	private static List<String> modIds(Predicate<KnowledgeBase.Status> changes) {
		return knowledge.sources().stream()
			.filter(s -> changes.test(knowledge.status(s)))
			.map(KnowledgeBase.Source::id).toList();
	}

	/** Runs {@code action} for the mod with this id, if the player can include or exclude it; otherwise says why not. */
	private static void withMod(FabricClientCommandSource source, String id, Consumer<KnowledgeBase.Source> action) {
		Optional<KnowledgeBase.Source> mod = knowledge.source(id).filter(s -> !s.isGame());
		if (mod.isEmpty()) {
			source.sendError(Component.literal("No installed mod with id " + id + "."));
			return;
		}

		KnowledgeBase.Status status = knowledge.status(mod.get());
		if (!status.includable() && !status.excludable()) {
			source.sendError(Component.literal(mod.get().label() + " has no code to decompile."));
			return;
		}

		action.accept(mod.get());
	}

	/** Progress messages from the knowledge builder: show them in chat if the player is in a world. */
	private static void notifyFromBackground(String message) {
		Minecraft.getInstance().execute(() -> ChatOutput.info(message));
	}
}
