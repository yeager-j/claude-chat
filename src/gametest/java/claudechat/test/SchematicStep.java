package claudechat.test;

import claudechat.Schematics;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static claudechat.test.Game.check;

/**
 * Schematics: copies the files in CLAUDECHAT_SCHEMATIC_TEST (a folder) into the schematics folder, reads their
 * material lists, and checks chests holding all but one redstone dust against the first. With the unit tests'
 * fixtures (see ExportSchematicFixtures) it also checks matching, upgrades, unreadable files and size limits. The
 * copied files are deleted afterwards.
 */
final class SchematicStep {
	private static final String FIXTURE_FARM = "farms/Witch Farm.litematic";

	private SchematicStep() {
	}

	static void run(Game game) throws IOException {
		Path source = game.options.schematicFolder().orElseThrow();
		Path folder = Schematics.folder().dir();
		List<Path> copied = copy(source, folder);
		try {
			List<JsonObject> files = Json.objects(game.tool("list_schematics", "{}").getAsJsonArray("schematics"));
			List<String> copiedNames = copied.stream().map(file -> folder.relativize(file).toString().replace('\\', '/')).toList();
			check(files.stream().map(file -> file.get("file").getAsString()).toList().containsAll(copiedNames),
				"not every copied schematic is listed: " + files);

			if (copiedNames.contains(FIXTURE_FARM)) {
				checkFixtures(game, files);
			}

			// One of the copies, not whatever else the folder holds.
			String first = files.stream().filter(file -> copiedNames.contains(file.get("file").getAsString()) && !file.has("error"))
				.map(file -> file.get("file").getAsString()).findFirst().orElseThrow(() -> new AssertionError("no readable schematic copied"));
			compareWithChests(game, first);
			game.options.question(Step.SCHEMATIC).ifPresent(question -> game.askClaude(question, "claudechat-schematic-answer").requireAnswer());
		} finally {
			for (Path file : copied) {
				Files.deleteIfExists(file);
			}
		}
	}

	/** The unit tests' fixtures: queries with a folder, ambiguity, regions, a 1.12 spawner, a broken file, a bomb. */
	private static void checkFixtures(Game game, List<JsonObject> files) {
		check(game.tool("list_schematics", "{\"query\": \"farms/witch\"}").getAsJsonArray("schematics").size() == 2,
			"a query with / should list both farms");
		check(game.toolError("inspect_schematic", "{\"schematic\": \"witch\"}").contains("several"), "\"witch\" should be ambiguous");
		JsonObject farm = game.tool("inspect_schematic", "{\"schematic\": \"farms\\\\witch farm\"}");
		check(farm.getAsJsonArray("regions").size() == 2, "the two-region schematic lost a region: " + farm);
		JsonObject spawner = game.tool("inspect_schematic", "{\"schematic\": \"Old Spawner\"}");
		check(Json.objects(spawner.getAsJsonArray("block_entities")).stream().anyMatch(entity -> Json.is(entity, "spawns", "skeleton")),
			"the 1.12 spawner wasn't upgraded: " + spawner);
		check(files.stream().anyMatch(file -> Json.is(file, "file", "old/Broken.litematic")
			&& Json.string(file, "error").filter(error -> error.startsWith("unreadable")).isPresent()), "the broken file isn't marked unreadable");
		check(files.stream().anyMatch(file -> file.get("file").getAsString().endsWith("Bomb.schem")
			&& Json.string(file, "error").filter(error -> error.contains("too large to read")).isPresent()),
			"listing doesn't refuse the decompression bomb by its limit");
		String bomb = game.toolError("inspect_schematic", "{\"schematic\": \"Bomb\"}");
		check(bomb.contains("too large") && bomb.contains("256 MiB"), "inspecting the decompression bomb isn't refused by its limit: " + bomb);
	}

	/**
	 * Chests named "Witch Farm" holding every material of {@code file} except one redstone dust (or one of the first
	 * item): the comparison must miss exactly that one, and count every material the chests hold as useful.
	 */
	private static void compareWithChests(Game game, String file) {
		JsonObject materials = game.tool("inspect_schematic", "{\"schematic\": \"" + file + "\", \"layers\": true}").getAsJsonObject("materials");
		check(!materials.isEmpty(), "schematic has no materials");

		String shortItem = materials.has("redstone") ? "redstone" : materials.keySet().iterator().next();
		Map<String, Integer> held = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : materials.entrySet()) {
			int count = entry.getValue().getAsInt() - (entry.getKey().equals(shortItem) ? 1 : 0);
			if (count > 0) {
				held.put(entry.getKey(), count);
			}
		}

		List<String> stacks = new ArrayList<>();
		held.forEach((item, count) -> {
			int max = BuiltInRegistries.ITEM.getValue(Identifier.parse(item)).getDefaultMaxStackSize();
			for (int left = count; left > 0; left -= max) {
				stacks.add("{id:\"" + item + "\",count:" + Math.min(max, left) + "}");
			}
		});

		BlockPos pos = game.playerPosition();
		for (int chest = 0; chest * 27 < stacks.size(); chest++) {
			StringBuilder items = new StringBuilder();
			for (int slot = 0; slot < 27 && chest * 27 + slot < stacks.size(); slot++) {
				String stack = stacks.get(chest * 27 + slot);
				items.append(slot == 0 ? "" : ",").append(stack, 0, stack.length() - 1).append(",Slot:").append(slot).append("b}");
			}
			game.command("setblock " + (pos.getX() + 2 + chest * 2) + " " + pos.getY() + " " + (pos.getZ() + 2)
				+ " minecraft:chest{CustomName:\"Witch Farm\",Items:[" + items + "]}");
		}
		game.context.waitTicks(10);

		JsonObject compared = game.tool("inspect_schematic", "{\"schematic\": \"" + file + "\", \"compare_with\": \"witch farm\"}")
			.getAsJsonObject("comparison");
		JsonObject stillMissing = compared.getAsJsonObject("still_missing_after_crafting_from_contents");
		check(stillMissing.size() == 1 && stillMissing.has(shortItem) && stillMissing.get(shortItem).getAsInt() == 1,
			"comparison should miss exactly one " + shortItem + ": " + compared);
		JsonObject useful = compared.getAsJsonObject("useful_contents");
		check(held.keySet().stream().allMatch(useful::has), "comparison doesn't show what the chests hold: " + useful);
	}

	/** Copies the folder's files, subfolders included; returns the copies. */
	private static List<Path> copy(Path from, Path to) throws IOException {
		List<Path> copies = new ArrayList<>();
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				Files.createDirectories(target.getParent());
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				copies.add(target);
			}
		}

		return copies;
	}
}
