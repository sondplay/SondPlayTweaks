# SondPlayTweaks

Mixin patches for Minecraft 1.7.10, aimed at bugs and performance problems in old mods that
nobody ever fixed.

Every patch here starts from reading the actual bytecode of the mod being patched and comparing
it against what vanilla does. If a claim is in this README, it was verified — and where something
was inferred rather than measured, it says so.

```
Minecraft   1.7.10
Requires    UniMixins
Version     0.5.0
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

### OreSpawn entities get vanilla's path recompute cooldown and failure backoff

**Measured, and the largest single cost in the profile.** Server thread at 75.96 ms/tick against a
50 ms budget, with hundreds of OreSpawn bosses fighting:

```
GiantRobot.updateAITasks  7.87 ms  ->  tryMoveToEntityLiving  7.12 ms
Hammerhead.updateAITasks  4.40 ms  ->  tryMoveToEntityLiving  4.04 ms
Godzilla.updateAITasks    3.36 ms  ->  tryMoveToEntityLiving  1.82 ms
                                       ----------------------------
                                       12.98 ms/tick, three classes
```

All three have the same shape underneath — `findPathOptions` → `getSafePoint` →
`getVerticalOffset` → `func_82565_a` → `World.getBlock`, with **8.57 ms of the 12.98** spent
reading blocks inside A*. Several more OreSpawn entities sit unexpanded in the same profile
(`SeaViper` 3.01, `Hydrolisc` 1.56, `PitchBlack` 1.41, `Kraken` 1.29, `Lizard` 1.02 ms/tick).

**The cause is not frequency.** OreSpawn's callers are already gated behind their own `nextInt`
rolls, at a rate comparable to vanilla's. The difference is that **when a target cannot be
reached, the cost never goes down**. Vanilla's `EntityAIAttackOnCollide` keeps a delay counter and
a failure penalty: every attempt whose path stops short of the target adds 10 ticks to the next
wait, so a mob stuck behind a wall degrades to roughly every 19, then 34, then 49 ticks. OreSpawn
calls `PathNavigate` straight out of `updateAITasks` with neither, so an unreachable target is
re-pathed at full rate forever. With hundreds of bosses shoving each other, most targets are
unreachable most of the time.

**The fix reproduces vanilla's arithmetic** at the navigator level, for OreSpawn entities only:

```
path only when   delay <= 0 && (no remembered target || target moved >= 1 block || rand < 0.05)
after pathing    delay = failPenalty + 4 + rand(7)
                 +10 beyond 32 blocks, +5 beyond 16
                 +15 if the call itself returned false
failPenalty      += 10 when the path stopped short,  = 0 when it reached
```

Three details that are easy to get wrong:

- **The 5% roll is not decoration.** It is the only thing that breaks a cooldown early. A plain
  cooldown without it turns a mob whose target is standing still into a mob that stops reacting.
- **When skipping, this returns `!noPath()`, never a flat `false`.** The return value means "am I
  moving toward it", and vanilla's own `EntityAIAttackOnCollide` reads it to decide whether to add
  its 15-tick penalty. Answering `false` would tell every caller the mob had given up while it was
  in fact walking a perfectly good path.
- **`failPenalty` is capped**, which vanilla does not do. Vanilla gets away with it because
  acquiring a new target restarts the AI task and resets the counter; there is no equivalent signal
  at the navigator level, so an unreachable target would otherwise push the delay up without bound
  and eventually stop the mob pathing at all.

Every mob in the game reaches this method, so scope is a single byte field read after the first
call per navigator. Vanilla AI keeps its own backoff untouched.

---

## Seeing what the patches are doing

Every patch counts what it does and prints a summary. Nothing logs per call — these methods run
thousands of times per tick, and a log line in any of them would cost more than the patch saves and
bury the log file. What is affordable in a hot path is an increment; what is useful is the ratio
between them, printed occasionally.

```
[SondPlayTweaks] --- last 60s ---
[SondPlayTweaks]   leaves       blocked 412 removal(s)
[SondPlayTweaks]   superheroes  250 new handler(s) matched | skipped 1204331 not-player + 8210 no-gear | allowed 1190 | 99% skipped
[SondPlayTweaks]                gear rescans 61  (expect ~1/player/second — a large number means the cache is not holding)
[SondPlayTweaks]   pathfinding  87 new OreSpawn navigator(s) | skipped 44120, ran A* 3310 | 93% skipped
[SondPlayTweaks]                of those that ran: 402 reached the target, 2908 stopped short | peak penalty 100 ticks
[SondPlayTweaks]                most paths are not reaching — this is the case the backoff exists for
```

`/spt` prints the same thing on demand and clears the counters, which is how you measure one
specific thing: `/spt reset`, do the thing, `/spt`.

### config/sondplaytweaks.cfg

```properties
orespawnLeaves=true
superheroesGuard=true
orespawnPathThrottle=true

statsIntervalSeconds=60
verbose=false
```

**Each patch has its own switch, and that is the point.** In a pack of two hundred mods, turning
one patch off and seeing whether a symptom moves is the only reliable way to attribute a change to
it. A patch switched off still has its mixin applied and returns immediately, which costs a static
boolean read — the price of being able to bisect a problem without rebuilding the jar.

`verbose=true` logs every individual decision instead of counting them. It writes from inside
methods that run thousands of times per tick: expect it to cost more than every patch here saves,
and to produce log files in the hundreds of megabytes. It exists for pinning down one misbehaving
entity, for a minute, deliberately.

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
| OreSpawn leaves destroying themselves | correctness, unmeasured | **0.1.0** |
| Path recompute cooldown and failure backoff for OreSpawn entities | **12.98 ms/tick** measured from three classes | **0.4.0** |
| **ExtrabiomesXL leaf decay.** `BlockLeafEbxl` overrides `updateTick` and, like OreSpawn's, is not covered by Hodgepodge's BFS decay (which handles vanilla, BiomesOPlenty, Magical, Nether and Witch leaves) or by BugTorch. | **2.85 ms/tick**, 29% of all block-tick time | planned |
| `findSomethingToAttack` — sorts the full entity list before filtering it | `GiantRobot` 0.02, `Hammerhead` 0.05, `Godzilla` 0.03 ms/tick | low priority |
| ~~`chunkLoadOverride` / `dummyChunk` ([GTNH #11425](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/issues/11425))~~ | **dropped** | Pathfinding never reaches disk in this configuration. Under `getPathEntityToEntity`, `getChunkFromChunkCoords` resolves through `ChunkProviderServer.provideChunk` → `ServerThreadLongHashMap.getValueByKey` → a fastutil map hit, 0.3 ms/tick, with no `loadChunk` beneath it. Hodgepodge's `preventLoadingChunksWhenPathfinding` already closes this. |

---

## License

[LGPL-3.0](LICENSE). The LGPL incorporates the GPL by reference, so the full GPL-3.0 text is
included as [`GPL-3.0.txt`](GPL-3.0.txt).

Modpacks may bundle this freely. Forks must stay open.
