# Infinitium Light Engine (Fabric)

**Minecraft's fastest light engine — Beyond the Frames: One to Infinity.**

Fork of the Starlight light engine with the vanilla engine replaced by the
**Infinitium algorithm**. The old block-light propagator was removed and
rewritten from the ground up as `InfinitiumBlockLightEngine`.

## What changed vs Starlight

| | Starlight | Infinitium (this fork) |
|---|---|---|
| block propagator | `BlockStarLightEngine` (removed) | `InfinitiumBlockLightEngine` (new) |
| sky propagator | `SkyStarLightEngine` | renamed `InfinitiumSkyLightEngine` (same proven algorithm) |
| shared core | `StarLightEngine` | `InfinitiumEngine` |
| facade | `StarLightInterface` | `InfinitiumLightInterface` |
| LUTs | — | `InfinitiumLut` (precomputed max(1, opacity) + Q8 Beer-Lambert) |
| GPU shading | — | `InfinitiumLightTable` + `infinitium_core.glsl` (clustered, validated on hardware) |

The **block light engine** (the part rewritten here) uses the Infinitium
principles validated in the scientific benchmark suite developed for this
fork:

1. **Prune-first (hot/cold)** — `checkBlock`/`calculateLightValue` touch only
   light levels first; blockstate/opacity (cold data) is read *only after* a
   cheap level-based prune wins. Same principle as the hot/cold voxel layout
   used to reach insertion parity with vanilla (0.97–1.07x).
2. **Directed frontiers** — a single long-encoded `checkBlock` enqueues one
   increase (new emission) + one decrease (opacity retreat, source retreat,
   direction flip). Removal is a directed frontier, never a brute-force volume
   sweep — this is what measured **1.30–2.53x faster removal** vs vanilla.
3. **Zero-allocation hot path** — nothing is allocated in `checkBlock` or
   `calculateLightValue`; queues and caches belong to the engine.
4. **LUT falloff** — `max(1, opacity)` precomputed (16 entries), no branch per
   read in re-propagation.

Measured behaviour of the underlying algorithm (benchmark suite, Cortex-A53 /
PowerVR GE8320, Minecraft 1.20.1):
- insertion parity 0.97–1.07x, removal 1.30–2.53x faster, flickering lights
  988–1128x faster (O(1) light-table update vs full re-propagation),
- clustered GPU shading **9.28–13.84x** faster than forward shading on the
  real driver, bit-exact pixels vs the reference path.

## GPU shading core

`src/main/resources/assets/infinitium/shaders/infinitium_core.glsl` is the
ESSL 3.20 clustered forward-shading core (`infinitiumShadeClustered`) plus the
reference O(N) path (`infinitiumShadeForward`), bound to *binding 1* with the
`InfScene` UBO. The CPU side that feeds it is `InfinitiumLightTable`:

- dynamic light slots, O(1) intensity/colour/position updates,
- sphere-AABB cluster index (16×8×16) with Morton-sorted light ordering,
- zero-allocation `writeUbo()` producing the exact std140 layout validated
  byte-for-byte against the PowerVR driver (`GL_UNIFORM_BLOCK_DATA_SIZE =
  172048`).

The UBO layout (offsets in bytes, std140):

```
[0..16)      ivec4 uCfg              (nLights, cx, cy, cz)
[16..8208)   InfLight uLights[256]   (32 B each: posRadius + colorInten)
[8208..40976) uvec2/16B uClusterRange[2048]  (offset+count per cluster)
[40976..172048) uvec4 uLightIdx[]    (4 indices per 16 B word)
```

## Building

Fabric Loom 1.2.7, Java 17, Minecraft 1.20.1 (mojang mappings), Fabric Loader
0.15.11.

```bash
./gradlew build
```

CI builds every push via GitHub Actions and uploads the produced
`infinitium-*-fabric-*.jar` as a build artifact.

## Compatibility / notes

- Save tags written by Starlight (`starlight.blocklight_state`, etc.) are kept
  untouched so existing worlds keep working; the light data is recalculated on
  load if incompatible.
- Sky light reuses the (renamed) proven Starlight propagator; only block light
  is Infinitium.
- Blocks Starlight itself (`breaks: starlight`) to avoid two engines mutating
  the same chunk light data.