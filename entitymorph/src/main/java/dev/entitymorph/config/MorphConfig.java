package dev.entitymorph.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import dev.entitymorph.EntityMorphClient;

/**
 * Per-world / per-server morph storage, keyed by entity UUID.
 * Files live in config/entitymorph/worlds/&lt;world&gt;.json and config/entitymorph/servers/&lt;address&gt;.json.
 */
public final class MorphConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<LinkedHashMap<String, MorphEntry>>() { }.getType();

	private static final Map<UUID, MorphEntry> ENTRIES = new HashMap<>();
	/** Unsaved edits shown live while the editor is open. */
	private static final Map<UUID, MorphEntry> PREVIEWS = new HashMap<>();

	private static Path file;
	private static String scopeLabel = "";
	/** Bumped on every change so caches (proxies) can notice. */
	private static int revision;

	private MorphConfig() {
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(EntityMorphClient.MOD_ID);
	}

	public static Path skinsDir() {
		Path dir = rootDir().resolve("skins");
		try {
			Files.createDirectories(dir);
		} catch (Exception ignored) {
		}
		return dir;
	}

	public static void onJoin(Minecraft mc) {
		ENTRIES.clear();
		PREVIEWS.clear();
		revision++;

		MinecraftServer server = mc.getSingleplayerServer();
		String folder;
		if (server != null) {
			String name;
			try {
				Path p = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
				name = p.getFileName() != null ? p.getFileName().toString() : server.getWorldData().getLevelName();
			} catch (Throwable t) {
				name = server.getWorldData().getLevelName();
			}
			scopeLabel = "World: " + name;
			file = rootDir().resolve("worlds").resolve(safe(name) + ".json");
		} else {
			ServerData data = mc.getCurrentServer();
			String address = data != null ? data.ip : "unknown-server";
			scopeLabel = "Server: " + address;
			file = rootDir().resolve("servers").resolve(safe(address) + ".json");
		}
		folder = file.toString();
		load();
		EntityMorphClient.LOGGER.info("Entity Morph using {} ({} entries)", folder, ENTRIES.size());
	}

	public static void onDisconnect() {
		ENTRIES.clear();
		PREVIEWS.clear();
		file = null;
		scopeLabel = "";
		revision++;
	}

	private static String safe(String s) {
		String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return out.isEmpty() ? "_" : out;
	}

	private static void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file)) {
			Map<String, MorphEntry> raw = GSON.fromJson(r, MAP_TYPE);
			if (raw == null) return;
			raw.forEach((k, v) -> {
				try {
					if (v == null) return;
					v.sanitize();
					if (!v.isEmpty()) ENTRIES.put(UUID.fromString(k), v);
				} catch (IllegalArgumentException ignored) {
				}
			});
		} catch (Exception e) {
			EntityMorphClient.LOGGER.error("Failed to read {}", file, e);
		}
	}

	public static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Map<String, MorphEntry> raw = new LinkedHashMap<>();
			ENTRIES.forEach((k, v) -> raw.put(k.toString(), v));
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(raw, MAP_TYPE, w);
			}
		} catch (Exception e) {
			EntityMorphClient.LOGGER.error("Failed to write {}", file, e);
		}
	}

	public static boolean isActive() {
		return file != null;
	}

	public static String scopeLabel() {
		return scopeLabel;
	}

	public static int revision() {
		return revision;
	}

	/** Effective entry (preview wins over saved). */
	public static MorphEntry get(UUID uuid) {
		if (uuid == null) return null;
		MorphEntry preview = PREVIEWS.get(uuid);
		return preview != null ? preview : ENTRIES.get(uuid);
	}

	public static MorphEntry getSaved(UUID uuid) {
		return ENTRIES.get(uuid);
	}

	public static Map<UUID, MorphEntry> savedEntries() {
		return Collections.unmodifiableMap(ENTRIES);
	}

	public static void put(UUID uuid, MorphEntry entry) {
		if (entry == null || entry.isEmpty()) {
			ENTRIES.remove(uuid);
		} else {
			entry.sanitize();
			ENTRIES.put(uuid, entry.copy());
		}
		revision++;
		save();
	}

	public static void remove(UUID uuid) {
		put(uuid, null);
	}

	public static void setPreview(UUID uuid, MorphEntry entry) {
		if (entry == null) PREVIEWS.remove(uuid);
		else PREVIEWS.put(uuid, entry.copy());
		revision++;
	}

	public static void clearPreview(UUID uuid) {
		if (PREVIEWS.remove(uuid) != null) revision++;
	}
}
