package claudechat;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One matcher for list_schematics and inspect_schematic: exact beats partial, several equal matches are ambiguous. */
class SchematicMatchTest {
	private static final Schematics.Entry WITCH_FARM = readable("farms/Witch Farm.litematic", "Witch Farm");
	private static final Schematics.Entry WITCH_FARM_V2 = readable("farms/Witch Farm v2.litematic", "Witch Farm v2");
	private static final Schematics.Entry IRON = readable("Iron.schem", "Golem Trap");
	private static final Schematics.Entry BROKEN = new Schematics.Unreadable(Path.of("Broken.nbt"), "old/Broken.nbt", "truncated");
	private static final List<Schematics.Entry> ENTRIES = List.of(WITCH_FARM, WITCH_FARM_V2, IRON, BROKEN);

	@Test
	void exactPathWinsOverPartialMatches() {
		assertEquals(new Schematics.Found(WITCH_FARM), Schematics.match(ENTRIES, "farms/Witch Farm"));
		assertEquals(new Schematics.Found(WITCH_FARM), Schematics.match(ENTRIES, "witch farm.litematic"));
	}

	@Test
	void backslashesWorkLikeSlashes() {
		assertEquals(new Schematics.Found(WITCH_FARM), Schematics.match(ENTRIES, "farms\\witch farm"));
		assertTrue(Schematics.matches(WITCH_FARM, "farms\\witch"));
	}

	@Test
	void schematicNameCountsForExactAndPartialMatches() {
		assertEquals(new Schematics.Found(IRON), Schematics.match(ENTRIES, "golem trap"));
		assertEquals(new Schematics.Found(IRON), Schematics.match(ENTRIES, "golem"));
	}

	@Test
	void severalPartialMatchesAreAmbiguous() {
		assertEquals(new Schematics.Ambiguous(List.of(WITCH_FARM, WITCH_FARM_V2)), Schematics.match(ENTRIES, "witch"));
	}

	@Test
	void unreadableFilesMatchByPath() {
		assertEquals(new Schematics.Found(BROKEN), Schematics.match(ENTRIES, "old/broken"));
		assertFalse(Schematics.matches(BROKEN, "truncated"));
	}

	@Test
	void nothingMatches() {
		assertEquals(new Schematics.NotFound(), Schematics.match(ENTRIES, "castle"));
	}

	private static Schematics.Entry readable(String path, String name) {
		return new Schematics.Readable(Path.of(path), path, new Schematics.Info(name, "", "", Vec3i.ZERO, OptionalLong.empty()));
	}
}
