package claudechat;

import claudechat.WorldIndex.Holder;
import claudechat.WorldIndex.Item;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads what holds items out of block entity, entity and player NBT: the same saved form whether it comes from a
 * region file or from the running game, so both sources give the same {@link Holder}s.
 */
final class HolderReader {
	/** Shulker boxes and bundles are opened two levels deep. */
	private static final int MAX_NESTING = 2;

	private HolderReader() {
	}

	/** A container block, or any block entity holding items or a name; empty if there's nothing worth listing. */
	static Optional<Holder> blockEntity(RegistryOps<Tag> ops, ResourceKey<Level> dimension, CompoundTag tag, Provenance provenance) {
		String name = customName(ops, tag);
		String loot = tag.getStringOr("LootTable", "");
		List<Item> items = items(ops, tag.getListOrEmpty("Items"));
		for (String single : List.of("item", "Book", "RecordItem")) {
			tag.getCompound(single).flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
		}
		if (items.isEmpty() && loot.isEmpty() && name == null) {
			return Optional.empty();
		}

		BlockPos pos = SavedChunk.blockEntityPos(tag);
		return Optional.of(new Holder(Holder.Kind.BLOCK, dimension, pos.getX(), pos.getY(), pos.getZ(), shortId(tag.getStringOr("id", "?")),
			name, loot.isEmpty() ? null : loot, false, items, provenance));
	}

	/** Entities worth finding: ones holding items (minecarts, item frames, armor stands, dropped items) or named or tamed. */
	static Optional<Holder> entity(RegistryOps<Tag> ops, ResourceKey<Level> dimension, String typeId, CompoundTag tag, Provenance provenance) {
		String type = shortId(typeId);
		String name = customName(ops, tag);
		boolean tamed = tag.contains("Owner");
		List<Item> items = items(ops, tag.getListOrEmpty("Items"));
		tag.getCompound("Item").flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
		if (type.equals("armor_stand")) {
			tag.getCompound("equipment").ifPresent(equipment -> {
				for (String slot : equipment.keySet()) {
					equipment.getCompound(slot).flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
				}
			});
		}
		String loot = tag.getStringOr("LootTable", "");
		if (items.isEmpty() && name == null && !tamed && loot.isEmpty()) {
			return Optional.empty();
		}

		ListTag pos = tag.getListOrEmpty("Pos");
		int x = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(0, 0)) : 0;
		int y = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(1, 0)) : 0;
		int z = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(2, 0)) : 0;
		return Optional.of(new Holder(Holder.Kind.ENTITY, dimension, x, y, z, type, name, loot.isEmpty() ? null : loot, tamed, items, provenance));
	}

	static List<Item> playerInventory(RegistryOps<Tag> ops, CompoundTag player) {
		return items(ops, player.getListOrEmpty("Inventory"));
	}

	static List<Item> playerEnderChest(RegistryOps<Tag> ops, CompoundTag player) {
		return items(ops, player.getListOrEmpty("EnderItems"));
	}

	private static List<Item> items(RegistryOps<Tag> ops, ListTag list) {
		List<Item> items = new ArrayList<>();
		list.compoundStream().forEach(tag -> item(ops, tag, 0).ifPresent(items::add));
		return items;
	}

	private static Optional<Item> item(RegistryOps<Tag> ops, CompoundTag tag, int depth) {
		Optional<ItemStack> parsed = ItemStack.CODEC.parse(ops, tag).result();
		if (parsed.isPresent()) {
			return item(parsed.get(), depth);
		}
		String id = tag.getStringOr("id", "");
		return id.isEmpty() ? Optional.empty() : Optional.of(new Item(id, id, tag.getIntOr("count", 1), List.of(), List.of()));
	}

	/** Name, count, enchantments, and what's inside shulker boxes and bundles. */
	private static Optional<Item> item(ItemStack stack, int depth) {
		if (stack.isEmpty()) {
			return Optional.empty();
		}

		List<String> enchantments = new ArrayList<>();
		for (ItemEnchantments enchants : new ItemEnchantments[] {stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
			if (enchants != null) {
				for (var entry : enchants.entrySet()) {
					enchantments.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
				}
			}
		}

		List<Item> contents = new ArrayList<>();
		if (depth < MAX_NESTING) {
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				container.nonEmptyItemCopyStream().forEach(inner -> item(inner, depth + 1).ifPresent(contents::add));
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				for (ItemStackTemplate inner : bundle.items()) {
					item(inner.create(), depth + 1).ifPresent(contents::add);
				}
			}
		}
		return Optional.of(new Item(stack.typeHolder().getRegisteredName(), stack.getHoverName().getString(), stack.getCount(), enchantments, contents));
	}

	private static String customName(RegistryOps<Tag> ops, CompoundTag tag) {
		Tag name = tag.get("CustomName");
		if (name == null) {
			return null;
		}
		return ComponentSerialization.CODEC.parse(ops, name).result().map(Component::getString).filter(s -> !s.isBlank()).orElse(null);
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
