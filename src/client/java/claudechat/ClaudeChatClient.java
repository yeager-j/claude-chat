package claudechat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
		[[recipe:minecraft:torch]]
		""";

	private static final AtomicBoolean busy = new AtomicBoolean(false);
	private static ClaudeConfig config;
	private static ClaudeCli cli;
	private static KnowledgeBase knowledge;
	private static ChatFormatter formatter;
	private static WorldIndex worldIndex;
	private static McpServer mcp;
	private static WorldTools worldTools;
	private static final StatusLine status = new StatusLine();

	/** For the client game test: is a question still being answered? */
	public static boolean isBusy() {
		return busy.get();
	}

	/** For the client game test: has the knowledge base finished all its background builds? */
	public static boolean isKnowledgeReady() {
		return !knowledge.dataRoots().isEmpty() && !knowledge.isBusy();
	}

	/** For the client game test. */
	public static KnowledgeBase knowledge() {
		return knowledge;
	}

	/** For the client game test: call the world tools directly, without Claude. */
	public static WorldTools worldTools() {
		return worldTools;
	}

	@Override
	public void onInitializeClient() {
		config = ClaudeConfig.load();
		knowledge = new KnowledgeBase(config);
		worldIndex = new WorldIndex();
		worldTools = new WorldTools(worldIndex);
		mcp = new McpServer(worldTools);
		cli = new ClaudeCli(config, knowledge, mcp);
		formatter = new ChatFormatter(new RecipeRenderer(knowledge));

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
					info("Model: " + config.model + ". Change it with /claude model <" + String.join("|", ClaudeConfig.MODEL_SUGGESTIONS) + ">.");
					return 1;
				})
				.then(ClientCommands.argument("model", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ClaudeConfig.MODEL_SUGGESTIONS, builder))
					.executes(ctx -> {
						config.model = StringArgumentType.getString(ctx, "model");
						config.save();
						info("Model set to " + config.model + ".");
						return 1;
					})))
			.then(ClientCommands.literal("effort")
				.executes(ctx -> {
					info("Effort: " + effortLabel() + ". Change it with /claude effort <" + String.join("|", ClaudeConfig.EFFORT_LEVELS) + "|default>.");
					return 1;
				})
				.then(ClientCommands.argument("effort", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
						Stream.concat(ClaudeConfig.EFFORT_LEVELS.stream(), Stream.of("default")), builder))
					.executes(ctx -> {
						String effort = StringArgumentType.getString(ctx, "effort").toLowerCase();
						if (effort.equals("default")) {
							effort = "";
						} else if (!ClaudeConfig.EFFORT_LEVELS.contains(effort)) {
							ctx.getSource().sendError(Component.literal("Effort must be one of: " + String.join(", ", ClaudeConfig.EFFORT_LEVELS) + ", default"));
							return 0;
						}
						config.effort = effort;
						config.save();
						info("Effort set to " + effortLabel() + ".");
						return 1;
					})))
			.then(ClientCommands.literal("preview").executes(ctx -> {
				showAnswer(formatter.format(PREVIEW), PREVIEW);
				return 1;
			}))
			.then(ClientCommands.literal("reset").executes(ctx -> {
				cli.resetSession();
				info("Started a new conversation.");
				return 1;
			}))
			.then(ClientCommands.literal("knowledge")
				.executes(ctx -> {
					showKnowledgeStatus();
					return 1;
				})
				.then(ClientCommands.literal("rebuild").executes(ctx -> {
					knowledge.rebuildAll(ClaudeChatClient::notifyFromBackground);
					info("Rebuilding the knowledge base in the background.");
					return 1;
				}))
				.then(ClientCommands.literal("include")
					.then(ClientCommands.argument("mod", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(modIds(KnowledgeBase.Tier.DATA), builder))
						.executes(ctx -> {
							withMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), mod -> {
								knowledge.include(mod, ClaudeChatClient::notifyFromBackground);
								info("Decompiling " + mod.label() + " from now on" + (knowledge.hasCode(mod) ? "." : "; starting now."));
							});
							return 1;
						})))
				.then(ClientCommands.literal("exclude")
					.then(ClientCommands.argument("mod", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(modIds(KnowledgeBase.Tier.CODE), builder))
						.executes(ctx -> {
							withMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), mod -> {
								knowledge.exclude(mod);
								info(mod.label() + " won't be decompiled (its recipes and other data stay searchable).");
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

		// Each world (or server) gets a fresh conversation.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			cli.resetSession();
			status.reset();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> status.tick());

		// Single-player worlds: read the world's enabled data packs when it opens and after /reload.
		// (On a multiplayer server its data packs live on the server and can't be read.)
		ServerLifecycleEvents.SERVER_STARTED.register(ClaudeChatClient::updateWorldPacks);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			updateWorldPacks(server);
			worldTools.prepareTemplates(server);
		});
		// ...and serve the world tools (containers, entities, player, structures) to Claude over MCP.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			worldIndex.start(server);
			worldTools.prepareTemplates(server);
			mcp.start();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			knowledge.clearWorldPacks();
			worldTools.clearTemplates();
			mcp.stop();
			worldIndex.stop();
		});
	}

	private static void updateWorldPacks(MinecraftServer server) {
		knowledge.setWorldPacks(server.getWorldData().getLevelName(), server.getWorldPath(LevelResource.DATAPACK_DIR),
			List.copyOf(server.getPackRepository().getSelectedIds()));
	}

	private static void ask(String question) {
		Minecraft mc = Minecraft.getInstance();
		if (!busy.compareAndSet(false, true)) {
			info("Still thinking about your last question...");
			return;
		}

		// Read game state now, on the client thread; the CLI call runs in the background.
		String gameContext = GameContext.describe(mc);
		String mentionText = question + " " + GameContext.modNamespaces(mc);
		send(Component.literal("> " + question).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		// Immediate feedback; Claude's first progress note can take several seconds.
		status.start();

		Consumer<String> onProgress = note -> {
			Component formatted = formatter.formatProgress(note);
			mc.execute(() -> status.setNote(formatted));
		};

		cli.ask(question, gameContext, mentionText, onProgress).whenComplete((answer, error) -> {
			// Format off the render thread: recipes are read from disk.
			List<Component> lines = null;
			Throwable failure = error;
			if (failure == null) {
				try {
					lines = formatter.format(answer);
				} catch (RuntimeException e) {
					failure = e;
				}
			}
			List<Component> finalLines = lines;
			Throwable finalFailure = failure;
			mc.execute(() -> {
				busy.set(false);
				status.remove();
				if (finalFailure != null) {
					Throwable cause = finalFailure.getCause() != null ? finalFailure.getCause() : finalFailure;
					LOGGER.warn("Claude request failed", cause);
					send(prefix().append(Component.literal(String.valueOf(cause.getMessage())).withStyle(ChatFormatting.RED)));
				} else {
					showAnswer(finalLines, answer);
				}
			});
		});
	}

	private static void showAnswer(List<Component> lines, String raw) {
		String plain = String.join("\n", lines.stream().map(Component::getString).toList());
		// A recipe grid needs every row to start at the same x, so it can't share a line with the [Claude] prefix.
		boolean prefixOwnLine = ChatFormatter.startsWithRecipe(raw);
		if (prefixOwnLine) {
			send(prefix());
		}
		for (int i = 0; i < lines.size(); i++) {
			// Siblings, not children of the prefix, so answer text doesn't inherit its gold color.
			MutableComponent line = Component.empty();
			if (i == 0 && !prefixOwnLine) {
				line.append(prefix());
			}
			line.append(lines.get(i));
			if (i == lines.size() - 1) {
				line.append(Component.literal(" [copy]").withStyle(style -> style
					.withColor(ChatFormatting.DARK_GRAY)
					.withClickEvent(new ClickEvent.CopyToClipboard(plain))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy answer to clipboard")))));
			}
			send(line);
		}
	}

	private static void showStatus() {
		info("Ask in chat with " + config.chatPrefix + " <question>. Model: " + config.model + ", effort: " + effortLabel() + ".");
		info("/claude model <name>, /claude effort <level>, /claude reset (new conversation), /claude knowledge, /claude preview.");
	}

	private static String effortLabel() {
		return config.effort.isEmpty() ? "default" : config.effort;
	}

	/** Two groups: decompiled code, and data only (with why). Hover a mod for its id and state. */
	private static void showKnowledgeStatus() {
		if (knowledge.sources().isEmpty()) {
			info("No game or mod jars found.");
			return;
		}
		info("Knowledge base: decompiled code for these (green = ready):");
		send(modList(knowledge.sources().stream().filter(s -> knowledge.hasCode(s) || knowledge.tier(s) == KnowledgeBase.Tier.CODE).toList()));
		List<KnowledgeBase.Source> dataOnly = knowledge.sources().stream()
			.filter(s -> !knowledge.hasCode(s) && knowledge.tier(s) == KnowledgeBase.Tier.DATA).toList();
		if (!dataOnly.isEmpty()) {
			info("Data only; code is decompiled when a question needs it:");
			send(modList(dataOnly));
		}
		info("Change with /claude knowledge include|exclude <mod>.");
		if (!config.buildKnowledge) {
			info("Automatic building is off (buildKnowledge=false). /claude knowledge rebuild builds it now.");
		}
	}

	private static Component modList(List<KnowledgeBase.Source> sources) {
		MutableComponent line = Component.literal("  ");
		for (int i = 0; i < sources.size(); i++) {
			KnowledgeBase.Source source = sources.get(i);
			KnowledgeBase.State state = knowledge.state(source);
			ChatFormatting color = switch (state) {
				case READY, DATA_READY -> ChatFormatting.GREEN;
				case FAILED -> ChatFormatting.RED;
				default -> ChatFormatting.YELLOW;
			};
			String detail = source.id() + ": " + state.name().toLowerCase().replace('_', ' ') + " (" + knowledge.reason(source) + ")";
			line.append(Component.literal(source.label()).withStyle(style -> style.withColor(color)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(detail)))));
			if (i < sources.size() - 1) {
				line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return line;
	}

	/** Tab completion: include offers mods not decompiled yet; exclude offers decompiled or included ones. */
	private static List<String> modIds(KnowledgeBase.Tier tier) {
		return knowledge.sources().stream()
			.filter(s -> !s.id().equals("minecraft") && !s.codeContainers().isEmpty())
			.filter(s -> tier == KnowledgeBase.Tier.DATA
				? knowledge.canDecompile(s) || knowledge.reason(s).equals("excluded by you")
				: knowledge.hasCode(s) || knowledge.tier(s) == KnowledgeBase.Tier.CODE)
			.map(KnowledgeBase.Source::id).toList();
	}

	private static void withMod(FabricClientCommandSource source, String id, Consumer<KnowledgeBase.Source> action) {
		knowledge.source(id).filter(s -> !s.id().equals("minecraft")).ifPresentOrElse(mod -> {
			if (mod.codeContainers().isEmpty()) {
				source.sendError(Component.literal(mod.label() + " has no code to decompile."));
			} else {
				action.accept(mod);
			}
		}, () -> source.sendError(Component.literal("No installed mod with id " + id + ".")));
	}

	/** Progress messages from the knowledge builder: show them in chat if the player is in a world. */
	private static void notifyFromBackground(String message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> info(message));
	}

	private static void info(String message) {
		send(prefix().append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	private static MutableComponent prefix() {
		return Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD);
	}

	private static void send(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(component);
		}
	}
}
