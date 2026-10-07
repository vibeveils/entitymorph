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
				if (!lower.contains("anim")) continue;
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
