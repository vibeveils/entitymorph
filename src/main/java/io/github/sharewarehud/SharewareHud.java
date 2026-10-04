package io.github.sharewarehud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import io.github.sharewarehud.SharewareConfig.ExperienceStyle;

public final class SharewareHud implements ClientModInitializer {
	public static final String MOD_ID = "sharewarehud";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Height of the vanilla hotbar the rest of the vanilla HUD is positioned against. */
	private static final int VANILLA_HOTBAR_HEIGHT = 22;

	public static SharewareConfig config;

	@Override
	public void onInitializeClient() {
		config = SharewareConfig.load();
		SharewarePanel panel = new SharewarePanel(config);

		// Every replacement falls back to the original element while the mod is switched off.

		// The panel takes the hotbar's place. The hotbar element is only extracted when the
		// player has a hotbar (not in spectator) and the HUD is visible.
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, original -> (graphics, deltaTracker) -> {
			if (config.enabled) {
				panel.extractRenderState(graphics, deltaTracker);
			} else {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		// Folded into the panel: hearts, armour, mount hearts, hunger, air.
		hiddenWhenEnabled(VanillaHudElements.HEALTH_BAR);
		hiddenWhenEnabled(VanillaHudElements.ARMOR_BAR);
		hiddenWhenEnabled(VanillaHudElements.FOOD_BAR);
		hiddenWhenEnabled(VanillaHudElements.AIR_BAR);
		hiddenWhenEnabled(VanillaHudElements.MOUNT_HEALTH);

		// Text that normally sits just above the hotbar is pushed above the panel.
		liftedWhenEnabled(VanillaHudElements.HELD_ITEM_TOOLTIP);
		liftedWhenEnabled(VanillaHudElements.OVERLAY_MESSAGE);

		// The contextual bar (XP / locator / horse jump). Jump and XP have meters in the panel;
		// the "vanilla" XP style keeps the whole bar, moved above the panel.
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, original -> (graphics, deltaTracker) -> {
			if (!config.enabled) {
				original.extractRenderState(graphics, deltaTracker);
			} else if (config.experienceStyle == ExperienceStyle.VANILLA) {
				translated(graphics, deltaTracker, original, lift());
			}
		});
		HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, original -> (graphics, deltaTracker) -> {
			if (!config.enabled) {
				original.extractRenderState(graphics, deltaTracker);
			} else if (config.experienceStyle == ExperienceStyle.VANILLA) {
				translated(graphics, deltaTracker, original, lift());
			}
		});

		LOGGER.info("MineCraft 3D: Memory Block Edition HUD loaded");
	}

	/** How far (negative = up) to move vanilla elements so they clear the panel at its current size. */
	private static int lift() {
		int panelHeight = (int) Math.ceil(SharewarePanel.HEIGHT * config.hudScale);
		return -Math.max(0, panelHeight - VANILLA_HOTBAR_HEIGHT);
	}

	private static void hiddenWhenEnabled(Identifier id) {
		HudElementRegistry.replaceElement(id, original -> (graphics, deltaTracker) -> {
			if (!config.enabled) original.extractRenderState(graphics, deltaTracker);
		});
	}

	private static void liftedWhenEnabled(Identifier id) {
		HudElementRegistry.replaceElement(id, original -> (graphics, deltaTracker) ->
				translated(graphics, deltaTracker, original, config.enabled ? lift() : 0));
	}

	private static void translated(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, HudElement element, int dy) {
		if (dy == 0) {
			element.extractRenderState(graphics, deltaTracker);
			return;
		}

		graphics.pose().pushMatrix();
		graphics.pose().translate(0.0F, (float) dy);
		element.extractRenderState(graphics, deltaTracker);
		graphics.pose().popMatrix();
	}
}
