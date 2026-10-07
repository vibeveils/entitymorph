package dev.entitymorph.render;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.skin.SkinCache;

/** Helpers that turn a {@link MorphEntry} into the textures/skins the renderers need. */
public final class MorphSkins {
	/** Texture override carried on a render state from extraction to submission. */
	public static final RenderStateDataKey<Identifier> TEXTURE = RenderStateDataKey.create(() -> "entitymorph texture override");
	/** Model scale for renderers that don't apply the SCALE attribute themselves (ender dragon). */
	/** Morphed shoulder parrots, extracted with the player and drawn by the shoulder layer. */
	public static final RenderStateDataKey<ShoulderRender> SHOULDER_LEFT = RenderStateDataKey.create(() -> "entitymorph shoulder left");
	public static final RenderStateDataKey<ShoulderRender> SHOULDER_RIGHT = RenderStateDataKey.create(() -> "entitymorph shoulder right");
	public static final RenderStateDataKey<Float> MODEL_SCALE = RenderStateDataKey.create(() -> "entitymorph model scale");

	private MorphSkins() {
	}

	/** Replacement skin for an avatar (player or player-model proxy), or null to keep the original. */
	public static @Nullable PlayerSkin avatarSkin(PlayerSkin base, @Nullable MorphEntry e) {
		if (e == null) return null;
		SkinCache.Resolved r = SkinCache.resolve(e);
		if (r == null && (e.arm == null || e.arm == MorphEntry.ArmModel.AUTO)) return null;

		boolean slim = switch (e.arm == null ? MorphEntry.ArmModel.AUTO : e.arm) {
			case WIDE -> false;
			case SLIM -> true;
			case AUTO -> r != null ? r.slim() : base.model() == PlayerModelType.SLIM;
		};
		PlayerModelType model = slim ? PlayerModelType.SLIM : PlayerModelType.WIDE;
		if (r == null) {
			return new PlayerSkin(base.body(), base.cape(), base.elytra(), model, base.secure());
		}
		ClientAsset.Texture body = new ClientAsset.DownloadedTexture(r.texture(), "entitymorph:" + r.texture());
		// Someone else's skin shouldn't wear the source's cape.
		return new PlayerSkin(body, null, null, model, false);
	}

	/** Texture override for non-player models, or null. */
	public static @Nullable Identifier entityTexture(@Nullable MorphEntry e) {
		SkinCache.Resolved r = SkinCache.resolve(e);
		return r == null ? null : r.texture();
	}

	/** Entry for the local player, used for first-person hands. */
	public static @Nullable MorphEntry localPlayerEntry() {
		Entity p = Minecraft.getInstance().player;
		return p == null ? null : MorphManager.entryFor(p);
	}
}
