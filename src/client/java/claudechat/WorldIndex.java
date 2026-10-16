package claudechat;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A searchable text index of what's stored where in the current single-player world: every container with its contents
 * (including items inside shulker boxes and bundles), unopened loot chests, entities that hold items or are named or
 * tamed (chest minecarts, item frames, armor stands, dropped items, pets), and the player's inventory and ender chest.
 * <p>
 * Saved chunks are read straight from the region files (read-only; only chunks whose save timestamp changed are
 * re-read). Chunks that are loaded right now are captured live from the server instead, since the save on disk can be
 * up to an autosave (5 minutes) behind. Written to knowledge/world/&lt;world folder&gt;/index.txt before each question.
 */
public class WorldIndex {
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int MAX_NESTING = 2;

	/** One chunk of one dimension; block entities and entities are stored in separate region folders. */
	private record ChunkKey(String dimension, int x, int z, boolean entities) {
	}

	private final KnowledgeBase knowledge;
	private volatile MinecraftServer server;
	private volatile Path indexFile;
	private volatile String relativePath;
	/** Last-seen save timestamp per chunk, so rescans only parse what changed. */
	private final Map<ChunkKey, Integer> timestamps = new ConcurrentHashMap<>();
	private final Map<ChunkKey, List<String>> savedLines = new ConcurrentHashMap<>();

	public WorldIndex(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	/** A single-player world opened. Scans it in the background so the first question is quick. */
	public void start(MinecraftServer server) {
		this.server = server;
		timestamps.clear();
		savedLines.clear();
		String world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
		relativePath = "world/" + world.replaceAll("[^A-Za-z0-9._+-]", "_") + "/index.txt";
		indexFile = knowledge.root().resolve(relativePath);
		knowledge.setWorldIndex(relativePath);
		Thread thread = new Thread(() -> {
			try {
				refresh();
			} catch (Exception e) {
				ClaudeChatClient.LOGGER.warn("Could not index the world", e);
			}
		}, "Claude Chat world index");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	public void stop() {
		server = null;
		knowledge.setWorldIndex(null);
	}

	public boolean isActive() {
		return server != null;
	}

	/** Rescan changed chunks, capture the live ones, and rewrite the index. Call off the server and render threads. */
	public synchronized void refresh() throws Exception {
		MinecraftServer server = this.server;
		if (server == null) {
			return;
		}
		long start = System.nanoTime();
		RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		int changed = 0;
		regionsSeen = 0;
		chunksSeen = 0;
		for (ServerLevel level : server.getAllLevels()) {
			Path dimensionDir = DimensionType.getStorageFolder(level.dimension(), worldRoot);
			changed += scanRegions(server, ops, dimensionName(level.dimension()), dimensionDir.resolve("region"), false);
			changed += scanRegions(server, ops, dimensionName(level.dimension()), dimensionDir.resolve("entities"), true);
		}

		Live live = server.submit(() -> captureLive(server, ops)).get(20, TimeUnit.SECONDS);
		write(server, live);
		ClaudeChatClient.LOGGER.info("World index: {} region files, {} saved chunks ({} re-read), {} live lines, {} ms",
			regionsSeen, chunksSeen, changed, live.lines.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
	}

	private int regionsSeen;
	private int chunksSeen;

	// --- saved chunks ---------------------------------------------------------------------------------------------

	private int scanRegions(MinecraftServer server, RegistryOps<Tag> ops, String dimension, Path dir, boolean entities) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		int changed = 0;
		List<Path> regions;
		try (Stream<Path> files = Files.list(dir)) {
			regions = files.filter(f -> REGION_NAME.matcher(f.getFileName().toString()).matches()).toList();
		} catch (IOException e) {
			return 0;
		}
		for (Path region : regions) {
			Matcher m = REGION_NAME.matcher(region.getFileName().toString());
			m.matches();
			int regionX = Integer.parseInt(m.group(1));
			int regionZ = Integer.parseInt(m.group(2));
			try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
				if (channel.size() < 8192) {
					continue;
				}
				ByteBuffer header = ByteBuffer.allocate(8192);
				readFully(channel, header, 0);
				regionsSeen++;
				for (int i = 0; i < 1024; i++) {
					int location = header.getInt(i * 4);
					if (location != 0) {
						chunksSeen++;
					}
					ChunkKey key = new ChunkKey(dimension, regionX * 32 + (i & 31), regionZ * 32 + (i >> 5), entities);
					if (location == 0) {
						if (savedLines.remove(key) != null) {
							timestamps.remove(key);
						}
						continue;
					}
					int timestamp = header.getInt(4096 + i * 4);
					if (Integer.valueOf(timestamp).equals(timestamps.get(key))) {
						continue;
					}
					// The game may be writing this region right now; a torn read just fails and is retried next time.
					try {
						CompoundTag chunk = readChunk(channel, region, location, key);
						if (chunk != null) {
							savedLines.put(key, describeChunk(server, ops, dimension, chunk, entities));
							timestamps.put(key, timestamp);
							changed++;
						}
					} catch (IOException | RuntimeException e) {
						logFailure("Skipping chunk " + key + " for now", e);
					}
				}
			} catch (IOException e) {
				logFailure("Could not read region " + region, e);
			}
		}
		return changed;
	}

	private int failuresLogged;

	/** Warn about the first few failures (a torn read now and then is normal; a failure on every chunk is a bug). */
	private void logFailure(String message, Exception e) {
		if (failuresLogged++ < 3) {
			ClaudeChatClient.LOGGER.warn(message, e);
		} else {
			ClaudeChatClient.LOGGER.debug(message, e);
		}
	}

	private static CompoundTag readChunk(FileChannel channel, Path region, int location, ChunkKey key) throws IOException {
		long offset = (long) (location >>> 8) * 4096;
		// While the game runs, the region file isn't padded to whole sectors yet (that happens when it's closed),
		// so read the chunk's own length rather than assuming its last sector is complete.
		ByteBuffer prefix = ByteBuffer.allocate(5);
		readFully(channel, prefix, offset);
		int length = prefix.getInt(0);
		byte type = prefix.get(4);
		if (length <= 1 || length > (location & 0xFF) * 4096) {
			return null;
		}
		ByteBuffer data = ByteBuffer.allocate(4 + length);
		readFully(channel, data, offset);
		InputStream raw;
		if ((type & 0x80) != 0) {
			// Oversized chunk stored next to the region file as c.<x>.<z>.mcc
			Path external = region.resolveSibling("c." + key.x() + "." + key.z() + ".mcc");
			raw = Files.newInputStream(external);
		} else {
			raw = new ByteArrayInputStream(data.array(), 5, length - 1);
		}
		RegionFileVersion version = RegionFileVersion.fromId(type & 0x7F);
		if (version == null) {
			raw.close();
			return null;
		}
		try (DataInputStream in = new DataInputStream(version.wrap(raw))) {
			return NbtIo.read(in);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, position + buffer.position()) < 0) {
				throw new IOException("unexpected end of region file at " + (position + buffer.position()) + " of " + channel.size());
			}
		}
	}

	private List<String> describeChunk(MinecraftServer server, RegistryOps<Tag> ops, String dimension, CompoundTag chunk, boolean entities) {
		chunk = upgrade(server, chunk, entities ? DataFixTypes.ENTITY_CHUNK : DataFixTypes.CHUNK);
		List<String> lines = new ArrayList<>();
		if (entities) {
			for (CompoundTag entity : compounds(chunk.getListOrEmpty("Entities"))) {
				addIfPresent(lines, describeEntity(ops, dimension, entity.getStringOr("id", "?"), entity));
			}
		} else {
			for (CompoundTag blockEntity : compounds(chunk.getListOrEmpty("block_entities"))) {
				addIfPresent(lines, describeBlockEntity(ops, dimension, blockEntity));
			}
		}
		return lines;
	}

	/** Chunks saved by older versions are upgraded with the game's own data fixers; if that fails, use them as they are. */
	private static CompoundTag upgrade(MinecraftServer server, CompoundTag chunk, DataFixTypes type) {
		int current = SharedConstants.getCurrentVersion().dataVersion().version();
		int version = NbtUtils.getDataVersion(chunk, current);
		if (version >= current) {
			return chunk;
		}
		try {
			return type.updateToCurrentVersion(server.getFixerUpper(), chunk, version);
		} catch (RuntimeException e) {
			return chunk;
		}
	}

	// --- live state -----------------------------------------------------------------------------------------------

	private record Live(Set<ChunkKey> loaded, List<String> lines, List<String> players) {
	}

	/** Server thread: what's in the chunks around players right now, plus the players themselves. */
	private static Live captureLive(MinecraftServer server, RegistryOps<Tag> ops) {
		Set<ChunkKey> loaded = new HashSet<>();
		List<String> lines = new ArrayList<>();
		List<String> players = new ArrayList<>();
		int radius = server.getPlayerList().getViewDistance() + 1;

		for (ServerLevel level : server.getAllLevels()) {
			String dimension = dimensionName(level.dimension());
			Set<Long> seen = new HashSet<>();
			for (ServerPlayer player : level.players()) {
				int px = player.chunkPosition().x();
				int pz = player.chunkPosition().z();
				for (int cx = px - radius; cx <= px + radius; cx++) {
					for (int cz = pz - radius; cz <= pz + radius; cz++) {
						if (!seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) {
							continue;
						}
						LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
						if (chunk == null) {
							continue;
						}
						loaded.add(new ChunkKey(dimension, cx, cz, false));
						loaded.add(new ChunkKey(dimension, cx, cz, true));
						for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
							try {
								addIfPresent(lines, describeBlockEntity(ops, dimension, blockEntity.saveWithFullMetadata(server.registryAccess())));
							} catch (RuntimeException e) {
								ClaudeChatClient.LOGGER.debug("Skipping block entity at {}", blockEntity.getBlockPos(), e);
							}
						}
					}
				}
			}
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Player || !loaded.contains(new ChunkKey(dimension, entity.chunkPosition().x(), entity.chunkPosition().z(), true))) {
					continue;
				}
				try {
					addIfPresent(lines, describeEntity(ops, dimension, EntityType.getKey(entity.getType()).toString(), save(server, entity)));
				} catch (RuntimeException e) {
					ClaudeChatClient.LOGGER.debug("Skipping entity {}", entity, e);
				}
			}
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CompoundTag tag = save(server, player);
			String who = "[player] " + player.getName().getString();
			players.add(who + " | at " + dimensionName(player.level().dimension()) + " " + player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ());
			players.add(who + " | inventory | " + describeItems(ops, tag.getListOrEmpty("Inventory")));
			players.add(who + " | ender chest | " + describeItems(ops, tag.getListOrEmpty("EnderItems")));
		}
		return new Live(loaded, lines, players);
	}

	private static CompoundTag save(MinecraftServer server, Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		entity.saveWithoutId(output);
		return output.buildResult();
	}

	// --- describing things ----------------------------------------------------------------------------------------

	/** "[overworld] 120 64 -340 | chest "Ores" | 64 Diamond (minecraft:diamond); ..." or null if there's nothing to say. */
	private static String describeBlockEntity(RegistryOps<Tag> ops, String dimension, CompoundTag tag) {
		String type = shortId(tag.getStringOr("id", "?"));
		String name = customName(ops, tag);
		String loot = tag.getStringOr("LootTable", "");
		List<String> items = new ArrayList<>();
		collectItems(ops, tag.getListOrEmpty("Items"), items);
		for (String single : List.of("item", "Book", "RecordItem")) {
			tag.getCompound(single).ifPresent(item -> addIfPresent(items, describeItem(ops, item, 0)));
		}
		if (items.isEmpty() && loot.isEmpty() && name == null) {
			return null;
		}
		String contents = !loot.isEmpty() ? "unopened, will fill from loot table " + loot : String.join("; ", merge(items));
		return "[" + dimension + "] " + tag.getIntOr("x", 0) + " " + tag.getIntOr("y", 0) + " " + tag.getIntOr("z", 0)
			+ " | " + type + (name != null ? " \"" + name + "\"" : "") + " | " + (contents.isEmpty() ? "empty" : contents);
	}

	/** Entities worth finding: ones holding items (minecarts, item frames, armor stands, dropped items) or named or tamed ones. */
	private static String describeEntity(RegistryOps<Tag> ops, String dimension, String typeId, CompoundTag tag) {
		String type = shortId(typeId);
		String name = customName(ops, tag);
		boolean tamed = tag.contains("Owner");
		List<String> items = new ArrayList<>();
		collectItems(ops, tag.getListOrEmpty("Items"), items);
		tag.getCompound("Item").ifPresent(item -> addIfPresent(items, describeItem(ops, item, 0)));
		if (type.equals("armor_stand")) {
			tag.getCompound("equipment").ifPresent(equipment -> {
				for (String slot : equipment.keySet()) {
					equipment.getCompound(slot).ifPresent(item -> addIfPresent(items, describeItem(ops, item, 0)));
				}
			});
		}
		String loot = tag.getStringOr("LootTable", "");
		if (items.isEmpty() && name == null && !tamed && loot.isEmpty()) {
			return null;
		}
		ListTag pos = tag.getListOrEmpty("Pos");
		String where = pos.size() == 3
			? (int) Math.floor(pos.getDoubleOr(0, 0)) + " " + (int) Math.floor(pos.getDoubleOr(1, 0)) + " " + (int) Math.floor(pos.getDoubleOr(2, 0))
			: "? ? ?";
		String contents = !loot.isEmpty() ? "unopened, will fill from loot table " + loot : String.join("; ", merge(items));
		return "[" + dimension + "] " + where + " | entity " + type + (name != null ? " \"" + name + "\"" : "") + (tamed ? " (tamed)" : "")
			+ (contents.isEmpty() ? "" : " | " + contents);
	}

	private static String describeItems(RegistryOps<Tag> ops, ListTag list) {
		List<String> items = new ArrayList<>();
		collectItems(ops, list, items);
		return items.isEmpty() ? "empty" : String.join("; ", merge(items));
	}

	private static void collectItems(RegistryOps<Tag> ops, ListTag list, List<String> into) {
		for (CompoundTag item : compounds(list)) {
			addIfPresent(into, describeItem(ops, item, 0));
		}
	}

	/** "64 Diamond (minecraft:diamond)", plus custom names, enchantments and what's inside shulker boxes and bundles. */
	private static String describeItem(RegistryOps<Tag> ops, CompoundTag tag, int depth) {
		Optional<ItemStack> parsed = ItemStack.CODEC.parse(ops, tag).result();
		if (parsed.isEmpty()) {
			String id = tag.getStringOr("id", "");
			return id.isEmpty() ? null : tag.getIntOr("count", 1) + " " + id;
		}
		return describeStack(parsed.get(), depth);
	}

	private static String describeStack(ItemStack stack, int depth) {
		if (stack.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(stack.getCount()).append(' ').append(stack.getHoverName().getString())
			.append(" (").append(stack.typeHolder().getRegisteredName()).append(')');

		List<String> enchantments = new ArrayList<>();
		for (ItemEnchantments enchants : new ItemEnchantments[] {stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
			if (enchants != null) {
				for (var entry : enchants.entrySet()) {
					Holder<Enchantment> enchantment = entry.getKey();
					enchantments.add(Enchantment.getFullname(enchantment, entry.getIntValue()).getString());
				}
			}
		}
		if (!enchantments.isEmpty()) {
			sb.append(" {").append(String.join(", ", enchantments)).append('}');
		}

		if (depth < MAX_NESTING) {
			List<String> inside = new ArrayList<>();
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				container.nonEmptyItemCopyStream().forEach(item -> addIfPresent(inside, describeStack(item, depth + 1)));
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				for (ItemStackTemplate item : bundle.items()) {
					addIfPresent(inside, describeStack(item.create(), depth + 1));
				}
			}
			if (!inside.isEmpty()) {
				sb.append(" [contains: ").append(String.join("; ", merge(inside))).append(']');
			}
		}
		return sb.toString();
	}

	/** Combine identical entries: two stacks of "32 Diamond (minecraft:diamond)" become "64 Diamond (...)". */
	private static List<String> merge(List<String> items) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String item : items) {
			int space = item.indexOf(' ');
			int count = 1;
			String rest = item;
			if (space > 0) {
				try {
					count = Integer.parseInt(item.substring(0, space));
					rest = item.substring(space + 1);
				} catch (NumberFormatException ignored) {
				}
			}
			counts.merge(rest, count, Integer::sum);
		}
		List<String> out = new ArrayList<>();
		counts.forEach((item, count) -> out.add(count + " " + item));
		return out;
	}

	private static String customName(RegistryOps<Tag> ops, CompoundTag tag) {
		Tag name = tag.get("CustomName");
		if (name == null) {
			return null;
		}
		return ComponentSerialization.CODEC.parse(ops, name).result().map(Component::getString).filter(s -> !s.isBlank()).orElse(null);
	}

	private static List<CompoundTag> compounds(ListTag list) {
		return list.compoundStream().toList();
	}

	private static String dimensionName(ResourceKey<Level> dimension) {
		return dimension.identifier().getNamespace().equals("minecraft") ? dimension.identifier().getPath() : dimension.identifier().toString();
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	private static void addIfPresent(List<String> list, String value) {
		if (value != null) {
			list.add(value);
		}
	}

	// --- output ---------------------------------------------------------------------------------------------------

	private void write(MinecraftServer server, Live live) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("# What's stored where in the world \"" + server.getWorldData().getLevelName() + "\", written "
			+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ".");
		lines.add("# One line per container, entity or inventory: [dimension] x y z | what | contents.");
		lines.add("# Chunks near the player are live; the rest is from the last save (up to ~5 minutes old).");
		lines.addAll(live.players());
		Map<ChunkKey, List<String>> saved = new HashMap<>(savedLines);
		saved.forEach((key, chunkLines) -> {
			if (!live.loaded().contains(key)) {
				lines.addAll(chunkLines);
			}
		});
		lines.addAll(live.lines());
		Files.createDirectories(indexFile.getParent());
		Path tmp = indexFile.resolveSibling("index.txt.tmp");
		Files.write(tmp, lines, StandardCharsets.UTF_8);
		Files.move(tmp, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
}
