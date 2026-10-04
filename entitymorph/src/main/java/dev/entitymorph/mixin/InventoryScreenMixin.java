package dev.entitymorph.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import dev.entitymorph.render.MorphManager;

/**
 * Inventory / creative inventory / editor preview: the paper-doll is drawn through this method, which
 * temporarily turns the entity to face the mouse. Handing it the (freshly synced) proxy shows the morph.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@ModifyVariable(method = "extractEntityInInventoryFollowsMouse", at = @At("HEAD"), argsOnly = true)
	private static LivingEntity entitymorph$swapEntity(LivingEntity entity) {
		Entity swapped = MorphManager.substitute(entity);
		return swapped instanceof LivingEntity living ? living : entity;
	}
}
