# Shareware HUD (Fabric, Minecraft 26.3)

Brings back the HUD from the 2019 April Fools' version **Java Edition 3D Shareware v1.34**
("MineCraft 3D: Memory Block Edition").

```
+------------+-------+---------+-----------+
| [heart] 10 |       | beef 12 | [1][2][3] |
| [armor] 20 | (you) | ======= | [4][5][6] |
| [horse] 15 |       |  [OH]   | [7][8][9] |
+------------+-------+---------+-----------+
```

- **3x3 hotbar** — slot 1 top-left through slot 9 bottom-right; keys 1–9 and scrolling work as normal.
- **Player portrait** — your live player model (skin, armour, held item, hurt flash), head glancing left and right at random.
- **Vitals** — one block with your hearts, armour points, and the hearts of whatever you're riding, each as a number next to its icon. Absorption turns the heart number gold.
- **Status column** — raw beef on a bone that drains as you get hungry (a compass in Creative), your XP level, and three thin meters: XP (green), air (blue, only underwater), horse jump charge (orange, only when riding something that jumps). Offhand slot underneath.
- Held-item name and action-bar messages move up above the panel.
- Can be switched off entirely from the settings to get the vanilla HUD back.

## Requirements
Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25. Client-side only.

## Building
JDK 25 required.

```
./gradlew build
```
The jar is in `build/libs/shareware-hud-1.2.0.jar`. Put it in `.minecraft/mods` alongside Fabric API.

No local JDK? Push this folder to a GitHub repo — the included workflow builds the jar and
uploads it as an artifact on the Actions tab.

To test in a dev client: `./gradlew runClient`.

## Settings
With Mod Menu installed: **Mods → Shareware HUD → Configure**. Changes apply straight away and
save when you press Done. Without Mod Menu, edit `config/sharewarehud.json` and restart.

| Setting | Key | Default | What it does |
|---|---|---|---|
| Shareware HUD on/off | `enabled` | `true` | Off restores the normal vanilla HUD. |
| HUD size | `hudScale` | `1.0` | Panel size relative to your GUI scale. `1.0` = same as GUI scale; slide down to 25% to shrink it. |
| HUD opacity | `hudOpacity` | `1.0` | Fades numbers, icons, meters and the selection box. Items and the portrait can't be faded by the game's GUI renderer, so they stay solid (and hide at 0%). |
| Background | `backgroundOpacity` | `1.0` | Fades the grey frame, section boxes and slot backgrounds. |
| XP | `experienceStyle` | `PANEL` | `PANEL`: level + meter in the panel. `VANILLA`: normal XP bar above the panel (also brings back the locator bar). `HIDDEN`: no XP, like the original. |
| Portrait zoom | `portraitScale` | `42` | Size of the player in the portrait box. |
| Portrait framing | `portraitYOffset` | `0.55` | Raise to show more head, lower to show more body. |
| Head turning | `portraitLooksAround` | `true` | Random head glances. |
| Item counts | `showItemDecorations` | `true` | Stack counts and durability bars on hotbar items. |

## How it works
No mixins. It uses Fabric API's `HudElementRegistry` to replace the vanilla `HOTBAR` element with
the panel, blank out the vanilla health/armour/food/air/mount bars, and translate the elements that sit
above the hotbar.
