package dev.entitymorph.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;

import dev.entitymorph.render.MorphSkins;

/**
 * The dragon renderer isn't a LivingEntityRenderer, so it never applies the SCALE attribute that
 * "Size: Fit hitbox / eye height" sets. Scale the whole submission around the entity's origin instead.
 */
@Mixin(EnderDragonRenderer.class)
public abstract class EnderDragonRendererMixin {
	@org.spongepowered.asm.mixin.Unique
	private static boolean entitymorph$logged;

	private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

	@Inject(method = SUBMIT, at = @At("HEAD"), require = 0)
	private void entitymorph$scalePush(EnderDragonRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		Float scale = ((FabricRenderState) state).getData(MorphSkins.MODEL_SCALE);
		if (scale != null) {
			if (!entitymorph$logged) {
				entitymorph$logged = true;
				dev.entitymorph.EntityMorphClient.LOGGER.info("Scaling ender dragon morph by {}", scale);
			}
			poseStack.pushPose();
			poseStack.scale(scale, scale, scale);
		}
	}

	@Inject(method = SUBMIT, at = @At("RETURN"), require = 0)
	private void entitymorph$scalePop(EnderDragonRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (((FabricRenderState) state).getData(MorphSkins.MODEL_SCALE) != null) {
			poseStack.popPose();
		}
	}
}
