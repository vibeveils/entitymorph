package dev.entitymorph.render;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Sets the "is riding" flag on whatever render state class the morph's renderer uses. */
public final class RidingPose {
	private RidingPose() {
	}

	private static final Map<Class<?>, Optional<Field>> FIELDS = new HashMap<>();
	private static java.lang.reflect.@org.jspecify.annotations.Nullable Method riderSit;
	private static boolean riderSitLooked;

	/** Older versions let vehicles opt out of the sitting pose (shouldRiderSit); if it's gone, riders always sit. */
	public static boolean riderSits(net.minecraft.world.entity.Entity vehicle) {
		if (!riderSitLooked) {
			riderSitLooked = true;
			for (Class<?> k = net.minecraft.world.entity.Entity.class; k != null && riderSit == null; k = k.getSuperclass()) {
				for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
					if (m.getName().equals("shouldRiderSit") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
						m.setAccessible(true);
						riderSit = m;
					}
				}
			}
		}
		if (riderSit == null) return true;
		try {
			return (boolean) riderSit.invoke(vehicle);
		} catch (Throwable t) {
			return true;
		}
	}

	public static void markPassenger(Object state) {
		if (state == null) return;
		Optional<Field> f = FIELDS.computeIfAbsent(state.getClass(), c -> {
			for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
				for (String name : new String[]{"isPassenger", "isRiding", "riding"}) {
					try {
						Field field = k.getDeclaredField(name);
						if (field.getType() == boolean.class) {
							field.setAccessible(true);
							return Optional.of(field);
						}
					} catch (NoSuchFieldException | RuntimeException ignored) {
					}
				}
			}
			return Optional.empty();
		});
		if (f.isEmpty()) return;
		try {
			f.get().setBoolean(state, true);
		} catch (IllegalAccessException ignored) {
		}
	}
}
