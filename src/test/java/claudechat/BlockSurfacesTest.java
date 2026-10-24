package claudechat;

import claudechat.BlockSurfaces.QuadPoint;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockSurfacesTest {
	/** A block's south face (z = 1), counter-clockwise seen from the south, with u along x and v down y. */
	private static final Vector3fc[] SOUTH_FACE = {
		new Vector3f(0, 1, 1), new Vector3f(0, 0, 1), new Vector3f(1, 0, 1), new Vector3f(1, 1, 1)};
	private static final long[] SOUTH_UVS = {
		UVPair.pack(0, 0), UVPair.pack(0, 1), UVPair.pack(1, 1), UVPair.pack(1, 0)};
	private static final Vector3fc SOUTH = new Vector3f(0, 0, 1);

	@Test
	void rayFromTheFrontHitsWithInterpolatedTextureCoordinates() {
		QuadPoint point = BlockSurfaces.crossQuad(SOUTH_FACE, SOUTH_UVS, SOUTH, new Vec3(0.25, 0.75, 3), new Vec3(0, 0, -4));

		assertNotNull(point);
		assertEquals(0.5, point.t(), 1e-9);
		assertEquals(0.25, point.u(), 1e-6);
		assertEquals(0.25, point.v(), 1e-6);
	}

	@Test
	void rayFromBehindMissesBecauseFacesAreOneSided() {
		assertNull(BlockSurfaces.crossQuad(SOUTH_FACE, SOUTH_UVS, SOUTH, new Vec3(0.5, 0.5, -1), new Vec3(0, 0, 4)));
	}

	@Test
	void windingDoesNotDecideTheFrontSide() {
		Vector3fc[] clockwise = {SOUTH_FACE[3], SOUTH_FACE[2], SOUTH_FACE[1], SOUTH_FACE[0]};
		long[] uvs = {SOUTH_UVS[3], SOUTH_UVS[2], SOUTH_UVS[1], SOUTH_UVS[0]};

		QuadPoint point = BlockSurfaces.crossQuad(clockwise, uvs, SOUTH, new Vec3(0.75, 0.25, 2), new Vec3(0, 0, -2));

		assertNotNull(point);
		assertEquals(0.75, point.u(), 1e-6);
		assertEquals(0.75, point.v(), 1e-6);
	}

	@Test
	void raysBesideOrAlongTheQuadOrStoppingShortMiss() {
		assertNull(BlockSurfaces.crossQuad(SOUTH_FACE, SOUTH_UVS, SOUTH, new Vec3(1.5, 0.5, 3), new Vec3(0, 0, -4)));
		assertNull(BlockSurfaces.crossQuad(SOUTH_FACE, SOUTH_UVS, SOUTH, new Vec3(-1, 0.5, 1), new Vec3(4, 0, 0)));
		assertNull(BlockSurfaces.crossQuad(SOUTH_FACE, SOUTH_UVS, SOUTH, new Vec3(0.5, 0.5, 3), new Vec3(0, 0, -1)));
	}

	@Test
	void diagonalQuadsOfCrossModelsAreHit() {
		// One blade of a flower's cross: from the north-west bottom corner to the south-east top corner, facing south-west.
		Vector3fc[] blade = {new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), new Vector3f(1, 0, 1), new Vector3f(1, 1, 1)};
		Vector3fc facing = new Vector3f(-1, 0, 1);

		QuadPoint point = BlockSurfaces.crossQuad(blade, SOUTH_UVS, facing, new Vec3(0.5, 0.5, 3), new Vec3(0, 0, -4));

		assertNotNull(point);
		assertEquals(0.625, point.t(), 1e-9);
		assertEquals(0.5, point.u(), 1e-6);
	}
}
