package dev.entitymorph.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;

import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.render.DragonAnimator;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.render.MorphSkins;

/** Tags every extracted render state with the texture override (if any) for non-player models. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
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
	}
}
