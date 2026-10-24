package claudechat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The last frame the game rendered, as {@code look} needs it: the camera position, the projection the level was drawn
 * with, the size of the image it was drawn into, and how far the player could see in it. Rays and image positions both
 * go through the same matrix, so they line up with the screenshot of that frame.
 */
public final class ViewFrame {
	/** Below this clip-space depth a point is behind the camera, or too close to it to place in the image. */
	private static final float MIN_DEPTH = 0.05f;

	private final Vec3 eye;
	private final Matrix4fc viewProjection;
	private final Matrix4fc inverse;
	private final int imageWidth;
	private final int imageHeight;
	private final Vec3 forward;
	private final float pitch;
	private final double fovVertical;
	private final double fovHorizontal;
	private final double sightDistance;
	private final String sightLimit;
	private final float nearDepth;
	private final float farDepth;

	/**
	 * @param eye           the camera position
	 * @param projection    view space to clip space, as the level was drawn
	 * @param viewRotation  world (relative to {@code eye}) to view space; the camera looks along view space's -z
	 * @param imageWidth    width of the render target, in pixels
	 * @param imageHeight   height of the render target, in pixels
	 * @param pitch         degrees, negative looking up
	 * @param fovVertical   degrees
	 * @param fovHorizontal degrees
	 * @param sightDistance blocks; beyond this, fog or the render distance hides everything
	 * @param sightLimit    what sets {@code sightDistance}, e.g. "render distance" or "water"
	 */
	public ViewFrame(Vec3 eye, Matrix4fc projection, Matrix4fc viewRotation, int imageWidth, int imageHeight, float pitch,
		double fovVertical, double fovHorizontal, double sightDistance, String sightLimit) {
		this.eye = eye;
		this.viewProjection = projection.mul(viewRotation, new Matrix4f());
		this.inverse = viewProjection.invert(new Matrix4f());
		this.imageWidth = imageWidth;
		this.imageHeight = imageHeight;
		this.forward = new Vec3(viewRotation.invert(new Matrix4f()).transformDirection(new Vector3f(0, 0, -1)));
		this.pitch = pitch;
		this.fovVertical = fovVertical;
		this.fovHorizontal = fovHorizontal;
		this.sightDistance = sightDistance;
		this.sightLimit = sightLimit;
		// Unprojecting needs depths in front of the camera. Which values those are depends on the device's depth range
		// and on the game drawing with near and far swapped, so take them from points straight ahead.
		this.nearDepth = depthAhead(projection, 1);
		this.farDepth = depthAhead(projection, 2);
	}

	/** A ray from the camera through one point of the image. */
	public record Ray(Vec3 origin, Vec3 direction) {
		public Vec3 at(double distance) {
			return origin.add(direction.scale(distance));
		}
	}

	/** A point in the image: -1..1 from left to right and from bottom to top. */
	public record ScreenPoint(double x, double y) {
		public boolean onScreen() {
			return Math.abs(x) <= 1 && Math.abs(y) <= 1;
		}

		/** Percent from the image's left edge, clamped to the image. */
		public long percentX() {
			return Math.round((Math.clamp(x, -1, 1) + 1) / 2 * 100);
		}

		/** Percent from the image's top edge, clamped to the image. */
		public long percentY() {
			return Math.round((1 - Math.clamp(y, -1, 1)) / 2 * 100);
		}

		/** "x 40%, y 62%". */
		public String describe() {
			return "x " + percentX() + "%, y " + percentY() + "%";
		}
	}

	/**
	 * The frame the game drew last, read from the game's render state. Client thread, between frames: the render state
	 * then still describes the frame whose pixels are in the main render target.
	 *
	 * @throws IllegalStateException when no level was drawn in the last frame
	 */
	public static ViewFrame lastRendered(Minecraft mc) {
		GameRenderState state = mc.gameRenderer.gameRenderState();
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		if (!state.shouldRenderLevel || !camera.initialized) {
			throw new IllegalStateException("The world isn't being drawn right now.");
		}

		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		double fovVertical = Math.toDegrees(2 * Math.atan(1 / camera.projectionMatrix.m11()));
		double fovHorizontal = Math.toDegrees(2 * Math.atan(1 / camera.projectionMatrix.m00()));
		float fogEnd = camera.fogData.environmentalEnd;
		float renderDistanceEnd = camera.fogData.renderDistanceEnd;

		return new ViewFrame(camera.pos, levelProjection(state), camera.viewRotationMatrix, target.width, target.height,
			camera.xRot, fovVertical, fovHorizontal, Math.min(fogEnd, renderDistanceEnd),
			sightLimit(camera, fogEnd < renderDistanceEnd));
	}

	/**
	 * The projection {@code GameRenderer.renderLevel} draws the level with: the camera's projection, then the hurt tilt,
	 * view bobbing and the nausea or portal warp. The game builds it in private code with no hook, so this mirrors that
	 * method; keep the two in step when updating Minecraft.
	 */
	private static Matrix4f levelProjection(GameRenderState state) {
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		OptionsRenderState options = state.optionsRenderState;
		PlayerRenderState player = state.levelRenderState.playerRenderState;
		Matrix4f projection = new Matrix4f(camera.projectionMatrix);
		PoseStack bob = new PoseStack();

		hurtTilt(camera.entityRenderState, options, bob);
		if (options.bobView) {
			viewBob(camera.entityRenderState, bob);
		}

		projection.mul(bob.last().pose());
		float spin = Math.max(player.portalEffectIntensity, player.nauseaEffectIntensity)
			* (options.screenEffectScale * options.screenEffectScale);
		if (spin > 0) {
			float skew = 5.0F / (spin * spin + 5.0F) - spin * 0.04F;
			skew *= skew;
			Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
			float angle = player.spinningEffectAngle * (float) (Math.PI / 180.0);
			projection.rotate(angle, axis);
			projection.scale(1.0F / skew, 1.0F, 1.0F);
			projection.rotate(-angle, axis);
		}

		return projection;
	}

	/** Mirrors {@code GameRenderer.bobHurt}. */
	private static void hurtTilt(CameraEntityRenderState entity, OptionsRenderState options, PoseStack pose) {
		if (!entity.isLiving) {
			return;
		}

		if (entity.isDeadOrDying) {
			float duration = Math.min(entity.deathTime, 20.0F);
			pose.rotateDegrees(Axis.ZP, 40.0F - 8000.0F / (duration + 200.0F));
		}

		float hurt = entity.hurtTime;
		if (hurt < 0.0F) {
			return;
		}

		hurt /= entity.hurtDuration;
		hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
		pose.rotateDegrees(Axis.YP, -entity.hurtDir);
		pose.rotateDegrees(Axis.ZP, (float) (-hurt * 14.0 * options.damageTiltStrength));
		pose.rotateDegrees(Axis.YP, entity.hurtDir);
	}

	/** Mirrors {@code GameRenderer.bobView}. */
	private static void viewBob(CameraEntityRenderState entity, PoseStack pose) {
		if (!entity.isPlayer) {
			return;
		}

		float walked = entity.backwardsInterpolatedWalkDistance;
		float bob = entity.bob;
		pose.translate(Mth.sin(walked * (float) Math.PI) * bob * 0.5F, -Math.abs(Mth.cos(walked * (float) Math.PI) * bob), 0.0F);
		pose.rotateDegrees(Axis.ZP, Mth.sin(walked * (float) Math.PI) * bob * 3.0F);
		pose.rotateDegrees(Axis.XP, Math.abs(Mth.cos(walked * (float) Math.PI - 0.2F) * bob) * 5.0F);
	}

	/** What the fog distances of this frame come from, in the player's words. */
	private static String sightLimit(CameraRenderState camera, boolean fogCloserThanRenderDistance) {
		if (camera.fogType != FogType.NONE && camera.fogType != FogType.ATMOSPHERIC) {
			return camera.fogType.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		}

		if (camera.entityRenderState.doesMobEffectBlockSky) {
			return "Blindness or Darkness effect";
		}

		return fogCloserThanRenderDistance ? "fog" : "render distance";
	}

	/** The ray through image point {@code x, y} (-1..1, right and up positive). */
	public Ray ray(double x, double y) {
		Vector3f near = inverse.transformProject((float) x, (float) y, nearDepth, new Vector3f());
		Vector3f far = inverse.transformProject((float) x, (float) y, farDepth, new Vector3f());
		Vec3 direction = new Vec3(far.sub(near)).normalize();
		Vec3 ahead = new Vec3(near);

		// Starts where the ray passes closest to the camera: bobbing can move the eye slightly off the camera position.
		Vec3 start = ahead.subtract(direction.scale(ahead.dot(direction)));
		return new Ray(eye.add(start), direction);
	}

	/** The depth in clip space (after the divide) of the point {@code distance} blocks straight ahead. */
	private static float depthAhead(Matrix4fc projection, float distance) {
		Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1.0F));
		return clip.z / clip.w;
	}

	/** Where a world position is in the image, or null when it's behind the camera (it may be off the image edges). */
	public @Nullable ScreenPoint project(Vec3 position) {
		Vec3 relative = position.subtract(eye);
		Vector4f clip = viewProjection.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F));
		if (clip.w < MIN_DEPTH) {
			return null;
		}

		return new ScreenPoint(clip.x / clip.w, clip.y / clip.w);
	}

	/** Image width over height. */
	public double aspect() {
		return (double) imageWidth / imageHeight;
	}

	public Vec3 eye() {
		return eye;
	}

	public int imageWidth() {
		return imageWidth;
	}

	public int imageHeight() {
		return imageHeight;
	}

	/** The camera's facing, without view bobbing. */
	public Vec3 forward() {
		return forward;
	}

	/** Degrees, negative looking up. */
	public float pitch() {
		return pitch;
	}

	public double fovVertical() {
		return fovVertical;
	}

	public double fovHorizontal() {
		return fovHorizontal;
	}

	/** Blocks; beyond this, fog or the render distance hides everything. */
	public double sightDistance() {
		return sightDistance;
	}

	/** What sets {@link #sightDistance()}, e.g. "render distance" or "water". */
	public String sightLimit() {
		return sightLimit;
	}
}
