package dev.entitymorph.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;

import dev.entitymorph.EntityMorphClient;

/**
 * Many newer mobs (bat, axolotl, rabbit, frog, camel, sniffer, armadillo, breeze, …) animate from
 * state they advance in their own client tick: keyframe {@link AnimationState}s started there, or
 * blend timers ("in water", "on ground", "moving", "playing dead"). Morph proxies never tick, so
 * those models sit in their reference pose.
 *
 * <p>Once per client tick this:
 * <ol>
 *   <li>calls the mob's own no-argument animation update methods (setupAnimationStates,
 *   tickAnimations, updateAnimations, …), found by name on the mob's own classes;</li>
 *   <li>drives the rabbit hop (jumpTicks / jumpDuration) from the real entity leaving the ground;</li>
 *   <li>starts any hop/jump keyframe animation on take-off.</li>
 * </ol>
 * Fluid state, ground state, pose and movement are copied in {@link MorphManager} so these methods
 * see the real entity's situation. Everything is reflective and fails soft per method.</p>
 */
public final class MobAnimator {
	private MobAnimator() {
	}

	private static final Set<String> SKIP_PREFIXES = Set.of("get", "is", "has", "can", "should", "play", "make", "create", "build");

	private record ClassInfo(List<Method> animMethods, @Nullable Field jumpTicks, @Nullable Field jumpDuration,
							 List<Field> jumpAnimations) {
	}

	private static final Map<Class<?>, ClassInfo> INFO = new HashMap<>();
	private static final Map<Method, Boolean> BROKEN = new HashMap<>();
	private static final Map<Entity, Boolean> WAS_ON_GROUND = new WeakHashMap<>();
	private static final Map<Class<?>, Map<String, Field>> FLOAT_FIELDS = new HashMap<>();

	/** Non-static float fields of the class hierarchy, by name (first match from the subclass up). */
	private static Map<String, Field> floats(Class<?> cls) {
		return FLOAT_FIELDS.computeIfAbsent(cls, c -> {
			Map<String, Field> out = new HashMap<>();
			for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
				for (Field f : k.getDeclaredFields()) {
					if (Modifier.isStatic(f.getModifiers()) || f.getType() != float.class || out.containsKey(f.getName())) continue;
					try {
						f.setAccessible(true);
						out.put(f.getName(), f);
					} catch (RuntimeException ignored) {
					}
				}
			}
			return out;
		});
	}

	private static boolean has(Map<String, Field> f, String... names) {
		for (String n : names) if (!f.containsKey(n)) return false;
		return true;
	}

	private static float get(Map<String, Field> f, Entity e, String name) throws IllegalAccessException {
		return f.get(name).getFloat(e);
	}

	private static void set(Map<String, Field> f, Entity e, String name, float v) throws IllegalAccessException {
		f.get(name).setFloat(e, v);
	}

	private static final float PI = (float) Math.PI;

	/**
	 * Squid / glow squid: tentacles and body pitch are advanced in aiStep. Same maths as vanilla, but the
	 * swim direction comes from the real entity's movement.
	 */
	private static void tickSquid(Entity src, Entity proxy, Map<String, Field> f) throws IllegalAccessException {
		set(f, proxy, "xBodyRotO", get(f, proxy, "xBodyRot"));
		set(f, proxy, "zBodyRotO", get(f, proxy, "zBodyRot"));
		set(f, proxy, "oldTentacleMovement", get(f, proxy, "tentacleMovement"));
		set(f, proxy, "oldTentacleAngle", get(f, proxy, "tentacleAngle"));

		float speed = f.containsKey("tentacleSpeed") ? get(f, proxy, "tentacleSpeed") : 0.15F;
		if (speed <= 0) speed = 0.15F;
		float movement = get(f, proxy, "tentacleMovement") + speed;
		if (movement > 2 * PI) {
			movement -= 2 * PI;
			// the "old" value must wrap too, or the tentacles snap for one frame
			set(f, proxy, "oldTentacleMovement", get(f, proxy, "oldTentacleMovement") - 2 * PI);
		}
		set(f, proxy, "tentacleMovement", movement);

		float xBodyRot = get(f, proxy, "xBodyRot");
		if (src.isInWater()) {
			float angle;
			if (movement < PI) {
				float t = movement / PI;
				angle = (float) Math.sin(t * t * PI) * PI * 0.25F;
			} else {
				angle = 0.0F;
			}
			set(f, proxy, "tentacleAngle", angle);
			double dx = src.getX() - src.xo;
			double dy = src.getY() - src.yo;
			double dz = src.getZ() - src.zo;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			float target = horizontal + Math.abs(dy) < 1.0E-4 ? 0.0F : (float) (-Math.atan2(horizontal, dy) * (180.0 / Math.PI));
			xBodyRot += (target - xBodyRot) * 0.1F;
		} else {
			set(f, proxy, "tentacleAngle", Math.abs((float) Math.sin(movement)) * PI * 0.25F);
			xBodyRot += (-90.0F - xBodyRot) * 0.02F;
		}
		set(f, proxy, "xBodyRot", xBodyRot);
	}

	/** Parrot / chicken wing flapping (advanced in aiStep): flap while the real entity is airborne. */
	private static void tickFlapping(Entity src, Entity proxy, Map<String, Field> f) throws IllegalAccessException {
		set(f, proxy, "oFlap", get(f, proxy, "flap"));
		set(f, proxy, "oFlapSpeed", get(f, proxy, "flapSpeed"));
		boolean airborne = !src.onGround() && !src.isPassenger();
		float flapSpeed = get(f, proxy, "flapSpeed") + (airborne ? 4 : -1) * 0.3F;
		set(f, proxy, "flapSpeed", Math.max(0.0F, Math.min(1.0F, flapSpeed)));
		float flapping = get(f, proxy, "flapping");
		if (airborne && flapping < 1.0F) flapping = 1.0F;
		flapping *= 0.9F;
		set(f, proxy, "flapping", flapping);
		set(f, proxy, "flap", get(f, proxy, "flap") + flapping * 2.0F);
	}

	private static ClassInfo info(Class<?> c) {
		return INFO.computeIfAbsent(c, MobAnimator::discover);
	}

	private static ClassInfo discover(Class<?> cls) {
		List<Method> methods = new ArrayList<>();
		List<Field> jumpAnims = new ArrayList<>();
		Field jumpTicks = null;
		Field jumpDuration = null;
		for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
			// Stop at the shared base classes (Entity, LivingEntity, Mob, PathfinderMob, AgeableMob…).
			if (k.getPackageName().equals("net.minecraft.world.entity")) break;
			for (Method m : k.getDeclaredMethods()) {
				if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
				String name = m.getName();
				String lower = name.toLowerCase(Locale.ROOT);
				// "peek": the shulker lid eases towards its target opening in updatePeekAmount().
				if (!lower.contains("anim") && !lower.contains("peek")) continue;
				if (SKIP_PREFIXES.stream().anyMatch(lower::startsWith)) continue;
				if (methods.stream().anyMatch(x -> x.getName().equals(name))) continue; // overridden lower down
				try {
					m.setAccessible(true);
					methods.add(m);
				} catch (RuntimeException ignored) {
				}
			}
			for (Field f : k.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				String lower = f.getName().toLowerCase(Locale.ROOT);
				try {
					if (f.getType() == int.class && f.getName().equals("jumpTicks")) {
						f.setAccessible(true);
						jumpTicks = f;
					} else if (f.getType() == int.class && f.getName().equals("jumpDuration")) {
						f.setAccessible(true);
						jumpDuration = f;
					} else if (AnimationState.class.isAssignableFrom(f.getType()) && (lower.contains("hop") || lower.contains("jump"))) {
						f.setAccessible(true);
						jumpAnims.add(f);
					}
				} catch (RuntimeException ignored) {
				}
			}
		}
		if (!methods.isEmpty() || jumpTicks != null || !jumpAnims.isEmpty()) {
			EntityMorphClient.LOGGER.debug("Morph animation hooks for {}: methods={}, jump={}/{}, jumpAnims={}",
					cls.getSimpleName(), methods.stream().map(Method::getName).toList(),
					jumpTicks != null, jumpDuration != null, jumpAnims.stream().map(Field::getName).toList());
		}
		return new ClassInfo(List.copyOf(methods), jumpTicks, jumpDuration, List.copyOf(jumpAnims));
	}

	/** Advance one tick of client-side animation on {@code proxy}, following {@code src}. */
	public static void tick(Entity src, Entity proxy) {
		ClassInfo ci = info(proxy.getClass());

		// 1. The mob's own animation update methods.
		for (Method m : ci.animMethods()) {
			if (BROKEN.containsKey(m)) continue;
			try {
				m.invoke(proxy);
			} catch (Throwable t) {
				BROKEN.put(m, true);
				EntityMorphClient.LOGGER.debug("Disabling morph animation hook {}.{}", proxy.getClass().getSimpleName(), m.getName(), t);
			}
		}

		// Field-driven animations normally advanced in aiStep.
		Map<String, Field> fl = floats(proxy.getClass());
		try {
			if (has(fl, "tentacleMovement", "oldTentacleMovement", "tentacleAngle", "oldTentacleAngle", "xBodyRot", "xBodyRotO", "zBodyRot", "zBodyRotO")) {
				tickSquid(src, proxy, fl);
			}
			if (has(fl, "flap", "oFlap", "flapSpeed", "oFlapSpeed", "flapping")) {
				tickFlapping(src, proxy, fl);
			}
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Field animation failed for {}", proxy.getClass().getSimpleName(), t);
		}

		// 2/3. Hopping: started when the real entity leaves the ground.
		boolean onGround = src.onGround();
		Boolean was = WAS_ON_GROUND.put(proxy, onGround);
		boolean takeOff = was != null && was && !onGround && src.getY() > src.yo;

		if (ci.jumpTicks() != null && ci.jumpDuration() != null) {
			try {
				int ticks = ci.jumpTicks().getInt(proxy);
				int duration = ci.jumpDuration().getInt(proxy);
				if (takeOff) {
					ci.jumpDuration().setInt(proxy, 10);
					ci.jumpTicks().setInt(proxy, 0);
				} else if (duration != 0) {
					if (ticks < duration) {
						ci.jumpTicks().setInt(proxy, ticks + 1);
					} else {
						ci.jumpTicks().setInt(proxy, 0);
						ci.jumpDuration().setInt(proxy, 0);
					}
				}
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Rabbit-style jump animation failed", t);
			}
		}
		if (takeOff) {
			for (Field f : ci.jumpAnimations()) {
				try {
					((AnimationState) f.get(proxy)).start(proxy.tickCount);
				} catch (Throwable ignored) {
				}
			}
		}
	}
}
