package dev.entitymorph.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;

import dev.entitymorph.render.MorphManager;

/**
 * extractPlayerState extracts the local player for the first-person hands and expects an
 * AvatarRenderState back, so morph substitution is switched off for that one call.
 * (The third-person body is extracted separately, with the morph applied.)
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@WrapOperation(
			method = "extractPlayerState",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;")
	)
	private EntityRenderState entitymorph$noMorphForHands(LevelExtractor self, Entity entity, float partialTick, Operation<EntityRenderState> original) {
		MorphManager.setSuppressed(true);
		try {
			return original.call(self, entity, partialTick);
		} finally {
			MorphManager.setSuppressed(false);
		}
	}
}
