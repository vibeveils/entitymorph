package dev.entitymorph.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;

import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.render.MorphSkins;

/**
 * Player-model handling:
 * - third person / inventory: swaps the skin (and wide/slim arms) on the extracted state;
 * - first person: swaps the arm texture, or hides the bare arm when you're morphed into a non-player model.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin<AvatarlikeEntity extends Avatar & ClientAvatarEntity> {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void entitymorph$applySkin(AvatarlikeEntity entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		MorphEntry e = MorphManager.entryFor(entity);
		if (e == null) return;
		PlayerSkin skin = MorphSkins.avatarSkin(state.skin, e);
		if (skin != null) {
			state.skin = skin;
			if (skin.cape() == null) state.showCape = false;
		}
	}

	@Inject(method = {"renderRightHand", "renderLeftHand"}, at = @At("HEAD"), cancellable = true)
	private void entitymorph$hideHand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
		MorphEntry e = MorphSkins.localPlayerEntry();
		if (e != null && Minecraft.getInstance().player != null && !MorphManager.isPlayerModel(e, Minecraft.getInstance().player)) {
			ci.cancel();
		}
	}

	@ModifyVariable(method = {"renderRightHand", "renderLeftHand"}, at = @At("HEAD"), argsOnly = true)
	private Identifier entitymorph$handSkin(Identifier skin) {
		MorphEntry e = MorphSkins.localPlayerEntry();
		if (e == null) return skin;
		Identifier override = MorphSkins.entityTexture(e);
		return override != null ? override : skin;
	}
}
