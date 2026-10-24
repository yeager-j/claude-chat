package claudechat.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static claudechat.test.Game.check;

/**
 * Builds scenes facing north from the player's position, calls look over MCP (data and screenshot) and checks what it
 * saw and didn't see. The scenes cover what look must hide: what's behind opaque blocks, the back of a sign, a
 * container's name, what's in the dark, past Blindness, or past the fog under water; and the third-person crosshair.
 */
final class LookStep {
	private LookStep() {
	}

	static void run(Game game) throws IOException {
		BlockPos player = game.playerPosition();
		occlusion(game, player);
		game.options.question(Step.LOOK).ifPresent(question -> game.askClaude(question, "claudechat-look-answer").requireAnswer());
		thirdPerson(game);
		darkness(game, player);
		blindness(game);
		underWater(game, player);
	}

	/**
	 * A stone wall with a glass window: the diamond blocks behind the window must be seen through glass; the gold block
	 * and the pig behind the wall must not be seen at all. In front of the wall, a sign whose back text faces away and a
	 * chest whose name isn't shown in the world.
	 */
	private static void occlusion(Game game, BlockPos p) throws IOException {
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		buildScene(game, p, List.of(
			"fill " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 3) + " " + (y + 4) + " " + (z - 4) + " stone",
			"fill " + (x + 1) + " " + (y + 1) + " " + (z - 4) + " " + (x + 2) + " " + (y + 2) + " " + (z - 4) + " glass",
			"fill " + (x + 1) + " " + (y + 1) + " " + (z - 6) + " " + (x + 2) + " " + (y + 2) + " " + (z - 6) + " diamond_block",
			"setblock " + (x - 2) + " " + (y + 2) + " " + (z - 6) + " gold_block",
			"setblock " + (x - 2) + " " + y + " " + (z - 2) + " oak_sign[rotation=0]{front_text:{messages:[\"Hello\",\"Claude\",\"\",\"\"]},"
				+ "back_text:{messages:[\"Behind\",\"\",\"\",\"\"]}}",
			"setblock " + x + " " + y + " " + (z - 3) + " chest[facing=south]{CustomName:\"Secret\"}",
			"setblock " + (x + 2) + " " + y + " " + (z - 3) + " torch",
			"summon pig " + (x - 1 + 0.5) + " " + y + " " + (z - 2 + 0.5) + " {NoAI:1b,CustomName:\"Visible\"}",
			"summon pig " + (x + 0.5) + " " + y + " " + (z - 7 + 0.5) + " {NoAI:1b,CustomName:\"Hidden\"}"));
		game.context.takeScreenshot("claudechat-look");

		JsonObject look = look(game, "claudechat-look-tool");
		JsonArray blocks = look.getAsJsonArray("blocks");
		check(Json.is(look.getAsJsonObject("crosshair"), "block", "stone"), "crosshair isn't on the stone wall: " + look.get("crosshair"));
		JsonObject diamonds = block(blocks, "diamond_block").orElseThrow(() -> new AssertionError("diamond blocks not seen: " + blocks));
		check(Json.string(diamonds, "seen_through").filter(through -> through.startsWith("glass")).isPresent() && diamonds.has("in_image"),
			"diamond blocks not seen through the glass, or not placed in the image: " + diamonds);
		check(block(blocks, "glass").flatMap(glass -> Json.string(glass, "screen_share")).filter(share -> !share.startsWith("none")).isPresent(),
			"the glass has no screen share: " + blocks);
		check(block(blocks, "gold_block").isEmpty(), "saw the gold block behind the wall");
		check(block(blocks, "torch").isPresent(), "missed the torch");
		check(block(blocks, "chest").isPresent() && !look.toString().contains("Secret"), "chest missing, or its hidden name shown: " + blocks);
		List<String> signText = details(look).stream().map(detail -> detail.get("text").getAsString()).toList();
		check(signText.stream().anyMatch(text -> text.contains("Hello / Claude")) && signText.stream().noneMatch(text -> text.contains("Behind")),
			"wrong sign text: " + signText);
		Optional<JsonObject> visible = entity(look, "Visible");
		check(visible.isPresent() && visible.get().has("in_image") && entity(look, "Hidden").isEmpty(), "wrong pigs: " + look.get("entities"));
	}

	/** In third person there's no crosshair: look must say so, and the player's own body is in view. */
	private static void thirdPerson(Game game) throws IOException {
		game.context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		game.context.waitTicks(5);
		JsonObject look = look(game, "claudechat-look-third-person");
		game.context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		game.context.waitTicks(5);

		check(Json.is(look.getAsJsonObject("camera"), "perspective", "third person"), "not third person: " + look.get("camera"));
		check(look.getAsJsonObject("crosshair").has("note"), "no third-person note on the crosshair: " + look.get("crosshair"));
		check(!look.getAsJsonObject("screenshot").has("crosshair"), "third-person screenshot claims a crosshair");
		check(Json.where(look.getAsJsonArray("entities"), "type", "player").size() == 1, "own body not seen in third person");
	}

	/**
	 * A sealed stone box with a tinted glass front, which keeps out sky light: the emerald block and the pig inside are
	 * too dark to see until a glowstone lights them.
	 */
	private static void darkness(Game game, BlockPos p) throws IOException {
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		buildScene(game, p, List.of(
			"fill " + (x - 2) + " " + (y - 1) + " " + (z - 8) + " " + (x + 2) + " " + (y + 3) + " " + (z - 4) + " stone",
			"fill " + (x - 1) + " " + y + " " + (z - 7) + " " + (x + 1) + " " + (y + 2) + " " + (z - 5) + " air",
			"fill " + (x - 1) + " " + y + " " + (z - 4) + " " + (x + 1) + " " + (y + 2) + " " + (z - 4) + " tinted_glass",
			"setblock " + x + " " + (y + 1) + " " + (z - 7) + " emerald_block",
			"summon pig " + (x + 0.5) + " " + y + " " + (z - 5 + 0.5) + " {NoAI:1b,CustomName:\"Lurker\"}"));

		JsonObject dark = look(game, "claudechat-look-dark");
		check(block(dark.getAsJsonArray("blocks"), "tinted_glass").isPresent() && block(dark.getAsJsonArray("blocks"), "emerald_block").isEmpty(),
			"saw into the dark box: " + dark.get("blocks"));
		check(dark.has("too_dark"), "no too_dark share: " + dark);
		check(entity(dark, "Lurker").isEmpty(), "saw the pig in the dark");

		game.command("setblock " + (x - 1) + " " + (y + 2) + " " + (z - 7) + " glowstone");
		game.context.waitTicks(20);
		game.world().getConnection().waitForChunksRender();
		JsonObject lit = look(game, "claudechat-look-lit");
		check(block(lit.getAsJsonArray("blocks"), "emerald_block").flatMap(emerald -> Json.string(emerald, "seen_through"))
			.filter(through -> through.startsWith("tinted_glass")).isPresent(), "emerald block not seen once lit: " + lit.get("blocks"));
		check(entity(lit, "Lurker").isPresent(), "pig not seen once lit: " + lit.get("entities"));
	}

	/** Blindness fogs everything past 5 blocks: the box's front (3.5 blocks away) shows, the emerald block (6.5) doesn't. */
	private static void blindness(Game game) throws IOException {
		game.command("effect give @p minecraft:blindness 60 0 true");
		game.context.waitTicks(10);
		JsonObject look = look(game, "claudechat-look-blind");
		game.command("effect clear @p minecraft:blindness");
		game.context.waitTicks(10);

		JsonArray blocks = look.getAsJsonArray("blocks");
		JsonObject camera = look.getAsJsonObject("camera");
		check(block(blocks, "tinted_glass").isPresent() && block(blocks, "emerald_block").isEmpty(), "Blindness didn't limit the view: " + blocks);
		check(camera.get("ray_range").getAsInt() <= 5 && Json.is(camera, "ray_range_limited_by", "Blindness or Darkness effect"),
			"ray range not limited by Blindness: " + camera);
	}

	/** With the camera under water, the water's fog limits how far rays go. */
	private static void underWater(Game game, BlockPos p) throws IOException {
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		game.command("fill " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " " + (y + 2) + " " + (z + 1) + " water");
		game.context.waitTicks(10);
		JsonObject look = look(game, "claudechat-look-water");

		JsonObject camera = look.getAsJsonObject("camera");
		check(Json.is(camera, "camera_in", "water") && Json.is(camera, "ray_range_limited_by", "water") && camera.get("ray_range").getAsInt() <= 96,
			"water fog didn't limit the view: " + camera);
	}

	/** Clears the space in front of the player, builds a scene, and faces the player north at it. */
	private static void buildScene(Game game, BlockPos p, List<String> scene) {
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		List<String> commands = new ArrayList<>(List.of(
			"fill " + (x - 6) + " " + y + " " + (z - 9) + " " + (x + 6) + " " + (y + 8) + " " + (z + 2) + " air",
			"fill " + (x - 6) + " " + (y - 1) + " " + (z - 9) + " " + (x + 6) + " " + (y - 1) + " " + (z + 2) + " grass_block",
			"kill @e[type=!player]"));
		commands.addAll(scene);
		commands.add("tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 0");
		commands.forEach(game::command);
		game.context.waitTicks(20);
		game.world().getConnection().waitForChunksRender();
	}

	/**
	 * Calls look over MCP and returns its data; its screenshot, the image after the data, is saved next to the test's
	 * own as {@code <name>.png}.
	 */
	private static JsonObject look(Game game, String name) throws IOException {
		ToolResult result = game.call("look", "{}");
		JsonObject look = result.object();
		check(result.images().size() == 1, "no screenshot: " + look.get("screenshot"));
		byte[] png = result.images().getFirst();
		check(png.length > 1000, "screenshot too small: " + png.length + " bytes");
		Files.write(FabricLoader.getInstance().getGameDir().resolve("screenshots/" + name + ".png"), png);
		return look;
	}

	private static Optional<JsonObject> block(JsonArray blocks, String id) {
		return Json.where(blocks, "block", id).stream().findFirst();
	}

	private static Optional<JsonObject> entity(JsonObject look, String name) {
		return Json.where(look.getAsJsonArray("entities"), "name", name).stream().findFirst();
	}

	private static List<JsonObject> details(JsonObject look) {
		return look.has("block_details") ? Json.objects(look.getAsJsonArray("block_details")) : List.of();
	}
}
