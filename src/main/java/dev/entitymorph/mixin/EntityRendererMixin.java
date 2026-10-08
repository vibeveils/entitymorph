package dev.entitymorph.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;

import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.render.DragonAnimator;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.render.RidingPose;
import dev.entitymorph.render.MouthItemLayer;
import dev.entitymorph.render.MorphSkins;

/** Tags every extracted render state with the texture override (if any) for non-player models. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
	/**
	 * Leads: if the holder is morphed, attach the lead where the morph holds it (its own rope position /
	 * quad-leash offsets) instead of the hidden real entity.
	 */
	@ModifyExpressionValue(
			method = "extractRenderState",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;getLeashHolder()Lnet/minecraft/world/entity/Entity;"),
			require = 0
	)
	private Entity entitymorph$morphedLeashHolder(Entity holder) {
		Entity proxy = MorphManager.existingProxy(holder);
		return proxy != null ? proxy : holder;
	}

	/**
	 * Riding pose: humanoid models (zombies, skeletons, piglins, endermen, villagers' illager cousins…)
	 * sit when their render state says "passenger". The morph itself never rides anything, so copy
	 * the real entity's riding state once the whole extraction chain has finished.
	 */
	@Inject(method = "createRenderState", at = @At("RETURN"), require = 0)
	private void entitymorph$ridingPose(CallbackInfoReturnable<S> cir, @Local(argsOnly = true) Entity entity) {
		Entity source = MorphManager.sourceOf(entity);
		if (source == entity) return;
		Entity vehicle = source.getVehicle();
		boolean sit = vehicle != null && RidingPose.riderSits(vehicle);
		if (sit) RidingPose.markPassenger(cir.getReturnValue());
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void entitymorph$tagTexture(CallbackInfo ci, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) S state) {
		Identifier texture = null;
		if (!(state instanceof AvatarRenderState)) {
			MorphEntry e = MorphManager.entryFor(entity);
			if (e != null) texture = MorphSkins.entityTexture(e);
		}
		((FabricRenderState) state).setData(MorphSkins.TEXTURE, texture);

		// Renderers that don't extend LivingEntityRenderer (the ender dragon) ignore the SCALE attribute,
		// so carry the morph's size to submit time ourselves.
		Float scale = null;
		if (DragonAnimator.isDragon(entity) && MorphManager.sourceOf(entity) != entity) {
			float s = DragonAnimator.scaleOf(entity);
			if (Math.abs(s - 1.0F) > 1.0E-3F) scale = s;
		}
		((FabricRenderState) state).setData(MorphSkins.MODEL_SCALE, scale);

		if (entity.getType() == EntityTypes.WOLF && entity instanceof LivingEntity living) {
			MouthItemLayer.extract(living, (FabricRenderState) state);
		} else if (entity.getType() == EntityTypes.ENDERMAN && entity instanceof LivingEntity living) {
			MouthItemLayer.extract(living, (FabricRenderState) state, true);
		}
	}
}
