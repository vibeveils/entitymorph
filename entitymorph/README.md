# Entity Morph (Fabric, Minecraft 26.3, client-only)

Render any player or entity — including yourself — as a different entity model, with a custom skin.
Everything is local: other players see nothing, and no server mod is needed.

## Using it

- **M** (rebindable, *Entity Morph* category) opens the editor for the living entity under your crosshair, or for yourself if you're not looking at one.
- A second, unbound key opens **All saved** — every morph saved for the current world/server, with Edit (if the entity is loaded) and Delete.

In the editor:

| Control | What it does |
|---|---|
| Search + scrolling mob list | Pick any client-creatable living entity, **Player**, or **Original model** (mouse wheel / scrollbar) |
| Look | Everything that mob supports: variants (fox type, wolf variant/collar/sound, cat, axolotl, parrot, frog, rabbit, horse, llama, sheep colour, tropical fish pattern/colours, villager type, …) and states (tamed, sitting, angry, aggressive, sheared, sleeping, begging, chest, charged, …). Also works on the entity's own model, e.g. turn a red fox into a snow fox |
| Skin | Default · Player name (downloads that account's skin) · Skin file (PNG in the skins folder) · Texture id (e.g. `minecraft:textures/entity/zombie/husk.png`) |
| Load / Next file | Apply or reload the typed value; cycle through PNGs in the skins folder |
| Arms | Auto / Wide / Slim for player models (also first person) |
| Save / Reset / Cancel | Edits preview live in the world and in the doll; only Save writes them |

## Where things are saved

- `config/entitymorph/worlds/<world folder>.json` (singleplayer)
- `config/entitymorph/servers/<address>.json` (multiplayer)
- `config/entitymorph/skins/` — drop skin PNGs here (64×64 or legacy 64×32; a name containing `slim`/`alex` defaults to slim arms)

Entries are keyed by entity UUID, so a named pet or a player keeps its morph across sessions on that world/server.

## What changes where

- **World / F5:** the entity is swapped for a client-only proxy of the chosen type at render-state extraction; position, rotation, head/body yaw, walk animation, swing, hurt/death, pose, sneaking, sprinting, swimming, fire, glowing, invisibility, held items and armor are copied every frame. Name tags carry over.
- **Inventory & creative inventory:** the paper-doll uses the morph.
- **First person:** with a player model, your arm uses the chosen skin and arm width. With a non-player model, the bare arm is hidden (held items still render).
- **Texture swaps on non-player models** apply to the main model texture; layers with their own textures (sheep wool, armor, glow eyes) keep theirs.

## Building

```
./gradlew build
```

Needs JDK 25. Output: `build/libs/entitymorph-1.0.0.jar`. Requires Fabric Loader ≥ 0.19.5 and Fabric API for 26.3.

## Hooks (for maintenance)

| Mixin | Target |
|---|---|
| `EntityRenderDispatcherMixin` | `extractEntity(Entity, float)` — swaps in the proxy |
| `InventoryScreenMixin` | `extractEntityInInventoryFollowsMouse` — swaps in the proxy |
| `AvatarRendererMixin` | `extractRenderState` (skin), `renderRightHand`/`renderLeftHand` (first-person) |
| `EntityRendererMixin` | `extractRenderState` — tags the state with the texture override |
| `LivingEntityRendererMixin` | `getRenderType` — applies the texture override (`require = 0`) |
