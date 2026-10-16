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

/** Resolves what Claude asks for (an id, a tag, or a plain word) to registry entries, for blocks and biomes. */
public final class RegistryQuery {
	private RegistryQuery() {
	}

	/**
	 * An id ("minecraft:warped_forest"), a tag ("#minecraft:is_nether"), or a word tried as an id, then a tag (also
	 * plural: "bed" → #minecraft:beds), then a substring of ids ("warped" → warped_forest).
	 */
	public static <T> List<Holder<T>> resolve(RegistryAccess access, ResourceKey<? extends Registry<T>> registryKey, String query) {
		HolderLookup.RegistryLookup<T> registry = access.lookupOrThrow(registryKey);
		String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (q.startsWith("#")) {
			Identifier id = Identifier.tryParse(q.substring(1));
			return id == null ? List.of() : registry.get(TagKey.create(registryKey, id))
				.map(set -> set.stream().toList()).orElse(List.of());
		}
		Identifier asId = q.contains(":") ? Identifier.tryParse(q) : Identifier.tryParse("minecraft:" + q);
		if (asId == null) {
			return List.of();
		}
		var exact = registry.get(ResourceKey.create(registryKey, asId));
		if (exact.isPresent()) {
			return List.of(exact.get());
		}
		for (Identifier tagId : List.of(asId, asId.withSuffix("s"))) {
			var tag = registry.get(TagKey.create(registryKey, tagId));
			if (tag.isPresent() && tag.get().size() > 0) {
				return tag.get().stream().toList();
			}
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
}
