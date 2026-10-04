package dev.entitymorph;

import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import dev.entitymorph.config.MorphConfig;
import dev.entitymorph.gui.MorphEditorScreen;
import dev.entitymorph.gui.MorphListScreen;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.skin.SkinCache;

public final class EntityMorphClient implements ClientModInitializer {
	public static final String MOD_ID = "entitymorph";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyMapping openKey;
	private static KeyMapping listKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.entitymorph.open",
				InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_M, category));
		listKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.entitymorph.list",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			MorphManager.clear();
			MorphConfig.onJoin(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
			MorphConfig.onDisconnect();
			MorphManager.clear();
			SkinCache.clearAll();
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null || mc.player == null) return;
			MorphManager.tick();
			while (openKey.consumeClick()) {
				if (mc.screen == null) mc.setScreen(new MorphEditorScreen(null, pickTarget(mc)));
			}
			while (listKey.consumeClick()) {
				if (mc.screen == null) mc.setScreen(new MorphListScreen(null));
			}
		});
	}

	/** The living entity under the crosshair, or the player themself. */
	private static LivingEntity pickTarget(Minecraft mc) {
		if (mc.hitResult instanceof EntityHitResult hit) {
			Entity e = hit.getEntity();
			if (e instanceof LivingEntity living) return living;
		}
		return mc.player;
	}
}
