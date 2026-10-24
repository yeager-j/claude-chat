package claudechat;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a name, an id or a #tag resolves to, and which wins when a name is both an entry and a tag. */
class RegistryQueryTest {
	private static final ResourceKey<Registry<String>> THINGS = ResourceKey.createRegistryKey(Identifier.parse("test:things"));

	private static final RegistryAccess ACCESS = access(
		List.of("minecraft:bed", "minecraft:red_bed", "minecraft:blue_bed", "minecraft:ruined_portal", "minecraft:ruined_portal_nether",
			"minecraft:ruined_portal_ocean", "minecraft:warped_forest", "minecraft:warped_wart", "minecraft:oak_log", "minecraft:crimson_log",
			"minecraft:stick", "create:cogwheel"),
		Map.of(
			"minecraft:bed", List.of("minecraft:red_bed", "minecraft:blue_bed"),
			"minecraft:ruined_portal", List.of("minecraft:ruined_portal", "minecraft:ruined_portal_nether", "minecraft:ruined_portal_ocean"),
			"minecraft:logs", List.of("minecraft:oak_log"),
			"minecraft:unused", List.of()));

	@Test
	void idFirstPrefersTheEntryOverTheTagOfTheSameName() {
		assertEquals(Set.of("minecraft:bed"), ids("bed", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void tagFirstPrefersTheTagOverTheEntryOfTheSameName() {
		assertEquals(Set.of("minecraft:red_bed", "minecraft:blue_bed"), ids("bed", RegistryQuery.Precedence.TAG_FIRST));
		assertEquals(Set.of("minecraft:ruined_portal", "minecraft:ruined_portal_nether", "minecraft:ruined_portal_ocean"),
			ids("ruined_portal", RegistryQuery.Precedence.TAG_FIRST));
	}

	@Test
	void idFirstStillFallsBackToTheTagWhenThereIsNoEntry() {
		assertEquals(Set.of("minecraft:oak_log"), ids("log", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void aPluralTagIsFoundForASingularName() {
		assertEquals(Set.of("minecraft:oak_log"), ids("log", RegistryQuery.Precedence.TAG_FIRST));
	}

	@Test
	void anExactIdWithOrWithoutNamespace() {
		assertEquals(Set.of("minecraft:stick"), ids("minecraft:stick", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of("minecraft:stick"), ids("stick", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of("create:cogwheel"), ids("create:cogwheel", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void aHashNamesATagAndNeverAnEntry() {
		assertEquals(Set.of("minecraft:red_bed", "minecraft:blue_bed"), ids("#minecraft:bed", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of(), ids("#minecraft:stick", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of(), ids("#minecraft:missing", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void caseAndSpacesAreNormalized() {
		assertEquals(Set.of("minecraft:warped_forest"), ids("  Warped Forest ", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void anUnknownWordWithoutNamespaceMatchesPartOfAnId() {
		assertEquals(Set.of("minecraft:warped_forest", "minecraft:warped_wart"), ids("warped", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void anUnknownIdWithANamespaceMatchesNothing() {
		assertEquals(Set.of(), ids("minecraft:warped", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of(), ids("nothing_like_it", RegistryQuery.Precedence.ID_FIRST));
	}

	@Test
	void anEmptyTagIsNotAMatch() {
		assertEquals(Set.of(), ids("#minecraft:unused", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of(), ids("unused", RegistryQuery.Precedence.TAG_FIRST));
	}

	@Test
	void anInvalidIdMatchesNothing() {
		assertEquals(Set.of(), ids("not an id!", RegistryQuery.Precedence.ID_FIRST));
		assertEquals(Set.of(), ids("#Bad:Tag:", RegistryQuery.Precedence.ID_FIRST));
	}

	private static Set<String> ids(String query, RegistryQuery.Precedence precedence) {
		return RegistryQuery.resolve(ACCESS, THINGS, query, precedence).stream().map(RegistryQuery::id).collect(Collectors.toSet());
	}

	/** A frozen registry of strings (each its own id) with the given tags. */
	private static RegistryAccess access(List<String> ids, Map<String, List<String>> tags) {
		MappedRegistry<String> registry = new MappedRegistry<>(THINGS, Lifecycle.stable());
		Map<String, Holder<String>> holders = new HashMap<>();
		for (String id : ids) {
			holders.put(id, registry.register(ResourceKey.create(THINGS, Identifier.parse(id)), id, RegistrationInfo.BUILT_IN));
		}

		Map<TagKey<String>, List<Holder<String>>> boundTags = new HashMap<>();
		tags.forEach((tag, members) -> boundTags.put(TagKey.create(THINGS, Identifier.parse(tag)), members.stream().map(holders::get).toList()));
		registry.bindTags(boundTags);
		registry.freeze();

		return new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
	}
}
