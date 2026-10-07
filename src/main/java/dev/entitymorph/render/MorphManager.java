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
		ShoulderProxy sp = SHOULDER_BY_ENTITY.get(rendered);
		if (sp != null) return sp.entry;
		return MorphConfig.get(sourceOf(rendered).getUUID());
	}

	public static boolean isPlayerModel(@Nullable MorphEntry e, Entity source) {
		if (e == null || !e.hasModel()) return source instanceof Player;
		return PLAYER_MODEL.equals(e.model);
	}

	/** The proxy currently standing in for {@code source}, without creating one. */
	public static @Nullable Entity existingProxy(@Nullable Entity source) {
		if (source == null) return null;
		Proxy p = PROXIES.get(source.getId());
		return p != null && SOURCE_OF_PROXY.get(p.entity) == source ? p.entity : null;
	}

	// ------------------------------------------------------------ camera

	private static net.minecraft.client.renderer.state.level.@Nullable CameraRenderState camera;

	public static void setCamera(net.minecraft.client.renderer.state.level.CameraRenderState cam) {
		camera = cam;
	}

	public static net.minecraft.client.renderer.state.level.@Nullable CameraRenderState camera() {
		return camera;
	}

	// ---------------------------------------------------- shoulder parrots

	private static final class ShoulderProxy {
		final Entity entity;
		final String key;
		final MorphEntry entry;
		java.lang.ref.WeakReference<Entity> holder;

		ShoulderProxy(Entity entity, String key, MorphEntry entry, Entity holder) {
			this.entity = entity;
			this.key = key;
			this.entry = entry;
			this.holder = new java.lang.ref.WeakReference<>(holder);
		}
	}

	private static final Map<String, ShoulderProxy> SHOULDERS = new HashMap<>();
	private static final Map<Entity, ShoulderProxy> SHOULDER_BY_ENTITY = new IdentityHashMap<>();
	private static final float SHOULDER_HEIGHT = 0.9F;

	/**
	 * Builds (or reuses) the morph for the parrot on {@code player}'s shoulder and extracts its render
	 * state, posed to sit on the shoulder. Null when the vanilla parrot should be drawn instead.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static @Nullable ShoulderRender shoulder(Player player, boolean left, MorphEntry e, float partialTick) {
		ensureLevel();
		String slot = player.getUUID() + (left ? ":L" : ":R");
		String model = e.hasModel() ? e.model : idOf(EntityTypes.PARROT);
		MorphEntry look = e.copy();
		if (!look.flags.contains("sitting")) look.flags.add("sitting");
		String key = model + "|" + look.appearanceKey();

		ShoulderProxy sp = SHOULDERS.get(slot);
		if (sp == null || !sp.key.equals(key) || sp.entity.level() != player.level()) {
			if (sp != null) SHOULDER_BY_ENTITY.remove(sp.entity);
			Entity created = create(model, player.level() instanceof ClientLevel cl ? cl : lastLevel);
			if (created == null) return null;
			applyBaby(created, look.baby);
			try {
				Appearance.apply(created, model, look);
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Shoulder appearance failed", t);
			}
			sp = new ShoulderProxy(created, key, e, player);
			SHOULDERS.put(slot, sp);
			SHOULDER_BY_ENTITY.put(created, sp);
		}
		sp.holder = new java.lang.ref.WeakReference<>(player);

		Entity proxy = sp.entity;
		// Lighting and animation timing follow the player.
		double y = player.getY() + 1.5;
		proxy.setPos(player.getX(), y, player.getZ());
		proxy.xo = player.xo;
		proxy.yo = player.yo + 1.5;
		proxy.zo = player.zo;
		proxy.xOld = proxy.xo;
		proxy.yOld = proxy.yo;
		proxy.zOld = proxy.zo;
		proxy.tickCount = player.tickCount;
		proxy.setOnGround(true);
		proxy.setInvisible(player.isInvisible());

		try {
			var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(proxy);
			var state = ((net.minecraft.client.renderer.entity.EntityRenderer) renderer).createRenderState(proxy, partialTick);
			if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
				// Face the same way as the player (we draw inside the player's rotated model space).
				living.bodyRot = 180.0F;
				living.yRot = 0.0F;
				living.xRot = 0.0F;
			}
			float h = Math.max(proxy.getBbHeight(), proxy.getBbWidth());
			float scale = h > SHOULDER_HEIGHT ? SHOULDER_HEIGHT / h : 1.0F;
			return new ShoulderRender(renderer, (net.minecraft.client.renderer.entity.state.EntityRenderState) state, scale);
		} catch (Throwable t) {
			EntityMorphClient.LOGGER.debug("Shoulder morph extraction failed", t);
			return null;
		}
	}

	public static void clearShoulder(Player player, boolean left) {
		ShoulderProxy sp = SHOULDERS.remove(player.getUUID() + (left ? ":L" : ":R"));
		if (sp != null) SHOULDER_BY_ENTITY.remove(sp.entity);
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
		BABY_SUPPORT.clear();
		SHOULDERS.clear();
		SHOULDER_BY_ENTITY.clear();
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
		// Mobs whose animation is advanced in their own tick (not from synced fields) need help here.
		for (Map.Entry<Entity, Entity> en : SOURCE_OF_PROXY.entrySet()) {
			Entity proxy = en.getKey();
			Entity src = en.getValue();
			if (src.isRemoved()) continue;
			try {
				sync(src, proxy);
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Morph sync failed", t);
			}
			if (DragonAnimator.isDragon(proxy)) {
				DragonAnimator.tick(src, proxy);
			} else {
				MobAnimator.tick(src, proxy);
			}
		}
		for (ShoulderProxy sp : SHOULDERS.values()) {
			Entity holder = sp.holder.get();
			if (holder == null || holder.isRemoved()) continue;
			try {
				// Sitting on a shoulder: animate as if standing on the ground, flap when the player is airborne.
				MobAnimator.tick(holder, sp.entity);
			} catch (Throwable t) {
				EntityMorphClient.LOGGER.debug("Shoulder animation failed", t);
			}
		}
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

	private static final Map<String, Boolean> BABY_SUPPORT = new HashMap<>();

	/**
	 * Whether the model has a baby form: a throwaway instance is made a baby and asked if it is one.
	 * Mobs without babies (most hostiles) inherit a setBaby that does nothing, so they answer no.
	 */
	public static boolean supportsBaby(String model) {
		ensureLevel();
		Boolean known = BABY_SUPPORT.get(model);
		if (known != null) return known;
		boolean ok = false;
		if (!PLAYER_MODEL.equals(model)) {
			Entity probe = create(model, lastLevel);
			if (probe instanceof LivingEntity living) {
				try {
					applyBaby(probe, true);
					ok = living.isBaby();
				} catch (Throwable ignored) {
				}
			}
		}
		BABY_SUPPORT.put(model, ok);
		return ok;
	}

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
		if (dst.getX() != src.getX() || dst.getY() != src.getY() || dst.getZ() != src.getZ()) {
			// Some mobs (shulker) snap setPos to the block grid; a morph must follow smoothly instead.
			dst.setPosRaw(src.getX(), src.getY(), src.getZ());
		}
		dst.xo = src.xo;
		dst.yo = src.yo;
		dst.zo = src.zo;
		dst.xOld = src.xOld;
		dst.yOld = src.yOld;
		dst.zOld = src.zOld;
		dst.setYRot(src.getYRot());
		dst.setXRot(src.getXRot());
		dst.yRotO = src.yRotO;
		if (DragonAnimator.isDragon(dst)) {
			// The dragon model faces the opposite way to other mobs.
			dst.setYRot(DragonAnimator.dragonYaw(src));
			dst.yRotO = DragonAnimator.dragonYawO(src);
		}
		dst.xRotO = src.xRotO;
		dst.tickCount = src.tickCount;
		dst.setOnGround(src.onGround());
		dst.setDeltaMovement(src.getDeltaMovement());
		copyLeash(src, dst);
		copyFluidState(src, dst);
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
			// Keep the same health *fraction*, so damage-dependent looks (iron golem cracks, wolf tail)
			// match how hurt the real entity is rather than comparing raw health to a different max.
			float fraction = s.getMaxHealth() > 0 ? Math.min(1.0F, s.getHealth() / s.getMaxHealth()) : 1.0F;
			float health = fraction * d.getMaxHealth();
			if (s.getHealth() > 0 && health <= 0) health = Math.min(1.0F, d.getMaxHealth());
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
			float base;
			if (DragonAnimator.isDragon(d)) {
				// Multipart boss: use its type size directly (its own dimensions may not follow SCALE).
				EntityDimensions typeDims = d.getType().getDimensions();
				base = mode == MorphEntry.FitMode.EYES ? typeDims.eyeHeight() : typeDims.height();
			} else {
				EntityDimensions dstDims = d.getDimensions(Pose.STANDING);
				float current = d.getScale();
				if (current <= 0) current = 1;
				// dimensions scale linearly with the attribute, so divide out the current scale
				base = (mode == MorphEntry.FitMode.EYES ? dstDims.eyeHeight() : dstDims.height()) / current;
			}
			float want = mode == MorphEntry.FitMode.EYES ? srcDims.eyeHeight() : srcDims.height();
			if (base > 0.01F && want > 0.01F) target = Math.max(0.0625, Math.min(16.0, want / base));
		}
		if (DragonAnimator.isDragon(d)) DragonAnimator.setScale(d, (float) target);
		if (Math.abs(scaleAttr.getBaseValue() - target) > 1.0E-3) {
			scaleAttr.setBaseValue(target);
			d.refreshDimensions();
		}
	}

	private static boolean leashLooked;
	private static @Nullable Method getLeashData;
	private static @Nullable Method setLeashData;

	/** A leashed mob's lead belongs to the real entity; share it with the proxy so the lead is drawn from the morph. */
	private static void copyLeash(Entity from, Entity to) {
		if (!(from instanceof net.minecraft.world.entity.Leashable) || !(to instanceof net.minecraft.world.entity.Leashable)) return;
		if (!leashLooked) {
			leashLooked = true;
			for (Method m : net.minecraft.world.entity.Leashable.class.getMethods()) {
				if (m.getName().equals("getLeashData") && m.getParameterCount() == 0) getLeashData = m;
				if (m.getName().equals("setLeashData") && m.getParameterCount() == 1) setLeashData = m;
			}
			if (getLeashData == null || setLeashData == null) {
				EntityMorphClient.LOGGER.warn("Leash data access not found; leads on morphed mobs disabled");
			}
		}
		if (getLeashData == null || setLeashData == null) return;
		try {
			Object data = getLeashData.invoke(from);
			if (getLeashData.invoke(to) != data) setLeashData.invoke(to, data);
		} catch (Throwable t) {
			getLeashData = null;
			EntityMorphClient.LOGGER.warn("Copying leash to morph failed; disabling", t);
		}
	}

	private static @Nullable Field[] fluidFields;

	/** isInWater / isUnderWater are cached flags updated in the entity's own tick; copy them from the source. */
	private static void copyFluidState(Entity from, Entity to) {
		if (fluidFields == null) {
			List<Field> fs = new ArrayList<>();
			for (String name : new String[]{"wasTouchingWater", "wasEyeInWater"}) {
				try {
					Field f = Entity.class.getDeclaredField(name);
					if (f.getType() == boolean.class) {
						f.setAccessible(true);
						fs.add(f);
					}
				} catch (NoSuchFieldException | RuntimeException ignored) {
				}
			}
			fluidFields = fs.toArray(new Field[0]);
		}
		try {
			for (Field f : fluidFields) f.setBoolean(to, f.getBoolean(from));
		} catch (IllegalAccessException ignored) {
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
