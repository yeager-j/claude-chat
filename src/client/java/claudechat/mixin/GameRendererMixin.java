package claudechat.mixin;

import claudechat.ViewFrame;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hands {@code look} the projection the level is drawn with. {@code renderLevel} builds it in a local from the camera's
 * projection, hurt tilt, view bobbing and the nausea warp, and only passes it on to the GPU buffer.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f claudechat$captureLevelProjection(Matrix4f projection) {
		ViewFrame.levelDrawnWith(projection);
		return projection;
	}
}
