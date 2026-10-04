package dev.entitymorph.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;

import dev.entitymorph.render.MorphSkins;

/** Applies the texture override when the model's render type is picked. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@ModifyExpressionValue(
			method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
			require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getTextureLocation(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Lnet/minecraft/resources/Identifier;")
	)
	private Identifier entitymorph$overrideTexture(Identifier original, @Local(argsOnly = true) LivingEntityRenderState state) {
		Identifier override = ((FabricRenderState) state).getData(MorphSkins.TEXTURE);
		return override != null ? override : original;
	}
}
