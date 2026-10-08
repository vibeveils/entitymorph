package dev.entitymorph.render;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;

import dev.entitymorph.EntityMorphClient;

/**
 * Look options that aren't entity data components, so {@link Appearance}'s generic component scan
 * can't find them: horse markings, villager profession/level, copper golem oxidation, panda gene,
 * slime size and llama decor. Each one is found by method name on the mob's class and hidden when
 * the method isn't there.
 */
final class ExtraOptions {
	private ExtraOptions() {
	}

	static final String PREFIX = "entitymorph:";
	static final String DECOR = PREFIX + "decor";

	static List<Appearance.ComponentOption> optionsFor(Entity sample) {
		List<Appearance.ComponentOption> out = new ArrayList<>();
		Class<?> c = sample.getClass();
		try {
			addMarkings(sample, c, out);
			addVillager(sample, c, out);
			addEnumGetterSetter(sample, c, out, "oxidation", "Oxidation", "getWeatherState", new String[]{"setWeatherState"});
			addEnumGetterSetter(sample, c, out, "gene", "Variant", "getMainGene", new String[]{"setMainGene", "setHiddenGene"});
			addSlimeSize(sample, c, out);
			addDecor(c, out);
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Extra option discovery failed for {}", c.getSimpleName(), t);
		}
		return out;
	}

	// ------------------------------------------------------------ helpers

	static @Nullable Method method(Class<?> c, String name, int params) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			for (Method m : k.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == params) {
					try {
						m.setAccessible(true);
						return m;
					} catch (RuntimeException ignored) {
					}
				}
			}
		}
		return null;
	}

	private static String labelOf(Object constant) {
		String raw = constant instanceof StringRepresentable sr ? sr.getSerializedName() : ((Enum<?>) constant).name();
		return Appearance.pretty(raw);
	}

	private static @Nullable Enum<?> byName(Class<?> enumClass, String name) {
		for (Object o : enumClass.getEnumConstants()) {
			if (((Enum<?>) o).name().equals(name)) return (Enum<?>) o;
		}
		return null;
	}

	// ------------------------------------------------------------ options

	/** Generic: an enum read with {@code getter} and written with each of {@code setters}. */
	private static void addEnumGetterSetter(Entity sample, Class<?> c, List<Appearance.ComponentOption> out,
											String id, String label, String getter, String[] setters) throws Exception {
		Method get = method(c, getter, 0);
		if (get == null || !get.getReturnType().isEnum()) return;
		List<Method> sets = new ArrayList<>();
		for (String s : setters) {
			Method m = method(c, s, 1);
			if (m != null) sets.add(m);
		}
		if (sets.isEmpty()) return;
		Class<?> enumClass = get.getReturnType();
		List<String> values = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (Object o : enumClass.getEnumConstants()) {
			values.add(((Enum<?>) o).name());
			labels.add(labelOf(o));
		}
		if (values.size() < 2) return;
		Object current = get.invoke(sample);
		String def = current instanceof Enum<?> en ? en.name() : values.get(0);
		out.add(new Appearance.ComponentOption(PREFIX + id, label, values, labels, def, s -> null, (e, v) -> {
			Enum<?> value = byName(enumClass, v);
			if (value == null) return;
			for (Method m : sets) {
				try {
					m.invoke(e, value);
				} catch (Throwable ignored) {
				}
			}
		}));
	}

	/** Horse markings: stored packed with the colour, set via setVariantAndMarkings(variant, markings). */
	private static void addMarkings(Entity sample, Class<?> c, List<Appearance.ComponentOption> out) throws Exception {
		Method getMarkings = method(c, "getMarkings", 0);
		Method getVariant = method(c, "getVariant", 0);
		Method set = method(c, "setVariantAndMarkings", 2);
		if (getMarkings == null || getVariant == null || set == null || !getMarkings.getReturnType().isEnum()) return;
		Class<?> enumClass = getMarkings.getReturnType();
		List<String> values = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (Object o : enumClass.getEnumConstants()) {
			values.add(((Enum<?>) o).name());
			labels.add(labelOf(o));
		}
		Object current = getMarkings.invoke(sample);
		String def = current instanceof Enum<?> en ? en.name() : values.get(0);
		out.add(new Appearance.ComponentOption(PREFIX + "markings", "Markings", values, labels, def, s -> null, (e, v) -> {
			try {
				Enum<?> m = byName(enumClass, v);
				// Read the colour at apply time, so it combines with the colour component.
				if (m != null) set.invoke(e, getVariant.invoke(e), m);
			} catch (Throwable ignored) {
			}
		}));
	}

	/** Villager / zombie villager profession and level (VillagerData.withProfession / withLevel). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void addVillager(Entity sample, Class<?> c, List<Appearance.ComponentOption> out) throws Exception {
		Method getData = method(c, "getVillagerData", 0);
		Method setData = method(c, "setVillagerData", 1);
		if (getData == null || setData == null) return;
		Object data = getData.invoke(sample);
		if (data == null) return;
		Class<?> dataClass = data.getClass();

		Method withProfession = null;
		Method withLevel = null;
		for (Method m : dataClass.getMethods()) {
			if (m.getName().equals("withProfession") && m.getParameterCount() == 1 && Holder.class.isAssignableFrom(m.getParameterTypes()[0])) withProfession = m;
			if (m.getName().equals("withLevel") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) withLevel = m;
		}

		Registry registry = BuiltInRegistries.VILLAGER_PROFESSION;
		if (withProfession != null) {
			Method wp = withProfession;
			List<Identifier> ids = new ArrayList<>(registry.keySet());
			ids.sort((a, b) -> a.toString().compareTo(b.toString()));
			List<String> values = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			for (Identifier id : ids) {
				values.add(id.toString());
				labels.add(Appearance.pretty(id.getPath()));
			}
			String def = values.contains("minecraft:none") ? "minecraft:none" : values.get(0);
			out.add(new Appearance.ComponentOption(PREFIX + "profession", "Profession", values, labels, def, s -> null, (e, v) -> {
				try {
					Object value = registry.getValue(Identifier.parse(v));
					if (value == null) return;
					Holder<?> holder = registry.wrapAsHolder(value);
					setData.invoke(e, wp.invoke(getData.invoke(e), holder));
				} catch (Throwable t) {
					EntityMorphClient.LOGGER.debug("Setting profession failed", t);
				}
			}));
		}
		if (withLevel != null) {
			Method wl = withLevel;
			List<String> values = List.of("1", "2", "3", "4", "5");
			List<String> labels = List.of("Novice", "Apprentice", "Journeyman", "Expert", "Master");
			out.add(new Appearance.ComponentOption(PREFIX + "level", "Level", values, labels, "1", s -> null, (e, v) -> {
				try {
					setData.invoke(e, wl.invoke(getData.invoke(e), Integer.parseInt(v)));
				} catch (Throwable t) {
					EntityMorphClient.LOGGER.debug("Setting villager level failed", t);
				}
			}));
		}
	}

	/** Slime / magma cube size via setSize(int, boolean). */
	private static void addSlimeSize(Entity sample, Class<?> c, List<Appearance.ComponentOption> out) throws Exception {
		Method set = method(c, "setSize", 2);
		Method get = method(c, "getSize", 0);
		if (set == null || get == null || set.getParameterTypes()[0] != int.class) return;
		List<String> values = List.of("1", "2", "3", "4");
		List<String> labels = List.of("Small", "Medium", "Large", "Huge");
		Object cur = get.invoke(sample);
		String def = cur instanceof Integer i && values.contains(String.valueOf(i)) ? String.valueOf(i) : "1";
		out.add(new Appearance.ComponentOption(PREFIX + "size", "Size", values, labels, def, s -> null, (e, v) -> {
			try {
				set.invoke(e, Integer.parseInt(v), true);
				e.refreshDimensions();
			} catch (Throwable ignored) {
			}
		}));
	}

	/** Llama / trader llama carpet decor; applied as a body-slot item in {@link Appearance#equipmentOverrides}. */
	private static void addDecor(Class<?> c, List<Appearance.ComponentOption> out) {
		boolean llama = false;
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			if (k.getSimpleName().toLowerCase(Locale.ROOT).contains("llama")) llama = true;
		}
		if (!llama) return;
		List<String> values = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		values.add("none");
		labels.add("None");
		for (DyeColor color : DyeColor.values()) {
			values.add(color.getSerializedName());
			labels.add(Appearance.pretty(color.getSerializedName()));
		}
		out.add(new Appearance.ComponentOption(DECOR, "Decor", values, labels, "none", s -> null, (e, v) -> {
		}));
	}

	/** Item id for a decor value, or empty. */
	static Optional<Identifier> decorItem(@Nullable String value) {
		if (value == null || value.equals("none")) return Optional.empty();
		return Optional.of(Identifier.fromNamespaceAndPath("minecraft", value + "_carpet"));
	}
}
