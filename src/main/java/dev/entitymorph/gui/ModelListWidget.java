package dev.entitymorph.gui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;

import dev.entitymorph.render.MorphManager;

/** Scrollable list of models. The empty string means "original model". */
public class ModelListWidget extends AbstractSelectionList<ModelListWidget.Row> {
	public static final int ROW_HEIGHT = 16;

	private final Consumer<String> onPick;
	private final Supplier<String> selected;

	public ModelListWidget(Minecraft mc, int x, int y, int width, int height, Consumer<String> onPick, Supplier<String> selected) {
		super(mc, width, height, y, ROW_HEIGHT);
		this.onPick = onPick;
		this.selected = selected;
		setX(x);
	}

	public void setModels(List<String> models) {
		clearEntries();
		for (String m : models) addEntry(new Row(m));
		setScrollAmount(0);
	}

	/** Scrolls so the given model is roughly centred. */
	public void reveal(String model) {
		List<Row> rows = children();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).model.equals(model)) {
				setScrollAmount(Math.max(0, i * ROW_HEIGHT - (getHeight() - ROW_HEIGHT) / 2.0));
				return;
			}
		}
	}

	@Override
	public int getRowWidth() {
		return width - 12;
	}

	@Override
	protected int scrollBarX() {
		return getX() + width - 6;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	public class Row extends AbstractSelectionList.Entry<Row> {
		final String model;
		final String name;

		Row(String model) {
			this.model = model;
			this.name = model.isEmpty() ? "Original model" : MorphManager.displayName(model);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
			int x = getContentX();
			int y = getContentY();
			int w = getContentWidth();
			boolean isSelected = model.equals(selected.get());
			if (isSelected) {
				graphics.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x60FFFFFF);
			} else if (hovered) {
				graphics.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x30FFFFFF);
			}
			graphics.text(minecraft.font, name, x + 2, y + 2, isSelected ? 0xFFFFFF80 : 0xFFFFFFFF);
			if (!model.isEmpty() && w > 140) {
				String id = model.startsWith("minecraft:") ? model.substring(10) : model;
				int idW = minecraft.font.width(id);
				if (minecraft.font.width(name) + idW + 12 < w) {
					graphics.text(minecraft.font, id, x + w - idW - 2, y + 2, 0xFF808080);
				}
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			onPick.accept(model);
			return true;
		}
	}
}
