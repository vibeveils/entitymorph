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

	// ------------------------------------------------------------ behaviour

	private static final Map<Entity, int[]> ARMADILLO = new WeakHashMap<>();
	private static final Map<Entity, Boolean> SAT = new WeakHashMap<>();
	private static final Map<Entity, Boolean> JUMPING = new WeakHashMap<>();
	private static final Map<Entity, Boolean> CROUCHED = new WeakHashMap<>();
	private static final Map<Class<?>, Map<String, Method>> METHODS = new HashMap<>();

	private static @Nullable Method method(Class<?> c, String name, Class<?>... types) {
		Map<String, Method> byName = METHODS.computeIfAbsent(c, k -> new HashMap<>());
		String key = name + java.util.Arrays.toString(types);
		if (byName.containsKey(key)) return byName.get(key);
		Method found = Appearance.findMethod(c, name, types);
		byName.put(key, found);
		return found;
	}

	private static @Nullable Field intField(Class<?> c, String name) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				if (f.getType() == int.class || f.getType() == boolean.class) {
					f.setAccessible(true);
					return f;
				}
			} catch (NoSuchFieldException | RuntimeException ignored) {
			}
		}
		return null;
	}

	private static void tickBehaviour(Entity src, Entity proxy, dev.entitymorph.config.@Nullable MorphEntry entry, Map<String, Field> fl,
									  @Nullable Boolean was, boolean onGround, boolean takeOff, boolean landed) throws Exception {
		Class<?> c = proxy.getClass();
		boolean crouching = src.isCrouching() || src.isShiftKeyDown();

		// Allay arms come up to hold an item over a few ticks.
		if (has(fl, "holdingItemAnimationTicks", "holdingItemAnimationTicks0") && proxy instanceof net.minecraft.world.entity.LivingEntity le) {
			float t = get(fl, proxy, "holdingItemAnimationTicks");
			set(fl, proxy, "holdingItemAnimationTicks0", t);
			boolean holding = !le.getMainHandItem().isEmpty() || !le.getOffhandItem().isEmpty();
			set(fl, proxy, "holdingItemAnimationTicks", Math.max(0.0F, Math.min(5.0F, t + (holding ? 1.0F : -1.0F))));
		}

		// Slime / magma cube squish: stretch on take-off, splat on landing.
		if (has(fl, "squish", "oSquish", "targetSquish")) {
			float squish = get(fl, proxy, "squish");
			set(fl, proxy, "oSquish", squish);
			float target = get(fl, proxy, "targetSquish");
			squish += (target - squish) * 0.5F;
			set(fl, proxy, "squish", squish);
			if (landed) target = -0.5F;
			else if (takeOff) target = 1.0F;
			set(fl, proxy, "targetSquish", target * 0.6F);
		}

		// Goat lowers its head to ram while the real entity sprints.
		Field lowering = intField(c, "isLoweringHead");
		Field lowerTick = intField(c, "lowerHeadTick");
		if (lowering != null && lowerTick != null && lowering.getType() == boolean.class && lowerTick.getType() == int.class) {
			boolean ram = src.isSprinting();
			lowering.setBoolean(proxy, ram);
			int t = lowerTick.getInt(proxy) + (ram ? 1 : -2);
			lowerTick.setInt(proxy, Math.max(0, Math.min(20, t)));
		}

		// Armadillo rolls up while crouching: ROLLING -> SCARED, then UNROLLING -> IDLE.
		Method switchTo = null;
		for (Class<?> k = c; k != null && k != Object.class && switchTo == null; k = k.getSuperclass()) {
			for (Method m : k.getDeclaredMethods()) {
				if (m.getName().equals("switchToState") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
					m.setAccessible(true);
					switchTo = m;
					break;
				}
			}
		}
		if (switchTo != null) {
			Class<?> states = switchTo.getParameterTypes()[0];
			int[] st = ARMADILLO.computeIfAbsent(proxy, k -> new int[]{0, 0}); // phase, ticks
			st[1]++;
			int phase = st[0];
			if (crouching && (phase == 0 || phase == 3)) { phase = 1; st[1] = 0; }
			else if (crouching && phase == 1 && st[1] > 14) { phase = 2; st[1] = 0; }
			else if (!crouching && (phase == 1 || phase == 2)) { phase = 3; st[1] = 0; }
			else if (!crouching && phase == 3 && st[1] > 26) { phase = 0; st[1] = 0; }
			if (phase != st[0]) {
				st[0] = phase;
				String name = switch (phase) {
					case 1 -> "ROLLING";
					case 2 -> "SCARED";
					case 3 -> "UNROLLING";
					default -> "IDLE";
				};
				for (Object o : states.getEnumConstants()) {
					if (((Enum<?>) o).name().equals(name)) switchTo.invoke(proxy, o);
				}
			}
		}

		// Enderman: a held block becomes its carried block (other items are drawn by MouthItemLayer).
		Method setCarried = method(c, "setCarriedBlock", net.minecraft.world.level.block.state.BlockState.class);
		if (setCarried != null && src instanceof net.minecraft.world.entity.LivingEntity holder) {
			net.minecraft.world.item.ItemStack held = holder.getMainHandItem();
			net.minecraft.world.level.block.state.BlockState block =
					held.getItem() instanceof net.minecraft.world.item.BlockItem bi ? bi.getBlock().defaultBlockState() : null;
			Method getCarried = method(c, "getCarriedBlock");
			Object cur = getCarried != null ? getCarried.invoke(proxy) : null;
			if (cur != block) setCarried.invoke(proxy, block);
		}

		// Horses rear up when they jump (vanilla calls standIfPossible on a jump), and settle on landing.
		if (has(fl, "standAnim", "standAnimO")) {
			boolean up = JUMPING.getOrDefault(proxy, false);
			if (takeOff) up = true;
			if (landed || onGround) up = up && !onGround;
			JUMPING.put(proxy, up);
			float a = get(fl, proxy, "standAnim");
			set(fl, proxy, "standAnimO", a);
			if (up) {
				a += (1.0F - a) * 0.4F + 0.05F;
				if (a > 1.0F) a = 1.0F;
			} else {
				a += (0.8F * a * a * a - a) * 0.6F - 0.05F;
				if (a < 0.0F) a = 0.0F;
			}
			set(fl, proxy, "standAnim", a);
		}

		// Sit while the real entity rides something; sneak while it crouches (fox, cat, wolf, parrot…).
		boolean wantSit = src.isPassenger() || Appearance.hasFlag(entry, "sitting");
		Boolean prevSit = SAT.put(proxy, wantSit);
		if (prevSit == null || prevSit != wantSit) {
			for (String name : new String[]{"setInSittingPose", "setOrderedToSit", "setSitting", "sit"}) {
				Method m = method(c, name, boolean.class);
				if (m != null) m.invoke(proxy, wantSit);
			}
		}
		boolean wantCrouch = crouching || Appearance.hasFlag(entry, "crouching");
		Boolean prevCrouch = CROUCHED.put(proxy, wantCrouch);
		if (prevCrouch == null || prevCrouch != wantCrouch) {
			Method m = method(c, "setIsCrouching", boolean.class);
			if (m != null) m.invoke(proxy, wantCrouch);
		}

		// Sulfur cube: show the held item inside it (synced item data, or an item field on the mob).
		if (c.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("sulfur") && src instanceof net.minecraft.world.entity.LivingEntity holder) {
			net.minecraft.world.item.ItemStack held = holder.getMainHandItem();
			setHeldItemData(proxy, held);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setHeldItemData(Entity proxy, net.minecraft.world.item.ItemStack held) {
		for (Class<?> k = proxy.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			if (k.getPackageName().equals("net.minecraft.world.entity")) break;
			for (Field f : k.getDeclaredFields()) {
				try {
					if (Modifier.isStatic(f.getModifiers()) && net.minecraft.network.syncher.EntityDataAccessor.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						var acc = (net.minecraft.network.syncher.EntityDataAccessor) f.get(null);
						Object cur = proxy.getEntityData().get(acc);
						if (cur instanceof net.minecraft.world.item.ItemStack stack && stack != held) proxy.getEntityData().set(acc, held);
					} else if (!Modifier.isStatic(f.getModifiers()) && f.getType() == net.minecraft.world.item.ItemStack.class) {
						f.setAccessible(true);
						if (f.get(proxy) != held) f.set(proxy, held);
					}
				} catch (Throwable ignored) {
				}
			}
		}
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
				// "peek": shulker lid; "...Amount": panda sit/roll/lie blend timers (updateSitAmount, …).
				boolean amount = (lower.startsWith("update") || lower.startsWith("tick")) && lower.endsWith("amount");
				if (!lower.contains("anim") && !lower.contains("peek") && !amount) continue;
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
		tick(src, proxy, null);
	}

	public static void tick(Entity src, Entity proxy, dev.entitymorph.config.@Nullable MorphEntry entry) {
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
		boolean landed = was != null && !was && onGround;

		try {
			tickBehaviour(src, proxy, entry, fl, was, onGround, takeOff, landed);
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Behaviour animation failed for {}", proxy.getClass().getSimpleName(), t);
		}

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
