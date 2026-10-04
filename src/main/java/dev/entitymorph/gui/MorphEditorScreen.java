package dev.entitymorph.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import dev.entitymorph.config.MorphConfig;
import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.skin.SkinCache;

/**
 * Editor for one entity: pick the model, the skin source, arm width and baby toggle.
 * Changes preview live (in the world and in the doll on the left) and are only written on Save.
 */
public class MorphEditorScreen extends Screen {
	private static final int ROW = 22;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6060;
	private static final int GREEN = 0xFF80FF80;

	private final @Nullable Screen parent;
	private final LivingEntity target;
	private final UUID uuid;
	private final MorphEntry draft;

	private List<String> allModels = List.of();
	private final List<String> filtered = new ArrayList<>();
	private String search = "";
	private int page;
	private int rowsPerPage = 1;
	private int columns = 1;

	private EditBox searchBox;
	private EditBox valueBox;
	/** Text in the value box (applied to the draft only on Load / Next file / Save). */
	private String typedValue;
	private boolean needJump = true;
	private int fileIndex = -1;

	// layout
	private int previewX0, previewY0, previewX1, previewY1;
	private int rightX, rightW, listY, optY, bottomY;

	public MorphEditorScreen(@Nullable Screen parent, LivingEntity target) {
		super(Component.literal("Entity Morph"));
		this.parent = parent;
		this.target = target;
		this.uuid = target.getUUID();
		MorphEntry saved = MorphConfig.getSaved(uuid);
		this.draft = saved != null ? saved.copy() : new MorphEntry();
		this.typedValue = draft.skinValue;
	}

	@Override
	protected void init() {
		if (allModels.isEmpty()) {
			List<String> models = new ArrayList<>();
			models.add("");
			models.addAll(MorphManager.availableModels());
			allModels = models;
			refilter();
		}

		int leftW = Math.min(170, this.width / 3);
		previewX0 = 10;
		previewY0 = 36;
		previewX1 = previewX0 + leftW;
		previewY1 = this.height - 34;

		rightX = previewX1 + 12;
		rightW = this.width - rightX - 10;
		bottomY = this.height - 26;
		optY = bottomY - 3 * 24 - 14;
		listY = 60;
		columns = rightW >= 320 ? 2 : 1;
		rowsPerPage = Math.max(1, (optY - 6 - listY - ROW) / ROW);
		if (needJump) {
			needJump = false;
			int idx = draft.hasModel() ? filtered.indexOf(draft.model) : 0;
			if (idx > 0) page = idx / (rowsPerPage * columns);
		}

		// search
		searchBox = new EditBox(this.font, rightX, 36, rightW, 18, Component.literal("Search models"));
		searchBox.setHint(Component.literal("Search models…"));
		searchBox.setMaxLength(64);
		searchBox.setValue(search);
		searchBox.setResponder(s -> {
			if (s.equals(search)) return;
			search = s;
			refilter();
			page = 0;
			rebuildWidgets();
			setFocused(searchBox);
		});
		addRenderableWidget(searchBox);

		// model list (paged buttons)
		int perPage = rowsPerPage * columns;
		int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		int colW = (rightW - (columns - 1) * 4) / columns;
		for (int i = 0; i < perPage; i++) {
			int idx = page * perPage + i;
			if (idx >= filtered.size()) break;
			String model = filtered.get(idx);
			int col = i / rowsPerPage;
			int row = i % rowsPerPage;
			boolean selected = model.isEmpty() ? !draft.hasModel() : model.equals(draft.model);
			String label = model.isEmpty() ? "Original model" : MorphManager.displayName(model);
			Button b = Button.builder(Component.literal((selected ? "▶ " : "") + label), btn -> {
						draft.model = model.isEmpty() ? null : model;
						changed();
						rebuildWidgets();
					})
					.pos(rightX + col * (colW + 4), listY + row * ROW)
					.size(colW, 20)
					.tooltip(Tooltip.create(Component.literal(model.isEmpty() ? "Render with its own model" : model)))
					.build();
			addRenderableWidget(b);
		}
		int pagerY = listY + rowsPerPage * ROW;
		Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
				.pos(rightX, pagerY).size(20, 20).build();
		prev.active = page > 0;
		addRenderableWidget(prev);
		Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
				.pos(rightX + rightW - 20, pagerY).size(20, 20).build();
		next.active = page < pages - 1;
		addRenderableWidget(next);

		// options row 1: skin source, arms, baby
		int w1 = Math.min(150, (rightW - 8) * 2 / 5);
		int w2 = Math.min(120, (rightW - 8) * 3 / 10);
		int w3 = rightW - w1 - w2 - 8;
		addRenderableWidget(Button.builder(Component.literal("Skin: " + draft.skinType.label), b -> {
					draft.skinType = draft.skinType.next();
					draft.skinValue = "";
					typedValue = "";
					fileIndex = -1;
					changed();
					rebuildWidgets();
				}).pos(rightX, optY).size(w1, 20)
				.tooltip(Tooltip.create(Component.literal("Default = normal texture. Player name = download that player's skin. Skin file = a PNG in the skins folder. Texture id = any loaded texture, e.g. minecraft:textures/entity/zombie/husk.png")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Arms: " + draft.arm.label), b -> {
					draft.arm = draft.arm.next();
					changed();
					rebuildWidgets();
				}).pos(rightX + w1 + 4, optY).size(w2, 20)
				.tooltip(Tooltip.create(Component.literal("Arm width for player models (also first person)")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Baby: " + (draft.baby ? "On" : "Off")), b -> {
					draft.baby = !draft.baby;
					changed();
					rebuildWidgets();
				}).pos(rightX + w1 + w2 + 8, optY).size(w3, 20).build());

		// options row 2: value + load + browse
		int btnW = 56;
		valueBox = new EditBox(this.font, rightX, optY + 25, rightW - 2 * (btnW + 4), 18, Component.literal("Skin value"));
		valueBox.setMaxLength(256);
		valueBox.setValue(typedValue);
		valueBox.setResponder(v -> typedValue = v);
		valueBox.setHint(Component.literal(switch (draft.skinType) {
			case DEFAULT -> "(choose a skin source)";
			case PLAYER_NAME -> "Player name, e.g. Notch";
			case FILE -> "File in skins folder, e.g. knight.png";
			case RESOURCE -> "namespace:textures/entity/....png";
		}));
		valueBox.setEditable(draft.skinType != MorphEntry.SkinType.DEFAULT);
		addRenderableWidget(valueBox);
		Button load = Button.builder(Component.literal("Load"), b -> applyValue(true))
				.pos(rightX + rightW - 2 * btnW - 4, optY + 24).size(btnW, 20)
				.tooltip(Tooltip.create(Component.literal("Apply / reload this skin")))
				.build();
		load.active = draft.skinType != MorphEntry.SkinType.DEFAULT;
		addRenderableWidget(load);
		Button browse = Button.builder(Component.literal("Next file"), b -> nextFile())
				.pos(rightX + rightW - btnW, optY + 24).size(btnW, 20)
				.tooltip(Tooltip.create(Component.literal("Cycle through PNGs in the skins folder")))
				.build();
		browse.active = draft.skinType == MorphEntry.SkinType.FILE;
		addRenderableWidget(browse);

		// options row 3: utilities
		int uw = (rightW - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Skins folder"), b -> com.mojang.blaze3d.Blaze3D.openPath(MorphConfig.skinsDir()))
				.pos(rightX, optY + 48).size(uw, 20).build());
		Button me = Button.builder(Component.literal("Edit me"), b -> {
					if (minecraft != null && minecraft.player != null) minecraft.gui.setScreen(new MorphEditorScreen(parent, minecraft.player));
				})
				.pos(rightX + uw + 4, optY + 48).size(uw, 20).build();
		me.active = minecraft != null && minecraft.player != null && minecraft.player != target;
		addRenderableWidget(me);
		addRenderableWidget(Button.builder(Component.literal("All saved…"), b -> {
					if (minecraft != null) minecraft.gui.setScreen(new MorphListScreen(this));
				})
				.pos(rightX + 2 * (uw + 4), optY + 48).size(rightW - 2 * (uw + 4), 20).build());

		// bottom
		int bw = (rightW - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).pos(rightX, bottomY).size(bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> reset())
				.pos(rightX + bw + 4, bottomY).size(bw, 20)
				.tooltip(Tooltip.create(Component.literal("Remove this entity's morph"))).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.pos(rightX + 2 * (bw + 4), bottomY).size(rightW - 2 * (bw + 4), 20).build());

		changed();
	}

	private void refilter() {
		filtered.clear();
		String q = search.trim().toLowerCase(Locale.ROOT);
		for (String m : allModels) {
			if (q.isEmpty() || (m.isEmpty() ? "original".contains(q)
					: m.toLowerCase(Locale.ROOT).contains(q) || MorphManager.displayName(m).toLowerCase(Locale.ROOT).contains(q))) {
				filtered.add(m);
			}
		}
	}

	private void applyValue(boolean reload) {
		draft.skinValue = typedValue.trim();
		if (reload) SkinCache.invalidate(draft);
		changed();
	}

	private void nextFile() {
		List<String> files = listSkinFiles();
		if (files.isEmpty()) {
			valueBox.setValue("");
			return;
		}
		fileIndex = (fileIndex + 1) % files.size();
		valueBox.setValue(files.get(fileIndex));
		applyValue(false);
	}

	private static List<String> listSkinFiles() {
		Path dir = MorphConfig.skinsDir();
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
					.map(p -> p.getFileName().toString())
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	private void changed() {
		MorphConfig.setPreview(uuid, draft);
	}

	private void save() {
		if (draft.skinType != MorphEntry.SkinType.DEFAULT) draft.skinValue = typedValue.trim();
		MorphConfig.clearPreview(uuid);
		MorphConfig.put(uuid, draft);
		onClose();
	}

	private void reset() {
		MorphConfig.clearPreview(uuid);
		MorphConfig.remove(uuid);
		onClose();
	}

	@Override
	public void onClose() {
		MorphConfig.clearPreview(uuid);
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		MorphConfig.clearPreview(uuid);
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// header
		String name = target.getDisplayName().getString();
		boolean self = minecraft != null && target == minecraft.player;
		graphics.text(this.font, "Entity Morph — " + name + (self ? " (you)" : ""), 10, 8, WHITE);
		graphics.text(this.font, MorphManager.idOf(target.getType()) + "  ·  " + uuid + "  ·  " + MorphConfig.scopeLabel(), 10, 20, GREY);

		// preview
		graphics.fill(previewX0, previewY0, previewX1, previewY1, 0x80000000);
		if (target.isAlive() && !target.isRemoved()) {
			Entity shown = MorphManager.proxyFor(target);
			if (shown == null) shown = target;
			float h = Math.max(shown.getBbHeight(), shown.getBbWidth() * 0.8F);
			int size = (int) Math.max(4, Math.min(120, (previewY1 - previewY0) * 0.65F / Math.max(0.3F, h)));
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, previewX0, previewY0, previewX1, previewY1,
					size, 0.0625F, mouseX, mouseY, target);
		} else {
			graphics.centeredText(this.font, "Entity is gone", (previewX0 + previewX1) / 2, (previewY0 + previewY1) / 2, RED);
		}
		String modelLabel = draft.hasModel() ? MorphManager.displayName(draft.model) : "Original model";
		graphics.centeredText(this.font, modelLabel, (previewX0 + previewX1) / 2, previewY1 + 6, WHITE);

		// pager label
		int perPage = rowsPerPage * columns;
		int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
		graphics.centeredText(this.font, "Page " + (page + 1) + " / " + pages + "  (" + Math.max(0, filtered.size() - 1) + " models)",
				rightX + rightW / 2, listY + rowsPerPage * ROW + 6, GREY);

		// status
		String status;
		int color;
		if (draft.hasModel() && MorphManager.proxyFor(target) == null && !(MorphManager.PLAYER_MODEL.equals(draft.model) && target instanceof Player)
				&& !draft.model.equals(MorphManager.idOf(target.getType()))) {
			status = "This model can't be created on the client.";
			color = RED;
		} else if (draft.skinType == MorphEntry.SkinType.DEFAULT) {
			status = "Using the model's normal texture.";
			color = GREY;
		} else if (!draft.hasSkin()) {
			status = "Enter a value and press Load.";
			color = GREY;
		} else {
			switch (SkinCache.status(draft)) {
				case LOADING -> { status = "Loading skin…"; color = GREY; }
				case FAILED -> { status = "Skin error: " + SkinCache.error(draft); color = RED; }
				default -> { status = "Skin ready."; color = GREEN; }
			}
			// trigger the load if nobody has asked for it yet
			SkinCache.resolve(draft);
		}
		graphics.text(this.font, status, rightX, optY + 72 + 2, color);
	}
}
