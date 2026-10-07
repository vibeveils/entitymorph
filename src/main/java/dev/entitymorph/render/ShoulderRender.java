package dev.entitymorph.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** A morphed shoulder parrot, extracted and ready to submit from the shoulder layer. */
public record ShoulderRender(EntityRenderer<?, ?> renderer, EntityRenderState state, float scale) {
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		((EntityRenderer) renderer).submit(state, poseStack, collector, camera);
	}
}
