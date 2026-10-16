package claudechat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Inline item icons for chat, using the game's own texture atlases (the "object" text component). */
public final class ItemIcons {
	private record SpriteRef(Identifier atlas, Identifier sprite) {
	}

	private static final Optional<SpriteRef> NONE = Optional.empty();
	private static final Map<Identifier, Optional<SpriteRef>> CACHE = new ConcurrentHashMap<>();
	private static final SpriteRef EMPTY_SLOT = new SpriteRef(AtlasIds.GUI, Identifier.withDefaultNamespace("container/slot"));

	private ItemIcons() {
	}

	/** "iron_ingot", "minecraft:iron_ingot" or "Iron Ingot" to an item, if one exists. */
	public static Optional<Holder.Reference<Item>> find(String raw) {
		String id = raw.trim().toLowerCase().replace(' ', '_');
		if (id.isEmpty()) {
			return Optional.empty();
		}
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (key == null) {
			return Optional.empty();
		}
		return BuiltInRegistries.ITEM.get(key);
	}

	/** Icon, then the item's name; hovering shows the real item tooltip. */
	public static MutableComponent named(Holder<Item> item) {
		MutableComponent out = Component.empty();
		icon(item).ifPresent(icon -> out.append(icon).append(" "));
		out.append(name(item));
		return out;
	}

	/** Just the item's (translated) name, highlighted, with its tooltip on hover. */
	public static MutableComponent name(Holder<Item> item) {
		return new ItemStack(item.value()).getHoverName().copy().withStyle(style -> style
			.withColor(ChatFormatting.YELLOW)
			.withHoverEvent(new HoverEvent.ShowItem(new ItemStackTemplate(item.value()))));
	}

	/** The item's icon with its tooltip on hover, if a texture for it can be found. */
	public static Optional<MutableComponent> icon(Holder<Item> item) {
		return sprite(item).map(ref -> Component.object(new AtlasSprite(ref.atlas(), ref.sprite()))
			.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowItem(new ItemStackTemplate(item.value())))));
	}

	/** An icon if possible, otherwise the name, so recipe grids always show something. */
	public static MutableComponent iconOrName(Holder<Item> item) {
		return icon(item).orElseGet(() -> Component.literal("[").append(name(item)).append("]"));
	}

	/** An empty crafting slot, the same width as an icon. */
	public static MutableComponent emptySlot() {
		return Component.object(new AtlasSprite(EMPTY_SLOT.atlas(), EMPTY_SLOT.sprite()));
	}

	/** Items with flat textures use item/<name>; blocks fall back to a face texture. */
	private static Optional<SpriteRef> sprite(Holder<Item> item) {
		Identifier id = item.unwrapKey().map(key -> key.identifier()).orElse(null);
		if (id == null) {
			return NONE;
		}
		return CACHE.computeIfAbsent(id, key -> {
			String ns = key.getNamespace();
			String path = key.getPath();
			List<SpriteRef> candidates = List.of(
				new SpriteRef(AtlasIds.ITEMS, Identifier.fromNamespaceAndPath(ns, "item/" + path)),
				new SpriteRef(AtlasIds.ITEMS, Identifier.fromNamespaceAndPath(ns, "item/" + path + "_00")),
				new SpriteRef(AtlasIds.BLOCKS, Identifier.fromNamespaceAndPath(ns, "block/" + path)),
				new SpriteRef(AtlasIds.BLOCKS, Identifier.fromNamespaceAndPath(ns, "block/" + path + "_front")),
				new SpriteRef(AtlasIds.BLOCKS, Identifier.fromNamespaceAndPath(ns, "block/" + path + "_side")),
				new SpriteRef(AtlasIds.BLOCKS, Identifier.fromNamespaceAndPath(ns, "block/" + path + "_top")));
			for (SpriteRef candidate : candidates) {
				if (exists(candidate)) {
					return Optional.of(candidate);
				}
			}
			return NONE;
		});
	}

	private static boolean exists(SpriteRef ref) {
		try {
			var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(ref.atlas());
			return atlas.getSprite(ref.sprite()) != atlas.missingSprite();
		} catch (RuntimeException e) {
			return false;
		}
	}
}
