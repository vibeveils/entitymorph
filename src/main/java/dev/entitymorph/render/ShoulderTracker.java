package dev.entitymorph.render;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import dev.entitymorph.EntityMorphClient;
import dev.entitymorph.config.MorphConfig;

/**
 * Works out which parrot (by UUID) sits on a player's shoulder, so its morph can be drawn there.
 * <ul>
 *   <li>Singleplayer: read straight from the integrated server's player data.</li>
 *   <li>Multiplayer: the client only learns "a parrot of colour X is on the left shoulder". When a
 *   morphed parrot disappears right next to a player whose shoulder then fills up, that parrot is
 *   remembered for that shoulder (saved per world/server so it survives relogging).</li>
 * </ul>
 */
public final class ShoulderTracker {
	private ShoulderTracker() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();

	/** "playerUuid:L" / "playerUuid:R" -> parrot UUID. */
	private static final Map<String, UUID> ASSIGNED = new LinkedHashMap<>();
	private record Pending(UUID player, UUID parrot, long time) {
	}
	private static final List<Pending> PENDING = new ArrayList<>();
	private static @Nullable Path file;

	private static String key(UUID player, boolean left) {
		return player + (left ? ":L" : ":R");
	}

	// ------------------------------------------------------------- lifecycle

	public static void onJoin(@Nullable Path morphFile) {
		ASSIGNED.clear();
		PENDING.clear();
		file = morphFile == null ? null : morphFile.resolveSibling(morphFile.getFileName().toString().replace(".json", "") + ".shoulders.json");
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file)) {
			Map<String, String> raw = GSON.fromJson(r, MAP_TYPE);
			if (raw != null) raw.forEach((k, v) -> {
				try {
					ASSIGNED.put(k, UUID.fromString(v));
				} catch (IllegalArgumentException ignored) {
				}
			});
		} catch (Exception e) {
			EntityMorphClient.LOGGER.warn("Could not read {}", file, e);
		}
	}

	public static void onDisconnect() {
		ASSIGNED.clear();
		PENDING.clear();
		file = null;
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Map<String, String> raw = new LinkedHashMap<>();
			ASSIGNED.forEach((k, v) -> raw.put(k, v.toString()));
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(raw, MAP_TYPE, w);
			}
		} catch (Exception e) {
			EntityMorphClient.LOGGER.warn("Could not write {}", file, e);
		}
	}

	/** A morphed parrot left the client world: it may have just hopped onto a nearby player's shoulder. */
	public static void onUnload(Entity entity, ClientLevel level) {
		if (entity.getType() != EntityTypes.PARROT) return;
		if (MorphConfig.getSaved(entity.getUUID()) == null) return;
		if (entity instanceof LivingEntity living && living.isDeadOrDying()) return;
		Player nearest = null;
		double best = 3.0 * 3.0;
		for (Player p : level.players()) {
			double d = p.distanceToSqr(entity);
			if (d < best) {
				best = d;
				nearest = p;
			}
		}
		if (nearest != null) {
			PENDING.add(new Pending(nearest.getUUID(), entity.getUUID(), level.getGameTime()));
		}
	}

	// ----------------------------------------------------------------- query

	/**
	 * UUID of the parrot on that shoulder, or null if unknown / empty.
	 * {@code occupied} is whether the client currently shows a parrot there.
	 */
	public static @Nullable UUID parrotOn(Player player, boolean left, boolean occupied) {
		String k = key(player.getUUID(), left);
		if (!occupied) {
			if (ASSIGNED.remove(k) != null) save();
			return null;
		}
		UUID fromServer = fromIntegratedServer(player.getUUID(), left);
		if (fromServer != null) {
			if (!fromServer.equals(ASSIGNED.get(k))) {
				ASSIGNED.put(k, fromServer);
				save();
			}
			return fromServer;
		}
		UUID known = ASSIGNED.get(k);
		if (known != null) return known;

		// Claim a recent "morphed parrot vanished next to this player" event.
		long now = player.level().getGameTime();
		Iterator<Pending> it = PENDING.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (now - p.time() > 60) {
				it.remove();
				continue;
			}
			if (p.player().equals(player.getUUID()) && !ASSIGNED.containsValue(p.parrot())) {
				it.remove();
				ASSIGNED.put(k, p.parrot());
				save();
				return p.parrot();
			}
		}
		return null;
	}

	// ------------------------------------------------- singleplayer server peek

	private static final Map<String, Optional<Method>> SHOULDER_GETTERS = new HashMap<>();
	private static @Nullable Method getIntArray;
	private static boolean getIntArrayLooked;

	private static @Nullable UUID fromIntegratedServer(UUID playerId, boolean left) {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return null;
		try {
			Player sp = server.getPlayerList().getPlayer(playerId);
			if (sp == null) return null;
			Method getter = shoulderGetter(sp.getClass(), left);
			if (getter == null) return null;
			Object tag = getter.invoke(sp);
			if (!(tag instanceof CompoundTag ct)) return null;
			int[] ints = intArray(ct, "UUID");
			return ints != null && ints.length == 4 ? UUIDUtil.uuidFromIntArray(ints) : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static @Nullable Method shoulderGetter(Class<?> cls, boolean left) {
		String side = left ? "left" : "right";
		return SHOULDER_GETTERS.computeIfAbsent(cls.getName() + side, k -> {
			for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Method m : c.getDeclaredMethods()) {
					String n = m.getName().toLowerCase(Locale.ROOT);
					if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
							&& CompoundTag.class.isAssignableFrom(m.getReturnType())
							&& n.contains("shoulder") && n.contains(side)) {
						try {
							m.setAccessible(true);
							return Optional.of(m);
						} catch (RuntimeException ignored) {
						}
					}
				}
			}
			return Optional.empty();
		}).orElse(null);
	}

	/** CompoundTag.getIntArray returns int[] in older versions and Optional&lt;int[]&gt; in newer ones. */
	private static int @Nullable [] intArray(CompoundTag tag, String name) throws Exception {
		if (!getIntArrayLooked) {
			getIntArrayLooked = true;
			try {
				getIntArray = CompoundTag.class.getMethod("getIntArray", String.class);
			} catch (NoSuchMethodException ignored) {
			}
		}
		if (getIntArray == null) return null;
		Object r = getIntArray.invoke(tag, name);
		if (r instanceof int[] a) return a;
		if (r instanceof Optional<?> o && o.isPresent() && o.get() instanceof int[] a) return a;
		return null;
	}
}
