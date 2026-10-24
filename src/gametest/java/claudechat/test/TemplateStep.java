package claudechat.test;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static claudechat.test.Game.check;

/**
 * Structure templates: search by blocks, trace to structures through pools, draw layers; a template saved with a
 * structure block shows up in the next search, and again after saving over it; imported templates within limits; a
 * saved or pack template reached through a junction isn't read.
 */
final class TemplateStep {
	/** Saved in a namespace folder that is a junction to a folder outside the world's generated folder. */
	private static final String LINKED_TEMPLATE = "claudechat_linked:secret";
	/** A folder pack in the world whose structure folder is a junction to templates elsewhere. */
	private static final String LINKED_TEMPLATE_PACK = "Linked Templates";
	private static final String LINKED_PACK_TEMPLATE = "claudechat_packlinked:secret";

	private TemplateStep() {
	}

	static void run(Game game) throws IOException {
		// The index is built in the background when the world opens; the first search waits for it if needed.
		JsonObject trial = game.tool("search_structure_templates", "{\"blocks\": [\"trial_spawner\"], \"query\": \"trial_chambers\", \"limit\": 3}");
		check(trial.get("templates_indexed").getAsInt() > 500, "too few templates indexed: " + trial.get("templates_indexed"));
		check(usedBy(firstResult(trial)).contains("trial_chambers"), "trial spawner template not traced to trial chambers: " + trial);
		JsonObject city = game.tool("search_structure_templates", "{\"blocks\": [\"sculk shrieker\", \"reinforced_deepslate\", \"soul lantern\"], \"limit\": 3}");
		check(Json.objects(city.getAsJsonArray("results")).stream().anyMatch(result -> result.get("template").getAsString().contains("ancient_city/")),
			"sculk shrieker search didn't find an ancient city piece: " + city);
		JsonObject house = game.tool("inspect_structure_template",
			"{\"template\": \"minecraft:village/plains/houses/plains_small_house_1\", \"layers\": true}");
		check(usedBy(house).contains("village_plains"), "house not traced to plains villages: " + house.get("used_by_structures"));
		check(house.getAsJsonArray("layers").size() > 3, "house layers missing");
		check(Json.is(house, "from", "vanilla"), "house isn't from the vanilla pack: " + house.get("from"));
		JsonObject igloo = game.tool("inspect_structure_template", "{\"template\": \"minecraft:igloo/bottom\"}");
		check(igloo.get("solid_blocks").getAsInt() > 0, "the igloo's bottom has no blocks: " + igloo);
		String partial = game.toolError("inspect_structure_template", "{\"template\": \"plains_small_house\"}");
		check(partial.contains("No structure template") && partial.contains("plains_small_house"), "a partial template id should fail by name: " + partial);

		savedTemplates(game);
		importedLimits(game);
		game.options.question(Step.TEMPLATE).ifPresent(question -> game.askClaude(question, "claudechat-template-answer").requireAnswer());
	}

	/** Saving with a structure block shows up in the next search, and again after saving over it. */
	private static void savedTemplates(Game game) {
		String towerSearch = "{\"blocks\": [\"gold_block\"], \"query\": \"claudechat:\"}";
		saveTestTower(game, 1);
		JsonObject saved = firstResult(game.tool("search_structure_templates", towerSearch));
		check(Json.is(saved, "template", "claudechat:test_tower") && saved.get("solid_blocks").getAsInt() == 1
			&& saved.get("from").getAsString().startsWith("this world"), "saved template not indexed: " + saved);
		saveTestTower(game, 3);
		JsonObject resaved = firstResult(game.tool("search_structure_templates", towerSearch));
		check(resaved.get("solid_blocks").getAsInt() == 3, "index kept the old save: " + resaved);
		JsonObject tower = game.tool("inspect_structure_template", "{\"template\": \"claudechat:test_tower\", \"layers\": true}");
		check(tower.get("solid_blocks").getAsInt() == 3 && tower.get("from").getAsString().startsWith("this world"),
			"inspect doesn't match the save: " + tower);
	}

	/**
	 * After a reload the index is rebuilt with a wide template, too big for the parallel pass but fine alone, and one
	 * whose data would need a gigabyte, which is refused by its limit.
	 */
	private static void importedLimits(Game game) throws IOException {
		Path generated = game.server().computeOnServer(server -> server.getWorldPath(LevelResource.GENERATED_DIR));
		Path datapacks = game.server().computeOnServer(server -> server.getWorldPath(LevelResource.DATAPACK_DIR));
		writeOversizedTemplates(generated);
		List<Path> junctions = new ArrayList<>();
		try {
			writeLinkedTemplate(generated.resolve(Identifier.parse(LINKED_TEMPLATE).getNamespace()), junctions);
			writeLinkedPackTemplate(datapacks.resolve(LINKED_TEMPLATE_PACK), junctions);
			game.reload("reload");
			JsonObject wide = game.tool("search_structure_templates", "{\"blocks\": [\"emerald_block\"], \"query\": \"claudechat:\"}");
			check(wide.get("templates_indexed").getAsInt() > 500, "too few templates indexed after adding oversized ones");
			check(Json.is(firstResult(wide), "template", "claudechat:wide_slab"), "the wide template wasn't indexed one at a time: " + wide);
			String bomb = game.toolError("inspect_structure_template", "{\"template\": \"claudechat:bomb\"}");
			check(bomb.contains("too large") && bomb.contains("256 MiB"), "the oversized template isn't refused by its limit: " + bomb);
			if (!junctions.isEmpty()) {
				for (String id : List.of(LINKED_TEMPLATE, LINKED_PACK_TEMPLATE)) {
					String secret = game.toolError("inspect_structure_template", "{\"template\": \"" + id + "\"}");
					check(secret.contains("No structure template"), id + " was read through a junction: " + secret);
					check(game.server().computeOnServer(server -> server.getStructureTemplateManager().get(Identifier.parse(id)).isPresent()),
						"the game doesn't read " + id + " either, so refusing it shows nothing");
				}
			}
		} finally {
			for (Path junction : junctions) {
				Files.delete(junction);
			}
		}
	}

	/**
	 * A template in the world but outside its generated folder, with a junction to its namespace folder at
	 * {@code namespace}: the game reads it by its id, but the template tools must not, since a junction there could
	 * lead anywhere. Adds nothing to {@code junctions} without junctions.
	 */
	private static void writeLinkedTemplate(Path namespace, List<Path> junctions) throws IOException {
		Path outside = namespace.getParent().resolveSibling("claudechat-outside-templates");
		writeEmptyTemplate(outside.resolve("structure/secret.nbt"));
		Game.junction(namespace, outside).ifPresent(junctions::add);
	}

	/**
	 * A plain folder pack whose structure folder is a junction to templates elsewhere in the world: the pack passes, but
	 * the game opens the template through the junction, which the template tools must not.
	 */
	private static void writeLinkedPackTemplate(Path pack, List<Path> junctions) throws IOException {
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
		Identifier id = Identifier.parse(LINKED_PACK_TEMPLATE);
		Path namespace = Files.createDirectories(pack.resolve("data").resolve(id.getNamespace()));
		Files.writeString(pack.resolve("pack.mcmeta"), """
			{"pack": {"description": "Linked templates", "min_format": [%d, %d], "max_format": [%d, %d]}}
			""".formatted(format.major(), format.minor(), format.major(), format.minor()));
		Path outside = pack.getParent().resolveSibling("claudechat-outside-pack-templates");
		writeEmptyTemplate(outside.resolve(id.getPath() + ".nbt"));
		Game.junction(namespace.resolve("structure"), outside).ifPresent(junctions::add);
	}

	private static void writeEmptyTemplate(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		NbtIo.writeCompressed(new StructureTemplate().save(new CompoundTag()), file);
	}

	private static JsonObject firstResult(JsonObject search) {
		List<JsonObject> results = Json.objects(search.getAsJsonArray("results"));
		check(!results.isEmpty(), "no results: " + search);
		return results.getFirst();
	}

	private static List<String> usedBy(JsonObject template) {
		return Json.strings(template.getAsJsonArray("used_by_structures"));
	}

	/**
	 * Saves claudechat:test_tower the way a structure block does: a 1×3×1 column above the player with {@code height}
	 * gold blocks from the bottom, air above them.
	 */
	private static void saveTestTower(Game game, int height) {
		BlockPos base = game.playerPosition().offset(3, 20, 3);
		boolean saved = game.server().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int y = 0; y < 3; y++) {
				level.setBlockAndUpdate(base.above(y), y < height ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
			Identifier id = Identifier.parse("claudechat:test_tower");
			server.getStructureTemplateManager().getOrCreate(id).fillFromWorld(level, base, new Vec3i(1, 3, 1), false, List.of(Blocks.STRUCTURE_VOID));
			return server.getStructureTemplateManager().save(id);
		});
		check(saved, "couldn't save the test tower");
	}

	/**
	 * Writes two templates into the world's generated folder: claudechat:wide_slab, 2049×1×2048 with one emerald block
	 * (more positions than the index reads in parallel), and claudechat:bomb, whose only tag claims a 1 GiB byte array
	 * (the game's own reader would allocate it before finding the data missing).
	 */
	private static void writeOversizedTemplates(Path generated) throws IOException {
		Path structures = Files.createDirectories(generated.resolve("claudechat/structure"));
		CompoundTag wide = new CompoundTag();
		NbtUtils.addCurrentDataVersion(wide);
		ListTag size = new ListTag();
		List.of(2049, 1, 2048).forEach(side -> size.add(IntTag.valueOf(side)));
		wide.put("size", size);
		ListTag palette = new ListTag();
		palette.add(NbtUtils.writeBlockState(Blocks.EMERALD_BLOCK.defaultBlockState()));
		wide.put("palette", palette);
		CompoundTag block = new CompoundTag();
		ListTag origin = new ListTag();
		List.of(0, 0, 0).forEach(coordinate -> origin.add(IntTag.valueOf(coordinate)));
		block.put("pos", origin);
		block.putInt("state", 0);
		ListTag blocks = new ListTag();
		blocks.add(block);
		wide.put("blocks", blocks);
		wide.put("entities", new ListTag());
		NbtIo.writeCompressed(wide, structures.resolve("wide_slab.nbt"));

		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(structures.resolve("bomb.nbt"))))) {
			out.writeByte(Tag.TAG_COMPOUND);
			out.writeUTF("");
			out.writeByte(Tag.TAG_BYTE_ARRAY);
			out.writeUTF("data");
			out.writeInt(1 << 30);
		}
	}
}
