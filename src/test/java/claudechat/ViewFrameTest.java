package claudechat;

import claudechat.ViewFrame.Ray;
import claudechat.ViewFrame.ScreenPoint;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFrameTest {
	private static final Vec3 EYE = new Vec3(10.5, 65.62, -3.5);
	private static final double FOV = 70;
	private static final double ASPECT = 16.0 / 9;

	/**
	 * A frame like the game's: a perspective projection, by default with near and far swapped as the game draws, and a
	 * view rotation; the camera looks along -z when unrotated.
	 */
	private static ViewFrame frame(Matrix4f viewRotation, boolean zeroToOneDepth, boolean reversedDepth) {
		float near = reversedDepth ? 1024 : 0.05f;
		float far = reversedDepth ? 0.05f : 1024;
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(FOV), (float) ASPECT, near, far, zeroToOneDepth);
		return new ViewFrame(EYE, projection, viewRotation, 1600, 900, 0, FOV, 0, 256, "render distance");
	}

	private static ViewFrame frame(Matrix4f viewRotation) {
		return frame(viewRotation, true, true);
	}

	@Test
	void centerRayGoesStraightAhead() {
		ViewFrame frame = frame(new Matrix4f());
		Ray ray = frame.ray(0, 0);

		assertEquals(0, ray.direction().x, 1e-5);
		assertEquals(0, ray.direction().y, 1e-5);
		assertEquals(-1, ray.direction().z, 1e-5);
		assertTrue(ray.origin().distanceTo(EYE) < 1e-4);
		assertEquals(-1, frame.forward().z, 1e-6);
	}

	@Test
	void cornerRaysFollowTheFieldOfViewInEveryDepthConvention() {
		double tanHalf = Math.tan(Math.toRadians(FOV / 2));
		for (boolean zeroToOne : new boolean[] {false, true}) {
			for (boolean reversed : new boolean[] {false, true}) {
				Ray ray = frame(new Matrix4f(), zeroToOne, reversed).ray(1, 1);

				assertEquals(-1, Math.signum(ray.direction().z));
				assertEquals(tanHalf, ray.direction().y / -ray.direction().z, 1e-4);
				assertEquals(tanHalf * ASPECT, ray.direction().x / -ray.direction().z, 1e-4);
			}
		}
	}

	@Test
	void projectingAPointOnARayGivesBackTheImagePoint() {
		ViewFrame frame = frame(new Matrix4f().rotateX(0.3f).rotateY(-1.1f));
		double[][] points = {{0, 0}, {0.5, -0.25}, {-0.9, 0.8}};

		for (double[] p : points) {
			Ray ray = frame.ray(p[0], p[1]);
			ScreenPoint projected = frame.project(ray.at(40));

			assertNotNull(projected);
			assertEquals(p[0], projected.x(), 1e-4);
			assertEquals(p[1], projected.y(), 1e-4);
		}
	}

	@Test
	void pointsBehindTheCameraHaveNoImagePoint() {
		assertNull(frame(new Matrix4f()).project(EYE.add(0, 0, 5)));
	}

	@Test
	void imagePercentsCountFromTheTopLeft() {
		ScreenPoint point = new ScreenPoint(-0.5, 0.5);

		assertEquals(25, point.percentX());
		assertEquals(25, point.percentY());
		assertEquals("x 25%, y 25%", point.describe());
	}
}
