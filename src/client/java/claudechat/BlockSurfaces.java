package claudechat;

import claudechat.SightTracer.Layer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a ray crossing one block meets of what the game draws there, read from the client's baked models: the quads the
 * ray crosses, and whether the texel it crosses is a hole, translucent or opaque. So glass, leaves, ice, doors and
 * trapdoors behave as drawn, with whatever resource pack is loaded, rather than by a list of see-through blocks. A block
 * whose model draws nothing counts as opaque over its outline shape when a block entity renderer draws it (chests,
 * signs, beds) and as absent otherwise (barriers, light blocks). Client thread.
 */
final class BlockSurfaces {
	/** Where a ray met a block: what it saw, how far along the ray, and the face it came through. */
	record Crossing(Layer layer, double distance, Direction face) {
	}

	/** A ray's crossing of one quad: the fraction of the ray's length, and the atlas texture coordinates there. */
	record QuadPoint(double t, float u, float v) {
	}

	/** Slack for points on a triangle's edge, so a ray along the seam of two triangles still hits one. */
	private static final double EDGE_SLACK = 1e-6;

	private final ClientLevel level;
	private final BlockStateModelSet blockModels;
	private final FluidStateModelSet fluidModels;
	private final BlockEntityRenderDispatcher blockEntityRenderers;
	private final Vec3 eye;
	private final boolean cutoutLeaves;
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final RandomSource random = RandomSource.create();

	/**
	 * @param eye          the camera position, which decides whether block entity renderers draw at all
	 * @param cutoutLeaves the frame's leaves setting; without it, leaves are drawn opaque
	 */
	BlockSurfaces(Minecraft mc, ClientLevel level, Vec3 eye, boolean cutoutLeaves) {
		this.level = level;
		this.blockModels = mc.getModelManager().getBlockStateModelSet();
		this.fluidModels = mc.getModelManager().getFluidStateModelSet();
		this.blockEntityRenderers = mc.getBlockEntityRenderDispatcher();
		this.eye = eye;
		this.cutoutLeaves = cutoutLeaves;
	}

	/** How the segment {@code from}-{@code to} meets the block drawn at {@code pos}, or null if it misses everything drawn. */
	@Nullable Crossing crossBlock(BlockState state, BlockPos pos, Vec3 from, Vec3 to) {
		if (state.getRenderShape() == RenderShape.INVISIBLE) {
			return drawnByBlockEntity(pos) ? crossOutline(state, pos, from, to) : null;
		}

		List<BakedQuad> quads = quads(state, pos, false);
		if (quads.isEmpty()) {
			return drawnByBlockEntity(pos) ? crossOutline(state, pos, from, to) : null;
		}

		boolean drawnOpaque = ModelBlockRenderer.forceOpaque(cutoutLeaves, state)
			|| quads.stream().allMatch(quad -> quad.materialInfo().layer() == ChunkSectionLayer.SOLID);
		if (drawnOpaque) {
			return crossOutline(state, pos, from, to);
		}

		return crossQuads(quads(state, pos, true), state, pos, from, to);
	}

	/** Where the segment enters the fluid's surface at {@code pos}; lava and other opaque fluids stop it. */
	@Nullable Crossing crossFluid(FluidState fluid, BlockPos pos, Vec3 from, Vec3 to) {
		BlockHitResult hit = fluid.getShape(level, pos).clip(from, to, pos);
		if (hit == null) {
			return null;
		}

		Layer layer = fluidModels.get(fluid).layer().translucent() ? Layer.TINTED : Layer.OPAQUE;
		return new Crossing(layer, from.distanceTo(hit.getLocation()), hit.getDirection());
	}

	private @Nullable Crossing crossOutline(BlockState state, BlockPos pos, Vec3 from, Vec3 to) {
		BlockHitResult hit = state.getShape(level, pos).clip(from, to, pos);
		if (hit == null) {
			return null;
		}

		return new Crossing(Layer.OPAQUE, from.distanceTo(hit.getLocation()), hit.getDirection());
	}

	/** Whether a block entity renderer draws something at {@code pos} from where the camera is. */
	private boolean drawnByBlockEntity(BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return false;
		}

		BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = blockEntityRenderers.getRenderer(blockEntity);
		return renderer != null && renderer.shouldRender(blockEntity, eye);
	}

	/**
	 * The quads the chunk renderer draws for this block: the same model variant (it's chosen by the position's seed),
	 * and, when {@code culled}, without the faces a neighbor hides.
	 */
	private List<BakedQuad> quads(BlockState state, BlockPos pos, boolean culled) {
		parts.clear();
		random.setSeed(state.getSeed(pos));
		blockModels.get(state).collectParts(random, parts);
		List<BakedQuad> quads = new ArrayList<>();

		for (BlockStateModelPart part : parts) {
			quads.addAll(part.getQuads(null));
			for (Direction direction : Direction.values()) {
				if (!culled || Block.shouldRenderFace(state, level.getBlockState(pos.relative(direction)), direction)) {
					quads.addAll(part.getQuads(direction));
				}
			}
		}

		return quads;
	}

	/**
	 * One crossing for the whole block: opaque if the ray meets an opaque texel anywhere in it, else tinted if it meets a
	 * translucent one, else clear (it only passed through holes). Its distance is that of the nearest texel it saw, or of
	 * the nearest hole when it saw none.
	 */
	private @Nullable Crossing crossQuads(List<BakedQuad> quads, BlockState state, BlockPos pos, Vec3 from, Vec3 to) {
		Vec3 origin = from.subtract(Vec3.atLowerCornerOf(pos)).subtract(state.getOffset(pos));
		Vec3 delta = to.subtract(from);
		QuadPoint nearest = null;
		Direction nearestFace = null;
		QuadPoint nearestSeen = null;
		Direction nearestSeenFace = null;
		boolean opaque = false;

		for (BakedQuad quad : quads) {
			QuadPoint point = crossQuad(quad, origin, delta);
			if (point == null) {
				continue;
			}

			if (nearest == null || point.t() < nearest.t()) {
				nearest = point;
				nearestFace = quad.direction();
			}

			if (isHole(quad.materialInfo().sprite(), point)) {
				continue;
			}

			opaque |= !quad.materialInfo().layer().translucent();
			if (nearestSeen == null || point.t() < nearestSeen.t()) {
				nearestSeen = point;
				nearestSeenFace = quad.direction();
			}
		}

		double length = delta.length();
		if (nearestSeen != null) {
			return new Crossing(opaque ? Layer.OPAQUE : Layer.TINTED, nearestSeen.t() * length, nearestSeenFace);
		}

		return nearest == null ? null : new Crossing(Layer.CLEAR, nearest.t() * length, nearestFace);
	}

	private static @Nullable QuadPoint crossQuad(BakedQuad quad, Vec3 origin, Vec3 delta) {
		Vector3fc[] corners = {quad.position0(), quad.position1(), quad.position2(), quad.position3()};
		long[] uvs = {quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3()};
		return crossQuad(corners, uvs, quad.direction().getUnitVec3f(), origin, delta);
	}

	/**
	 * Where the segment {@code origin + t·delta} (t in 0..1) crosses a quad's front side, with the texture coordinates
	 * there, or null when it misses or comes from behind (block faces are drawn one-sided). The quad is split into the
	 * triangles 0-1-2 and 0-2-3, as it's drawn. {@code facing} is roughly the quad's outward normal (its {@link
	 * Direction}), which settles which side is the front whatever the corners' winding.
	 */
	static @Nullable QuadPoint crossQuad(Vector3fc[] corners, long[] uvs, Vector3fc facing, Vec3 origin, Vec3 delta) {
		Vec3[] p = new Vec3[4];
		for (int i = 0; i < 4; i++) {
			p[i] = new Vec3(corners[i]);
		}

		Vec3 normal = p[2].subtract(p[0]).cross(p[3].subtract(p[1]));
		if (normal.dot(new Vec3(facing)) < 0) {
			normal = normal.scale(-1);
		}

		double approach = delta.dot(normal);
		if (approach >= 0) {
			return null;
		}

		double t = p[0].subtract(origin).dot(normal) / approach;
		if (t < 0 || t > 1) {
			return null;
		}

		Vec3 hit = origin.add(delta.scale(t));
		int[][] triangles = {{0, 1, 2}, {0, 2, 3}};
		for (int[] triangle : triangles) {
			double[] weights = barycentric(hit, p[triangle[0]], p[triangle[1]], p[triangle[2]]);
			if (weights == null) {
				continue;
			}

			float u = 0;
			float v = 0;
			for (int i = 0; i < 3; i++) {
				long uv = uvs[triangle[i]];
				u += (float) weights[i] * UVPair.unpackU(uv);
				v += (float) weights[i] * UVPair.unpackV(uv);
			}

			return new QuadPoint(t, u, v);
		}

		return null;
	}

	/** Weights of {@code a}, {@code b}, {@code c} at {@code point} (in their plane), or null if it's outside the triangle. */
	private static double @Nullable [] barycentric(Vec3 point, Vec3 a, Vec3 b, Vec3 c) {
		Vec3 ab = b.subtract(a);
		Vec3 ac = c.subtract(a);
		Vec3 ap = point.subtract(a);
		double abab = ab.dot(ab);
		double abac = ab.dot(ac);
		double acac = ac.dot(ac);
		double apab = ap.dot(ab);
		double apac = ap.dot(ac);
		double denominator = abab * acac - abac * abac;
		if (denominator == 0) {
			return null;
		}

		double wb = (acac * apab - abac * apac) / denominator;
		double wc = (abab * apac - abac * apab) / denominator;
		double wa = 1 - wb - wc;
		if (wa < -EDGE_SLACK || wb < -EDGE_SLACK || wc < -EDGE_SLACK) {
			return null;
		}

		return new double[] {wa, wb, wc};
	}

	/** Whether the sprite's texel at the point is fully transparent (the first frame, for animated sprites). */
	private static boolean isHole(TextureAtlasSprite sprite, QuadPoint point) {
		SpriteContents contents = sprite.contents();
		float spriteU = (point.u() - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
		float spriteV = (point.v() - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
		int x = Mth.clamp(Mth.floor(spriteU * contents.width()), 0, contents.width() - 1);
		int y = Mth.clamp(Mth.floor(spriteV * contents.height()), 0, contents.height() - 1);
		return contents.isTransparent(0, x, y);
	}
}
