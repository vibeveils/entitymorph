package dev.entitymorph.render;

import java.lang.reflect.Method;
import java.util.NoSuchElementException;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

import dev.entitymorph.EntityMorphClient;

/**
 * Draws the main-hand item in a wolf's mouth, the way foxes carry items. The wolf renderer has no
 * such layer, so this adds one; the item is resolved at extract time and stored on the render state.
 */
public class MouthItemLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	public static final RenderStateDataKey<ItemStackRenderState> MOUTH_ITEM = RenderStateDataKey.create(() -> "entitymorph mouth item");

	private static @Nullable ItemModelResolver resolver;
	private static @Nullable Method updateForLiving;
	private static boolean looked;

	/** Where the item is drawn: in a wolf's mouth, or carried in front like an enderman's block. */
	public enum Mode { MOUTH, CARRY }

	private final Mode mode;

	public MouthItemLayer(RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> parent, ItemModelResolver itemModelResolver) {
		this(parent, itemModelResolver, Mode.MOUTH);
	}

	public MouthItemLayer(RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> parent, ItemModelResolver itemModelResolver, Mode mode) {
		super(parent);
		resolver = itemModelResolver;
		this.mode = mode;
	}

	/** Called from extraction: resolve the held item into a render state, or clear it. */
	public static void extract(LivingEntity entity, FabricRenderState state) {
		extract(entity, state, false);
	}

	/** @param skipBlocks blocks are drawn natively (enderman carried block), so only resolve other items */
	public static void extract(LivingEntity entity, FabricRenderState state, boolean skipBlocks) {
		ItemStack stack = entity.getMainHandItem();
		if (stack.isEmpty() || resolver == null || entity.isInvisible()
				|| (skipBlocks && stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
			state.setData(MOUTH_ITEM, null);
			return;
		}
		ItemStackRenderState item = new ItemStackRenderState();
		if (update(item, stack, entity)) state.setData(MOUTH_ITEM, item);
		else state.setData(MOUTH_ITEM, null);
	}

	/** ItemModelResolver.updateForLiving's parameter list has changed between versions; match it by type. */
	private static boolean update(ItemStackRenderState item, ItemStack stack, LivingEntity entity) {
		if (!looked) {
			looked = true;
			for (Method m : ItemModelResolver.class.getMethods()) {
				if (m.getName().equals("updateForLiving")) {
					updateForLiving = m;
					break;
				}
			}
			if (updateForLiving == null) EntityMorphClient.LOGGER.warn("ItemModelResolver.updateForLiving not found; wolf mouth items disabled");
		}
		if (updateForLiving == null) return false;
		Class<?>[] types = updateForLiving.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			Class<?> t = types[i];
			if (t.isInstance(item)) args[i] = item;
			else if (t.isInstance(stack)) args[i] = stack;
			else if (t == ItemDisplayContext.class) args[i] = ItemDisplayContext.GROUND;
			else if (t.isInstance(entity)) args[i] = entity;
			else if (t == boolean.class) args[i] = false;
			else if (t == int.class) args[i] = 0;
			else args[i] = null;
		}
		try {
			updateForLiving.invoke(resolver, args);
			return true;
		} catch (Throwable t) {
			updateForLiving = null;
			EntityMorphClient.LOGGER.warn("Resolving wolf mouth item failed; disabling", t);
			return false;
		}
	}

	private static @Nullable ModelPart child(ModelPart parent, String name) {
		try {
			return parent.getChild(name);
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return null;
		}
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, LivingEntityRenderState state, float yRot, float xRot) {
		ItemStackRenderState item = ((FabricRenderState) state).getData(MOUTH_ITEM);
		if (item == null || item.isEmpty()) return;

		if (mode == Mode.CARRY) {
			// Same spot the enderman holds its block: in front of the body, between the hands.
			poseStack.pushPose();
			getParentModel().root().translateAndRotate(poseStack);
			poseStack.translate(0.0F, 0.6875F, -0.75F);
			poseStack.rotate(Axis.XP, (float) Math.toRadians(20.0));
			poseStack.translate(0.0F, 0.1875F, 0.1F);
			poseStack.rotate(Axis.XP, (float) Math.toRadians(180.0));
			item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
			return;
		}

		ModelPart root = getParentModel().root();
		ModelPart head = child(root, "head");
		if (head == null) return;
		ModelPart realHead = child(head, "real_head");

		poseStack.pushPose();
		// Follow the head exactly (baby scale, look direction, begging head tilt).
		root.translateAndRotate(poseStack);
		head.translateAndRotate(poseStack);
		if (realHead != null) realHead.translateAndRotate(poseStack);
		// Just under the tip of the snout, held crosswise like a fox does.
		poseStack.translate(1.0F / 16.0F, 3.3F / 16.0F, -4.5F / 16.0F);
		poseStack.rotate(Axis.XP, (float) Math.toRadians(90.0));
		item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		poseStack.popPose();
	}
}
