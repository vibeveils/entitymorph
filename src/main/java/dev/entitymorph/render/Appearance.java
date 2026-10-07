package dev.entitymorph.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;

import dev.entitymorph.EntityMorphClient;
import dev.entitymorph.config.MorphEntry;

/**
 * Discovers and applies per-mob looks:
 * <ul>
 *   <li><b>Components</b> – every entity data component the mob exposes whose value is an enum or a
 *   registry entry (fox type, wolf variant/collar/sound, cat variant/collar, sheep colour, axolotl,
 *   parrot, frog, rabbit, horse, llama, tropical fish pattern/colours, villager type, …). Found
 *   generically, so new variants added by Mojang or mods show up automatically.</li>
 *   <li><b>Flags</b> – on/off states such as tamed, sitting, angry, sheared, found by looking for the
 *   matching setter on the mob's class.</li>
 * </ul>
 * Everything goes through reflection on the (unobfuscated) method names so a missing method just
 * hides that option instead of breaking the build or crashing.
 */
public final class Appearance {
	private Appearance() {
	}

	// ------------------------------------------------------------ components

	/** One choosable component: values are stored as strings (enum name or registry id). */
	public record ComponentOption(String id, String label, List<String> values, List<String> labels,
								  String defaultValue, Function<String, @Nullable Object> decoder) {
		/** The value currently in effect: the chosen one, or what the mob spawns with. */
		public String effective(@Nullable String chosen) {
			return chosen != null && values.contains(chosen) ? chosen : defaultValue;
		}

		public String labelFor(@Nullable String chosen) {
			String v = effective(chosen);
			int i = values.indexOf(v);
			return i < 0 ? v : labels.get(i);
		}

		/** Next value, wrapping around (there is no "default" entry). */
		public String next(@Nullable String chosen) {
			int i = values.indexOf(effective(chosen));
			return values.get((i + 1) % values.size());
		}

		/** Main variant pickers (fox type, wolf variant, sound) sort before colours/collars. */
		public boolean isPrimary() {
			String l = label.toLowerCase(Locale.ROOT);
			return l.contains("variant") || l.contains("type") || l.contains("size");
		}
	}

	private static @Nullable Method getMethod;
	private static @Nullable Method setMethod;
	private static boolean methodsLooked;

	private static void lookupMethods() {
		if (methodsLooked) return;
		methodsLooked = true;
		List<Method> candidates = new ArrayList<>(List.of(Entity.class.getMethods()));
		for (Class<?> c = Entity.class; c != null && c != Object.class; c = c.getSuperclass()) {
			candidates.addAll(List.of(c.getDeclaredMethods()));
		}
		{
			for (Method m : candidates) {
				Class<?>[] p = m.getParameterTypes();
				if (Modifier.isStatic(m.getModifiers()) || p.length < 1 || !DataComponentType.class.isAssignableFrom(p[0])) continue;
				if (getMethod == null && m.getName().equals("get") && p.length == 1) {
					m.setAccessible(true);
					getMethod = m;
				} else if (setMethod == null && m.getName().equals("setComponent") && p.length == 2) {
					m.setAccessible(true);
					setMethod = m;
				}
			}
		}
		if (getMethod == null || setMethod == null) {
			EntityMorphClient.LOGGER.warn("Entity component access not found (get={}, setComponent={}); variants disabled", getMethod, setMethod);
		}
	}

	private static @Nullable Object getComponent(Entity e, DataComponentType<?> type) {
		lookupMethods();
		if (getMethod == null) return null;
		try {
			return getMethod.invoke(e, type);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void setComponent(Entity e, DataComponentType<?> type, Object value) {
		lookupMethods();
		if (setMethod == null) return;
		try {
			setMethod.invoke(e, type, value);
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Could not set {} on {}", type, e, t);
		}
	}

	private static final Map<String, List<ComponentOption>> COMPONENT_CACHE = new HashMap<>();

	public static void clearCache() {
		COMPONENT_CACHE.clear();
		FLAG_CACHE.clear();
	}

	/** Variant components the given model supports (sample is a fresh instance of that model). */
	public static List<ComponentOption> componentsFor(String model, Entity sample) {
		return COMPONENT_CACHE.computeIfAbsent(model, k -> discoverComponents(sample));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static List<ComponentOption> discoverComponents(Entity sample) {
		List<ComponentOption> out = new ArrayList<>();
		for (DataComponentType<?> type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
			Object value = getComponent(sample, type);
			if (value == null) continue;
			Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
			if (id == null) continue;
			String label = componentLabel(id);

			if (value instanceof Enum<?> en) {
				Object[] constants = en.getDeclaringClass().getEnumConstants();
				List<String> values = new ArrayList<>();
				List<String> labels = new ArrayList<>();
				for (Object c : constants) {
					values.add(((Enum<?>) c).name());
					labels.add(pretty(c instanceof StringRepresentable sr ? sr.getSerializedName() : ((Enum<?>) c).name()));
				}
				Class enumClass = en.getDeclaringClass();
				if (values.size() < 2) continue;
				out.add(new ComponentOption(id.toString(), label, values, labels, en.name(), s -> {
					try {
						return Enum.valueOf(enumClass, s);
					} catch (Exception ex) {
						return null;
					}
				}));
			} else if (value instanceof Holder<?> holder) {
				Registry registry = registryOf(holder);
				if (registry == null) continue;
				List<Identifier> ids = new ArrayList<>(registry.keySet());
				ids.sort((a, b) -> a.toString().compareTo(b.toString()));
				if (ids.size() < 2) continue;
				List<String> values = new ArrayList<>();
				List<String> labels = new ArrayList<>();
				for (Identifier vid : ids) {
					values.add(vid.toString());
					labels.add(pretty(vid.getPath()));
				}
				Identifier current = registry.getKey(holder.value());
				String def = current != null ? current.toString() : values.get(0);
				out.add(new ComponentOption(id.toString(), label, values, labels, def, s -> {
					try {
						Object v = registry.getValue(Identifier.parse(s));
						return v == null ? null : registry.wrapAsHolder(v);
					} catch (Exception ex) {
						return null;
					}
				}));
			}
		}
		out.sort((a, b) -> a.label().compareTo(b.label()));
		return Collections.unmodifiableList(out);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Registry<?> registryOf(Holder<?> holder) {
		Optional<? extends ResourceKey<?>> key = (Optional) holder.unwrapKey();
		if (key.isEmpty()) return null;
		ResourceKey registryKey = key.get().registryKey();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			Optional<Registry<?>> fromLevel = (Optional) mc.level.registryAccess().lookup(registryKey);
			if (fromLevel.isPresent()) return fromLevel.get();
		}
		return null;
	}

	private static String componentLabel(Identifier id) {
		String path = id.getPath();
		int slash = path.lastIndexOf('/');
		String last = slash >= 0 ? path.substring(slash + 1) : path;
		String owner = slash >= 0 ? path.substring(0, slash) : "";
		// e.g. "tropical_fish/base_color" -> "Base color", "wolf/sound_variant" -> "Sound variant"
		String l = pretty(last);
		return owner.isEmpty() || last.contains("variant") || last.contains("color") || last.contains("collar")
				|| last.contains("pattern") || last.contains("size") ? l : pretty(owner) + " " + l.toLowerCase(Locale.ROOT);
	}

	public static String pretty(String raw) {
		String s = raw.replace('_', ' ').replace('/', ' ').trim().toLowerCase(Locale.ROOT);
		return s.isEmpty() ? raw : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ------------------------------------------------------------------ flags

	/** An on/off look. {@code methods} are tried in order; the first that exists marks it supported. */
	public record Flag(String id, String label, java.util.Set<String> onlyFor, Call... calls) {
	}

	private static Flag flag(String id, String label, Call... calls) {
		return new Flag(id, label, java.util.Set.of(), calls);
	}

	/** A setter call to try: method name, argument types and the arguments for "on". */
	public record Call(String method, Class<?>[] types, Object[] args) {
		static Call of(String method, boolean value) {
			return new Call(method, new Class<?>[]{boolean.class}, new Object[]{value});
		}
	}

	public static final List<Flag> FLAGS = List.of(
			flag("tamed", "Tamed",
					new Call("setTame", new Class<?>[]{boolean.class, boolean.class}, new Object[]{true, false}),
					Call.of("setTame", true)),
			flag("sitting", "Sitting",
					Call.of("setInSittingPose", true), Call.of("setOrderedToSit", true),
					Call.of("setSitting", true), Call.of("sit", true)),
			flag("angry", "Angry",
					new Call("setRemainingPersistentAngerTime", new Class<?>[]{int.class}, new Object[]{Integer.MAX_VALUE / 2}),
					new Call("setPersistentAngerEndTime", new Class<?>[]{long.class}, new Object[]{Long.MAX_VALUE / 2}),
					Call.of("setAngry", true), Call.of("setCreepy", true)),
			// Every Mob has setAggressive, but only these models actually show it (raised arms, aiming, charging).
			new Flag("aggressive", "Aggressive",
					java.util.Set.of("Zombie", "AbstractSkeleton", "AbstractIllager", "AbstractPiglin", "Giant"),
					Call.of("setAggressive", true)),
			flag("sheared", "Sheared", Call.of("setSheared", true)),
			flag("no_pumpkin", "No pumpkin", Call.of("setPumpkin", false)),
			flag("sleeping", "Sleeping", Call.of("setSleeping", true)),
			flag("resting", "Hanging (resting)", Call.of("setResting", true)),
			flag("lying", "Lying down", Call.of("setLying", true)),
			flag("interested", "Begging / curious", Call.of("setIsInterested", true)),
			flag("crouching", "Crouching", Call.of("setIsCrouching", true)),
			flag("chest", "Chest", Call.of("setChest", true)),
			flag("screaming", "Screaming", Call.of("setScreamingGoat", true)),
			flag("playing_dead", "Playing dead", Call.of("setPlayingDead", true)),
			flag("charged", "Charged", Call.of("setPowered", true)),
			flag("puffed", "Puffed up", new Call("setPuffState", new Class<?>[]{int.class}, new Object[]{2})),
			flag("dancing", "Dancing", Call.of("setDancing", true)),
			flag("hiding", "Peeking (closed)", new Call("setRawPeekAmount", new Class<?>[]{int.class}, new Object[]{0}))
	);

	private static final Map<String, List<Flag>> FLAG_CACHE = new HashMap<>();

	public static List<Flag> flagsFor(String model, Entity sample) {
		return FLAG_CACHE.computeIfAbsent(model, k -> {
			List<Flag> out = new ArrayList<>();
			for (Flag f : FLAGS) {
				if (!f.onlyFor().isEmpty() && !isA(sample.getClass(), f.onlyFor())) continue;
				for (Call c : f.calls()) {
					if (findMethod(sample.getClass(), c.method(), c.types()) != null) {
						out.add(f);
						break;
					}
				}
			}
			// "Charged" via the creeper's synced data if there is no setter.
			if (out.stream().noneMatch(f -> f.id().equals("charged")) && findDataAccessor(sample.getClass(), "DATA_IS_POWERED") != null) {
				out.add(FLAGS.stream().filter(f -> f.id().equals("charged")).findFirst().orElseThrow());
			}
			return Collections.unmodifiableList(out);
		});
	}

	private static boolean isA(Class<?> c, java.util.Set<String> simpleNames) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			if (simpleNames.contains(k.getSimpleName())) return true;
		}
		return false;
	}

	private static @Nullable Method findMethod(Class<?> c, String name, Class<?>[] types) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Method m = k.getDeclaredMethod(name, types);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException | RuntimeException ignored) {
			}
		}
		return null;
	}

	private static @Nullable Field findDataAccessor(Class<?> c, String name) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				if (Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					return f;
				}
			} catch (NoSuchFieldException | RuntimeException ignored) {
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ apply

	/** Applies baby, components and flags from {@code e} to a freshly created proxy. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void apply(Entity proxy, String model, MorphEntry e) {
		if (e.components != null && !e.components.isEmpty()) {
			for (ComponentOption opt : componentsFor(model, proxy)) {
				String v = e.components.get(opt.id());
				if (v == null) continue;
				Object decoded = opt.decoder().apply(v);
				if (decoded == null) continue;
				DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(opt.id()));
				if (type != null) setComponent(proxy, type, decoded);
			}
		}
		if (e.flags != null) {
			for (String id : e.flags) {
				for (Flag f : FLAGS) {
					if (f.id().equals(id)) applyFlag(proxy, f);
				}
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void applyFlag(Entity proxy, Flag f) {
		boolean any = false;
		for (Call c : f.calls()) {
			Method m = findMethod(proxy.getClass(), c.method(), c.types());
			if (m == null) continue;
			try {
				m.invoke(proxy, c.args());
				any = true;
				// Sitting needs both "ordered" and "pose" on tamables; keep trying other calls for it.
				if (!f.id().equals("sitting")) break;
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Flag {} failed via {}", f.id(), c.method(), t);
			}
		}
		if (!any && f.id().equals("charged")) {
			Field acc = findDataAccessor(proxy.getClass(), "DATA_IS_POWERED");
			if (acc != null) {
				try {
					proxy.getEntityData().set((net.minecraft.network.syncher.EntityDataAccessor) acc.get(null), true);
				} catch (Throwable t) {
					EntityMorphClient.LOGGER.debug("Charged flag failed", t);
				}
			}
		}
	}
}
