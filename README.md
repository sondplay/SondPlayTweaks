# SondPlayTweaks

Mixin patches for Minecraft 1.7.10, aimed at bugs and performance problems in old mods that
nobody ever fixed.

Every patch here starts from reading the actual bytecode of the mod being patched and comparing
it against what vanilla does. If a claim is in this README, it was verified — and where something
was inferred rather than measured, it says so.

```
Minecraft   1.7.10
Requires    UniMixins
Version     0.3.0
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

**This is a correctness fix. Its performance effect is unmeasured.** The scan still runs; this
patch does not touch it.

A 20-minute spark profile shows no OreSpawn leaf tick at all — but that profile was taken in the
**overworld**, where this mod's trees are rare. The dimensions where they grow in bulk, which is
where the symptom was reported, were never profiled. Absence there is not evidence of zero cost
here.

What the same profile does establish is that a *different* mod's leaves are the real block-tick
cost in the overworld:

```
updateBlocks total                                     9.70 ms/tick
  extrabiomes.blocks.BlockLeafEbxl.updateTick          2.85 ms/tick     29% of all block ticks
  net.minecraft.block.BlockLeaves.updateTick           0.07 ms/tick
```

Either way, trees destroying themselves is a bug regardless of what it costs.

### Superheroes Unlimited event handlers skip entities they cannot affect

Superheroes Unlimited registers on the order of 250 listeners for `LivingUpdateEvent`. Forge posts
that event once per living entity per tick and dispatches it to every listener, so listener count
multiplies against entity count. None of them do anything for a mob, and none do anything for a
player who is not carrying the mod's gear. This cancels the call at the head of
`ASMEventHandler.invoke` when the handler belongs to that mod and the entity cannot be affected.

**What a profile can and cannot show here.** The listeners this cancels never run, so they never
appear in a profile. Their cost is invisible and no measurement can be used to argue the guard
pays for itself. What a profile *can* show is the guard's own cost — and that is why this exists
in its current form. The version inherited from the pack's earlier patch jar reached the event's
entity through `java.lang.reflect.Field.get` and spent **1.94 ms/tick** doing so, more than every
listener that survived the filter put together. `LivingEvent.entityLiving` is `public final`.
There was never a reason to reflect on it.

Every reflective access is gone: `ASMEventHandler.owner` and `.readable` are `@Shadow`ed rather
than looked up, the entity is a direct field read, "is it a player" is an `instanceof` rather than
`getClass().getName().contains(...)`, and the inventory scan uses the real fields.

The original also wrote `(++n & 0x2710) == 0` for its periodic cache cleanup. That is a bitwise
AND, not a modulo — true for `n` = 1 through 15 and then only sporadically, so the cache was wiped
almost continuously at first and unpredictably afterwards. It is now `% 10000`.

### Worthless drops in water despawn in 30 seconds instead of 5 minutes

Water collects items: currents carry drops together and nothing removes them early, so any body of
water near a fight accumulates stacks nobody will collect, each running a full entity tick for five
minutes.

The filter is deliberately conservative, because deleting an item a player wanted is worse than any
tick it costs. Untouched: anything with a thrower set (player-dropped), enchanted, named in an
anvil, unstackable (`maxStackSize <= 1` — tools, armour, weapons, boss drops), and everything from
OreSpawn, whose scales and bones are crafting materials. The check itself only runs when
`ticksExisted % 40 == 20`.

---

## Which phase a mixin belongs in, and why it matters

A mixin config listed in the jar manifest under `MixinConfigs` is processed during the
LaunchWrapper phase, **before FML has added ordinary mod jars to the classpath**. A mixin in that
config targeting a class that belongs to a regular mod finds nothing, and Mixin drops it with a
warning rather than an error:

```
[mixin]: Error loading class: some/mod/SomeClass (ClassNotFoundException)
[mixin]: @Mixin target some.mod.SomeClass was not found ...
```

Boot continues and the patch silently never applies. This is not theoretical. It happened to this
mod's own 0.1.0, and it is still happening in the pack it was built for to another mod's mixin
targeting `morph.common.morph.MorphState` — that class is present in the Morph jar; it is simply
not on the classpath yet when the early config is read, and the patch has never once run.

So the split is by what a mixin targets, not by preference:

| Target | Config | Available when |
|---|---|---|
| vanilla, Forge, FML | `mixins.sondplaytweaks.json` | LaunchWrapper — already on the classpath |
| any other mod | `mixins.sondplaytweaks.late.json` | `LoaderState.CONSTRUCTING` |

The late config is registered through GTNHMixins' `ILateMixinLoader`, which ships inside UniMixins.
It also hands over the set of loaded mod ids, so each patch is gated on its target mod actually
being installed rather than relying on a not-found warning:

```java
public List<String> getMixins(Set<String> loadedMods) {
    List<String> mixins = new ArrayList<String>();
    if (loadedMods.contains("orespawn")) mixins.add("MixinOreSpawnLeaves");
    return mixins;
}
```

This is why UniMixins is a hard requirement rather than "any Mixin 0.8+ provider".

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

### Classpath order is not cosmetic

Minecraft 1.7.10 runs with **MCP class names** (`net.minecraft.entity.Entity`) but **SRG member
names** (`func_145782_y`, `field_70173_aa`). Without a refmap, whatever member names the compiler
writes into the bytecode are the names looked up at runtime — so they have to be SRG, or the patch
dies with `NoSuchMethodError` the first time it runs.

That makes the jar providing `net.minecraft.*` a correctness dependency, not a convenience:

| | |
|---|---|
| `deobfed.jar` | Minecraft with **SRG** members — **must come first** |
| `forgeSrc-1.7.10-10.13.4.1614-1.7.10.jar` | Minecraft with MCP members, plus `cpw.mods.fml.*` and `net.minecraftforge.*` |
| `+unimixins-all-1.7.10-0.3.1.jar` | Mixin annotations and GTNHMixins |
| the target mod's jar | resolving `targets` in late mixins |

Both Minecraft jars carry `net.minecraft.*`. With the SRG one first, `javac` resolves vanilla
members to SRG. Forge's and FML's own classes exist only in `forgeSrc` and are not obfuscated, so
they come from there safely.

This is checkable, and worth checking after any build that touches vanilla members:

```
javap -c -p com.sondplay.tweaks.mixins.early.MixinASMEventHandler \
  | grep -oE '// (Method|Field) net/minecraft[^ ]*'

// Field  net/minecraft/entity/player/EntityPlayer.field_71071_by:...
// Method net/minecraft/item/ItemStack.func_77973_b:()Lnet/minecraft/item/Item;
```

Anything readable in that output that is not under `net.minecraftforge` is a bug waiting to fire.

Compile with JDK 8, or with a newer JDK targeting 8. The **runtime** is Java 17+ — only the
emitted bytecode has to be Java 8, because Mixin merges these methods into classes compiled for
Java 6.

The lack of a reproducible build is the main open weakness here: `deobfed.jar` is produced locally
rather than fetched. Moving to [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
would fix that and generate a proper refmap, at the cost of pulling down all of Minecraft and MCP.

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
