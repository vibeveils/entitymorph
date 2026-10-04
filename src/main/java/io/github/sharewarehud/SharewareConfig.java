package io.github.sharewarehud;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * JSON config stored at {@code config/sharewarehud.json}.
 * Editable in-game through Mod Menu, or by hand (restart to apply hand edits).
 */
public final class SharewareConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sharewarehud.json");

	public enum ExperienceStyle {
		/** Level number and a meter inside the panel. */
		PANEL,
		/** The normal vanilla XP bar, moved above the panel. */
		VANILLA,
		/** No XP at all, like the original April Fools version. */
		HIDDEN
	}

	/** Master switch. When off, the vanilla HUD is shown unchanged. */
	public boolean enabled = true;
	/** Size of the panel relative to the GUI scale. 1.0 = same as the GUI scale, smaller shrinks it. */
	public float hudScale = 1.0F;
	/** Opacity of the panel's contents: numbers, labels, icons, selection box. */
	public float hudOpacity = 1.0F;
	/** Opacity of the panel's frame, section boxes and slot backgrounds. */
	public float backgroundOpacity = 1.0F;
	public ExperienceStyle experienceStyle = ExperienceStyle.PANEL;

	/** Size of the player model inside the portrait box (pixels per block). */
	public int portraitScale = 42;
	/** Shifts the model inside the portrait box. Higher = model sits lower, showing more of the head. */
	public float portraitYOffset = 0.55F;
	/** The portrait's head glances left/right at random, like the original. */
	public boolean portraitLooksAround = true;
	/** Draw item count / durability overlays on the 3x3 hotbar. */
	public boolean showItemDecorations = true;

	public static SharewareConfig load() {
		SharewareConfig config = new SharewareConfig();

		try {
			if (Files.exists(FILE)) {
				try (Reader reader = Files.newBufferedReader(FILE)) {
					SharewareConfig read = GSON.fromJson(reader, SharewareConfig.class);
					if (read != null) config = read;
				}
			}
		} catch (Exception e) {
			SharewareHud.LOGGER.warn("Could not read {}, using defaults", FILE, e);
		}

		config.sanitize();
		config.save();
		return config;
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(this, writer);
		} catch (Exception e) {
			SharewareHud.LOGGER.warn("Could not write {}", FILE, e);
		}
	}

	public void copyFrom(SharewareConfig other) {
		enabled = other.enabled;
		hudScale = other.hudScale;
		hudOpacity = other.hudOpacity;
		backgroundOpacity = other.backgroundOpacity;
		experienceStyle = other.experienceStyle;
		portraitScale = other.portraitScale;
		portraitYOffset = other.portraitYOffset;
		portraitLooksAround = other.portraitLooksAround;
		showItemDecorations = other.showItemDecorations;
	}

	public void sanitize() {
		hudScale = clamp(hudScale, 0.1F, 2.0F);
		hudOpacity = clamp(hudOpacity, 0.0F, 1.0F);
		backgroundOpacity = clamp(backgroundOpacity, 0.0F, 1.0F);
		portraitScale = Math.max(1, Math.min(200, portraitScale));
		portraitYOffset = clamp(portraitYOffset, -3.0F, 3.0F);
		if (experienceStyle == null) experienceStyle = ExperienceStyle.PANEL;
	}

	private static float clamp(float v, float min, float max) {
		return Float.isNaN(v) ? max : Math.max(min, Math.min(max, v));
	}
}
