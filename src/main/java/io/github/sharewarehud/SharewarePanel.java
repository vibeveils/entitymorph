package io.github.sharewarehud;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import io.github.sharewarehud.SharewareConfig.ExperienceStyle;

/**
 * The 3D Shareware v1.34 status bar, laid out like a 90s FPS:
 *
 * <pre>
 * +----------+-------+---------+-----------+
 * | [heart]10|       | beef 12 | [1][2][3] |
 * | [armor]20| (you) | xp/air/ | [4][5][6] |
 * | [horse]15|       | jump    | [7][8][9] |
 * |          |       |  [OH]   |           |
 * +----------+-------+---------+-----------+
 * </pre>
 *
 * Everything is laid out in "panel space" and scaled by {@code hudScale} around the bottom of
 * the screen. The portrait is the exception: the entity renderer ignores the pose stack, so its
 * box is converted to real screen coordinates by hand.
 */
public final class SharewarePanel implements HudElement {
	// ---- layout (panel pixels) ----
	private static final int BORDER = 2;
	private static final int GAP = 2;
	private static final int SLOT = 20;
	private static final int VITALS_W = 66;
	private static final int FACE_W = 46;
	private static final int STATUS_W = 40;
	private static final int ARMS_W = SLOT * 3 + 2;
	private static final int SECTION_H = SLOT * 3 + 2;
	private static final int ROW_H = 20;
	private static final int ICON = 18;

	public static final int HEIGHT = SECTION_H + BORDER * 2;
	public static final int WIDTH = BORDER * 2 + VITALS_W + FACE_W + STATUS_W + ARMS_W + GAP * 3;

	// ---- colours (RGB; alpha comes from the opacity settings) ----
	private static final int PANEL = 0x5B5B5B;
	private static final int PANEL_LIGHT = 0x8C8C8C;
	private static final int PANEL_DARK = 0x2C2C2C;
	private static final int WELL = 0x3A3A3A;
	private static final int WELL_DARK = 0x1E1E1E;
	private static final int WELL_LIGHT = 0x767676;
	private static final int SLOT_BG = 0x8B8B8B;
	private static final int SLOT_DARK = 0x373737;
	private static final int SLOT_LIGHT = 0xFFFFFF;
	private static final int DIGITS = 0xD42A1C;
	private static final int DIGITS_ABSORB = 0xF2C12E;
	private static final int DIGITS_ARMOR = 0xD8D8D8;
	private static final int DIGITS_MOUNT = 0xE08A3A;
	private static final int XP_GREEN = 0x80FF20;
	private static final int AIR_BLUE = 0x5CB8FF;
	private static final int JUMP_ORANGE = 0xF0A030;
	private static final int METER_BG = 0x000000;

	// ---- vanilla GUI sprites ----
	private static final Identifier HOTBAR_SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_ABSORBING = Identifier.withDefaultNamespace("hud/heart/absorbing_full");
	private static final Identifier VEHICLE_CONTAINER = Identifier.withDefaultNamespace("hud/heart/vehicle_container");
	private static final Identifier VEHICLE_FULL = Identifier.withDefaultNamespace("hud/heart/vehicle_full");
	private static final Identifier ARMOR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
	private static final Identifier ARMOR_EMPTY = Identifier.withDefaultNamespace("hud/armor_empty");

	// Created lazily: ItemStacks can't be built during mod init, before item components are bound.
	private static ItemStack bone;
	private static ItemStack beef;
	private static ItemStack compass;

	private final SharewareConfig config;

	// per-frame opacity, 0..255
	private int fgAlpha;
	private int bgAlpha;

	// head-turning state for the portrait
	private float lookCurrent;
	private float lookTarget;
	private long nextLookChange;
	private long lastFrame;

	public SharewarePanel(SharewareConfig config) {
		this.config = config;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) return;

		if (bone == null) {
			bone = new ItemStack(Items.BONE);
			beef = new ItemStack(Items.BEEF);
			compass = new ItemStack(Items.COMPASS);
		}

		Font font = mc.font;
		boolean survival = mc.gameMode.canHurtPlayer();
		float scale = config.hudScale;
		fgAlpha = Math.round(config.hudOpacity * 255.0F);
		bgAlpha = Math.round(config.backgroundOpacity * 255.0F);

		// panel space: the screen as seen after scaling
		int x = Math.round((graphics.guiWidth() / scale - WIDTH) / 2.0F);
		int y = Math.round(graphics.guiHeight() / scale - HEIGHT);

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);

		bevel(graphics, x, y, WIDTH, HEIGHT, PANEL, PANEL_LIGHT, PANEL_DARK);

		int sx = x + BORDER;
		int sy = y + BORDER;

		vitals(graphics, font, player, survival, sx, sy);
		sx += VITALS_W + GAP;

		well(graphics, sx, sy, FACE_W, SECTION_H);
		if (fgAlpha > 0) {
			portrait(graphics, player, scale, sx + 1, sy + 1, sx + FACE_W - 1, sy + SECTION_H - 1);
		}
		sx += FACE_W + GAP;

		status(graphics, font, player, survival, sx, sy);
		sx += STATUS_W + GAP;

		hotbar(graphics, font, player, sx, sy);

		graphics.pose().popMatrix();
	}

	// ------------------------------------------------------------------ sections

	/** Hearts, armour points and mount hearts, one row each. */
	private void vitals(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, boolean survival, int sx, int sy) {
		well(graphics, sx, sy, VITALS_W, SECTION_H);

		// row 1: player hearts (absorption included, shown in gold)
		int row = sy + 2;
		float absorb = player.getAbsorptionAmount();
		sprite(graphics, HEART_CONTAINER, sx + 3, row, ICON, ICON);
		sprite(graphics, absorb > 0 ? HEART_ABSORBING : HEART_FULL, sx + 3, row, ICON, ICON);
		if (survival) {
			int halves = Mth.ceil(player.getHealth()) + Mth.ceil(absorb);
			rowNumber(graphics, font, hearts(halves), sx, row, absorb > 0 ? DIGITS_ABSORB : DIGITS);
		}

		// row 2: armour points
		row += ROW_H;
		int armor = player.getArmorValue();
		sprite(graphics, armor > 0 ? ARMOR_FULL : ARMOR_EMPTY, sx + 3, row, ICON, ICON);
		if (survival) {
			rowNumber(graphics, font, String.valueOf(armor), sx, row, DIGITS_ARMOR);
		}

		// row 3: the ridden mob's hearts (empty when not riding anything with health)
		row += ROW_H;
		Entity vehicle = player.getVehicle();
		if (vehicle instanceof LivingEntity mount && mount.isAlive()) {
			sprite(graphics, VEHICLE_CONTAINER, sx + 3, row, ICON, ICON);
			sprite(graphics, VEHICLE_FULL, sx + 3, row, ICON, ICON);
			rowNumber(graphics, font, hearts(Mth.ceil(mount.getHealth())), sx, row, DIGITS_MOUNT);
		}
	}

	/** Food (or a compass in Creative), XP / air / jump meters, and the offhand slot. */
	private void status(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, boolean survival, int sx, int sy) {
		well(graphics, sx, sy, STATUS_W, SECTION_H);

		int ix = sx + 3;
		int iy = sy + 3;
		if (survival) {
			food(graphics, player, ix, iy);
		} else if (fgAlpha > 0) {
			// with the player as the holder, the compass needle points the right way
			graphics.item(player, compass, ix, iy, 0);
		}

		boolean showXp = survival && config.experienceStyle == ExperienceStyle.PANEL;

		// XP level number to the right of the food icon
		if (showXp && player.experienceLevel > 0) {
			String level = String.valueOf(player.experienceLevel);
			int areaX = ix + 17;
			int areaW = sx + STATUS_W - 2 - areaX;
			float s = Math.min(1.0F, areaW / (float) font.width(level));
			graphics.pose().pushMatrix();
			graphics.pose().translate(areaX + (areaW - font.width(level) * s) / 2.0F, iy + 4.0F);
			graphics.pose().scale(s, s);
			outlined(graphics, font, level, 0, 0, XP_GREEN);
			graphics.pose().popMatrix();
		}

		// meters: fixed rows so they don't jump around as they appear
		int mx = sx + 3;
		int mw = STATUS_W - 6;
		int my = sy + 22;

		if (showXp) {
			meter(graphics, mx, my, mw, player.experienceProgress, XP_GREEN);
		}

		int maxAir = player.getMaxAirSupply();
		int air = Math.clamp(player.getAirSupply(), 0, maxAir);
		if (survival && (player.isEyeInFluid(FluidTags.WATER) || air < maxAir)) {
			meter(graphics, mx, my + 6, mw, air / (float) Math.max(1, maxAir), AIR_BLUE);
		}

		if (player.getVehicle() instanceof PlayerRideableJumping) {
			meter(graphics, mx, my + 12, mw, player.getJumpRidingScale(), JUMP_ORANGE);
		}

		// offhand
		int offX = sx + (STATUS_W - SLOT) / 2;
		int offY = sy + SECTION_H - SLOT - 1;
		slot(graphics, offX, offY);
		drawItem(graphics, font, player, player.getOffhandItem(), offX + 2, offY + 2, 10);
	}

	/** The 3x3 hotbar, slot 1 top-left .. slot 9 bottom-right. */
	private void hotbar(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, int sx, int sy) {
		well(graphics, sx, sy, ARMS_W, SECTION_H);
		int gx = sx + 1;
		int gy = sy + 1;
		for (int i = 0; i < 9; i++) {
			slot(graphics, gx + (i % 3) * SLOT, gy + (i / 3) * SLOT);
		}
		int selected = player.getInventory().getSelectedSlot();
		if (selected >= 0 && selected < 9) {
			sprite(graphics, HOTBAR_SELECTION, gx + (selected % 3) * SLOT - 2, gy + (selected / 3) * SLOT - 2, 24, 23);
		}
		for (int i = 0; i < 9; i++) {
			drawItem(graphics, font, player, player.getInventory().getItem(i),
					gx + (i % 3) * SLOT + 2, gy + (i / 3) * SLOT + 2, i + 1);
		}
	}

	private void portrait(GuiGraphicsExtractor graphics, LocalPlayer player, float scale, int x1, int y1, int x2, int y2) {
		long now = Util.getMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.25F, (now - lastFrame) / 1000.0F);
		lastFrame = now;

		if (config.portraitLooksAround) {
			if (now >= nextLookChange) {
				ThreadLocalRandom rng = ThreadLocalRandom.current();
				// mostly look ahead, sometimes glance to one side, like the shareware face
				lookTarget = rng.nextFloat() < 0.45F ? 0.0F : (rng.nextBoolean() ? -1.0F : 1.0F);
				nextLookChange = now + 900 + rng.nextInt(1800);
			}
			lookCurrent += (lookTarget - lookCurrent) * (1.0F - (float) Math.exp(-dt * 10.0F));
		} else {
			lookCurrent = 0.0F;
		}

		// The entity renderer works in real screen coordinates and ignores the pose stack.
		int sx1 = Math.round(x1 * scale);
		int sy1 = Math.round(y1 * scale);
		int sx2 = Math.round(x2 * scale);
		int sy2 = Math.round(y2 * scale);
		int modelScale = Math.max(1, Math.round(config.portraitScale * scale));

		float cx = (sx1 + sx2) / 2.0F;
		float cy = (sy1 + sy2) / 2.0F;
		// the renderer turns the model to "follow" this point; feed it a fake mouse position
		float mouseX = cx + lookCurrent * 45.0F * scale;
		float mouseY = cy - 6.0F * scale;

		InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, sx1, sy1, sx2, sy2,
				modelScale, config.portraitYOffset, mouseX, mouseY, player);
	}

	/** Raw beef on a bone; the beef drains from the top as you get hungry. */
	private void food(GuiGraphicsExtractor graphics, LocalPlayer player, int ix, int iy) {
		if (fgAlpha == 0) return;
		graphics.item(bone, ix, iy);

		int food = Mth.clamp(player.getFoodData().getFoodLevel(), 0, 20);
		if (food <= 0) return;

		int visible = Mth.ceil(16 * food / 20.0F);
		graphics.enableScissor(ix, iy + 16 - visible, ix + 16, iy + 16);
		graphics.item(beef, ix, iy);
		graphics.disableScissor();
	}

	// ------------------------------------------------------------------ helpers

	/** Number to the right of a vitals icon, as big as fits (up to 2x). */
	private void rowNumber(GuiGraphicsExtractor graphics, Font font, String text, int sx, int rowY, int rgb) {
		int areaX = sx + 3 + ICON + 3;
		int areaW = sx + VITALS_W - 3 - areaX;
		int textW = font.width(text);
		float s = Math.min(2.0F, areaW / (float) textW);

		graphics.pose().pushMatrix();
		graphics.pose().translate(areaX, rowY + (ICON - 7.0F * s) / 2.0F);
		graphics.pose().scale(s, s);
		text(graphics, font, text, 0, 0, rgb, true);
		graphics.pose().popMatrix();
	}

	/** Thin progress bar: black trough, coloured fill. */
	private void meter(GuiGraphicsExtractor graphics, int x, int y, int w, float progress, int rgb) {
		if (fgAlpha == 0) return;
		int filled = Math.round((w - 2) * Math.clamp(progress, 0.0F, 1.0F));
		graphics.fill(x, y, x + w, y + 4, fg(METER_BG));
		if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + 3, fg(rgb));
	}

	/** Half-heart count as hearts: 19 -> "9.5", 20 -> "10". */
	private static String hearts(int halves) {
		return halves % 2 == 0 ? String.valueOf(halves / 2) : (halves / 2) + ".5";
	}

	/** Items can't be faded by the GUI renderer, so they stay opaque unless the HUD is fully hidden. */
	private void drawItem(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, ItemStack stack, int ix, int iy, int seed) {
		if (stack.isEmpty() || fgAlpha == 0) return;
		graphics.item(player, stack, ix, iy, seed);
		if (config.showItemDecorations) graphics.itemDecorations(font, stack, ix, iy);
	}

	private void outlined(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int rgb) {
		text(graphics, font, text, x + 1, y, 0x000000, false);
		text(graphics, font, text, x - 1, y, 0x000000, false);
		text(graphics, font, text, x, y + 1, 0x000000, false);
		text(graphics, font, text, x, y - 1, 0x000000, false);
		text(graphics, font, text, x, y, rgb, false);
	}

	private void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int rgb, boolean shadow) {
		if (fgAlpha == 0) return;
		graphics.text(font, text, x, y, fg(rgb), shadow);
	}

	private void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int w, int h) {
		if (fgAlpha == 0) return;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, fg(0xFFFFFF));
	}

	private int fg(int rgb) {
		return (fgAlpha << 24) | (rgb & 0xFFFFFF);
	}

	private int bg(int rgb) {
		return (bgAlpha << 24) | (rgb & 0xFFFFFF);
	}

	/** Raised panel: fill plus a light top/left edge and a dark bottom/right edge. */
	private void bevel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int light, int dark) {
		if (bgAlpha == 0) return;
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg(fill));
		g.fill(x, y, x + w, y + 1, bg(light));
		g.fill(x, y + 1, x + 1, y + h, bg(light));
		g.fill(x + 1, y + h - 1, x + w, y + h, bg(dark));
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, bg(dark));
	}

	/** Recessed section. */
	private void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		bevel(g, x, y, w, h, WELL, WELL_DARK, WELL_LIGHT);
	}

	/** Inventory-style 18x18 slot centred in a 20x20 cell. */
	private void slot(GuiGraphicsExtractor g, int x, int y) {
		bevel(g, x + 1, y + 1, 18, 18, SLOT_BG, SLOT_DARK, SLOT_LIGHT);
	}
}
