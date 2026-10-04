package dev.entitymorph.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import dev.entitymorph.EntityMorphClient;

/**
 * The ender dragon doesn't use the usual body yaw / walk animation. Its renderer reads:
 * <ul>
 *   <li>a flight history of (y, yRot) samples recorded every tick in aiStep — the model's facing,
 *   pitch and neck/tail curve come from it;</li>
 *   <li>flapTime / oFlapTime, advanced every tick, for the wing beat.</li>
 * </ul>
 * Proxies never tick, so without this the dragon sits in its reference pose facing north.
 * This drives both from the real entity once per client tick. Accessed by (unobfuscated) name,
 * so a rename just disables it with a warning.
 */
public final class DragonAnimator {
	private DragonAnimator() {
	}

	private static boolean looked;
	private static boolean broken;
	private static @Nullable Field historyField;
	private static @Nullable Method recordMethod;
	private static @Nullable Field flapField;
	private static @Nullable Field oFlapField;

	/** Proxies that have had their history pre-filled. */
	private static final Map<Entity, Boolean> PRIMED = new WeakHashMap<>();

	private static final Map<Entity, Float> SCALES = new WeakHashMap<>();

	/** Size set by "Size: Fit …" (the dragon renderer ignores the SCALE attribute, so it's applied at submit). */
	public static void setScale(Entity dragon, float scale) {
		SCALES.put(dragon, scale);
	}

	public static float scaleOf(Entity dragon) {
		Float s = SCALES.get(dragon);
		return s == null ? 1.0F : s;
	}

	public static boolean isDragon(Entity e) {
		for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			if (c.getSimpleName().equals("EnderDragon")) return true;
		}
		return false;
	}

	/** Dragon yaw faces the opposite way to other mobs' body yaw. */
	public static float dragonYaw(Entity src) {
		float body = src instanceof LivingEntity l ? l.yBodyRot : src.getYRot();
		return Mth.wrapDegrees(body + 180.0F);
	}

	public static float dragonYawO(Entity src) {
		float body = src instanceof LivingEntity l ? l.yBodyRotO : src.yRotO;
		return Mth.wrapDegrees(body + 180.0F);
	}

	private static void lookup(Class<?> dragonClass) {
		if (looked) return;
		looked = true;
		try {
			historyField = findField(dragonClass, "flightHistory");
			if (historyField != null) {
				for (Class<?> k = historyField.getType(); k != null && k != Object.class && recordMethod == null; k = k.getSuperclass()) {
					for (Method m : k.getDeclaredMethods()) {
						Class<?>[] p = m.getParameterTypes();
						if (m.getName().equals("record") && p.length == 2 && p[0] == double.class && p[1] == float.class) {
							m.setAccessible(true);
							recordMethod = m;
						}
					}
				}
			}
			flapField = findField(dragonClass, "flapTime");
			oFlapField = findField(dragonClass, "oFlapTime");
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.warn("Ender dragon animation lookup failed", t);
		}
		if (historyField == null || recordMethod == null || flapField == null || oFlapField == null) {
			broken = true;
			EntityMorphClient.LOGGER.warn("Ender dragon morph animation unavailable (history={}, record={}, flap={}, oFlap={})",
					historyField, recordMethod, flapField, oFlapField);
		}
	}

	private static @Nullable Field findField(Class<?> c, String name) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	/** Advance one tick of dragon animation for {@code proxy}, following {@code src}. */
	public static void tick(Entity src, Entity proxy) {
		lookup(proxy.getClass());
		if (broken) return;
		try {
			Object history = historyField.get(proxy);
			float yaw = dragonYaw(src);
			if (!PRIMED.containsKey(proxy)) {
				// Fill the whole history so the neck/tail don't start curled from (0, 0).
				for (int i = 0; i < 64; i++) recordMethod.invoke(history, src.getY(), yaw);
				PRIMED.put(proxy, true);
			}

			// Wing beat, as in EnderDragon.aiStep: slower when moving fast, faster when climbing.
			float flap = flapField.getFloat(proxy);
			oFlapField.setFloat(proxy, flap);
			double dx = src.getX() - src.xo;
			double dy = src.getY() - src.yo;
			double dz = src.getZ() - src.zo;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			float step = 0.2F / ((float) horizontal * 10.0F + 1.0F);
			step *= (float) Math.pow(2.0, Mth.clamp(dy, -1.0, 1.0));
			// Standing still on the ground: slow idle flap like a perched dragon.
			if (src.onGround() && horizontal < 0.01) step = 0.05F;
			flapField.setFloat(proxy, flap + step);

			recordMethod.invoke(history, src.getY(), yaw);
		} catch (Throwable t) {
			broken = true;
			EntityMorphClient.LOGGER.warn("Ender dragon morph animation failed; disabling", t);
		}
	}
}
