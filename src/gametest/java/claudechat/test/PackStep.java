package claudechat.test;

import claudechat.ClaudeConfig;
import claudechat.Schematics;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * World data packs: a folder pack adding a loot table, and a zip pack overriding a vanilla loot table and the piston
 * recipe. Recipe grids, material lists and the prompt's file map follow the server's packs, and a disabled pack is gone
 * from all three.
 */
final class PackStep {
	/** The zip pack, as the server names it. */
	private static final String OVERRIDE_PACK = "file/Better Dungeons.zip";
	private static final String OVERRIDE_NAME = "Better Dungeons";
	private static final String FOLDER_NAME = "Secret Vaults";
	private static final String PISTONS_SCHEMATIC = ModStep.MOD_ID + "-pistons.nbt";
	/** Copying a pack into the knowledge base takes a moment after the reload. */
	private static final int PROMPT_ATTEMPTS = 30;

	private PackStep() {
	}

	static void run(Game game) throws IOException {
		Path schematic = Schematics.folder().dir().resolve(PISTONS_SCHEMATIC);
		try {
			writeTestPacks(game.server().computeOnServer(server -> server.getWorldPath(LevelResource.DATAPACK_DIR)));
			game.reload("reload");
			check(game.server().computeOnServer(server -> server.getPackRepository().getSelectedIds().contains(OVERRIDE_PACK)),
				"the zip pack wasn't enabled by /reload");

			String withPack = game.ask("answer [[recipe:minecraft:piston]]\nPistons.", "how are pistons made with the packs?").requireAnswer().text();
			check(withPack.contains("2×") && withPack.contains("block/dirt"), "the piston grid doesn't show the pack's recipe: " + withPack);
			writePistons(game, schematic);
			checkPistonMaterials(game);
			checkPromptLists(game);
			if (game.options.live()) {
				game.askClaude("what's in a secret vault chest? and what can dungeon chests have in this world?",
					"claudechat-datapack-answer").requireAnswer();
			}

			game.reload("datapack disable \"" + OVERRIDE_PACK + "\"");
			String vanilla = game.ask("answer [[recipe:minecraft:piston]]\nPistons.", "how are pistons made now?").requireAnswer().text();
			check(!vanilla.contains("2×") && !vanilla.contains("block/dirt"), "the disabled pack's recipe is still drawn: " + vanilla);
			game.ask("answer ok", "which packs are left?").requireAnswer();
			check(!game.systemPrompt().contains(OVERRIDE_NAME), "the disabled pack is still in the prompt");
		} finally {
			Files.deleteIfExists(schematic);
		}
	}

	/** Two pistons in a schematic: their material list must use the pack's recipe, which makes two from dirt and more. */
	private static void checkPistonMaterials(Game game) {
		JsonObject pistons = game.tool("inspect_schematic", "{\"schematic\": \"" + PISTONS_SCHEMATIC + "\"}");
		List<JsonObject> crafts = Json.where(pistons.getAsJsonArray("crafting"), "item", "piston");
		check(crafts.size() == 1 && crafts.getFirst().get("craft_times").getAsInt() == 1 && Json.is(crafts.getFirst(), "recipe", "piston"),
			"the material list doesn't use the pack's piston recipe: " + pistons);
		JsonObject base = pistons.getAsJsonObject("base_materials");
		check(base.has("dirt") && base.get("dirt").getAsInt() == 1, "the pack's dirt isn't in the material list: " + base);
	}

	/**
	 * The prompt's world packs, highest priority first, each with the folder its data was copied to; the copy has the
	 * pack's loot table. Waits for the copy, which the knowledge base makes after the reload.
	 */
	private static void checkPromptLists(Game game) {
		Optional<String> overrideFolder = Optional.empty();
		for (int attempt = 0; attempt < PROMPT_ATTEMPTS && overrideFolder.isEmpty(); attempt++) {
			game.ask("answer ok", "pack check " + attempt).requireAnswer();
			String prompt = game.systemPrompt();
			overrideFolder = dataFolder(prompt, OVERRIDE_NAME).filter(folder -> dataFolder(prompt, FOLDER_NAME).isPresent());
			if (overrideFolder.isEmpty()) {
				game.context.waitTicks(SECONDS);
			}
		}

		check(overrideFolder.isPresent(), "the prompt never listed both packs with their data: " + game.systemPrompt());
		Path lootTable = ClaudeConfig.dir().resolve("knowledge").resolve(overrideFolder.get()).resolve("minecraft/loot_table/chests/simple_dungeon.json");
		try {
			check(Files.readString(lootTable).contains("netherite_ingot"), "the copied pack's loot table isn't the pack's");
		} catch (IOException e) {
			throw new AssertionError("the prompt lists a pack folder without its loot table: " + lootTable, e);
		}
	}

	/** The data folder the prompt lists for a pack ("- name: folder/data"), once the pack is copied. */
	private static Optional<String> dataFolder(String prompt, String pack) {
		return prompt.lines()
			.filter(line -> line.startsWith("- ") && line.contains(pack) && line.endsWith("/data"))
			.map(line -> line.substring(line.indexOf(": ") + 2))
			.findFirst();
	}

	/** Saves two pistons as a structure file in the schematics folder. */
	private static void writePistons(Game game, Path file) throws IOException {
		BlockPos base = game.playerPosition().offset(3, 10, 3);
		CompoundTag tag = game.server().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			level.setBlockAndUpdate(base, Blocks.PISTON.defaultBlockState());
			level.setBlockAndUpdate(base.east(), Blocks.PISTON.defaultBlockState());
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(level, base, new Vec3i(2, 1, 1), false, List.of(Blocks.STRUCTURE_VOID));
			return template.save(new CompoundTag());
		});
		Files.createDirectories(file.getParent());
		NbtIo.writeCompressed(tag, file);
	}

	private static void writeTestPacks(Path datapacks) throws IOException {
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
		String mcmeta = """
			{"pack": {"description": "%s", "min_format": [%d, %d], "max_format": [%d, %d]}}
			""";
		String vault = """
			{"type": "minecraft:chest", "pools": [{"rolls": 2, "entries": [
			  {"type": "minecraft:item", "name": "minecraft:diamond", "weight": 1,
			   "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 3}}]},
			  {"type": "minecraft:item", "name": "minecraft:golden_apple", "weight": 3}]}]}
			""";
		String dungeon = """
			{"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [
			  {"type": "minecraft:item", "name": "minecraft:netherite_ingot"}]}]}
			""";
		// Two pistons from cobblestone, redstone and dirt, so both the count and an ingredient show the override.
		String piston = """
			{"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:cobblestone", "minecraft:redstone", "minecraft:dirt"],
			 "result": {"id": "minecraft:piston", "count": 2}}
			""";

		Path folder = datapacks.resolve(FOLDER_NAME);
		Files.createDirectories(folder.resolve("data/claudetest/loot_table/chests"));
		Files.writeString(folder.resolve("pack.mcmeta"), mcmeta.formatted("Secret vaults", format.major(), format.minor(), format.major(), format.minor()));
		Files.writeString(folder.resolve("data/claudetest/loot_table/chests/secret_vault.json"), vault);

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(datapacks.resolve(OVERRIDE_PACK.substring("file/".length()))))) {
			zip.putNextEntry(new ZipEntry("pack.mcmeta"));
			zip.write(mcmeta.formatted("Better dungeons", format.major(), format.minor(), format.major(), format.minor()).getBytes(StandardCharsets.UTF_8));
			zip.putNextEntry(new ZipEntry("data/minecraft/loot_table/chests/simple_dungeon.json"));
			zip.write(dungeon.getBytes(StandardCharsets.UTF_8));
			zip.putNextEntry(new ZipEntry("data/minecraft/recipe/piston.json"));
			zip.write(piston.getBytes(StandardCharsets.UTF_8));
		}
	}
}
