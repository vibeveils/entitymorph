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
								  String defaultValue, Function<String, @Nullable Object> decoder,
								  java.util.function.@Nullable BiConsumer<Entity, String> applier) {
		public ComponentOption(String id, String label, List<String> values, List<String> labels,
							   String defaultValue, Function<String, @Nullable Object> decoder) {
			this(id, label, values, labels, defaultValue, decoder, null);
		}

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
			return l.contains("variant") || l.contains("type") || l.contains("size") || l.contains("profession")
					|| l.contains("level") || l.contains("oxidation");
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
		out.addAll(ExtraOptions.optionsFor(sample));
		out.sort((a, b) -> a.isPrimary() != b.isPrimary() ? (a.isPrimary() ? -1 : 1) : a.label().compareTo(b.label()));
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

	/**
	 * An on/off look. Supported when any {@code calls} setter exists, or a static synced-data field whose
	 * name contains {@code data} holds a boolean, or (for {@code virtual} flags, applied elsewhere) the mob
	 * is one of {@code onlyFor}.
	 */
	public record Flag(String id, String label, java.util.Set<String> onlyFor, List<Call> calls,
					   @Nullable String data, boolean dataValue, boolean virtual) {
	}

	private static Flag flag(String id, String label, Call... calls) {
		return new Flag(id, label, java.util.Set.of(), List.of(calls), null, true, false);
	}

	private static Flag dataFlag(String id, String label, String data, boolean value, Call... calls) {
		return new Flag(id, label, java.util.Set.of(), List.of(calls), data, value, false);
	}

	private static Flag only(String id, String label, java.util.Set<String> onlyFor, Call... calls) {
		return new Flag(id, label, onlyFor, List.of(calls), null, true, false);
	}

	private static Flag virtual(String id, String label, java.util.Set<String> onlyFor) {
		return new Flag(id, label, onlyFor, List.of(), null, true, true);
	}

	/** A setter call to try: method name, argument types and the arguments for "on". */
	public record Call(String method, Class<?>[] types, Object[] args) {
		static Call of(String method, boolean value) {
			return new Call(method, new Class<?>[]{boolean.class}, new Object[]{value});
		}

		static Call ofInt(String method, int value) {
			return new Call(method, new Class<?>[]{int.class}, new Object[]{value});
		}
	}

	public static final String SADDLE = "saddle";
	public static final String SMOKE = "smoke";

	public static final List<Flag> FLAGS = List.of(
			flag("tamed", "Tamed",
					new Call("setTame", new Class<?>[]{boolean.class, boolean.class}, new Object[]{true, false}),
					Call.of("setTame", true), Call.of("setTamed", true)),
			flag("sitting", "Sitting",
					Call.of("setInSittingPose", true), Call.of("setOrderedToSit", true),
					Call.of("setSitting", true), Call.of("sit", true)),
			// Endermen open their mouth from the "creepy" synced flag, not from anger.
			dataFlag("angry", "Angry", "CREEPY", true,
					new Call("setRemainingPersistentAngerTime", new Class<?>[]{int.class}, new Object[]{Integer.MAX_VALUE / 2}),
					new Call("setPersistentAngerEndTime", new Class<?>[]{long.class}, new Object[]{Long.MAX_VALUE / 2}),
					Call.of("setAngry", true), Call.of("setCreepy", true)),
			// Every Mob has setAggressive, but only these models actually show it (raised arms, aiming, charging).
			only("aggressive", "Aggressive",
					java.util.Set.of("Zombie", "AbstractSkeleton", "AbstractIllager", "AbstractPiglin", "Giant"),
					Call.of("setAggressive", true)),
			virtual(SADDLE, "Saddle", java.util.Set.of("AbstractHorse", "Pig", "Strider", "Camel")),
			flag("arms", "Arms", Call.of("setShowArms", true)),
			flag("no_base", "No base plate", Call.of("setNoBasePlate", true)),
			flag("nectar", "Pollinated", Call.of("setHasNectar", true)),
			flag("sheared", "Sheared", Call.of("setSheared", true)),
			flag("no_pumpkin", "No pumpkin", Call.of("setPumpkin", false)),
			flag("sleeping", "Sleeping", Call.of("setSleeping", true)),
			flag("resting", "Hanging (resting)", Call.of("setResting", true)),
			flag("lying", "Lying down", Call.of("setLying", true)),
			flag("interested", "Begging / curious", Call.of("setIsInterested", true)),
			flag("crouching", "Crouching", Call.of("setIsCrouching", true)),
			flag("chest", "Chest", Call.of("setChest", true)),
			flag("screaming", "Screaming", Call.of("setScreamingGoat", true)),
			dataFlag("no_left_horn", "No left horn", "LEFT_HORN", false),
			dataFlag("no_right_horn", "No right horn", "RIGHT_HORN", false),
			flag("playing_dead", "Playing dead", Call.of("setPlayingDead", true)),
			dataFlag("charged", "Charged", "POWERED", true, Call.of("setPowered", true)),
			flag("cold", "Cold (shivering)", Call.of("setSuffocating", true)),
			flag("invulnerable", "Invulnerable (blue)", Call.ofInt("setInvulnerableTicks", 220)),
			virtual(SMOKE, "Smoke particles", java.util.Set.of("Blaze")),
			flag("puffed", "Puffed up", Call.ofInt("setPuffState", 2)),
			flag("dancing", "Dancing", Call.of("setDancing", true)),
			flag("open_shell", "Shell open", Call.ofInt("setRawPeekAmount", 100))
	);

	private static final Map<String, List<Flag>> FLAG_CACHE = new HashMap<>();

	public static List<Flag> flagsFor(String model, Entity sample) {
		return FLAG_CACHE.computeIfAbsent(model, k -> {
			List<Flag> out = new ArrayList<>();
			for (Flag f : FLAGS) {
				if (supports(sample, f)) out.add(f);
			}
			return Collections.unmodifiableList(out);
		});
	}

	private static boolean supports(Entity sample, Flag f) {
		Class<?> c = sample.getClass();
		if (!f.onlyFor().isEmpty() && !isA(c, f.onlyFor())) return false;
		if (f.virtual()) return true;
		for (Call call : f.calls()) {
			if (findMethod(c, call.method(), call.types()) != null) return true;
		}
		return f.data() != null && !booleanData(sample, f.data()).isEmpty();
	}

	public static boolean hasFlag(@Nullable MorphEntry e, String id) {
		return e != null && e.flags != null && e.flags.contains(id);
	}

	private static boolean isA(Class<?> c, java.util.Set<String> simpleNames) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			if (simpleNames.contains(k.getSimpleName())) return true;
		}
		return false;
	}

	static @Nullable Method findMethod(Class<?> c, String name, Class<?>[] types) {
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

	/** Static synced-data accessors whose name contains {@code namePart} and whose value is a Boolean. */
	@SuppressWarnings("rawtypes")
	static List<net.minecraft.network.syncher.EntityDataAccessor> booleanData(Entity e, String namePart) {
		List<net.minecraft.network.syncher.EntityDataAccessor> out = new ArrayList<>();
		for (Class<?> k = e.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) || !net.minecraft.network.syncher.EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
				if (!f.getName().toUpperCase(Locale.ROOT).contains(namePart)) continue;
				try {
					f.setAccessible(true);
					var acc = (net.minecraft.network.syncher.EntityDataAccessor) f.get(null);
					if (e.getEntityData().get(acc) instanceof Boolean) out.add(acc);
				} catch (Throwable ignored) {
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ apply

	/** Applies components, extra options and flags from {@code e} to a freshly created proxy. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void apply(Entity proxy, String model, MorphEntry e) {
		if (e.components != null && !e.components.isEmpty()) {
			for (ComponentOption opt : componentsFor(model, proxy)) {
				String v = e.components.get(opt.id());
				if (v == null) continue;
				if (opt.applier() != null) {
					try {
						opt.applier().accept(proxy, v);
					} catch (Throwable t) {
						EntityMorphClient.LOGGER.debug("Option {} failed", opt.id(), t);
					}
					continue;
				}
				Object decoded = opt.decoder().apply(v);
				if (decoded == null) continue;
				DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(opt.id()));
				if (type != null) setComponent(proxy, type, decoded);
			}
		}
		if (e.flags != null) {
			for (String id : e.flags) {
				for (Flag f : FLAGS) {
					if (f.id().equals(id) && !f.virtual()) applyFlag(proxy, f);
				}
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void applyFlag(Entity proxy, Flag f) {
		for (Call c : f.calls()) {
			Method m = findMethod(proxy.getClass(), c.method(), c.types());
			if (m == null) continue;
			try {
				m.invoke(proxy, c.args());
				// Sitting needs both "ordered" and "pose" on tamables; keep trying other calls for it.
				if (!f.id().equals("sitting")) break;
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Flag {} failed via {}", f.id(), c.method(), t);
			}
		}
		if (f.data() != null) {
			for (var acc : booleanData(proxy, f.data())) {
				try {
					proxy.getEntityData().set(acc, f.dataValue());
				} catch (Throwable t) {
					EntityMorphClient.LOGGER.debug("Flag {} data failed", f.id(), t);
				}
			}
		}
	}

	/** Equipment the morph forces regardless of what the real entity wears: saddle, llama decor. */
	public static Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> equipmentOverrides(@Nullable MorphEntry e) {
		if (e == null) return Map.of();
		Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> out = new java.util.EnumMap<>(net.minecraft.world.entity.EquipmentSlot.class);
		if (hasFlag(e, SADDLE)) {
			out.put(net.minecraft.world.entity.EquipmentSlot.SADDLE, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE));
		}
		if (e.components != null) {
			ExtraOptions.decorItem(e.components.get(ExtraOptions.DECOR)).ifPresent(id -> {
				var item = BuiltInRegistries.ITEM.getValue(id);
				if (item != null) out.put(net.minecraft.world.entity.EquipmentSlot.BODY, new net.minecraft.world.item.ItemStack(item));
			});
		}
		return out;
	}
}
