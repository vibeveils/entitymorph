package dev.entitymorph.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import dev.entitymorph.render.MorphManager;

/**
 * World rendering: every entity's render state is extracted here, so swapping the entity for its
 * morph proxy makes the proxy's renderer produce the state (and later submit it).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@ModifyVariable(
			method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @At("HEAD"),
			argsOnly = true
	)
	private Entity entitymorph$swapEntity(Entity entity) {
		return MorphManager.substitute(entity);
	}

	/** Remember the camera so shoulder morphs (drawn from inside the player's layer) can submit with it. */
	@Inject(
			method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
			at = @At("HEAD"),
			require = 0
	)
	private void entitymorph$captureCamera(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
										   PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		MorphManager.setCamera(camera);
	}
}
