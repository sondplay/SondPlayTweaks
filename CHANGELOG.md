# Changelog

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
