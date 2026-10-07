package dev.entitymorph.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.animal.parrot.Parrot;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;

import dev.entitymorph.render.MorphManager;
import dev.entitymorph.render.MorphSkins;
import dev.entitymorph.render.ShoulderRender;

/** Draws a morphed shoulder parrot as its morph instead of a plain parrot. */
@Mixin(ParrotOnShoulderLayer.class)
public abstract class ParrotOnShoulderLayerMixin {
	@Inject(method = "submitOnShoulder", at = @At("HEAD"), cancellable = true)
	private void entitymorph$morphOnShoulder(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
											 Parrot.Variant variant, float yRot, float xRot, boolean left, CallbackInfo ci) {
		ShoulderRender render = ((FabricRenderState) state).getData(left ? MorphSkins.SHOULDER_LEFT : MorphSkins.SHOULDER_RIGHT);
		CameraRenderState camera = MorphManager.camera();
		if (render == null || camera == null) return;

		poseStack.pushPose();
		// Same spot the vanilla parrot's feet land on (player model space is y-down).
		poseStack.translate(left ? 0.4F : -0.4F, state.isCrouching ? 0.2F : 0.0F, 0.0F);
		// Back to y-up entity space; the morph's own renderer applies its usual flips from here.
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		poseStack.scale(render.scale(), render.scale(), render.scale());
		render.state().lightCoords = light;
		try {
			render.submit(poseStack, collector, camera);
		} finally {
			poseStack.popPose();
		}
		ci.cancel();
	}
}
