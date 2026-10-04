package dev.entitymorph.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import dev.entitymorph.config.MorphConfig;
import dev.entitymorph.config.MorphEntry;
import dev.entitymorph.render.MorphManager;

/** Every morph saved for the current world/server, with edit (if the entity is loaded) and delete. */
public class MorphListScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;

	private final @Nullable Screen parent;
	private int page;
	private int perPage = 1;
	private final List<Row> rows = new ArrayList<>();

	private record Row(UUID uuid, MorphEntry entry, String name, @Nullable LivingEntity loaded, int y) {
	}

	public MorphListScreen(@Nullable Screen parent) {
		super(Component.literal("Saved morphs"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		List<Map.Entry<UUID, MorphEntry>> all = new ArrayList<>(MorphConfig.savedEntries().entrySet());
		all.sort(Map.Entry.comparingByKey());

		int top = 40;
		perPage = Math.max(1, (this.height - top - 40) / 24);
		int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));

		int panelW = Math.min(420, this.width - 20);
		int x0 = (this.width - panelW) / 2;
		for (int i = 0; i < perPage; i++) {
			int idx = page * perPage + i;
			if (idx >= all.size()) break;
			UUID uuid = all.get(idx).getKey();
			MorphEntry entry = all.get(idx).getValue();
			LivingEntity loaded = find(uuid);
			int y = top + i * 24;
			rows.add(new Row(uuid, entry, nameOf(uuid, loaded), loaded, y));

			Button edit = Button.builder(Component.literal("Edit"), b -> {
				if (minecraft != null && loaded != null) minecraft.gui.setScreen(new MorphEditorScreen(this, loaded));
			}).pos(x0 + panelW - 104, y).size(50, 20).build();
			edit.active = loaded != null;
			addRenderableWidget(edit);
			addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
				MorphConfig.remove(uuid);
				rebuildWidgets();
			}).pos(x0 + panelW - 50, y).size(50, 20).build());
		}

		Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
				.pos(x0, this.height - 30).size(20, 20).build();
		prev.active = page > 0;
		addRenderableWidget(prev);
		Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
				.pos(x0 + panelW - 20, this.height - 30).size(20, 20).build();
		next.active = page < pages - 1;
		addRenderableWidget(next);
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.pos(this.width / 2 - 50, this.height - 30).size(100, 20).build());
	}

	private @Nullable LivingEntity find(UUID uuid) {
		if (minecraft == null || minecraft.level == null) return null;
		if (minecraft.player != null && minecraft.player.getUUID().equals(uuid)) return minecraft.player;
		for (Entity e : minecraft.level.entitiesForRendering()) {
			if (e instanceof LivingEntity living && uuid.equals(e.getUUID())) return living;
		}
		return null;
	}

	private String nameOf(UUID uuid, @Nullable LivingEntity loaded) {
		if (loaded != null) return loaded.getDisplayName().getString();
		if (minecraft != null && minecraft.getConnection() != null) {
			PlayerInfo info = minecraft.getConnection().getPlayerInfo(uuid);
			if (info != null) return info.getProfile().name();
		}
		return uuid.toString().substring(0, 8) + "… (not loaded)";
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, "Saved morphs — " + MorphConfig.scopeLabel(), this.width / 2, 12, WHITE);
		if (rows.isEmpty()) {
			graphics.centeredText(this.font, "Nothing saved here yet. Look at an entity and press the Edit Morph key.", this.width / 2, this.height / 2, GREY);
			return;
		}
		int panelW = Math.min(420, this.width - 20);
		int x0 = (this.width - panelW) / 2;
		for (Row r : rows) {
			String model = r.entry.hasModel() ? MorphManager.displayName(r.entry.model) : "own model";
			String skin = r.entry.hasSkin() ? r.entry.skinType.label + ": " + r.entry.skinValue : "default skin";
			graphics.text(this.font, r.name, x0, r.y + 1, WHITE);
			graphics.text(this.font, model + " · " + skin + (r.entry.baby ? " · baby" : ""), x0, r.y + 11, GREY);
		}
	}
}
