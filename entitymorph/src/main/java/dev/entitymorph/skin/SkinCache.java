package dev.entitymorph.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import dev.entitymorph.EntityMorphClient;
import dev.entitymorph.config.MorphConfig;
import dev.entitymorph.config.MorphEntry;

/**
 * Resolves a {@link MorphEntry}'s skin settings into a texture {@link Identifier}.
 * Player-name and file skins load asynchronously; until then {@link #resolve} returns null and the
 * entity keeps its normal texture.
 */
public final class SkinCache {
	public record Resolved(Identifier texture, boolean slim) {
	}

	public enum Status { LOADING, READY, FAILED }

	private static final class Slot {
		volatile Status status = Status.LOADING;
		volatile Resolved resolved;
		volatile String error;
		Identifier registered;
	}

	private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();
	private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "EntityMorph skin loader");
		t.setDaemon(true);
		return t;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static int counter;

	private SkinCache() {
	}

	private static String key(MorphEntry e) {
		String v = e.skinValue.trim();
		if (e.skinType == MorphEntry.SkinType.PLAYER_NAME) v = v.toLowerCase(Locale.ROOT);
		return e.skinType.name() + ":" + v;
	}

	/** @return the texture to use, or null if there is no override / it isn't ready yet. */
	public static Resolved resolve(MorphEntry e) {
		if (e == null || !e.hasSkin()) return null;
		if (e.skinType == MorphEntry.SkinType.RESOURCE) {
			Identifier id = parseId(e.skinValue.trim());
			return id == null ? null : new Resolved(id, false);
		}
		Slot slot = SLOTS.computeIfAbsent(key(e), k -> startLoad(e));
		return slot.status == Status.READY ? slot.resolved : null;
	}

	public static Status status(MorphEntry e) {
		if (e == null || !e.hasSkin()) return Status.READY;
		if (e.skinType == MorphEntry.SkinType.RESOURCE) {
			return parseId(e.skinValue.trim()) == null ? Status.FAILED : Status.READY;
		}
		Slot slot = SLOTS.get(key(e));
		return slot == null ? Status.LOADING : slot.status;
	}

	public static String error(MorphEntry e) {
		if (e == null) return null;
		if (e.skinType == MorphEntry.SkinType.RESOURCE && parseId(e.skinValue.trim()) == null) return "Invalid texture id";
		Slot slot = SLOTS.get(key(e));
		return slot == null ? null : slot.error;
	}

	/** Forget a loaded skin so it is fetched/read again (e.g. after editing the file). */
	public static void invalidate(MorphEntry e) {
		if (e == null || !e.hasSkin()) return;
		Slot slot = SLOTS.remove(key(e));
		if (slot != null && slot.registered != null) {
			Identifier id = slot.registered;
			Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
		}
	}

	public static void clearAll() {
		for (Slot slot : SLOTS.values()) {
			if (slot.registered != null) {
				Identifier id = slot.registered;
				Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
			}
		}
		SLOTS.clear();
	}

	private static Identifier parseId(String s) {
		if (s.isEmpty()) return null;
		try {
			return Identifier.parse(s);
		} catch (Exception ex) {
			return null;
		}
	}

	private static Slot startLoad(MorphEntry entry) {
		Slot slot = new Slot();
		MorphEntry e = entry.copy();
		CompletableFuture.supplyAsync(() -> {
			try {
				return switch (e.skinType) {
					case FILE -> readFile(e.skinValue.trim());
					case PLAYER_NAME -> fetchPlayer(e.skinValue.trim());
					default -> throw new IllegalStateException("unsupported");
				};
			} catch (Exception ex) {
				throw new RuntimeException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex);
			}
		}, IO).whenComplete((loaded, err) -> {
			if (err != null) {
				Throwable cause = err.getCause() != null ? err.getCause() : err;
				slot.error = cause.getMessage();
				slot.status = Status.FAILED;
				EntityMorphClient.LOGGER.warn("Could not load skin {}: {}", key(e), slot.error);
				return;
			}
			Minecraft.getInstance().execute(() -> upload(slot, loaded));
		});
		return slot;
	}

	private record Loaded(byte[] png, boolean slim, String label) {
	}

	private static void upload(Slot slot, Loaded loaded) {
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(loaded.png));
			Identifier id = Identifier.fromNamespaceAndPath(EntityMorphClient.MOD_ID, "dynamic/skin_" + (counter++));
			DynamicTexture texture = new DynamicTexture("EntityMorph " + loaded.label, image.getWidth(), image.getHeight(), true);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			texture.setPixels(image);
			texture.upload();
			slot.registered = id;
			slot.resolved = new Resolved(id, loaded.slim);
			slot.status = Status.READY;
		} catch (Exception ex) {
			slot.error = "Bad image: " + ex.getMessage();
			slot.status = Status.FAILED;
			EntityMorphClient.LOGGER.warn("Could not upload skin {}", loaded.label, ex);
		}
	}

	// ---------------------------------------------------------------- sources

	private static Loaded readFile(String name) throws Exception {
		Path dir = MorphConfig.skinsDir().toAbsolutePath().normalize();
		Path p = dir.resolve(name).normalize();
		if (!p.startsWith(dir)) throw new IllegalArgumentException("File must be inside the skins folder");
		if (!Files.isRegularFile(p) && !name.toLowerCase(Locale.ROOT).endsWith(".png")) {
			p = dir.resolve(name + ".png").normalize();
		}
		if (!Files.isRegularFile(p)) throw new IllegalArgumentException("Not found: " + dir.relativize(p));
		byte[] bytes = Files.readAllBytes(p);
		String lower = name.toLowerCase(Locale.ROOT);
		boolean slim = lower.contains("slim") || lower.contains("alex");
		return new Loaded(normalizeSkin(bytes), slim, name);
	}

	private static Loaded fetchPlayer(String name) throws Exception {
		if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid player name");
		JsonObject profile = getJson("https://api.mojang.com/users/profiles/minecraft/" + name);
		if (profile == null || !profile.has("id")) throw new IllegalArgumentException("No such player");
		String id = profile.get("id").getAsString();
		JsonObject session = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
		if (session == null || !session.has("properties")) throw new IllegalStateException("No profile data");

		String skinUrl = null;
		boolean slim = false;
		for (var el : session.getAsJsonArray("properties")) {
			JsonObject prop = el.getAsJsonObject();
			if (!"textures".equals(prop.get("name").getAsString())) continue;
			String json = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
			JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
			if (textures != null && textures.has("SKIN")) {
				JsonObject skin = textures.getAsJsonObject("SKIN");
				skinUrl = skin.get("url").getAsString();
				if (skin.has("metadata")) {
					JsonObject meta = skin.getAsJsonObject("metadata");
					slim = meta.has("model") && "slim".equals(meta.get("model").getAsString());
				}
			}
		}
		if (skinUrl == null) throw new IllegalStateException("Player has no custom skin");
		if (skinUrl.startsWith("http://")) skinUrl = "https://" + skinUrl.substring(7);

		HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(skinUrl)).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200) throw new IllegalStateException("Skin download failed (" + res.statusCode() + ")");
		return new Loaded(normalizeSkin(res.body()), slim, name);
	}

	private static JsonObject getJson(String url) throws Exception {
		HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() == 204 || res.statusCode() == 404) return null;
		if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from " + URI.create(url).getHost());
		return JsonParser.parseString(res.body()).getAsJsonObject();
	}

	/** Converts legacy 64x32 player skins to 64x64 (the same way vanilla does). Other images pass through. */
	private static byte[] normalizeSkin(byte[] png) throws Exception {
		BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
		if (src == null) throw new IllegalArgumentException("Not a PNG image");
		if (src.getWidth() != 64 || src.getHeight() != 32) return png;

		BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) img.setRGB(x, y, src.getRGB(x, y));
		}
		copyRect(img, 4, 16, 16, 32, 4, 4);
		copyRect(img, 8, 16, 16, 32, 4, 4);
		copyRect(img, 0, 20, 24, 32, 4, 12);
		copyRect(img, 4, 20, 16, 32, 4, 12);
		copyRect(img, 8, 20, 8, 32, 4, 12);
		copyRect(img, 12, 20, 16, 32, 4, 12);
		copyRect(img, 44, 16, -8, 32, 4, 4);
		copyRect(img, 48, 16, -8, 32, 4, 4);
		copyRect(img, 40, 20, 0, 32, 4, 12);
		copyRect(img, 44, 20, -8, 32, 4, 12);
		copyRect(img, 48, 20, -16, 32, 4, 12);
		copyRect(img, 52, 20, -8, 32, 4, 12);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** Copy a w*h block from (sx,sy) to (sx+ox, sy+oy), mirrored horizontally. */
	private static void copyRect(BufferedImage img, int sx, int sy, int ox, int oy, int w, int h) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = img.getRGB(sx + x, sy + y);
				img.setRGB(sx + ox + (w - 1 - x), sy + oy + y, argb);
			}
		}
	}
}
