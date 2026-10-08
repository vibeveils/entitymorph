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
