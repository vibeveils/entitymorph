package dev.entitymorph.config;

import java.util.Objects;

/**
 * What one entity (identified by UUID) should look like.
 * Serialized as-is with Gson, so keep fields simple.
 */
public final class MorphEntry {
	/** Entity type id to render as, e.g. "minecraft:zombie". Null/empty = keep the original model. */
	public String model;
	public SkinType skinType = SkinType.DEFAULT;
	/** Player name, file name (inside the skins folder) or texture id, depending on {@link #skinType}. */
	public String skinValue = "";
	/** Only used when the model is a player model. */
	public ArmModel arm = ArmModel.AUTO;
	/** Render the morph as a baby where the model supports it. */
	public boolean baby = false;

	public enum SkinType {
		DEFAULT("Default"),
		PLAYER_NAME("Player name"),
		FILE("Skin file"),
		RESOURCE("Texture id");

		public final String label;

		SkinType(String label) {
			this.label = label;
		}

		public SkinType next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public enum ArmModel {
		AUTO("Auto"),
		WIDE("Wide (Steve)"),
		SLIM("Slim (Alex)");

		public final String label;

		ArmModel(String label) {
			this.label = label;
		}

		public ArmModel next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public boolean hasModel() {
		return model != null && !model.isBlank();
	}

	public boolean hasSkin() {
		return skinType != null && skinType != SkinType.DEFAULT && skinValue != null && !skinValue.isBlank();
	}

	public boolean isEmpty() {
		return !hasModel() && !hasSkin() && !baby && (arm == null || arm == ArmModel.AUTO);
	}

	public MorphEntry copy() {
		MorphEntry e = new MorphEntry();
		e.model = model;
		e.skinType = skinType;
		e.skinValue = skinValue;
		e.arm = arm;
		e.baby = baby;
		return e;
	}

	public void sanitize() {
		if (skinType == null) skinType = SkinType.DEFAULT;
		if (skinValue == null) skinValue = "";
		if (arm == null) arm = ArmModel.AUTO;
		if (model != null && model.isBlank()) model = null;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof MorphEntry e)) return false;
		return baby == e.baby && Objects.equals(model, e.model) && skinType == e.skinType
				&& Objects.equals(skinValue, e.skinValue) && arm == e.arm;
	}

	@Override
	public int hashCode() {
		return Objects.hash(model, skinType, skinValue, arm, baby);
	}
}
