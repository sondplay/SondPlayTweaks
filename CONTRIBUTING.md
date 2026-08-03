# Contributing

## Ground rules for patches

A patch gets added here only if it clears these:

1. **Read the bytecode first.** Every claim in the README traces to a disassembly or a comparison
   against vanilla. `javap -p -c` on the target class, before writing anything. The leaf patch
   changed shape entirely once the disassembly showed the block's drops lived inside the branch
   the original plan was going to cancel.
2. **Say what was measured and what was inferred.** "This is 17% of the tick" is a claim that
   needs a profile behind it. If there isn't one, the README says so.
3. **Never A/B two profiles.** Each profiling run happens on a different world, in a different
   region, with different terrain and a different mob population. Comparing run A to run B
   measures the worlds, not the patch, and it will manufacture a result every time. Read *ratios
   within a single run* — what dominates, what is a rounding error, what shape a hot path has.
   To measure a patch, the setup has to be reproducible (superflat, N entities spawned by
   command), not two ordinary sessions placed side by side.
4. **A profile cannot show what a patch prevented.** Work that was skipped does not appear. If a
   guard exists to stop handlers from running, the handlers it stopped are invisible, and the
   surviving ones are not a measure of what it saved. Costs are measurable; avoided costs are
   not. Do not write a verdict that depends on the counterfactual.
5. **Absence in a profile is not zero.** A block that never ticked in the profiled region may
   dominate somewhere else. Say where the profile was taken.
3. **No behaviour loss disguised as optimisation.** Throttling a mob's update by distance makes
   the mob visibly worse; that is not a performance fix, it is a downgrade with a benchmark
   attached.

## Mixin rules for 1.7.10

Each of these cost either a crash or hours of bytecode reading.

**Never touch `net.minecraft.client.*`.** Rendering belongs to Angelica, which carries a
hardcoded incompatibility list (`optifine`, `fastcraft`, `optimizationsandtweaks`) and removes
matching mods from the boot. Sharing that space is not a negotiation you win.

**Target the mod class, never the vanilla superclass.** A subclass that overrides the method
escapes an `@Overwrite` on its parent — virtual dispatch calls the subclass. Mods that patch
`BlockLeaves` have never covered OreSpawn's leaves for exactly this reason.

**Prefer a cancellable `@Inject` over `@Overwrite`.** `@Overwrite` replaces the method body, and
every injection point another mod relies on disappears with it. Observed live: a leaf-decay mod
overwriting `BlockLeaves.func_149674_a` at priority 1000 killed BugTorch's `@At("STORE")`
injection at priority 100, producing `MixinApplyError` → `NoClassDefFoundError` → no boot.

**List mixins in the JSON, not through a plugin.** A `MixinPlugin` that populates the list at
runtime means anyone auditing the mod has to disassemble it to find out what it loads. Both
OptimizationsAndTweaks and the noleafdecay fork do this, and both cost real time to audit.

**Static members in a mixin must be `private`**, or you get `InvalidMixinException`.

**`@Overwrite` requires `@author` and `@reason`** in the javadoc.

**No ASM class transformers.** In a lwjgl3ify + RetroFuturaBootstrap setup they are unreliable —
lwjgl3ify adds packages to the `LaunchClassLoader` transformer exclusion list, and a transformer
in an excluded package registers successfully, logs nothing, and silently never runs. Mixin is
the only dependable route here.

## Build

`bash build.sh`. See the README for why there is no Gradle. Keep `SondPlayTweaks.VERSION` and the
`VERSION` variable in `build.sh` in sync — the script refuses to build otherwise.
