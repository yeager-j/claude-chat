package claudechat;

import claudechat.SightTracer.Hit;
import claudechat.SightTracer.Layer;
import claudechat.SightTracer.Sample;
import claudechat.ViewFrame.Ray;
import claudechat.ViewFrame.ScreenPoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The {@code look} tool: what the player sees, as data rather than pixels, so shaders and resource packs can't disguise
 * it. Casts one ray per cell of a grid laid over the image through the projection the last frame was drawn with
 * ({@link ViewFrame}), walking blocks exactly ({@link SightTracer}) and meeting each block where its model is drawn
 * ({@link BlockSurfaces}), so a torch, a glass pane's frame or a leaf is hit where it really is. Sampling in screen
 * space means coverage follows on-screen size, like the player's eye. Cells where what's seen or its distance changes
 * from a neighbor are sampled 3×3 more, to catch small things on edges. Entities get their own pass: line of sight to a
 * few points on their box.
 * <p>
 * The data comes with a screenshot of the same frame. Screen positions in the data are percent from the image's left
 * and top edges, so the two line up.
 * <p>
 * Reads only the client's world, so it works on multiplayer servers too. It never reports more than the player could
 * see themselves:
 * <ul>
 * <li>only what's in view and not hidden behind something drawn opaque;</li>
 * <li>nothing past the frame's fog: the render distance, water or lava around the camera, Blindness or Darkness;</li>
 * <li>nothing too dark to make out ({@link SightTracer#bright}), unless the player has night vision; this also limits
 * how deep into water the player sees;</li>
 * <li>only the side of a sign that faces the player, and no block entity names, which aren't shown in the world;</li>
 * <li>no other player's health.</li>
 * </ul>
 * Carved out on purpose: mob health (the game sends it to every client, and HUD mods show it), entity names (shown when
 * the player looks at the entity), and the HUD and open screens, which cover part of the screenshot but not the data.
 * Runs on the client thread.
 */
public final class ViewSnapshot {
	public static final String TOOL = "look";

	/** Rays across the image before refining edges. Rows follow the image's shape. */
	private static final int COLUMNS = 49;
	/** Screenshots are scaled down by a whole factor to at most about this wide. */
	private static final int MAX_IMAGE_WIDTH = 1280;
	private static final int MAX_DETAILS = 20;
	private static final int MAX_ENTITIES = 50;
	/** Positions kept per kind of block, to bound memory on wide views; past it, counts read "4096+". */
	private static final int MAX_POSITIONS = 4096;
	/** A kind of block seen at this many positions or fewer lists them; more get a count and a bounding box. */
	private static final int MAX_LISTED_POSITIONS = 6;
	/** Points on an entity's box checked for line of sight. */
	private static final int SIGHT_POINTS = 7;

	private static final Param<Boolean> SCREENSHOT = Param.flag("screenshot", "Include the screenshot.", true);
	private static final Param<Integer> MAX_DISTANCE = Param.integer("max_distance",
		"How far rays go, in blocks; never past what fog and the render distance let the player see.", 256, 8, 512);

	/** The look tool, offered while a world is loaded on the client: single-player or a server. */
	public Tool tool() {
		Tool.Requirement anyWorld = new Tool.Requirement(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level != null && mc.player != null;
		}, "No world is open, so there's nothing to look at.");
		return Tool.of(TOOL,
			"What the player sees right now: a screenshot plus data about everything in it. The screenshot shows the "
				+ "layout, but shaders and resource packs can make blocks look like something else, so go by the data for "
				+ "what things are. Rays are cast through the player's screen, so only what the player could see is "
				+ "reported: nothing hidden behind opaque blocks, past fog, water or a Blindness or Darkness effect, or too "
				+ "dark to make out (too_dark says how much of the view is). Returns the camera (position, facing, field of "
				+ "view, how far rays went and why), what the player is aiming at (in first person that's under the "
				+ "crosshair, the middle of the image), every visible kind of block (screen share, where it is in the "
				+ "image, nearest position, the area it spans, what it was seen through such as glass or water), the "
				+ "text on the sides of visible signs facing the player, and visible entities (where in the image, "
				+ "distance, mob health, held items). Image positions are percent from the image's left and top edges. "
				+ "The screenshot includes the HUD and any open chat or screen, which cover part of the view; the data "
				+ "doesn't. Use it for \"what am I looking at\", \"what's that\", or to understand the player's "
				+ "surroundings.",
			anyWorld, List.of(SCREENSHOT, MAX_DISTANCE),
			(arguments, context) -> look(arguments.get(MAX_DISTANCE), arguments.get(SCREENSHOT)));
	}

	/**
	 * The data, and the PNG of the same frame unless not asked for or it couldn't be taken. Call from any thread but
	 * the client's: waits for the client thread to take the snapshot, then for the GPU to hand back the screenshot.
	 */
	private static Tool.Result look(int requestedRange, boolean screenshot) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture<byte[]> png = new CompletableFuture<>();

		JsonObject data = mc.submit(() -> {
			JsonObject out = capture(mc, requestedRange);
			if (screenshot) {
				out.add("screenshot", startScreenshot(mc, png));
			}

			return out;
		}).get(20, TimeUnit.SECONDS);

		if (!screenshot) {
			return Tool.Result.of(data);
		}

		try {
			return new Tool.Result(data, png.get(10, TimeUnit.SECONDS));
		} catch (Exception e) {
			ClaudeChatClient.LOGGER.warn("Couldn't take the screenshot for look", e);
			data.addProperty("screenshot", "couldn't be taken: " + McpServer.errorText(e));
			return Tool.Result.of(data);
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
			// NativeImage encodes PNGs only into a file (its channel writer is private), so go through a temp file.
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
		if (mc.gameRenderer.gameRenderState().optionsRenderState.cameraType.isFirstPerson()) {
			o.addProperty("crosshair", "50% 50%");
		}

		return o;
	}

	// --- the snapshot ---------------------------------------------------------------------------------------------

	private static JsonObject capture(Minecraft mc, int requestedRange) {
		long start = System.nanoTime();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			throw new IllegalStateException("No world is open.");
		}

		GameRenderState state = mc.gameRenderer.gameRenderState();
		ViewFrame frame = ViewFrame.lastRendered(mc);
		double range = Math.min(requestedRange, frame.sightDistance());
		boolean nightVision = state.lightmapRenderState.nightVisionEffectIntensity > 0;
		BlockSurfaces surfaces = new BlockSurfaces(mc, level, frame.eye(), state.optionsRenderState.cutoutLeaves);
		SightTracer tracer = new SightTracer(level, surfaces, nightVision);
		boolean firstPerson = state.optionsRenderState.cameraType.isFirstPerson();
		Scan scan = scan(tracer, frame, range);

		JsonObject out = new JsonObject();
		out.add("camera", cameraJson(mc, level, frame, firstPerson, range, requestedRange));
		out.add("crosshair", crosshairJson(mc, level, frame, tracer, firstPerson));
		out.add("blocks", blocksJson(scan, frame));
		if (scan.darkWeight > 0) {
			out.addProperty("too_dark", share(scan.darkWeight, scan.cells) + " of the view is too dark to make out");
		}

		JsonArray signs = signsJson(mc, level, scan.signs, frame.eye());
		if (!signs.isEmpty()) {
			out.add("block_details", signs);
		}

		addEntities(out, visibleEntities(mc, level, frame, tracer, range, firstPerson));
		out.addProperty("rays", scan.rays);
		out.addProperty("ms", (System.nanoTime() - start) / 1_000_000);
		return out;
	}

	/** Per block or fluid seen: screen share, where, and what it was seen through. */
	private static final class Seen {
		final Identifier id;
		double weight;
		final ImageArea area = new ImageArea();
		final Set<BlockPos> positions = new LinkedHashSet<>();
		final Set<Identifier> through = new LinkedHashSet<>();
		BlockPos nearest;
		double nearestDistance = Double.MAX_VALUE;
		double farthest;

		Seen(Identifier id) {
			this.id = id;
		}

		void add(Hit hit) {
			if (positions.size() < MAX_POSITIONS) {
				positions.add(hit.pos());
			}

			if (hit.distance() < nearestDistance) {
				nearestDistance = hit.distance();
				nearest = hit.pos();
			}

			farthest = Math.max(farthest, hit.distance());
		}
	}

	/** What the grid of rays found, in cells of the coarse grid. */
	private static final class Scan {
		final Map<Identifier, Seen> seen = new HashMap<>();
		/** Signs seen and lit, whose text faces may be readable. */
		final Set<BlockPos> signs = new LinkedHashSet<>();
		double darkWeight;
		int rays;
		int cells;

		/** Adds one ray's hits, cast through {@code point}, standing for {@code weight} of a cell. */
		void record(Sample sample, ScreenPoint point, double weight) {
			if (sample.dark()) {
				darkWeight += weight;
			}

			List<Identifier> through = new ArrayList<>();
			for (Hit hit : sample.hits()) {
				if (!hit.lit()) {
					continue;
				}

				Seen s = seen.computeIfAbsent(hit.id(), Seen::new);
				s.add(hit);
				s.area.include(point);
				if (hit.layer().visible()) {
					s.weight += weight;
					if (hit.sign()) {
						signs.add(hit.pos());
					}
				}

				through.stream().filter(id -> !id.equals(hit.id())).forEach(s.through::add);
				through.add(hit.id());
			}
		}
	}

	/** One ray per grid cell, then 3×3 more in cells on an edge. */
	private static Scan scan(SightTracer tracer, ViewFrame frame, double range) {
		int columns = COLUMNS;
		int rows = Math.max(9, (int) Math.round(columns / frame.aspect()));
		Sample[][] grid = new Sample[rows][columns];
		Scan scan = new Scan();
		scan.cells = rows * columns;

		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				grid[r][c] = trace(tracer, frame, cellPoint(r, c, rows, columns, 0, 0), range);
				scan.rays++;
			}
		}

		// Edges between different things, or distance jumps, get 3×3 samples; flat areas count their one ray for the cell.
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				if (!isEdge(grid, r, c)) {
					scan.record(grid[r][c], cellPoint(r, c, rows, columns, 0, 0), 1);
					continue;
				}

				for (int sy = -1; sy <= 1; sy++) {
					for (int sx = -1; sx <= 1; sx++) {
						ScreenPoint point = cellPoint(r, c, rows, columns, sx / 3.0, sy / 3.0);
						boolean center = sx == 0 && sy == 0;
						Sample sample = center ? grid[r][c] : trace(tracer, frame, point, range);
						if (!center) {
							scan.rays++;
						}

						scan.record(sample, point, 1 / 9.0);
					}
				}
			}
		}

		return scan;
	}

	private static Sample trace(SightTracer tracer, ViewFrame frame, ScreenPoint point, double range) {
		Ray ray = frame.ray(point.x(), point.y());
		return tracer.trace(ray.origin(), ray.at(range));
	}

	/** The image point of a cell's center, moved by {@code dx}, {@code dy} cells (right and down). */
	private static ScreenPoint cellPoint(int row, int column, int rows, int columns, double dx, double dy) {
		double x = -1 + (2 * (column + 0.5 + dx)) / columns;
		double y = 1 - (2 * (row + 0.5 + dy)) / rows;
		return new ScreenPoint(x, y);
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
			if (!edgeKey(other).equals(edgeKey(here)) || other.hits().size() != here.hits().size()) {
				return true;
			}

			double depth = depth(here);
			double otherDepth = depth(other);
			if (here.shown() != null && Math.max(depth, otherDepth) > 1.5 * Math.min(depth, otherDepth) + 1) {
				return true;
			}
		}

		return false;
	}

	/** What a ray shows, for telling neighboring cells apart: the block or fluid, darkness, or nothing. */
	private static String edgeKey(Sample sample) {
		Hit shown = sample.shown();
		if (shown != null) {
			return shown.id().toString();
		}

		return sample.dark() ? "dark" : "";
	}

	private static double depth(Sample sample) {
		Hit shown = sample.shown();
		return shown == null ? Double.MAX_VALUE : shown.distance();
	}

	// --- output ---------------------------------------------------------------------------------------------------

	/** The part of the image something covers, grown point by point. */
	private static final class ImageArea {
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;

		void include(ScreenPoint point) {
			minX = Math.min(minX, point.x());
			maxX = Math.max(maxX, point.x());
			minY = Math.min(minY, point.y());
			maxY = Math.max(maxY, point.y());
		}

		/** "x 10-40%, y 20-62%", clamped to the image. */
		String describe() {
			ScreenPoint topLeft = new ScreenPoint(minX, maxY);
			ScreenPoint bottomRight = new ScreenPoint(maxX, minY);
			return "x " + topLeft.percentX() + "-" + bottomRight.percentX() + "%, y " + topLeft.percentY() + "-"
				+ bottomRight.percentY() + "%";
		}
	}

	/** Most of the screen first; ties nearest first, then by id, so the order is stable. */
	private static JsonArray blocksJson(Scan scan, ViewFrame frame) {
		Comparator<Seen> order = Comparator.comparingDouble((Seen s) -> -s.weight)
			.thenComparingDouble(s -> s.nearestDistance)
			.thenComparing(s -> s.id.toString());
		JsonArray blocks = new JsonArray();
		scan.seen.values().stream().sorted(order).forEach(s -> blocks.add(seenJson(s, scan.cells, frame)));
		return blocks;
	}

	private static JsonObject seenJson(Seen s, int cells, ViewFrame frame) {
		JsonObject o = new JsonObject();
		o.addProperty("block", ToolFormat.id(s.id));
		// Clear blocks (the middle of a glass pane) are crossed but show what's behind them.
		o.addProperty("screen_share", s.weight > 0 ? share(s.weight, cells) : "none: clear, only seen through");
		o.addProperty("in_image", s.area.describe());
		o.addProperty("nearest", ToolFormat.position(s.nearest));
		o.addProperty("nearest_distance_blocks", ToolFormat.distance(s.nearestDistance));
		o.addProperty("farthest_distance_blocks", ToolFormat.distance(s.farthest));

		if (s.positions.size() <= MAX_LISTED_POSITIONS) {
			JsonArray positions = new JsonArray();
			s.positions.stream().sorted(nearestFirst(frame.eye())).forEach(p -> {
				ScreenPoint point = frame.project(Vec3.atCenterOf(p));
				boolean shown = point != null && point.onScreen();
				positions.add(ToolFormat.position(p) + (shown ? " (image " + point.describe() + ")" : ""));
			});
			o.add("positions", positions);
		} else {
			o.addProperty("blocks_seen", s.positions.size() >= MAX_POSITIONS ? MAX_POSITIONS + "+" : String.valueOf(s.positions.size()));
			o.addProperty("spans", spans(s.positions));
		}

		if (!s.through.isEmpty()) {
			o.addProperty("seen_through", s.through.stream().map(ToolFormat::id).collect(Collectors.joining(", ")));
		}

		return o;
	}

	/** "x 1..9, y 60..64, z -3..4": the box around the positions. */
	private static String spans(Set<BlockPos> positions) {
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (BlockPos p : positions) {
			min[0] = Math.min(min[0], p.getX());
			min[1] = Math.min(min[1], p.getY());
			min[2] = Math.min(min[2], p.getZ());
			max[0] = Math.max(max[0], p.getX());
			max[1] = Math.max(max[1], p.getY());
			max[2] = Math.max(max[2], p.getZ());
		}

		return "x " + min[0] + ".." + max[0] + ", y " + min[1] + ".." + max[1] + ", z " + min[2] + ".." + max[2];
	}

	private static String share(double weight, int cells) {
		double percent = 100 * weight / cells;
		return percent < 0.1 ? "under 0.1%" : String.format(Locale.ROOT, "%.1f%%", percent);
	}

	private static Comparator<BlockPos> nearestFirst(Vec3 eye) {
		return Comparator.comparingDouble((BlockPos p) -> Vec3.atCenterOf(p).distanceToSqr(eye)).thenComparing(p -> p);
	}

	/**
	 * Text on the visible signs, nearest first: only the side facing the player, as the game decides it when the player
	 * edits a sign.
	 */
	private static JsonArray signsJson(Minecraft mc, ClientLevel level, Set<BlockPos> signs, Vec3 eye) {
		JsonArray details = new JsonArray();
		for (BlockPos pos : signs.stream().sorted(nearestFirst(eye)).toList()) {
			if (details.size() >= MAX_DETAILS) {
				break;
			}

			if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
				continue;
			}

			SignTextSlot side = sign.getSlotPlayerIsFacing(mc.player);
			String text = signText(sign.getText(side));
			if (text.isEmpty()) {
				continue;
			}

			JsonObject detail = new JsonObject();
			detail.addProperty("block", ToolFormat.id(sign.getBlockState().getBlock()));
			detail.addProperty("position", ToolFormat.position(pos));
			detail.addProperty("side", side == SignTextSlot.FRONT ? "front" : "back");
			detail.addProperty("text", text);
			details.add(detail);
		}

		return details;
	}

	private static String signText(SignText text) {
		return text.getMessages(false).stream().map(Component::getString).filter(line -> !line.isBlank())
			.collect(Collectors.joining(" / "));
	}

	// --- camera, crosshair, entities ------------------------------------------------------------------------------

	private static JsonObject cameraJson(Minecraft mc, ClientLevel level, ViewFrame frame, boolean firstPerson, double range,
		int requestedRange) {
		JsonObject o = new JsonObject();
		Vec3 eye = frame.eye();
		o.addProperty("dimension", Dimensions.name(level.dimension()));
		o.addProperty("eye_position", ToolFormat.position(eye.x, eye.y, eye.z));
		o.addProperty("facing", ToolFormat.compass(frame.forward().x * 100, frame.forward().z * 100));
		float pitch = frame.pitch();
		o.addProperty("looking", Math.abs(pitch) < 10 ? "level" : (pitch < 0 ? "up " : "down ") + Math.round(Math.abs(pitch)) + "°");
		o.addProperty("fov_vertical", Math.round(frame.fovVertical()));
		o.addProperty("fov_horizontal", Math.round(frame.fovHorizontal()));
		o.addProperty("perspective", firstPerson ? "first person" : "third person");
		o.addProperty("ray_range", Math.round(range));
		o.addProperty("ray_range_limited_by", range < requestedRange ? frame.sightLimit() : "max_distance");

		FluidState inFluid = level.getFluidState(BlockPos.containing(eye));
		if (!inFluid.isEmpty()) {
			o.addProperty("camera_in", ToolFormat.id(SightTracer.fluidId(inFluid)));
		}

		return o;
	}

	/**
	 * What the player aims at: the game's own pick from the player's eyes, within reach. In first person that's under
	 * the crosshair, in the middle of the image; in third person no crosshair is drawn, so it says where in the image the
	 * target is.
	 */
	private static JsonObject crosshairJson(Minecraft mc, ClientLevel level, ViewFrame frame, SightTracer tracer,
		boolean firstPerson) {
		JsonObject o = new JsonObject();
		if (!firstPerson) {
			o.addProperty("note", "Third person: no crosshair is drawn. This is what the player aims at from their eyes.");
		}

		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = level.getBlockState(block.getBlockPos());
			o.addProperty("in_reach", true);
			addImagePoint(o, frame, hit.getLocation());
			if (!tracer.lit(state, block.getBlockPos(), block.getDirection())) {
				o.addProperty("too_dark", true);
				return o;
			}

			ToolFormat.addBlockState(o, state);
			o.addProperty("position", ToolFormat.position(block.getBlockPos()));
			o.addProperty("face", block.getDirection().getSerializedName());
			return o;
		}

		if (hit instanceof EntityHitResult entityHit) {
			o.addProperty("in_reach", true);
			Entity entity = entityHit.getEntity();
			if (!lit(mc, level, tracer, entity)) {
				o.addProperty("too_dark", true);
				addImagePoint(o, frame, hit.getLocation());
				return o;
			}

			o.add("entity", entityJson(mc, entity, frame));
			return o;
		}

		o.addProperty("in_reach", false);
		o.addProperty("note", firstPerson
			? "Nothing within reach; the blocks at the middle of the image are what's farther away."
			: "Third person: no crosshair is drawn, and nothing is within the player's reach.");
		return o;
	}

	private static void addImagePoint(JsonObject o, ViewFrame frame, Vec3 position) {
		ScreenPoint point = frame.project(position);
		if (point != null && point.onScreen()) {
			o.addProperty("in_image", point.describe());
		}
	}

	/** Entities in view, lit, and with a line of sight to some part of them, nearest first. */
	private static List<JsonObject> visibleEntities(Minecraft mc, ClientLevel level, ViewFrame frame, SightTracer tracer,
		double range, boolean firstPerson) {
		Entity self = mc.getCameraEntity();
		Vec3 eye = frame.eye();
		Map<Entity, Integer> visible = new HashMap<>();

		for (Entity entity : level.entitiesForRendering()) {
			if ((entity == self && firstPerson) || entity.isInvisibleTo(mc.player)) {
				continue;
			}

			if (entity.getBoundingBox().getCenter().distanceTo(eye) > range || !lit(mc, level, tracer, entity)) {
				continue;
			}

			int points = visiblePoints(entity, frame, tracer);
			if (points > 0) {
				visible.put(entity, points);
			}
		}

		Comparator<Entity> nearestFirst = Comparator.comparingDouble((Entity e) -> e.getBoundingBox().getCenter().distanceToSqr(eye))
			.thenComparingInt(Entity::getId);
		List<JsonObject> out = new ArrayList<>();
		for (Entity entity : visible.keySet().stream().sorted(nearestFirst).toList()) {
			JsonObject o = entityJson(mc, entity, frame);
			o.addProperty("visible", visible.get(entity) == SIGHT_POINTS ? "fully" : "partly");
			out.add(o);
		}

		return out;
	}

	/**
	 * How many of the middle, top, bottom and four side points of the entity's box ({@link #SIGHT_POINTS}) are in the
	 * image with nothing opaque between them and the camera.
	 */
	private static int visiblePoints(Entity entity, ViewFrame frame, SightTracer tracer) {
		AABB box = entity.getBoundingBox();
		Vec3 c = box.getCenter();
		double dx = box.getXsize() * 0.35;
		double dz = box.getZsize() * 0.35;
		Vec3[] points = {c, new Vec3(c.x, box.maxY - 0.05, c.z), new Vec3(c.x, box.minY + 0.05, c.z),
			new Vec3(c.x + dx, c.y, c.z + dz), new Vec3(c.x - dx, c.y, c.z - dz), new Vec3(c.x + dx, c.y, c.z - dz),
			new Vec3(c.x - dx, c.y, c.z + dz)};
		int seen = 0;

		for (Vec3 point : points) {
			ScreenPoint screen = frame.project(point);
			if (screen == null || !screen.onScreen()) {
				continue;
			}

			double distance = point.distanceTo(frame.eye());
			Sample sample = tracer.trace(frame.eye(), point);
			boolean blocked = sample.hits().stream().anyMatch(h -> h.layer() == Layer.OPAQUE && h.distance() < distance - 0.05);
			if (!blocked) {
				seen++;
			}
		}

		return seen;
	}

	/** Whether the entity is lit enough to make out, by the light its renderer draws it with (glowing mobs count). */
	private static boolean lit(Minecraft mc, ClientLevel level, SightTracer tracer, Entity entity) {
		float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		int packed = mc.getEntityRenderDispatcher().getPackedLightCoords(entity, partialTicks);
		int light = Math.max(LightCoordsUtil.block(packed), LightCoordsUtil.sky(packed) - level.getSkyDarken());
		return tracer.bright(light);
	}

	/** "entities", nearest first, and a note on the farther ones past {@link #MAX_ENTITIES}. */
	private static void addEntities(JsonObject out, List<JsonObject> visible) {
		JsonArray entities = new JsonArray();
		visible.stream().limit(MAX_ENTITIES).forEach(entities::add);
		out.add("entities", entities);
		if (visible.size() > MAX_ENTITIES) {
			ToolFormat.noteMore(out, visible.size() - MAX_ENTITIES + " farther entities.");
		}
	}

	private static JsonObject entityJson(Minecraft mc, Entity entity, ViewFrame frame) {
		JsonObject o = new JsonObject();
		o.addProperty("type", ToolFormat.id(EntityType.getKey(entity.getType())));
		if (entity instanceof Player player) {
			o.addProperty("name", player.getGameProfile().name());
		} else if (entity.getCustomName() != null) {
			o.addProperty("name", entity.getCustomName().getString());
		}

		Vec3 center = entity.getBoundingBox().getCenter();
		o.addProperty("position", ToolFormat.position(entity.getX(), entity.getY(), entity.getZ()));
		o.addProperty("distance_blocks", ToolFormat.distance(center.distanceTo(frame.eye())));
		String inImage = imageArea(entity.getBoundingBox(), frame);
		if (inImage != null) {
			o.addProperty("in_image", inImage);
		}

		if (entity instanceof LivingEntity living) {
			// Other players' health isn't shown in the game; the player's own is on their HUD.
			if (!(entity instanceof Player) || entity == mc.player) {
				o.addProperty("health", Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth()));
			}

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
		} else if (entity instanceof ItemFrame itemFrame && !itemFrame.getItem().isEmpty()) {
			o.addProperty("item", itemText(itemFrame.getItem()));
		}

		if (entity.getVehicle() != null) {
			o.addProperty("riding", ToolFormat.id(EntityType.getKey(entity.getVehicle().getType())));
		}

		return o;
	}

	/**
	 * Where the box is in the image, from its eight corners; just its center when part of it is behind the camera; null
	 * when the center is off the image too.
	 */
	private static String imageArea(AABB box, ViewFrame frame) {
		ImageArea area = new ImageArea();
		for (int corner = 0; corner < 8; corner++) {
			Vec3 position = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
				(corner & 4) == 0 ? box.minZ : box.maxZ);
			ScreenPoint point = frame.project(position);
			if (point == null) {
				ScreenPoint center = frame.project(box.getCenter());
				return center != null && center.onScreen() ? center.describe() : null;
			}

			area.include(point);
		}

		return area.describe();
	}

	/** The stack as the player sees it on hover: its name, custom or not. Enchantments only show as a glint. */
	private static String itemText(ItemStack stack) {
		return ToolFormat.item(stack.getCount(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
			stack.getHoverName().getString(), List.of());
	}
}
