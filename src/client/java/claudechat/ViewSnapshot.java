package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.util.Util;
import net.minecraft.client.Screenshot;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * The {@code look} tool: what the player sees, as data rather than pixels, so shaders and resource packs can't disguise
 * it. Casts one ray per cell of a grid laid over the screen through the camera's real projection, walking blocks
 * exactly (DDA) and clipping against block outline shapes, so a torch or a fence is hit where it really is. Sampling in
 * screen space means coverage follows on-screen size, like the player's eye, unlike rays spread evenly by angle from a
 * point (an explosion's), which drift apart with distance. Cells where the hit block or distance changes from a
 * neighbor are sampled 3×3 more, to catch small things on edges. Glass, panes and water let the ray continue, recording
 * what's behind. Entities get their own pass: in the view frustum, then line of sight to a few points on their box.
 * <p>
 * The data comes with a screenshot of the same frame. The screenshot shows the layout; the data names what's in it,
 * whatever shaders or resource packs make it look like. Screen positions in the data are percent from the image's
 * left and top edges, so the two line up.
 * <p>
 * Reads only the client's world, so it works on multiplayer servers too, and reports only what's in view and not
 * hidden behind opaque blocks: never more than the player could see themselves. Runs on the client thread.
 */
public final class ViewSnapshot {
	public static final String TOOL = "look";

	/** Rays across the screen before refining edges. Rows follow the window's shape. */
	private static final int COLUMNS = 49;
	/** Screenshots are scaled down by a whole factor to at most about this wide. */
	private static final int MAX_IMAGE_WIDTH = 1280;
	private static final TagKey<net.minecraft.world.level.block.Block> C_GLASS_BLOCKS =
		TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "glass_blocks"));
	private static final TagKey<net.minecraft.world.level.block.Block> C_GLASS_PANES =
		TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "glass_panes"));
	/** At most this many blocks along one ray: see-through layers plus the one that stops it. */
	private static final int MAX_LAYERS = 6;
	private static final int MAX_DETAILS = 20;
	private static final int MAX_ENTITIES = 50;

	public JsonObject definition() {
		return WorldTools.tool(TOOL,
			"What the player sees right now: a screenshot plus data about everything in it. The screenshot shows the "
				+ "layout, but shaders and resource packs can make blocks look like something else, so go by the data for "
				+ "what things are. Rays are cast through the player's screen, so only what's in view and not hidden behind "
				+ "opaque blocks is reported. Returns the camera (position, facing, field of view), the block or entity under "
				+ "the crosshair (the middle of the image), every visible kind of block (screen share, where it is in the "
				+ "image, nearest position, the area it spans, what it was seen through such as glass or water), sign text "
				+ "and custom names of visible blocks, and visible entities (where in the image, distance, health, held "
				+ "items). Image positions are percent from the image's left and top edges. The screenshot includes the "
				+ "HUD and any open chat or screen, which cover part of the view; the data doesn't. Use it for \"what am I "
				+ "looking at\", \"what's that\", or to understand the player's surroundings.",
			"""
			{"type": "object", "properties": {
			  "screenshot": {"type": "boolean", "description": "Include the screenshot (default true)."},
			  "max_distance": {"type": "integer", "description": "How far rays go, in blocks (default: render distance, at most 256; max 512)."}
			}}
			""");
	}

	/** The data, and the PNG of the same frame (null if not asked for or it couldn't be taken). */
	public record Result(JsonObject data, byte[] png) {
	}

	/** A world is loaded on the client: single-player or a server. */
	public boolean available() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null;
	}

	/**
	 * Call from any thread but the client's: waits for the client thread to take the snapshot, then for the GPU to
	 * hand back the screenshot.
	 */
	public Result call(JsonObject args) throws Exception {
		Integer maxDistance = args.has("max_distance") ? Math.max(8, Math.min(512, integer(args, "max_distance", 256))) : null;
		boolean screenshot = !args.has("screenshot") || args.get("screenshot").getAsBoolean();
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture<byte[]> png = new CompletableFuture<>();
		JsonObject data = mc.submit(() -> {
			JsonObject out = capture(mc, maxDistance);
			if (screenshot) {
				out.add("screenshot", startScreenshot(mc, png));
			}
			return out;
		}).get(20, TimeUnit.SECONDS);
		if (!screenshot) {
			return new Result(data, null);
		}
		try {
			return new Result(data, png.get(10, TimeUnit.SECONDS));
		} catch (Exception e) {
			ClaudeChatClient.LOGGER.warn("Couldn't take the screenshot for look", e);
			data.addProperty("screenshot", "couldn't be taken: " + e.getMessage());
			return new Result(data, null);
		}
	}

	/**
	 * Client thread. Reads the last rendered frame back from the GPU, scaled down by a whole factor, and completes
	 * {@code png} once it's encoded. Returns the image's description.
	 */
	private static JsonObject startScreenshot(Minecraft mc, CompletableFuture<byte[]> png) {
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		int factor = 1;
		for (int f = 2; f <= 4 && target.width / factor > MAX_IMAGE_WIDTH; f++) {
			if (target.width % f == 0 && target.height % f == 0) {
				factor = f;
			}
		}
		Screenshot.takeScreenshot(target, factor, image -> Util.ioPool().execute(() -> {
			try (image) {
				Path file = Files.createTempFile("claudechat-look", ".png");
				try {
					image.writeToFile(file);
					png.complete(Files.readAllBytes(file));
				} finally {
					Files.deleteIfExists(file);
				}
			} catch (Exception e) {
				png.completeExceptionally(e);
			}
		}));
		JsonObject o = new JsonObject();
		o.addProperty("width", target.width / factor);
		o.addProperty("height", target.height / factor);
		o.addProperty("crosshair", "50% 50%");
		return o;
	}

	// --- rays -----------------------------------------------------------------------------------------------------

	/** One block or fluid surface a ray hit. {@code label} is the block or fluid id, without "minecraft:". */
	private record Hit(String label, BlockPos pos, double distance, boolean seeThrough) {
	}

	/**
	 * Everything one ray hit, front to back. {@code shown} is what the player sees there: the first hit that isn't
	 * glass (water counts, since its surface is what the player sees), or null for nothing.
	 */
	private record Sample(List<Hit> hits, Hit shown) {
		String key() {
			return shown == null ? "" : shown.label();
		}

		double depth() {
			return shown == null ? Double.MAX_VALUE : shown.distance();
		}
	}

	/** The camera's frame: rays and screen projection both go through it. */
	private record View(Vec3 eye, Vec3 forward, Vec3 up, Vec3 right, double tanHalfV, double aspect, double range) {
		/** Ray direction through screen point x, y in -1..1 (right and up positive). */
		Vec3 ray(double x, double y) {
			return forward.add(up.scale(y * tanHalfV)).add(right.scale(x * tanHalfV * aspect)).normalize();
		}

		/** Screen point -1..1 of a world position, or null if it's behind the camera. */
		double[] project(Vec3 p) {
			Vec3 v = p.subtract(eye);
			double depth = v.dot(forward);
			if (depth <= 0.05) {
				return null;
			}
			return new double[] {v.dot(right) / (depth * tanHalfV * aspect), v.dot(up) / (depth * tanHalfV)};
		}
	}

	private static Sample trace(ClientLevel level, View view, Vec3 dir) {
		return trace(level, view.eye(), view.eye().add(dir.scale(view.range())));
	}

	/** Walks every block from {@code from} to {@code to}, recording hits until an opaque one stops the ray. */
	private static Sample trace(ClientLevel level, Vec3 from, Vec3 to) {
		List<Hit> hits = new ArrayList<>();
		// Water the camera is already in isn't a surface the player sees; a fluid counts only where the ray enters it.
		FluidState[] previousFluid = {level.getFluidState(BlockPos.containing(from))};
		BlockGetter.traverseBlocks(from, to, hits, (list, pos) -> {
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			List<Hit> here = new ArrayList<>(2);
			if (!state.isAir() && !invisible(state)) {
				BlockHitResult hit = state.getShape(level, pos).clip(from, to, pos);
				if (hit != null) {
					here.add(new Hit(blockLabel(state), pos.immutable(), from.distanceTo(hit.getLocation()), seeThrough(state)));
				}
			}
			if (!fluid.isEmpty() && !fluid.getType().isSame(previousFluid[0].getType())) {
				BlockHitResult hit = fluid.getShape(level, pos).clip(from, to, pos);
				if (hit != null) {
					here.add(new Hit(fluidLabel(fluid), pos.immutable(), from.distanceTo(hit.getLocation()), !fluid.is(FluidTags.LAVA)));
				}
			}
			previousFluid[0] = fluid;
			here.sort(Comparator.comparingDouble(Hit::distance));
			for (Hit hit : here) {
				list.add(hit);
				if (!hit.seeThrough() || list.size() >= MAX_LAYERS) {
					return Boolean.TRUE;
				}
			}
			return null;
		}, list -> Boolean.FALSE);
		Hit shown = null;
		for (Hit hit : hits) {
			if (!hit.seeThrough() || hit.label().equals("water")) {
				shown = hit;
				break;
			}
		}
		if (shown == null && !hits.isEmpty()) {
			shown = hits.getLast(); // Only glass all the way: draw the glass.
		}
		return new Sample(hits, shown);
	}

	/** Glass and panes: the player sees what's behind them. */
	private static boolean seeThrough(BlockState state) {
		return state.is(BlockTags.IMPERMEABLE) || state.is(C_GLASS_BLOCKS) || state.is(C_GLASS_PANES)
			|| state.getBlock() instanceof IronBarsBlock;
	}

	/** Blocks with a shape but nothing drawn in survival. */
	private static boolean invisible(BlockState state) {
		return state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT) || state.is(Blocks.STRUCTURE_VOID);
	}

	// --- the snapshot ---------------------------------------------------------------------------------------------

	/** Per block or fluid seen: screen share, positions, and what it was seen through. */
	private static final class Seen {
		final String label;
		double weight;
		/** Where it's shown, in screen coordinates -1..1. */
		double minX = 1, maxX = -1, minY = 1, maxY = -1;
		final Set<BlockPos> positions = new LinkedHashSet<>();
		final Set<String> through = new LinkedHashSet<>();
		BlockPos nearest;
		double nearestDistance = Double.MAX_VALUE;
		double farthest;

		Seen(String label) {
			this.label = label;
		}
	}

	private static JsonObject capture(Minecraft mc, Integer maxDistanceArg) {
		long start = System.nanoTime();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			throw new IllegalStateException("No world is open.");
		}
		Camera camera = mc.gameRenderer.mainCamera();
		double fov = camera.getFov();
		int width = Math.max(1, mc.getWindow().getWidth());
		int height = Math.max(1, mc.getWindow().getHeight());
		double aspect = (double) width / height;
		int range = maxDistanceArg != null ? maxDistanceArg : Math.min(256, mc.options.getEffectiveRenderDistance() * 16);
		Vec3 forward = new Vec3(camera.forwardVector());
		Vec3 up = new Vec3(camera.upVector());
		Vec3 right = new Vec3(camera.leftVector()).scale(-1);
		View view = new View(camera.position(), forward, up, right, Math.tan(Math.toRadians(fov) / 2), aspect, range);

		int columns = COLUMNS;
		int rows = Math.max(9, (int) Math.round(columns / aspect));
		Sample[][] grid = new Sample[rows][columns];
		Map<String, Seen> seen = new HashMap<>();
		int rays = 0;
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				grid[r][c] = trace(level, view, view.ray(cellX(c, columns, 0), cellY(r, rows, 0)));
				rays++;
			}
		}
		// Edges between different blocks, or distance jumps, get 3×3 samples; flat areas count their one ray for the cell.
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				if (!isEdge(grid, r, c)) {
					record(seen, grid[r][c], cellX(c, columns, 0), cellY(r, rows, 0), 1);
					continue;
				}
				for (int sy = -1; sy <= 1; sy++) {
					for (int sx = -1; sx <= 1; sx++) {
						double x = cellX(c, columns, sx / 3.0);
						double y = cellY(r, rows, sy / 3.0);
						Sample sample = sx == 0 && sy == 0 ? grid[r][c] : trace(level, view, view.ray(x, y));
						if (sx != 0 || sy != 0) {
							rays++;
						}
						record(seen, sample, x, y, 1 / 9.0);
					}
				}
			}
		}

		JsonObject out = new JsonObject();
		out.add("camera", cameraJson(mc, camera, view, fov, range));
		out.add("crosshair", crosshair(mc, level));

		List<Seen> byShare = seen.values().stream()
			.sorted(Comparator.comparingDouble((Seen s) -> -s.weight).thenComparingDouble(s -> s.nearestDistance)).toList();
		double total = rows * columns;
		JsonArray blocks = new JsonArray();
		for (Seen s : byShare) {
			blocks.add(seenJson(s, total, view));
		}
		out.add("blocks", blocks);
		JsonArray details = blockDetails(level, seen.values(), view.eye());
		if (!details.isEmpty()) {
			out.add("block_details", details);
		}
		out.add("entities", entitiesJson(visibleEntities(mc, level, view)));
		out.addProperty("rays", rays);
		out.addProperty("ms", (System.nanoTime() - start) / 1_000_000);
		return out;
	}

	/** Screen x of a cell's center, plus {@code offset} cells. */
	private static double cellX(int c, int columns, double offset) {
		return -1 + (2 * (c + 0.5 + offset)) / columns;
	}

	private static double cellY(int r, int rows, double offset) {
		return 1 - (2 * (r + 0.5 + offset)) / rows;
	}

	/** Screen x -1..1 as percent from the image's left edge. */
	private static long percentX(double x) {
		return Math.round((Math.clamp(x, -1, 1) + 1) / 2 * 100);
	}

	/** Screen y -1..1 (up) as percent from the image's top edge. */
	private static long percentY(double y) {
		return Math.round((1 - Math.clamp(y, -1, 1)) / 2 * 100);
	}

	/** "x 40%, y 62%" for a world position, or null when it's off screen. */
	private static String imagePoint(View view, Vec3 p) {
		double[] screen = view.project(p);
		if (screen == null || Math.abs(screen[0]) > 1 || Math.abs(screen[1]) > 1) {
			return null;
		}
		return "x " + percentX(screen[0]) + "%, y " + percentY(screen[1]) + "%";
	}

	private static boolean isEdge(Sample[][] grid, int r, int c) {
		Sample here = grid[r][c];
		int[][] neighbors = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
		for (int[] n : neighbors) {
			int nr = r + n[0];
			int nc = c + n[1];
			if (nr < 0 || nr >= grid.length || nc < 0 || nc >= grid[0].length) {
				continue;
			}
			Sample other = grid[nr][nc];
			if (!other.key().equals(here.key()) || other.hits().size() != here.hits().size()) {
				return true;
			}
			if (here.shown() != null && Math.max(here.depth(), other.depth()) > 1.5 * Math.min(here.depth(), other.depth()) + 1) {
				return true;
			}
		}
		return false;
	}

	/** Adds one ray's hits, cast through screen point x, y, standing for {@code weight} of a cell. */
	private static void record(Map<String, Seen> seen, Sample sample, double x, double y, double weight) {
		List<String> through = new ArrayList<>();
		for (Hit hit : sample.hits()) {
			Seen s = seen.computeIfAbsent(hit.label(), Seen::new);
			if (hit == sample.shown()) {
				s.weight += weight;
				s.minX = Math.min(s.minX, x);
				s.maxX = Math.max(s.maxX, x);
				s.minY = Math.min(s.minY, y);
				s.maxY = Math.max(s.maxY, y);
			}
			if (s.positions.size() < 4096) {
				s.positions.add(hit.pos());
			}
			if (hit.distance() < s.nearestDistance) {
				s.nearestDistance = hit.distance();
				s.nearest = hit.pos();
			}
			s.farthest = Math.max(s.farthest, hit.distance());
			through.stream().filter(label -> !label.equals(hit.label())).forEach(s.through::add);
			through.add(hit.label());
		}
	}

	private static JsonObject seenJson(Seen s, double totalCells, View view) {
		JsonObject o = new JsonObject();
		o.addProperty("block", s.label);
		double share = 100 * s.weight / totalCells;
		o.addProperty("screen_share", s.weight <= 0 ? "none: only seen partly, around other blocks" // Never the front-most hit.
			: share < 0.1 ? "under 0.1%" : String.format(java.util.Locale.ROOT, "%.1f%%", share));
		if (s.weight > 0) {
			o.addProperty("in_image", "x " + percentX(s.minX) + "-" + percentX(s.maxX) + "%, y "
				+ percentY(s.maxY) + "-" + percentY(s.minY) + "%");
		}
		o.addProperty("nearest", posText(s.nearest));
		o.addProperty("nearest_distance", Math.round(s.nearestDistance * 10) / 10.0);
		o.addProperty("farthest_distance", Math.round(s.farthest));
		if (s.positions.size() <= 6) {
			// Few enough to list each one, with where its center is in the image.
			JsonArray positions = new JsonArray();
			s.positions.stream().sorted(Comparator.comparingDouble((BlockPos p) -> Vec3.atCenterOf(p).distanceToSqr(view.eye())))
				.forEach(p -> {
					String point = imagePoint(view, Vec3.atCenterOf(p));
					positions.add(posText(p) + (point == null ? "" : " (image " + point + ")"));
				});
			o.add("positions", positions);
		} else {
			o.addProperty("blocks_seen", s.positions.size() >= 4096 ? "4096+" : String.valueOf(s.positions.size()));
			int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
			int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			for (BlockPos p : s.positions) {
				min[0] = Math.min(min[0], p.getX());
				min[1] = Math.min(min[1], p.getY());
				min[2] = Math.min(min[2], p.getZ());
				max[0] = Math.max(max[0], p.getX());
				max[1] = Math.max(max[1], p.getY());
				max[2] = Math.max(max[2], p.getZ());
			}
			o.addProperty("spans", "x " + min[0] + ".." + max[0] + ", y " + min[1] + ".." + max[1] + ", z " + min[2] + ".." + max[2]);
		}
		if (!s.through.isEmpty()) {
			o.addProperty("seen_through", String.join(", ", s.through));
		}
		return o;
	}

	/** Sign text and custom names of visible block entities, nearest first. */
	private static JsonArray blockDetails(ClientLevel level, Iterable<Seen> seen, Vec3 eye) {
		List<BlockPos> positions = new ArrayList<>();
		for (Seen s : seen) {
			positions.addAll(s.positions);
		}
		positions.sort(Comparator.comparingDouble((BlockPos p) -> Vec3.atCenterOf(p).distanceToSqr(eye)));
		JsonArray details = new JsonArray();
		for (BlockPos pos : positions) {
			if (details.size() >= MAX_DETAILS) {
				break;
			}
			BlockEntity blockEntity = level.getChunkAt(pos).getBlockEntities().get(pos);
			if (blockEntity == null) {
				continue;
			}
			JsonObject o = new JsonObject();
			if (blockEntity instanceof SignBlockEntity sign) {
				String front = signText(sign.getText(SignTextSlot.FRONT));
				String back = signText(sign.getText(SignTextSlot.BACK));
				if (!front.isEmpty()) {
					o.addProperty("front_text", front);
				}
				if (!back.isEmpty()) {
					o.addProperty("back_text", back);
				}
			}
			if (blockEntity instanceof Nameable nameable && nameable.getCustomName() != null) {
				o.addProperty("name", nameable.getCustomName().getString());
			}
			if (!o.isEmpty()) {
				JsonObject detail = new JsonObject();
				detail.addProperty("block", blockLabel(blockEntity.getBlockState()));
				detail.addProperty("position", posText(pos));
				o.entrySet().forEach(e -> detail.add(e.getKey(), e.getValue()));
				details.add(detail);
			}
		}
		return details;
	}

	private static String signText(SignText text) {
		return text.getMessages(false).stream().map(Component::getString).filter(line -> !line.isBlank())
			.collect(Collectors.joining(" / "));
	}

	// --- camera, crosshair, entities ------------------------------------------------------------------------------

	private static JsonObject cameraJson(Minecraft mc, Camera camera, View view, double fov, int range) {
		JsonObject o = new JsonObject();
		Vec3 eye = view.eye();
		o.addProperty("dimension", mc.level.dimension().identifier().toString());
		o.addProperty("eye_position", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", eye.x, eye.y, eye.z));
		o.addProperty("facing", WorldTools.compass(view.forward().x * 100, view.forward().z * 100));
		double pitch = camera.xRot();
		o.addProperty("looking", Math.abs(pitch) < 10 ? "level" : (pitch < 0 ? "up " : "down ") + Math.round(Math.abs(pitch)) + "°");
		o.addProperty("fov_vertical", Math.round(fov));
		o.addProperty("fov_horizontal", Math.round(Math.toDegrees(2 * Math.atan(view.tanHalfV() * view.aspect()))));
		o.addProperty("perspective", mc.options.getCameraType().isFirstPerson() ? "first person" : "third person");
		o.addProperty("ray_range", range);
		FluidState inFluid = mc.level.getFluidState(BlockPos.containing(eye));
		if (!inFluid.isEmpty()) {
			o.addProperty("camera_in", fluidLabel(inFluid));
		}
		return o;
	}

	private static JsonObject crosshair(Minecraft mc, ClientLevel level) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = level.getBlockState(block.getBlockPos());
			o.addProperty("block", blockLabel(state));
			o.addProperty("position", posText(block.getBlockPos()));
			o.addProperty("face", block.getDirection().getSerializedName());
			String properties = state.getValues().map(Object::toString).collect(Collectors.joining(", "));
			if (!properties.isEmpty()) {
				o.addProperty("state", properties);
			}
			o.addProperty("in_reach", true);
		} else if (hit instanceof EntityHitResult entity) {
			o.add("entity", entityJson(entity.getEntity(), mc.gameRenderer.mainCamera().position(), null));
			o.addProperty("in_reach", true);
		} else {
			o.addProperty("in_reach", false);
			o.addProperty("note", "Nothing within reach; the middle cell of the screen picture shows what's farther away.");
		}
		return o;
	}

	/** Entities in view with a line of sight to some part of them, nearest first. */
	private static List<Map.Entry<Entity, JsonObject>> visibleEntities(Minecraft mc, ClientLevel level, View view) {
		Entity self = mc.getCameraEntity();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		var frustum = mc.gameRenderer.mainCamera().getCullFrustum();
		List<Map.Entry<Entity, JsonObject>> visible = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if ((entity == self && firstPerson) || entity.isInvisibleTo(mc.player)) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			if (box.getCenter().distanceTo(view.eye()) > view.range() || !frustum.isVisible(box)) {
				continue;
			}
			// Line of sight to the middle, top, bottom and four side points of its box.
			Vec3 c = box.getCenter();
			double dx = box.getXsize() * 0.35;
			double dz = box.getZsize() * 0.35;
			Vec3[] points = {c, new Vec3(c.x, box.maxY - 0.05, c.z), new Vec3(c.x, box.minY + 0.05, c.z),
				new Vec3(c.x + dx, c.y, c.z + dz), new Vec3(c.x - dx, c.y, c.z - dz), new Vec3(c.x + dx, c.y, c.z - dz), new Vec3(c.x - dx, c.y, c.z + dz)};
			int seen = 0;
			for (Vec3 point : points) {
				double[] screen = view.project(point);
				if (screen == null || Math.abs(screen[0]) > 1 || Math.abs(screen[1]) > 1) {
					continue;
				}
				Sample sample = trace(level, view.eye(), point);
				double distance = point.distanceTo(view.eye());
				if (sample.hits().stream().noneMatch(h -> !h.seeThrough() && h.distance() < distance - 0.05)) {
					seen++;
				}
			}
			if (seen == 0) {
				continue;
			}
			JsonObject o = entityJson(entity, view.eye(), view);
			o.addProperty("visible", seen == points.length ? "fully" : "partly");
			visible.add(Map.entry(entity, o));
		}
		visible.sort(Comparator.comparingDouble(e -> e.getKey().getBoundingBox().getCenter().distanceToSqr(view.eye())));
		return visible;
	}

	private static JsonArray entitiesJson(List<Map.Entry<Entity, JsonObject>> visible) {
		JsonArray out = new JsonArray();
		visible.stream().limit(MAX_ENTITIES).forEach(e -> out.add(e.getValue()));
		if (visible.size() > MAX_ENTITIES) {
			JsonObject more = new JsonObject();
			more.addProperty("more", visible.size() - MAX_ENTITIES + " farther entities not listed");
			out.add(more);
		}
		return out;
	}

	private static JsonObject entityJson(Entity entity, Vec3 eye, View view) {
		JsonObject o = new JsonObject();
		o.addProperty("type", shortId(EntityType.getKey(entity.getType())));
		if (entity instanceof Player player) {
			o.addProperty("name", player.getGameProfile().name());
		} else if (entity.getCustomName() != null) {
			o.addProperty("name", entity.getCustomName().getString());
		}
		Vec3 center = entity.getBoundingBox().getCenter();
		o.addProperty("position", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", entity.getX(), entity.getY(), entity.getZ()));
		o.addProperty("distance", Math.round(center.distanceTo(eye) * 10) / 10.0);
		if (view != null) {
			// The box its bounding box covers in the image, from its eight corners.
			AABB box = entity.getBoundingBox();
			double minX = 1, maxX = -1, minY = 1, maxY = -1;
			boolean behind = false;
			for (int corner = 0; corner < 8; corner++) {
				double[] screen = view.project(new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
					(corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ));
				if (screen == null) {
					behind = true;
					break;
				}
				minX = Math.min(minX, screen[0]);
				maxX = Math.max(maxX, screen[0]);
				minY = Math.min(minY, screen[1]);
				maxY = Math.max(maxY, screen[1]);
			}
			String point = imagePoint(view, center);
			if (!behind) {
				o.addProperty("in_image", "x " + percentX(minX) + "-" + percentX(maxX) + "%, y " + percentY(maxY) + "-" + percentY(minY) + "%");
			} else if (point != null) {
				o.addProperty("in_image", point);
			}
		}
		if (entity instanceof LivingEntity living) {
			o.addProperty("health", Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth()));
			if (living.isBaby()) {
				o.addProperty("baby", true);
			}
			JsonArray held = new JsonArray();
			for (ItemStack stack : new ItemStack[] {living.getMainHandItem(), living.getOffhandItem()}) {
				if (!stack.isEmpty()) {
					held.add(itemText(stack));
				}
			}
			if (!held.isEmpty()) {
				o.add("holding", held);
			}
		}
		if (entity instanceof ItemEntity item) {
			o.addProperty("item", itemText(item.getItem()));
		} else if (entity instanceof ItemFrame frame && !frame.getItem().isEmpty()) {
			o.addProperty("item", itemText(frame.getItem()));
		}
		if (entity.getVehicle() != null) {
			o.addProperty("riding", shortId(EntityType.getKey(entity.getVehicle().getType())));
		}
		return o;
	}

	// --- labels ---------------------------------------------------------------------------------------------------

	private static String blockLabel(BlockState state) {
		return shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
	}

	private static String fluidLabel(FluidState fluid) {
		if (fluid.is(FluidTags.WATER)) {
			return "water";
		}
		if (fluid.is(FluidTags.LAVA)) {
			return "lava";
		}
		return shortId(BuiltInRegistries.FLUID.getKey(fluid.getType() == Fluids.EMPTY ? Fluids.EMPTY : fluid.getType()));
	}

	private static String itemText(ItemStack stack) {
		String id = shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		String name = stack.getHoverName().getString();
		return (stack.getCount() > 1 ? stack.getCount() + " " : "") + id
			+ (stack.getCustomName() != null ? " named \"" + name + "\"" : "");
	}

	/** "stone" for vanilla ids, the full id for mods. */
	private static String shortId(Identifier id) {
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}

	private static String posText(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static int integer(JsonObject args, String key, int fallback) {
		try {
			return args != null && args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsInt() : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
