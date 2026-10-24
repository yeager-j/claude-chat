package claudechat;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Dimensions as the world tools accept and show them, and which dimensions a search covers. Tool arguments are parsed
 * once into level keys; the short display name ("the_nether") is only for output.
 */
public final class Dimensions {
	private Dimensions() {
	}

	/** "overworld", "the_nether", or a modded dimension's full id ("aether:the_aether"). */
	public static String name(ResourceKey<Level> dimension) {
		return ToolFormat.id(dimension.identifier());
	}

	/**
	 * A dimension argument: blank means none was given; "the_nether" and "minecraft:the_nether" are the same. Any other
	 * name fails with the dimensions this world has.
	 */
	public static Optional<ResourceKey<Level>> parse(String argument, Collection<ResourceKey<Level>> dimensions) {
		String name = argument.trim();
		if (name.isEmpty()) {
			return Optional.empty();
		}

		Identifier id = Identifier.tryParse(name);
		for (ResourceKey<Level> dimension : dimensions) {
			if (dimension.identifier().equals(id)) {
				return Optional.of(dimension);
			}
		}

		String known = dimensions.stream().map(Dimensions::name).collect(Collectors.joining(", "));
		throw new IllegalArgumentException("No dimension \"" + name + "\". This world has: " + known + ".");
	}

	/**
	 * The dimensions a nearest-first search of placed things covers, in order: only the requested one if given;
	 * otherwise the player's first and then, if no distance limit applies, the others. A limit means "near me", so it
	 * keeps the search in the player's dimension unless another is asked for by name.
	 */
	public static List<ResourceKey<Level>> searchOrder(ResourceKey<Level> home, Optional<ResourceKey<Level>> requested,
		boolean distanceLimited, Collection<ResourceKey<Level>> all) {
		if (requested.isPresent()) {
			return List.of(requested.get());
		}
		if (distanceLimited) {
			return List.of(home);
		}

		List<ResourceKey<Level>> order = new ArrayList<>(List.of(home));
		all.stream().filter(dimension -> !dimension.equals(home)).forEach(order::add);
		return order;
	}

	/**
	 * Where to search for something that generates only in some dimensions (a structure, a biome): the requested
	 * dimension; else the player's if it exists there; else the first where it does (a fortress asked about from the
	 * Overworld is in the Nether).
	 */
	public static ResourceKey<Level> whereItExists(ResourceKey<Level> home, Optional<ResourceKey<Level>> requested,
		List<ResourceKey<Level>> existsIn, String what) {
		if (requested.isPresent()) {
			return requested.get();
		}
		if (existsIn.contains(home)) {
			return home;
		}
		if (existsIn.isEmpty()) {
			throw new IllegalArgumentException(what + " doesn't generate in any dimension of this world.");
		}
		return existsIn.getFirst();
	}
}
