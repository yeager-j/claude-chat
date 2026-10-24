package claudechat;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Resolves what Claude asks for (an id, a tag, or a plain word) to registry entries: blocks, biomes and structures. */
public final class RegistryQuery {
	private RegistryQuery() {
	}

	/** Whether a name that is both an entry and a tag means the entry or the whole tag. */
	public enum Precedence {
		/** "bed" is the bed block if there is one, else #beds. */
		ID_FIRST,
		/**
		 * "ruined_portal" is #ruined_portal (every variant), not just the standard one: a structure tag groups variants
		 * that a player calls by one name.
		 */
		TAG_FIRST
	}

	/**
	 * An id ("minecraft:warped_forest"), a tag ("#minecraft:is_nether"), or a name tried as an id and as a tag (also
	 * plural: "bed" → #minecraft:beds) in the order {@code precedence} gives; a word without a namespace then falls
	 * back to a substring of ids ("warped" → warped_forest).
	 */
	public static <T> List<Holder<T>> resolve(RegistryAccess access, ResourceKey<? extends Registry<T>> registryKey, String query,
		Precedence precedence) {
		HolderLookup.RegistryLookup<T> registry = access.lookupOrThrow(registryKey);
		String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (q.startsWith("#")) {
			Identifier id = Identifier.tryParse(q.substring(1));
			return id == null ? List.of() : tag(registry, registryKey, id).orElse(List.of());
		}

		Identifier asId = q.contains(":") ? Identifier.tryParse(q) : Identifier.tryParse("minecraft:" + q);
		if (asId == null) {
			return List.of();
		}
		Optional<List<Holder<T>>> exact = registry.get(ResourceKey.create(registryKey, asId)).map(holder -> List.<Holder<T>>of(holder));
		Optional<List<Holder<T>>> tag = tag(registry, registryKey, asId).or(() -> tag(registry, registryKey, asId.withSuffix("s")));
		Optional<List<Holder<T>>> named = precedence == Precedence.ID_FIRST ? exact.or(() -> tag) : tag.or(() -> exact);
		if (named.isPresent()) {
			return named.get();
		}
		if (q.contains(":")) {
			return List.of();
		}
		return registry.listElements().filter(h -> h.key().identifier().getPath().contains(q)).<Holder<T>>map(h -> h).toList();
	}

	/** "minecraft:warped_forest" for a registered holder. */
	public static String id(Holder<?> holder) {
		return holder.unwrapKey().map(key -> key.identifier().toString()).orElse("?");
	}

	/** A non-empty tag's entries. */
	private static <T> Optional<List<Holder<T>>> tag(HolderLookup.RegistryLookup<T> registry, ResourceKey<? extends Registry<T>> registryKey,
		Identifier id) {
		return registry.get(TagKey.create(registryKey, id)).filter(set -> set.size() > 0).map(set -> set.stream().toList());
	}
}
