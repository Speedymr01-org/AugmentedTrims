# AugmentedTrims

Armour trims that actually *do* something. Each trim material grants a tiered set of
powers, and the more armour pieces share that material, the stronger the effect — one
piece is a hint of a buff, a full same-material set is a real build.

```
1 piece  -> tier 1  (subtle)
2 pieces -> tier 2
3 pieces -> tier 3
4 pieces -> tier 4  (strongest)
```

Mixing materials is supported: each material's piece count is evaluated independently, so
a copper helmet + copper boots (`copper x2` → tier 2) and a netherite chestplate
(`netherite x1` → tier 1) both apply their own powers.

## Requirements

- Paper **26.1.2** or newer (single jar covers the whole 26.x line)
- Java 21+

## Install

1. Drop `AugmentedTrims-<version>.jar` into `plugins/`.
2. Start the server — `config.yml` and `trims.yml` are generated in `plugins/AugmentedTrims/`.
3. Edit `trims.yml` to taste, then `/trims reload`.

## Ability Stars

Trim powers are passive. Active abilities come from **Ability Stars**: a nether star attuned
to a trim material.

**Craft a formless star** (3×3, checkerboard of ghast tears and glass blocks):

```
T G T      T = ghast tear (5)
G T G      G = glass block (4)
T G T
```

**Attune it** at a smithing table with a vanilla netherite upgrade template, the star, and
the material's item (ingot / shard / clump). The template is consumed. Attuning an already
attuned star re-attunes it. A plain nether star cannot be attuned — it must be crafted.

| Ability | Material | What it does |
|---|---|---|
| Arrow Torrent | Iron | A stream of arrows along your crosshair, blocked by terrain (2–4 arrows/s for 3–5 s) |
| Refract | Quartz | Incoming projectiles are deflected back at the shooter along a solved arc; tier 4 homes |
| Thunderstrike | Copper | Lightning on the block you look at, no fire, 15 armour-ignoring damage in a 3 block radius |
| Cobweb Shot | Resin | Throws a single cobweb that lands as a real block; no damage |

Attuned stars read `Attuned star: <Material>` (aqua) and glint. Right-click with one in your
main hand to cast — a green `Ability activated.` or a red `Ability on cooldown.`

- Cooldowns are tracked in memory, per material, and mirror onto the vanilla item cooldown
  display. They survive armour swaps, dropping the star, re-attuning and relogging; a server
  restart clears them.
- Without armour of that material the star shows a permanent cooldown until you wear some.
- Dying or logging out cancels an active ability; the cooldown keeps running.
- Stars cannot be used to craft beacons.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/trims info` | `augmentedtrims.use` (default: everyone) | Show your worn trims and their tiers |
| `/trims info <player>` | `augmentedtrims.admin` | Inspect another player |
| `/trims reload` | `augmentedtrims.admin` | Reload `config.yml` + `trims.yml` |
| `/trims give <formless\|material> [player]` | `augmentedtrims.admin` | Give a formless or attuned star |
| `/trims cooldown [player]` | `augmentedtrims.admin` | Clear ability cooldowns |

Aliases: `/trim`, `/augtrims`

## Configuration

`config.yml` holds refresh/visual settings plus ability tuning under `abilities:`:

```yaml
settings:
  refresh-interval-ticks: 20   # how often armour is re-evaluated (20 = 1s)
  duration-buffer-ticks: 40    # prevents effect flicker between refreshes
  ambient: true
  particles: false
  icon: true
  remove-on-unequip: true      # strip the effect the moment the piece comes off
  debug: false
```

`trims.yml` holds the power table. Every entry is a vanilla trim material id, tiers are
keyed by piece count, and effects are vanilla potion effect ids:

```yaml
materials:
  copper:
    tiers:
      "1":
        effects:
          - type: speed
            amplifier: 0
      "4":
        effects:
          - type: speed
            amplifier: 1
          - type: jump_boost
            amplifier: 0
```

- Missing tiers fall back to the **highest configured tier at or below** the piece count,
  so authoring only `1` and `4` is enough.
- `amplifier: 0` is level I. `duration-ticks` is optional (defaults to the refresh interval).
- Unknown materials/effects are logged and skipped, never fatal.

### Why new trim materials need no update

Materials are resolved through Paper's registry (`RegistryKey.TRIM_MATERIAL`) by
`NamespacedKey`, never as compile-time constants. When a Minecraft drop adds a trim
material, an operator adds a `trims.yml` entry — no plugin rebuild, no new release.

The same reasoning drives the version policy: `api-version: '26.1.2'` is a **minimum**
marker, so this one jar loads on 26.1.2, 26.2, 26.3, and the compiled-against version is
pinned to the oldest supported (`26.1.2.build.74-stable`) rather than a version *range*
that would silently resolve to the newest API.

## Building

```powershell
./build.ps1     # sets JAVA_HOME to the bundled JDK 25 and runs mvn clean package
```

Releases are cut by pushing a tag; CI builds the jar, creates the GitHub release, and
(backend-version bumps only) publishes to Modrinth:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

## Notes / limitations

- Trim-granted effects are tracked per player. With `remove-on-unequip: true` the plugin
  removes the effect type when it stops granting it, which will also clear that effect if
  the player had it from another source. Set it to `false` if that matters to you.
- Only the four armour slots are scanned (no elytra, no held items).

## License

MIT
