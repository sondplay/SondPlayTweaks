# Changelog

## 0.1.0

First release.

- **OreSpawn leaves no longer delete themselves.** The mod's four leaf classes override
  `func_149674_a` with a decay implementation that has no "needs check" metadata gate and no
  flood fill, so canopy leaves on large trees decay simply for having spawned more than 3
  Manhattan steps from a log. Cancels `removeLeaves` rather than the whole tick method, which
  keeps the XP bottle drops and the day/night Scary ↔ Apple transformation intact.
