package dev.entitymorph.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import dev.entitymorph.EntityMorphClient;
import dev.entitymorph.config.MorphConfig;
import dev.entitymorph.config.MorphEntry;

/**
 * Keeps one client-only "proxy" entity per morphed entity. Rendering code is handed the proxy
 * instead of the real entity, so the proxy's own renderer, model and animations are used while
 * its position, rotation, pose, swing, equipment, etc. are copied from the real entity every frame.
 */
public final class MorphManager {
	/** Model id used in configs to mean "player model". */
	public static final String PLAYER_MODEL = "minecraft:player";

	private static final class Proxy {
		final Entity entity;
		final String model;
		final String key;

		Proxy(Entity entity, String model, String key) {
			this.entity = entity;
			this.model = model;
			this.key = key;
		}
	}

	private static final Map<Integer, Proxy> PROXIES = new HashMap<>();
	private static final Map<Entity, Entity> SOURCE_OF_PROXY = new IdentityHashMap<>();
	/** model id -> whether it can be created as a living entity on the client. */
	private static final Map<String, Boolean> CREATABLE = new HashMap<>();

	private static @Nullable ClientLevel lastLevel;
	private static int tickCounter;
	private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
	/** Set while vanilla extracts the local player's first-person state, which must stay a player state. */
	private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> false);
	/** Proxies are never added to the level, so give them their own (negative) ids. */
	private static int nextProxyId = -1_000_000;

	public static void setSuppressed(boolean suppressed) {
		SUPPRESS.set(suppressed);
	}

	private MorphManager() {
	}

	// ------------------------------------------------------------------ lookup

	/** The entity that should actually be rendered in place of {@code source}. */
	public static Entity substitute(Entity source) {
		if (source == null || BUSY.get() || SUPPRESS.get() || SOURCE_OF_PROXY.containsKey(source)) return source;
		Entity proxy = proxyFor(source);
		if (proxy == null) return source;
		BUSY.set(true);
		try {
			sync(source, proxy);
			applyFit(source, proxy, MorphConfig.get(source.getUUID()));
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Morph sync failed", t);
		} finally {
			BUSY.set(false);
		}
		return proxy;
	}

	/** For a proxy, the real entity it stands in for; otherwise the entity itself. */
	public static Entity sourceOf(Entity entity) {
		Entity src = SOURCE_OF_PROXY.get(entity);
		return src != null ? src : entity;
	}

	/** The morph settings that apply to whatever is being rendered (real entity or proxy). */
	public static @Nullable MorphEntry entryFor(Entity rendered) {
		if (!MorphConfig.isActive() || rendered == null) return null;
		return MorphConfig.get(sourceOf(rendered).getUUID());
	}

	public static boolean isPlayerModel(@Nullable MorphEntry e, Entity source) {
		if (e == null || !e.hasModel()) return source instanceof Player;
		return PLAYER_MODEL.equals(e.model);
	}

	/** Proxy for the given source or null when the source should render as itself. */
	public static @Nullable Entity proxyFor(Entity source) {
		ensureLevel();
		MorphEntry e = MorphConfig.isActive() ? MorphConfig.get(source.getUUID()) : null;
		String model = targetModel(e, source);
		if (model == null) {
			removeProxy(source.getId());
			return null;
		}
		String key = e.appearanceKey();
		Proxy p = PROXIES.get(source.getId());
		if (p != null && p.model.equals(model) && p.key.equals(key) && p.entity.level() == source.level()) {
			return p.entity;
		}
		removeProxy(source.getId());
		Entity created = create(model, source.level() instanceof ClientLevel cl ? cl : lastLevel);
		if (created == null) return null;
		applyBaby(created, e.baby);
		try {
			Appearance.apply(created, model, e);
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.warn("Could not apply appearance to {}", model, t);
		}
		PROXIES.put(source.getId(), new Proxy(created, model, key));
		SOURCE_OF_PROXY.put(created, source);
		return created;
	}

	/** Resolved model id to swap to, or null if no proxy is needed. */
	private static @Nullable String targetModel(@Nullable MorphEntry e, Entity source) {
		if (e == null) return null;
		String own = idOf(source.getType());
		String model = e.hasModel() ? e.model : null;
		if (model == null) {
			// No model change, but variants / baby / flags still need a proxy of the same type.
			if (e.hasAppearance() && !(source instanceof Player)) return own;
			return null;
		}
		if (PLAYER_MODEL.equals(model) && source instanceof Player) return null; // skin swap only
		if (model.equals(own) && !e.hasAppearance()) return null;
		return model;
	}

	public static @Nullable Entity create(String model, @Nullable ClientLevel level) {
		if (level == null) return null;
		if (Boolean.FALSE.equals(CREATABLE.get(model))) return null;
		Entity created = null;
		try {
			EntityType<?> type = PLAYER_MODEL.equals(model) ? EntityTypes.MANNEQUIN : typeOf(model);
			if (type != null && type != EntityTypes.PLAYER) {
				created = type.create(level, EntitySpawnReason.LOAD);
			}
			if (created != null && !(created instanceof LivingEntity)) created = null;
			if (created != null && PLAYER_MODEL.equals(model) && !(created instanceof ClientAvatarEntity)) {
				EntityMorphClient.LOGGER.warn("Mannequin was not a client avatar ({}); player model unavailable", created.getClass().getName());
				created = null;
			}
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.warn("Can't create {} for morphing", model, t);
			created = null;
		}
		CREATABLE.put(model, created != null);
		if (created != null) {
			// 26.3 throws if getId() is called before an id is assigned (item models seed from it).
			created.setId(nextProxyId--);
			created.setSilent(true);
			if (created instanceof Mob mob) mob.setNoAi(true);
		}
		return created;
	}

	private static final Map<String, Entity> SAMPLES = new HashMap<>();

	/** A spare (never rendered in the world) instance of a model, used to discover its variants. */
	public static @Nullable Entity sample(String model) {
		ensureLevel();
		if (SAMPLES.containsKey(model)) return SAMPLES.get(model);
		Entity e = create(model, lastLevel);
		SAMPLES.put(model, e);
		return e;
	}

	/** All entity types that can be used as morph targets, as ids. */
	public static List<String> availableModels() {
		ensureLevel();
		List<String> out = new ArrayList<>();
		out.add(PLAYER_MODEL);
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (type == EntityTypes.PLAYER || type == EntityTypes.MANNEQUIN) continue;
			String id = idOf(type);
			Boolean ok = CREATABLE.get(id);
			if (ok == null) {
				Entity e = create(id, lastLevel);
				ok = e != null;
			}
			if (ok) out.add(id);
		}
		out.subList(1, out.size()).sort(String::compareTo);
		return out;
	}

	public static String displayName(String model) {
		if (PLAYER_MODEL.equals(model)) return "Player";
		EntityType<?> t = typeOf(model);
		return t == null ? model : t.getDescription().getString();
	}

	public static String idOf(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
	}

	public static @Nullable EntityType<?> typeOf(String id) {
		try {
			Identifier ident = Identifier.parse(id);
			Optional<EntityType<?>> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ident);
			return t.orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	// --------------------------------------------------------------- lifecycle

	private static void ensureLevel() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != lastLevel) {
			clear();
			lastLevel = level;
		}
	}

	public static void clear() {
		PROXIES.clear();
		SOURCE_OF_PROXY.clear();
		CREATABLE.clear();
		SAMPLES.clear();
		Appearance.clearCache();
		lastLevel = null;
	}

	private static void removeProxy(int sourceId) {
		Proxy p = PROXIES.remove(sourceId);
		if (p != null) SOURCE_OF_PROXY.remove(p.entity);
	}

	/** Called every client tick: drop proxies of entities that are gone. */
	public static void tick() {
		ensureLevel();
		if (++tickCounter % 100 != 0) return;
		Iterator<Map.Entry<Entity, Entity>> it = SOURCE_OF_PROXY.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Entity, Entity> en = it.next();
			if (en.getValue().isRemoved()) {
				PROXIES.remove(en.getValue().getId());
				it.remove();
			}
		}
	}

	// -------------------------------------------------------------------- sync

	private static void applyBaby(Entity e, boolean baby) {
		if (!baby) return;
		try {
			if (e instanceof AgeableMob ageable) {
				ageable.setBaby(true);
				return;
			}
			Method m = findMethod(e.getClass(), "setBaby", boolean.class);
			if (m != null) m.invoke(e, true);
		} catch (Throwable ignored) {
		}
	}

	private static @Nullable Method findMethod(Class<?> c, String name, Class<?>... args) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Method m = k.getDeclaredMethod(name, args);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static void sync(Entity src, Entity dst) {
		dst.setPos(src.getX(), src.getY(), src.getZ());
		dst.xo = src.xo;
		dst.yo = src.yo;
		dst.zo = src.zo;
		dst.xOld = src.xOld;
		dst.yOld = src.yOld;
		dst.zOld = src.zOld;
		dst.setYRot(src.getYRot());
		dst.setXRot(src.getXRot());
		dst.yRotO = src.yRotO;
		dst.xRotO = src.xRotO;
		dst.tickCount = src.tickCount;
		dst.setOnGround(src.onGround());
		if (dst.getPose() != src.getPose()) dst.setPose(src.getPose());
		dst.setShiftKeyDown(src.isShiftKeyDown());
		dst.setSprinting(src.isSprinting());
		dst.setSwimming(src.isSwimming());
		dst.setInvisible(src.isInvisible());
		dst.setGlowingTag(src.hasGlowingTag());
		dst.setRemainingFireTicks(src.getRemainingFireTicks());

		Minecraft mc = Minecraft.getInstance();
		boolean isCamera = src == mc.getCameraEntity();
		if (src instanceof Player && !isCamera) {
			dst.setCustomName(src.getDisplayName());
			dst.setCustomNameVisible(true);
		} else if (!isCamera) {
			dst.setCustomName(src.getCustomName());
			dst.setCustomNameVisible(src.isCustomNameVisible());
		} else {
			dst.setCustomName(null);
			dst.setCustomNameVisible(false);
		}

		if (src instanceof LivingEntity s && dst instanceof LivingEntity d) {
			d.yBodyRot = s.yBodyRot;
			d.yBodyRotO = s.yBodyRotO;
			d.yHeadRot = s.yHeadRot;
			d.yHeadRotO = s.yHeadRotO;
			copyWalk(s.walkAnimation, d.walkAnimation);
			copySwing(s, d);
			d.hurtTime = s.hurtTime;
			d.hurtDuration = s.hurtDuration;
			d.deathTime = s.deathTime;
			float health = Math.min(s.getHealth(), d.getMaxHealth());
			if (s.getHealth() > 0 && health <= 0) health = 1;
			if (d.getHealth() != health) d.setHealth(health);

			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack want = s.getItemBySlot(slot);
				if (d.getItemBySlot(slot) != want) {
					try {
						d.setItemSlot(slot, want);
					} catch (Throwable ignored) {
						// some slots (e.g. saddle/body) are rejected by some entities
					}
				}
			}
			if (d instanceof Mob mob) {
				boolean left = s.getMainArm() == HumanoidArm.LEFT;
				if (mob.isLeftHanded() != left) mob.setLeftHanded(left);
			}
		}
	}

	/**
	 * Scales the proxy (via its SCALE attribute, so model, shadow, name tag and hitbox all follow) so its
	 * standing height or eye height matches the real entity's.
	 */
	private static void applyFit(Entity src, Entity dst, @Nullable MorphEntry e) {
		if (!(dst instanceof LivingEntity d)) return;
		AttributeInstance scaleAttr = d.getAttribute(Attributes.SCALE);
		if (scaleAttr == null) return;
		double target = 1.0;
		MorphEntry.FitMode mode = e == null || e.fit == null ? MorphEntry.FitMode.NONE : e.fit;
		if (mode != MorphEntry.FitMode.NONE) {
			EntityDimensions srcDims = src.getDimensions(Pose.STANDING);
			EntityDimensions dstDims = d.getDimensions(Pose.STANDING);
			float current = d.getScale();
			if (current <= 0) current = 1;
			// dimensions scale linearly with the attribute, so divide out the current scale
			float base = (mode == MorphEntry.FitMode.EYES ? dstDims.eyeHeight() : dstDims.height()) / current;
			float want = mode == MorphEntry.FitMode.EYES ? srcDims.eyeHeight() : srcDims.height();
			if (base > 0.01F && want > 0.01F) target = Math.max(0.0625, Math.min(16.0, want / base));
		}
		if (Math.abs(scaleAttr.getBaseValue() - target) > 1.0E-3) {
			scaleAttr.setBaseValue(target);
			d.refreshDimensions();
		}
	}

	private static Field[] swingFields;

	/**
	 * Copies the arm-swing / attack animation state. In 26.3 this lives in private fields behind
	 * getCurrentSwing(), so every non-final instance field of LivingEntity whose name mentions
	 * "swing" or "attack" is copied (references for objects, values for primitives).
	 */
	private static void copySwing(LivingEntity from, LivingEntity to) {
		if (swingFields == null) {
			List<Field> fs = new ArrayList<>();
			for (Field f : LivingEntity.class.getDeclaredFields()) {
				int mod = f.getModifiers();
				if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
				String n = f.getName().toLowerCase(java.util.Locale.ROOT);
				if (!n.contains("swing") && !n.contains("attackanim")) continue;
				try {
					f.setAccessible(true);
					fs.add(f);
				} catch (RuntimeException ignored) {
				}
			}
			swingFields = fs.toArray(new Field[0]);
			EntityMorphClient.LOGGER.debug("Swing fields copied for morphs: {}", fs.stream().map(Field::getName).toList());
		}
		try {
			for (Field f : swingFields) f.set(to, f.get(from));
		} catch (IllegalAccessException ignored) {
		}
	}

	private static Field[] walkFields;

	/** Copies every float field of WalkAnimationState (speedOld, speed, position) without depending on names. */
	private static void copyWalk(WalkAnimationState from, WalkAnimationState to) {
		if (walkFields == null) {
			List<Field> fs = new ArrayList<>();
			for (Field f : WalkAnimationState.class.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType() != float.class) continue;
				f.setAccessible(true);
				fs.add(f);
			}
			walkFields = fs.toArray(new Field[0]);
		}
		try {
			for (Field f : walkFields) f.setFloat(to, f.getFloat(from));
		} catch (IllegalAccessException ignored) {
		}
	}
}
