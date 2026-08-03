# SondPlayTweaks

Mixin patches for Minecraft 1.7.10, aimed at bugs and performance problems in old mods that
nobody ever fixed.

Every patch here starts from reading the actual bytecode of the mod being patched and comparing
it against what vanilla does. If a claim is in this README, it was verified — and where something
was inferred rather than measured, it says so.

```
Minecraft   1.7.10
Requires    UniMixins (or any Mixin 0.8+ provider)
Version     0.1.0
```

---

## Patches

### OreSpawn leaves no longer delete themselves

**Symptom:** trees in OreSpawn dimensions fall apart on their own, many at once, with no player
interaction at all.

**Cause.** OreSpawn's four leaf classes — `BlockAppleLeaves`, `BlockCrystalLeaves`,
`BlockExperienceLeaves`, `BlockScaryLeaves` — all extend `net.minecraft.block.BlockLeaves` but
**override `func_149674_a`** with their own decay implementation. It differs from vanilla in two
ways that matter:

| vanilla `BlockLeaves.updateTick` | OreSpawn `Block*Leaves.func_149674_a` |
|---|---|
| `if ((meta & 8) == 0) return;` | no gate — runs on every random tick |
| `if ((meta & 4) != 0) return;` | no gate |
| radius 4, breadth-first flood fill | radius 2, direct line of sight, Manhattan ≤ 3 |
| a leaf chains to a log **through other leaves** | a leaf must see a log directly |

Vanilla's scan is *larger* (up to 9×9×9) but only runs when the "needs check" bit is set, which
happens when a neighbouring block breaks. OreSpawn's is smaller but runs **unconditionally**.

The flood fill is the part that actually breaks things. On this mod's colossal trees, a leaf in
the outer canopy is more than 3 Manhattan steps from any log and has no way to chain through
other leaves. It does not decay because something happened to it — **it decays because it spawned
too far from a trunk.** The entire canopy of every large tree is in that state simultaneously,
which is exactly the reported symptom.

**Fix.** `@Inject` at the head of `removeLeaves`, cancelled.

**Why `removeLeaves` and not `func_149674_a`.** The block's drops and transformations live
*inside* the "found a log" branch of `func_149674_a`:

```
BlockExperienceLeaves   nextInt(65) == 1  ->  drops an XP bottle item
                        nextInt(75) == 1  ->  spawns an EntityExpBottle
BlockScaryLeaves        at a time of day  ->  turns into MyAppleLeaves
BlockAppleLeaves        at a time of day, in DimensionID4  ->  turns into MyScaryLeaves
all four                chance roll       ->  dropBlockAsItem
```

Cancelling the whole method would delete those features while claiming to fix lag. Cancelling
only `removeLeaves` keeps every one of them and simply never deletes the block.

All four classes declare `private void removeLeaves(World, int, int, int)` with an identical
signature, so a single mixin with a `targets` list covers all of them.

**This is a correctness fix, not a performance one — now measured.** The scan still runs; this
patch does not touch it. A 20-minute spark profile under deliberate stress (hundreds of OreSpawn
bosses fighting, then the player running far away to force chunk generation) puts every OreSpawn
block tick at the bottom of the table:

```
updateBlocks total                                     9.70 ms/tick
  extrabiomes.blocks.BlockLeafEbxl.updateTick          2.85 ms/tick
  net.minecraft.block.BlockLeaves.updateTick           0.07 ms/tick
  danger.orespawn.AntBlock.updateTick                  0.00 ms/tick
  danger.orespawn.BlockButterflyPlant.updateTick       0.00 ms/tick
  danger.orespawn.BlockLettuce.updateTick              0.00 ms/tick
  the four OreSpawn leaf classes                       absent from the profile entirely
```

So the earlier suspicion that OreSpawn leaves were a meaningful share of block-tick time was
wrong. They cost essentially nothing. What this patch fixes is trees destroying themselves, which
is a bug regardless of its cost.

---

## Compatibility

**Nothing in this mod touches `net.minecraft.client.*`.** Rendering is Angelica's territory, and
Angelica ships a hardcoded incompatibility list (`optifine`, `fastcraft`,
`optimizationsandtweaks`) that strips offending mods out of the boot entirely.

**Nothing here uses `@Overwrite` on a vanilla class.** A hard overwrite deletes the method body
and destroys the injection points every other mod depends on. This is not hypothetical — in the
pack this mod was built for, `noleafdecay-1.0fork1` overwrites `BlockLeaves.func_149674_a` at
priority 1000, which kills BugTorch's `@At("STORE")` injection at priority 100 and takes the game
down at boot with a `MixinApplyError` → `NoClassDefFoundError`.

This mod targets **mod classes by name**, never a vanilla superclass. A subclass that overrides
the method escapes a superclass overwrite anyway — that is precisely why the leaf-decay mods that
patch `BlockLeaves` never covered OreSpawn.

Known to run alongside: UniMixins, lwjgl3ify, RetroFuturaBootstrap, Angelica, ArchaicFix,
Hodgepodge, BugTorch, FalsePatternLib, GTNHLib.

---

## Building

```bash
bash build.sh
```

No Gradle. ForgeGradle 1.2 is dead upstream (its asset endpoints have returned 403 since 2022),
and RetroFuturaGradle would pull down all of Minecraft and MCP for what is currently a handful of
classes. It is not needed here: these mixins target **mod classes**, which are not obfuscated,
and **SRG method names**, which already appear literally in the compiled bytecode. There is
nothing to remap, so the refmap is empty and `javac` alone is enough. `-proc:none` disables the
Mixin annotation processor deliberately, since its only job is generating that refmap.

The script checks its classpath before compiling and fails loudly if anything is missing:

| | |
|---|---|
| `forgeSrc-1.7.10-10.13.4.1614-1.7.10.jar` | deobfuscated Minecraft classes |
| `+unimixins-all-1.7.10-0.3.1.jar` | Mixin annotations |
| `Ore-Spawn-Mod-1.7.10.jar` | resolving the mixin targets |

Compile with JDK 8, or with a newer JDK targeting 8. The **runtime** is Java 17+ — only the
emitted bytecode has to be Java 8, because Mixin merges these methods into classes compiled for
Java 6.

---

## Roadmap

Ordered by measured cost. Numbers are ms per tick from a 20-minute spark profile on a
deliberately overloaded world (hundreds of OreSpawn bosses fighting), server thread total
**75.96 ms/tick** against a 50 ms budget.

| Patch | Measured | Status |
|---|---|---|
| OreSpawn leaves destroying themselves | correctness, ~0 ms | **0.1.0** |
| **Path recompute cooldown and failure backoff for OreSpawn entities.** OreSpawn calls `tryMoveToEntityLiving` (53 classes) and `tryMoveToXYZ` (30 classes) directly from `updateAITasks` with no cooldown and no `failedPathFindingPenalty`, so an unreachable target is re-pathed forever at a fixed rate. Vanilla degrades to 19, 34, then 49 ticks between attempts. | `GiantRobot` 7.12, `Hammerhead` 4.04, `Godzilla` 1.82 — **12.98 ms/tick** from three mobs alone, of which **8.57 ms** is `getBlock` inside `func_82565_a` | next |
| **ExtrabiomesXL leaf decay.** `BlockLeafEbxl` overrides `updateTick` and, like OreSpawn's, is not covered by Hodgepodge's BFS decay (which handles vanilla, BiomesOPlenty, Magical, Nether and Witch leaves) or by BugTorch. | **2.85 ms/tick**, 29% of all block-tick time | planned |
| `findSomethingToAttack` — sorts the full entity list before filtering it | `GiantRobot` 0.02, `Hammerhead` 0.05, `Godzilla` 0.03 ms/tick | low priority |
| ~~`chunkLoadOverride` / `dummyChunk` ([GTNH #11425](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/issues/11425))~~ | **dropped** | Pathfinding never reaches disk in this configuration. Under `getPathEntityToEntity`, `getChunkFromChunkCoords` resolves through `ChunkProviderServer.provideChunk` → `ServerThreadLongHashMap.getValueByKey` → a fastutil map hit, 0.3 ms/tick, with no `loadChunk` beneath it. Hodgepodge's `preventLoadingChunksWhenPathfinding` already closes this. |

---

## License

[LGPL-3.0](LICENSE). The LGPL incorporates the GPL by reference, so the full GPL-3.0 text is
included as [`GPL-3.0.txt`](GPL-3.0.txt).

Modpacks may bundle this freely. Forks must stay open.
