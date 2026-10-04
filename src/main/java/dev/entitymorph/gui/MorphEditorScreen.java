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
import dev.entitymorph.render.Appearance;
import dev.entitymorph.render.MorphManager;
import dev.entitymorph.skin.SkinCache;

/**
 * Editor for one entity. Three columns:
 * live preview | scrolling, searchable mob list | look (variants, collar, tamed, angry, …) + skin.
 * Changes preview live in the world and are only written on Save.
 */
public class MorphEditorScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6060;
	private static final int GREEN = 0xFF80FF80;
	private static final int GOLD = 0xFFFFD060;

	private final @Nullable Screen parent;
	private final LivingEntity target;
	private final UUID uuid;
	private final MorphEntry draft;

	private List<String> allModels = List.of();
	private final List<String> filtered = new ArrayList<>();
	private String search = "";

	private @Nullable ModelListWidget modelList;
	private EditBox valueBox;
	/** Text in the value box (applied to the draft only on Load / Next file / Save). */
	private String typedValue;
	private int fileIndex = -1;
	private int lookPage;

	// layout
	private int previewX0, previewY0, previewX1, previewY1;
	private int listX, listW;
	private int optX, optW, lookTitleY, statusY, bottomY;
	private int lookPages = 1;

	public MorphEditorScreen(@Nullable Screen parent, LivingEntity target) {
		super(Component.literal("Entity Morph"));
		this.parent = parent;
		this.target = target;
		this.uuid = target.getUUID();
		MorphEntry saved = MorphConfig.getSaved(uuid);
		this.draft = saved != null ? saved.copy() : new MorphEntry();
		this.draft.sanitize();
		this.typedValue = draft.skinValue;
	}

	/** The model whose variants are being edited (the chosen one, or the entity's own). */
	private String effectiveModel() {
		if (draft.hasModel()) return draft.model;
		return target instanceof Player ? MorphManager.PLAYER_MODEL : MorphManager.idOf(target.getType());
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

		// ---- columns
		int pw = Math.max(100, Math.min(160, (int) (this.width * 0.24F)));
		previewX0 = 10;
		previewY0 = 36;
		previewX1 = previewX0 + pw;
		previewY1 = this.height - 34;

		listX = previewX1 + 10;
		listW = Math.max(120, Math.min(200, (int) (this.width * 0.28F)));
		optX = listX + listW + 10;
		optW = this.width - optX - 10;
		bottomY = this.height - 26;

		// ---- mob list (scrolling)
		EditBox searchBox = new EditBox(this.font, listX, 36, listW, 18, Component.literal("Search mobs"));
		searchBox.setHint(Component.literal("Search mobs…"));
		searchBox.setMaxLength(64);
		searchBox.setValue(search);
		searchBox.setResponder(s -> {
			if (s.equals(search)) return;
			search = s;
			refilter();
			if (modelList != null) modelList.setModels(filtered);
		});
		addRenderableWidget(searchBox);

		double keepScroll = modelList != null ? modelList.scrollAmount() : -1;
		modelList = new ModelListWidget(this.minecraft, listX, 58, listW, previewY1 - 58,
				this::pickModel, () -> draft.hasModel() ? draft.model : "");
		modelList.setModels(filtered);
		if (keepScroll >= 0) modelList.setScrollAmount(keepScroll);
		else modelList.reveal(draft.hasModel() ? draft.model : "");
		addRenderableWidget(modelList);

		// ---- bottom-anchored skin section
		int rowC = bottomY - 24;
		int rowB = rowC - 24;
		int rowA = rowB - 24;
		statusY = rowA - 12;
		buildSkinSection(rowA, rowB, rowC);

		// ---- look section (fills the space above)
		lookTitleY = 36;
		buildLookSection(lookTitleY + 12, statusY - 4);

		// ---- bottom buttons
		int bw = (optW - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).pos(optX, bottomY).size(bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> reset())
				.pos(optX + bw + 4, bottomY).size(bw, 20)
				.tooltip(Tooltip.create(Component.literal("Remove this entity's morph"))).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.pos(optX + 2 * (bw + 4), bottomY).size(optW - 2 * (bw + 4), 20).build());

		changed();
	}

	// ------------------------------------------------------------------ look

	private interface Item {
		Button build(int x, int y, int w);
	}

	private void buildLookSection(int top, int bottom) {
		String model = effectiveModel();
		Entity sample = MorphManager.sample(model);
		List<Item> items = new ArrayList<>();

		items.add((x, y, w) -> toggle(x, y, w, "Baby", draft.baby, () -> draft.baby = !draft.baby));

		if (sample != null) {
			for (Appearance.ComponentOption opt : Appearance.componentsFor(model, sample)) {
				items.add((x, y, w) -> {
					String current = draft.components.get(opt.id());
					Button b = Button.builder(Component.literal(opt.label() + ": " + opt.labelFor(current)), btn -> {
								String next = cycle(opt.values(), current);
								if (next == null) draft.components.remove(opt.id());
								else draft.components.put(opt.id(), next);
								changed();
								rebuildWidgets();
							}).pos(x, y).size(w, 20).build();
					b.setTooltip(Tooltip.create(Component.literal(opt.id() + " · click to cycle (" + opt.values().size() + " options)")));
					return b;
				});
			}
			for (Appearance.Flag flag : Appearance.flagsFor(model, sample)) {
				items.add((x, y, w) -> toggle(x, y, w, flag.label(), draft.flags.contains(flag.id()), () -> {
					if (!draft.flags.remove(flag.id())) draft.flags.add(flag.id());
				}));
			}
		}

		int cols = optW >= 230 ? 2 : 1;
		int colW = (optW - (cols - 1) * 4) / cols;
		int rows = Math.max(1, (bottom - top) / 22);
		int perPage = rows * cols;
		lookPages = Math.max(1, (items.size() + perPage - 1) / perPage);
		lookPage = Math.max(0, Math.min(lookPage, lookPages - 1));

		for (int i = 0; i < perPage; i++) {
			int idx = lookPage * perPage + i;
			if (idx >= items.size()) break;
			int col = i % cols;
			int row = i / cols;
			addRenderableWidget(items.get(idx).build(optX + col * (colW + 4), top + row * 22, colW));
		}

		if (lookPages > 1) {
			Button prev = Button.builder(Component.literal("<"), b -> { lookPage--; rebuildWidgets(); })
					.pos(optX + optW - 42, lookTitleY - 5).size(20, 14).build();
			prev.active = lookPage > 0;
			addRenderableWidget(prev);
			Button next = Button.builder(Component.literal(">"), b -> { lookPage++; rebuildWidgets(); })
					.pos(optX + optW - 20, lookTitleY - 5).size(20, 14).build();
			next.active = lookPage < lookPages - 1;
			addRenderableWidget(next);
		}
	}

	private Button toggle(int x, int y, int w, String label, boolean on, Runnable flip) {
		return Button.builder(Component.literal(label + ": " + (on ? "On" : "Off")), b -> {
			flip.run();
			changed();
			rebuildWidgets();
		}).pos(x, y).size(w, 20).build();
	}

	/** Next value after {@code current}; after the last value comes null ("Default"). */
	private static @Nullable String cycle(List<String> values, @Nullable String current) {
		if (current == null) return values.isEmpty() ? null : values.get(0);
		int i = values.indexOf(current);
		return i < 0 || i + 1 >= values.size() ? null : values.get(i + 1);
	}

	private void pickModel(String model) {
		String newModel = model.isEmpty() ? null : model;
		if (java.util.Objects.equals(newModel, draft.model)) return;
		draft.model = newModel;
		// Variants and states belong to the old mob type.
		draft.components.clear();
		draft.flags.clear();
		lookPage = 0;
		changed();
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ skin

	private void buildSkinSection(int rowA, int rowB, int rowC) {
		int w1 = (optW - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal("Skin: " + draft.skinType.label), b -> {
					draft.skinType = draft.skinType.next();
					draft.skinValue = "";
					typedValue = "";
					fileIndex = -1;
					changed();
					rebuildWidgets();
				}).pos(optX, rowA).size(w1, 20)
				.tooltip(Tooltip.create(Component.literal("Default = normal texture. Player name = download that player's skin. Skin file = a PNG in the skins folder. Texture id = any loaded texture, e.g. minecraft:textures/entity/zombie/husk.png")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Arms: " + draft.arm.label), b -> {
					draft.arm = draft.arm.next();
					changed();
					rebuildWidgets();
				}).pos(optX + w1 + 4, rowA).size(optW - w1 - 4, 20)
				.tooltip(Tooltip.create(Component.literal("Arm width for player models (also first person)")))
				.build());

		int btnW = Math.min(56, (optW - 8) / 4);
		valueBox = new EditBox(this.font, optX, rowB + 1, optW - 2 * (btnW + 4), 18, Component.literal("Skin value"));
		valueBox.setMaxLength(256);
		valueBox.setValue(typedValue);
		valueBox.setResponder(v -> typedValue = v);
		valueBox.setHint(Component.literal(switch (draft.skinType) {
			case DEFAULT -> "(choose a skin source)";
			case PLAYER_NAME -> "Player name, e.g. Notch";
			case FILE -> "File in skins folder";
			case RESOURCE -> "namespace:textures/….png";
		}));
		valueBox.setEditable(draft.skinType != MorphEntry.SkinType.DEFAULT);
		addRenderableWidget(valueBox);
		Button load = Button.builder(Component.literal("Load"), b -> applyValue(true))
				.pos(optX + optW - 2 * btnW - 4, rowB).size(btnW, 20)
				.tooltip(Tooltip.create(Component.literal("Apply / reload this skin")))
				.build();
		load.active = draft.skinType != MorphEntry.SkinType.DEFAULT;
		addRenderableWidget(load);
		Button browse = Button.builder(Component.literal("Next"), b -> nextFile())
				.pos(optX + optW - btnW, rowB).size(btnW, 20)
				.tooltip(Tooltip.create(Component.literal("Cycle through PNGs in the skins folder")))
				.build();
		browse.active = draft.skinType == MorphEntry.SkinType.FILE;
		addRenderableWidget(browse);

		int uw = (optW - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Skins folder"), b -> com.mojang.blaze3d.Blaze3D.openPath(MorphConfig.skinsDir()))
				.pos(optX, rowC).size(uw, 20).build());
		Button me = Button.builder(Component.literal("Edit me"), b -> {
					if (minecraft != null && minecraft.player != null) minecraft.gui.setScreen(new MorphEditorScreen(parent, minecraft.player));
				})
				.pos(optX + uw + 4, rowC).size(uw, 20).build();
		me.active = minecraft != null && minecraft.player != null && minecraft.player != target;
		addRenderableWidget(me);
		addRenderableWidget(Button.builder(Component.literal("All saved…"), b -> {
					if (minecraft != null) minecraft.gui.setScreen(new MorphListScreen(this));
				})
				.pos(optX + 2 * (uw + 4), rowC).size(optW - 2 * (uw + 4), 20).build());
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

	// ---------------------------------------------------------------- render

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
			int size = (int) Math.max(4, Math.min(120, (previewY1 - previewY0) * 0.6F / Math.max(0.3F, h)));
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, previewX0, previewY0, previewX1, previewY1,
					size, 0.0625F, mouseX, mouseY, target);
		} else {
			graphics.centeredText(this.font, "Entity is gone", (previewX0 + previewX1) / 2, (previewY0 + previewY1) / 2, RED);
		}
		String modelLabel = draft.hasModel() ? MorphManager.displayName(draft.model) : "Original model";
		graphics.centeredText(this.font, modelLabel, (previewX0 + previewX1) / 2, previewY1 + 6, WHITE);
		graphics.text(this.font, Math.max(0, filtered.size() - 1) + " mobs", listX, previewY1 + 6, GREY);

		// look title
		String lookTitle = "Look — " + MorphManager.displayName(effectiveModel()) + (lookPages > 1 ? "  (" + (lookPage + 1) + "/" + lookPages + ")" : "");
		graphics.text(this.font, lookTitle, optX, lookTitleY, GOLD);

		// status
		String status;
		int color;
		if (draft.hasModel() && MorphManager.proxyFor(target) == null && !(MorphManager.PLAYER_MODEL.equals(draft.model) && target instanceof Player)
				&& !draft.model.equals(MorphManager.idOf(target.getType()))) {
			status = "This model can't be created on the client.";
			color = RED;
		} else if (draft.skinType == MorphEntry.SkinType.DEFAULT) {
			status = "Texture: the model's own (variants apply).";
			color = GREY;
		} else if (!draft.hasSkin()) {
			status = "Enter a value and press Load.";
			color = GREY;
		} else {
			switch (SkinCache.status(draft)) {
				case LOADING -> { status = "Loading skin…"; color = GREY; }
				case FAILED -> { status = "Skin error: " + SkinCache.error(draft); color = RED; }
				default -> { status = "Skin ready (overrides variant texture)."; color = GREEN; }
			}
			SkinCache.resolve(draft);
		}
		graphics.text(this.font, this.font.plainSubstrByWidth(status, optW), optX, statusY, color);
	}
}
