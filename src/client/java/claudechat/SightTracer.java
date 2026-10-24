package claudechat;

import claudechat.BlockSurfaces.Crossing;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walks rays through the client's world block by block and records what the player sees along each: the blocks and fluid
 * surfaces met front to back until something opaque stops the ray, and whether each is lit well enough to make out.
 * Client thread.
 */
final class SightTracer {
	/** How a ray meets a block or fluid surface. Decided once per crossing, by {@link BlockSurfaces}. */
	enum Layer {
		/** Passed through its holes (the clear middle of a glass pane): it's there, but what's behind it shows. */
		CLEAR,
		/** Seen, and seen through: water, stained glass, ice. */
		TINTED,
		/** Seen, and the ray stops. */
		OPAQUE;

		boolean visible() {
			return this != CLEAR;
		}
	}

	/**
	 * One block or fluid surface a ray met.
	 *
	 * @param id       the block's or fluid's id
	 * @param distance blocks from the ray's start
	 * @param lit      whether there's light enough to make it out
	 * @param sign     whether it's a sign, whose text the player can read
	 */
	record Hit(Identifier id, BlockPos pos, double distance, Layer layer, boolean lit, boolean sign) {
	}

	/** Everything one ray met, front to back. */
	record Sample(List<Hit> hits) {
		/** What shows at this point of the image: the first hit that isn't clear, or null for sky or darkness. */
		@Nullable Hit shown() {
			Hit front = front();
			return front != null && front.lit() ? front : null;
		}

		/** Whether anything showing here, in front or seen through something, is too dark to make out. */
		boolean dark() {
			return hits.stream().anyMatch(hit -> hit.layer().visible() && !hit.lit());
		}

		private @Nullable Hit front() {
			for (Hit hit : hits) {
				if (hit.layer().visible()) {
					return hit;
				}
			}

			return null;
		}
	}

	/** At most this many hits along one ray: see-through layers plus the one that stops it. */
	private static final int MAX_LAYERS = 6;
	/**
	 * The game's brightness of light level 2 where the dimension has no ambient light (the Overworld). Anything darker
	 * is too dark to make out. The Nether's and the End's ambient light keep everything there above it.
	 */
	private static final float MIN_BRIGHTNESS = 0.035f;

	private final ClientLevel level;
	private final BlockSurfaces surfaces;
	private final boolean nightVision;

	/** @param nightVision whether the player has night vision (or conduit power under water), which lights everything */
	SightTracer(ClientLevel level, BlockSurfaces surfaces, boolean nightVision) {
		this.level = level;
		this.surfaces = surfaces;
		this.nightVision = nightVision;
	}

	/** Walks every block from {@code from} to {@code to}, recording hits until an opaque one stops the ray. */
	Sample trace(Vec3 from, Vec3 to) {
		List<Hit> hits = new ArrayList<>();
		// Water the camera is already in isn't a surface the player sees; a fluid counts only where the ray enters it.
		FluidState[] previousFluid = {level.getFluidState(BlockPos.containing(from))};

		BlockGetter.traverseBlocks(from, to, hits, (list, cursor) -> {
			BlockPos pos = cursor.immutable();
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			boolean entersFluid = !fluid.isEmpty() && !fluid.getType().isSame(previousFluid[0].getType());
			previousFluid[0] = fluid;

			for (Hit hit : hitsAt(state, fluid, entersFluid, pos, from, to)) {
				list.add(hit);
				if (hit.layer() == Layer.OPAQUE || list.size() >= MAX_LAYERS) {
					return Boolean.TRUE;
				}
			}

			return null;
		}, list -> Boolean.FALSE);

		return new Sample(List.copyOf(hits));
	}

	/** The block and the fluid surface the ray meets in one block space, nearest first. */
	private List<Hit> hitsAt(BlockState state, FluidState fluid, boolean entersFluid, BlockPos pos, Vec3 from, Vec3 to) {
		List<Hit> here = new ArrayList<>(2);

		if (!state.isAir()) {
			Crossing crossing = surfaces.crossBlock(state, pos, from, to);
			if (crossing != null) {
				Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
				here.add(hit(id, state, pos, crossing, state.getBlock() instanceof SignBlock));
			}
		}

		if (entersFluid) {
			Crossing crossing = surfaces.crossFluid(fluid, pos, from, to);
			if (crossing != null) {
				here.add(hit(fluidId(fluid), state, pos, crossing, false));
			}
		}

		here.sort(Comparator.comparingDouble(Hit::distance));
		return here;
	}

	private Hit hit(Identifier id, BlockState state, BlockPos pos, Crossing crossing, boolean sign) {
		return new Hit(id, pos, crossing.distance(), crossing.layer(), lit(state, pos, crossing.face()), sign);
	}

	/**
	 * Whether a block's face is lit enough to make out: it gives off light itself, or the light in it or just in front of
	 * the face is bright enough. Solid blocks hold no light, so the space in front of the face is what counts for them.
	 */
	boolean lit(BlockState state, BlockPos pos, Direction face) {
		if (state.getLightEmission() > 0) {
			return true;
		}

		int light = Math.max(level.getMaxLocalRawBrightness(pos), level.getMaxLocalRawBrightness(pos.relative(face)));
		return bright(light);
	}

	/**
	 * Whether a light level (the brighter of block light and sky light after the time of day, 0-15) is enough to make
	 * things out, by the game's own brightness curve and the dimension's ambient light. The Brightness setting isn't
	 * counted, so a player on "Bright" may see more than this.
	 */
	boolean bright(int light) {
		return nightVision || Lightmap.getBrightness(level.dimensionType(), light) >= MIN_BRIGHTNESS;
	}

	/** The still fluid's id for flowing fluids too: "water", not "flowing_water". */
	static Identifier fluidId(FluidState fluid) {
		Fluid type = fluid.getType() instanceof FlowingFluid flowing ? flowing.getSource() : fluid.getType();
		return BuiltInRegistries.FLUID.getKey(type);
	}
}
