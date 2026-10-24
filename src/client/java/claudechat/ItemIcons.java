package claudechat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
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

	/** The two atlases icons come from, as stitched right now. */
	private record Atlases(TextureAtlas items, TextureAtlas blocks) {
		boolean has(SpriteRef ref) {
			TextureAtlas atlas = ref.atlas().equals(AtlasIds.ITEMS) ? items : blocks;
			return atlas.getSprite(ref.sprite()) != atlas.missingSprite();
		}
	}

	private static final SpriteRef EMPTY_SLOT = new SpriteRef(AtlasIds.GUI, Identifier.withDefaultNamespace("container/slot"));

	/** Sprites found per item, for one stitch of the atlases. */
	private static final Map<Identifier, Optional<SpriteRef>> CACHE = new ConcurrentHashMap<>();

	/**
	 * The stitch {@link #CACHE} belongs to. The atlas objects live for the whole game, but every resource reload
	 * restitches them with new sprites, the missing sprite included, so its identity marks the stitch.
	 */
	private static volatile TextureAtlasSprite cachedStitch;

	private ItemIcons() {
	}

	/** The item with this id, if one exists. */
	public static Optional<Holder.Reference<Item>> find(Identifier id) {
		return BuiltInRegistries.ITEM.get(id);
	}

	/** The item's icon and name, or for an id that isn't an item, {@link #unknown}. */
	public static MutableComponent named(Identifier id) {
		return find(id).map(ItemIcons::named).orElseGet(() -> unknown(id));
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

	/** Something Claude named that isn't an item (a tag, a modded id that isn't loaded): its readable path. */
	public static MutableComponent unknown(Identifier id) {
		return Component.literal(readableName(id))
			.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(id.toString()))));
	}

	/** An id's last path segment as words: "minecraft:tags/oak_logs" → "oak logs". */
	public static String readableName(Identifier id) {
		return id.getPath().replaceAll(".*/", "").replace('_', ' ');
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

	/** The item's sprite; empty (and not remembered) while the atlases aren't ready. */
	private static Optional<SpriteRef> sprite(Holder<Item> item) {
		Optional<Identifier> id = item.unwrapKey().map(ResourceKey::identifier);
		Optional<Atlases> atlases = stitchedAtlases();
		if (id.isEmpty() || atlases.isEmpty()) {
			return Optional.empty();
		}

		forgetOlderStitch(atlases.get());

		try {
			return CACHE.computeIfAbsent(id.get(), key -> findSprite(item, key, atlases.get()));
		} catch (RuntimeException e) {
			// Thrown out of computeIfAbsent, so nothing is cached: a model mid-reload gets another try next time.
			ClaudeChatClient.LOGGER.debug("Could not find an icon for {}", id.get(), e);
			return Optional.empty();
		}
	}

	private static Optional<Atlases> stitchedAtlases() {
		try {
			AtlasManager manager = Minecraft.getInstance().getAtlasManager();
			Atlases atlases = new Atlases(manager.getAtlasOrThrow(AtlasIds.ITEMS), manager.getAtlasOrThrow(AtlasIds.BLOCKS));
			atlases.items().missingSprite(); // throws until the first stitch
			return Optional.of(atlases);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static void forgetOlderStitch(Atlases atlases) {
		TextureAtlasSprite stitch = atlases.items().missingSprite();
		if (stitch != cachedStitch) {
			CACHE.clear();
			cachedStitch = stitch;
		}
	}

	/**
	 * The flat texture named after the item first: multi-layer items such as potions put a tinted overlay on the
	 * model's first layer, which is the one the model offers as its particle. Then the item model's own texture, for
	 * items whose texture is named differently (compass, crossbow, many mods' items). Then a face of the block.
	 */
	private static Optional<SpriteRef> findSprite(Holder<Item> item, Identifier id, Atlases atlases) {
		SpriteRef flat = new SpriteRef(AtlasIds.ITEMS, id.withPrefix("item/"));
		if (atlases.has(flat)) {
			return Optional.of(flat);
		}

		Optional<SpriteRef> model = modelSprite(item, atlases);
		if (model.isPresent()) {
			return model;
		}

		List<SpriteRef> blockFaces = List.of(
			new SpriteRef(AtlasIds.BLOCKS, id.withPrefix("block/")),
			new SpriteRef(AtlasIds.BLOCKS, id.withPath(path -> "block/" + path + "_front")),
			new SpriteRef(AtlasIds.BLOCKS, id.withPath(path -> "block/" + path + "_side")),
			new SpriteRef(AtlasIds.BLOCKS, id.withPath(path -> "block/" + path + "_top")));
		return blockFaces.stream().filter(atlases::has).findFirst();
	}

	/**
	 * The texture the item model draws with, when it's a flat item texture. Block models are skipped: their
	 * texture is a face picked for particles (dirt for grass), and special models (chests, shields) give planks.
	 */
	private static Optional<SpriteRef> modelSprite(Holder<Item> item, Atlases atlases) {
		ItemStackRenderState state = new ItemStackRenderState();
		Minecraft.getInstance().getItemModelResolver()
			.updateForTopItem(state, new ItemStack(item.value()), ItemDisplayContext.GUI, null, null, 0);
		Material.Baked material = state.pickParticleMaterial(RandomSource.create(0));
		if (material == null) {
			return Optional.empty();
		}

		TextureAtlasSprite sprite = material.sprite();
		boolean flatItemTexture = sprite.atlasLocation().equals(atlases.items().location())
			&& sprite != atlases.items().missingSprite();
		return flatItemTexture ? Optional.of(new SpriteRef(AtlasIds.ITEMS, sprite.contents().name())) : Optional.empty();
	}
}
