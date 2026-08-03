# Changelog

## 0.3.0

Absorbs the pack's earlier patch jar, which is now retired.

- **Superheroes Unlimited event handlers skip entities they cannot affect.** Carried over, with
  every reflective access removed. The inherited version reached the event's entity through
  `Field.get` and spent 1.94 ms/tick on it — `LivingEvent.entityLiving` is `public final`.
  `ASMEventHandler.owner` and `.readable` are now `@Shadow`ed, the player test is an `instanceof`
  instead of a class-name string search, and the inventory scan uses the real fields. Its periodic
  cache cleanup used `(++n & 0x2710) == 0`, a bitwise AND rather than a modulo, which fired for
  n = 1 through 15 and then only sporadically; it is now `% 10000`.
- **Worthless drops in water despawn in 30 seconds instead of 5 minutes.** Carried over unchanged
  in behaviour. Player-dropped, enchanted, named, unstackable and OreSpawn items are untouched.
- A third patch from that jar was dropped rather than migrated: it fixed a `ClassCastException` in
  `MorphState.parseTag` that Morph 0.9.2 caused by casting the NBT map to `HashMap`. Morph 0.9.3
  casts to `Map`, so the crash is gone at the source. That mixin had also never applied — it was
  in an early config targeting a mod class, the same bug fixed in 0.2.0.
- **Build fix: classpath order.** 1.7.10 runs with MCP class names but SRG member names, so
  without a refmap the emitted bytecode has to name SRG members or die with `NoSuchMethodError`.
  The build now puts an SRG-named Minecraft jar ahead of the MCP-named one. This is why the
  inherited code used reflection everywhere — it was working around not having one.

## 0.2.0

**The leaf patch in 0.1.0 never applied.** It was registered in the early mixin config, which is
processed before FML puts ordinary mod jars on the classpath, so `danger.orespawn.BlockAppleLeaves`
and its three siblings did not exist yet and Mixin dropped the mixin with a warning instead of an
error. Boot continued and nothing was patched.

- Moved every mixin to `mixins.sondplaytweaks.late.json`, registered through GTNHMixins'
  `ILateMixinLoader` at `LoaderState.CONSTRUCTING`. Mod classes are loadable by then.
- Each patch is now gated on `loadedMods` containing its target mod, so an absent mod means the
  mixin is simply not requested rather than producing a not-found warning.
- UniMixins is now a hard requirement, not just "any Mixin 0.8+ provider".
- Renamed from `$ondPlayTweaks` to `SondPlayTweaks`: GitHub strips `$` from release asset
  filenames, so the published jar did not match the documented name.
- Replaced the leaf patch's "not measured" note with what a profile actually shows, and with the
  caveat that the profile was taken in the overworld rather than in the OreSpawn dimensions where
  the symptom was reported.

## 0.1.0

First release.

- **OreSpawn leaves no longer delete themselves.** The mod's four leaf classes override
  `func_149674_a` with a decay implementation that has no "needs check" metadata gate and no
  flood fill, so canopy leaves on large trees decay simply for having spawned more than 3
  Manhattan steps from a log. Cancels `removeLeaves` rather than the whole tick method, which
  keeps the XP bottle drops and the day/night Scary ↔ Apple transformation intact.
