package claudechat;

import com.google.gson.JsonObject;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The output vocabulary every tool shares, and the block query find_blocks parses its arguments into. */
class ToolFormatTest {
	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void minecraftIdsAreShortAndOthersFull() {
		assertEquals("stone", ToolFormat.id("minecraft:stone"));
		assertEquals("stone", ToolFormat.id("stone"));
		assertEquals("create:cogwheel", ToolFormat.id("create:cogwheel"));
		assertEquals("village/plains/houses/plains_temple_3", ToolFormat.id("minecraft:village/plains/houses/plains_temple_3"));
		assertEquals("Not An Id!", ToolFormat.id("Not An Id!"));
		assertEquals("respawn_anchor", ToolFormat.id(Blocks.RESPAWN_ANCHOR));
		assertEquals("oak_stairs[facing=east]", ToolFormat.blockState("minecraft:oak_stairs[facing=east]"));
	}

	@Test
	void blockStatesShowTheirProperties() {
		JsonObject out = new JsonObject();
		ToolFormat.addBlockState(out, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 4));

		assertEquals("{\"block\":\"respawn_anchor\",\"properties\":{\"charges\":\"4\"}}", out.toString());
	}

	@Test
	void distancesAreTenthsUpCloseAndWholeFarAway() {
		assertEquals(3.4, ToolFormat.distance(3.42));
		assertEquals(2830L, ToolFormat.distance(2830.4));
	}

	@Test
	void directionsFollowTheCompass() {
		Origin origin = Origin.at(Level.OVERWORLD, 0, 64, 0);
		JsonObject out = new JsonObject();
		ToolFormat.addDirection(out, origin, 100, 70.0, -100);

		assertEquals("north-east", out.get("direction").getAsString());
		assertEquals("142", out.get("distance_blocks").toString());
		assertEquals(6, out.get("blocks_up").getAsInt());
		assertEquals("here", ToolFormat.compass(1, -1));
	}

	@Test
	void itemsAndSearchesReadTheSameEverywhere() {
		assertEquals("1 Diamond Sword (diamond_sword) {Sharpness V}",
			ToolFormat.item(1, "minecraft:diamond_sword", "Diamond Sword", List.of("Sharpness V")));
		assertEquals("warped_forest", ToolFormat.searched(List.of("minecraft:warped_forest"), "warped", "biomes"));
		assertEquals("2 biomes matching \"warped\"", ToolFormat.searched(List.of("a", "b"), "warped", "biomes"));
	}

	@Test
	void notesOnLeftOutResultsShareOneKey() {
		JsonObject out = new JsonObject();
		ToolFormat.noteMore(out, "3 more block entities.");
		ToolFormat.noteMore(out, "2 more entities.");

		assertEquals("3 more block entities. 2 more entities.", out.get(ToolFormat.MORE_KEY).getAsString());
	}

	@Test
	void blockQueriesParsePropertiesOncePerBlock() {
		BlockFinder.Query query = BlockFinder.Query.of(List.of(Blocks.RESPAWN_ANCHOR, Blocks.STONE), Map.of("charges", "4"), "Skeleton");

		assertEquals(List.of(Blocks.RESPAWN_ANCHOR), List.copyOf(query.blocks().keySet()));
		assertEquals("skeleton", query.data());
		assertTrue(query.matches(Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 4)));
		assertFalse(query.matches(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
		assertFalse(query.matches(Blocks.STONE.defaultBlockState()));
	}

	@Test
	void anInvalidPropertyListsTheValidOnes() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> BlockFinder.Query.of(List.of(Blocks.RESPAWN_ANCHOR), Map.of("charges", "9"), ""));

		assertTrue(e.getMessage().contains("charges (0|1|2|3|4)"), e.getMessage());
	}
}
