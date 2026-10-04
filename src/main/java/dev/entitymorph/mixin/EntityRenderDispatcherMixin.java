package dev.entitymorph.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
}
