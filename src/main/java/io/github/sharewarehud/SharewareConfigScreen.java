package io.github.sharewarehud;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import io.github.sharewarehud.SharewareConfig.ExperienceStyle;

/**
 * Settings screen opened from Mod Menu. Built only from vanilla widgets, so it needs no config library.
 * Changes apply immediately and are saved when the screen closes.
 */
public final class SharewareConfigScreen extends Screen {
	private static final int ROW_H = 24;
	private static final int COL_W = 150;
	private static final int COL_GAP = 10;

	private final Screen parent;
	private final SharewareConfig config;

	public SharewareConfigScreen(Screen parent) {
		super(Component.literal("Shareware HUD"));
		this.parent = parent;
		this.config = SharewareHud.config;
	}

	@Override
	protected void init() {
		int full = COL_W * 2 + COL_GAP;
		int left = this.width / 2 - full / 2;
		int right = left + COL_W + COL_GAP;
		int y = 30;

		this.addRenderableWidget(toggle(left, y, full,
				() -> "Shareware HUD: " + (config.enabled ? "ON" : "OFF (vanilla HUD)"),
				() -> config.enabled = !config.enabled));
		y += ROW_H + 6;

		// look
		this.addRenderableWidget(new Slider(left, y, COL_W, 25, 100, 5,
				() -> config.hudScale * 100, v -> config.hudScale = (float) (v / 100),
				v -> "HUD size: " + (int) v + "%"));
		this.addRenderableWidget(toggle(right, y, COL_W,
				() -> "XP: " + switch (config.experienceStyle) {
					case PANEL -> "In panel";
					case VANILLA -> "Vanilla bar";
					case HIDDEN -> "Hidden";
				},
				() -> {
					ExperienceStyle[] all = ExperienceStyle.values();
					config.experienceStyle = all[(config.experienceStyle.ordinal() + 1) % all.length];
				}));
		y += ROW_H;

		this.addRenderableWidget(new Slider(left, y, COL_W, 0, 100, 5,
				() -> config.hudOpacity * 100, v -> config.hudOpacity = (float) (v / 100),
				v -> "HUD opacity: " + (int) v + "%"));
		this.addRenderableWidget(new Slider(right, y, COL_W, 0, 100, 5,
				() -> config.backgroundOpacity * 100, v -> config.backgroundOpacity = (float) (v / 100),
				v -> "Background: " + (int) v + "%"));
		y += ROW_H;

		// portrait
		this.addRenderableWidget(new Slider(left, y, COL_W, 10, 120, 1,
				() -> config.portraitScale, v -> config.portraitScale = (int) v,
				v -> "Portrait zoom: " + (int) v));
		this.addRenderableWidget(new Slider(right, y, COL_W, -2, 2, 0.05,
				() -> config.portraitYOffset, v -> config.portraitYOffset = (float) v,
				v -> String.format("Portrait framing: %.2f", v)));
		y += ROW_H;

		this.addRenderableWidget(toggle(left, y, COL_W,
				() -> "Head turning: " + onOff(config.portraitLooksAround),
				() -> config.portraitLooksAround = !config.portraitLooksAround));
		this.addRenderableWidget(toggle(right, y, COL_W,
				() -> "Item counts: " + onOff(config.showItemDecorations),
				() -> config.showItemDecorations = !config.showItemDecorations));
		y += ROW_H + 10;

		this.addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), _ -> {
			config.copyFrom(new SharewareConfig());
			this.rebuildWidgets();
		}).pos(left, y).size(COL_W, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), _ -> this.onClose())
				.pos(right, y).size(COL_W, 20).build());
	}

	/** Button whose text shows the current value; clicking changes it. */
	private static Button toggle(int x, int y, int w, Supplier<String> text, Runnable action) {
		return Button.builder(Component.literal(text.get()), b -> {
			action.run();
			b.setMessage(Component.literal(text.get()));
		}).pos(x, y).size(w, 20).build();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, "Shareware HUD", this.width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		config.sanitize();
		config.save();
		this.minecraft.gui.setScreen(parent);
	}

	private static String onOff(boolean b) {
		return b ? "ON" : "OFF";
	}

	/** Slider over [min, max] snapped to {@code step}; writes straight into the config. */
	private static final class Slider extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final double step;
		private final DoubleConsumer setter;
		private final DoubleFunction<String> label;

		Slider(int x, int y, int w, double min, double max, double step,
				DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> label) {
			super(x, y, w, 20, Component.empty(), toSlider(getter.getAsDouble(), min, max));
			this.min = min;
			this.max = max;
			this.step = step;
			this.setter = setter;
			this.label = label;
			this.updateMessage();
		}

		private static double toSlider(double v, double min, double max) {
			return Math.clamp((v - min) / (max - min), 0.0, 1.0);
		}

		private double current() {
			double v = min + this.value * (max - min);
			v = Math.round(v / step) * step;
			return Math.clamp(v, min, max);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(label.apply(current())));
		}

		@Override
		protected void applyValue() {
			setter.accept(current());
		}
	}
}
