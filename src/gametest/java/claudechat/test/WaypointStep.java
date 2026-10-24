package claudechat.test;

import claudechat.Waypoints;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static claudechat.test.Game.LOGGER;
import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * Xaero waypoint buttons (load Xaero's Minimap with -PextraMods): an answer with three [[waypoint:…]] tags shows a
 * button for each; clicking one runs its command the way chat does, the test confirms Xaero's Add Waypoint screen,
 * and the waypoint is saved in the right dimension. The name with a colon checks Xaero's escaping, the Nether's
 * "dim%-1" the '-' handling, and the tag without a dimension gets the one the player asked in.
 */
final class WaypointStep {
	private WaypointStep() {
	}

	/** A tag in the answer and where Xaero must save it: its dimension folder, and the coordinates in its line. */
	private record Expected(String name, String tag, String folder, String coordinates, String screenshot) {
	}

	private static final List<Expected> WAYPOINTS = List.of(
		new Expected("Claude End City", "Claude End City|1240 ~ -3380|the_end", "dim%1", ":1240:~:-3380:", "end"),
		new Expected("Claude: Fortress", "Claude: Fortress|-210 70 400|nether", "dim%-1", ":-210:70:400:", "nether"),
		new Expected("Claude Here", "Claude Here|5 64 -5", "dim%0", ":5:64:-5:", "current"));

	static void run(Game game) {
		check(Waypoints.available(), "CLAUDECHAT_WAYPOINT_TEST needs Xaero's Minimap: pass -PextraMods");
		StringBuilder answer = new StringBuilder("answer Places to go:");
		WAYPOINTS.forEach(waypoint -> answer.append("\n- `").append(waypoint.tag()).append("` [[waypoint:").append(waypoint.tag()).append("]]"));
		game.ask(answer.toString(), "where should I go?").requireAnswer();

		for (Expected waypoint : WAYPOINTS) {
			addWaypoint(game, waypoint);
		}

		game.options.question(Step.WAYPOINT).ifPresent(question -> game.askClaude(question, "claudechat-waypoint-answer").requireAnswer());
	}

	/**
	 * Clicks the waypoint's button without the mouse: runs its command as chat does, presses Confirm in Xaero's screen,
	 * then waits for a new line in Xaero's folder for the dimension. The test client's xaero folder survives between
	 * runs, so lines from earlier runs are counted first.
	 */
	private static void addWaypoint(Game game, Expected waypoint) {
		Path minimap = FabricLoader.getInstance().getGameDir().resolve("xaero/minimap");
		int before = savedWaypoints(minimap, waypoint).size();
		String command = game.context.computeOnClient(mc -> button(mc, waypoint.name()))
			.orElseThrow(() -> new AssertionError("no waypoint button for " + waypoint.name()));
		LOGGER.info("waypoint {} -> {}", waypoint.tag(), command);
		game.click(command);
		game.context.waitFor(mc -> isXaeroAddScreen(mc.gui.screen()), 5 * SECONDS);
		game.context.waitTicks(5);
		game.context.takeScreenshot("claudechat-waypoint-" + waypoint.screenshot());
		game.context.runOnClient(mc -> {
			String confirm = I18n.get("gui.xaero_confirm");
			AbstractWidget button = mc.gui.screen().children().stream()
				.filter(w -> w instanceof AbstractWidget widget && widget.getMessage().getString().equals(confirm))
				.map(AbstractWidget.class::cast)
				.findFirst().orElseThrow(() -> new AssertionError("no Confirm button in Xaero's screen"));
			button.onClick(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)), false);
		});
		game.context.waitFor(mc -> !isXaeroAddScreen(mc.gui.screen()), 5 * SECONDS);
		game.context.waitFor(mc -> savedWaypoints(minimap, waypoint).size() > before, 10 * SECONDS);
		LOGGER.info("waypoint saved in {}: {}", waypoint.folder(), savedWaypoints(minimap, waypoint).getLast());
	}

	/** The command of the button whose hover names the waypoint. */
	private static Optional<String> button(Minecraft mc, String name) {
		return ChatHistory.lines(mc).stream()
			.flatMap(line -> ChatHistory.segments(line.content()).stream())
			.filter(segment -> segment.style().getHoverEvent() instanceof HoverEvent.ShowText(var hover)
				&& hover.getString().startsWith("Add \"" + name + "\""))
			.map(segment -> segment.style().getClickEvent())
			.filter(click -> click instanceof ClickEvent.RunCommand)
			.map(click -> ((ClickEvent.RunCommand) click).command())
			.findFirst();
	}

	private static boolean isXaeroAddScreen(Screen screen) {
		return screen != null && screen.getClass().getName().equals("xaero.common.gui.GuiAddWaypoint");
	}

	private static List<String> savedWaypoints(Path minimap, Expected waypoint) {
		List<String> found = new ArrayList<>();
		if (!Files.isDirectory(minimap)) {
			return found;
		}

		try (Stream<Path> files = Files.walk(minimap)) {
			for (Path file : files.filter(f -> f.getParent().getFileName().toString().equals(waypoint.folder()) && f.toString().endsWith(".txt")).toList()) {
				for (String line : Files.readAllLines(file)) {
					if (line.startsWith("waypoint:") && line.contains(waypoint.coordinates())) {
						found.add(line);
					}
				}
			}
		} catch (IOException e) {
			// Xaero may be writing the file; the caller polls again.
		}

		return found;
	}
}
