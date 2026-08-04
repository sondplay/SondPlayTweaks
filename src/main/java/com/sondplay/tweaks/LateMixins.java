package com.sondplay.tweaks;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registers the late-phase mixin config and decides which of its mixins to request.
 *
 * WHY A LATE CONFIG EXISTS. A mixin config listed in the jar manifest under MixinConfigs is
 * processed during the LaunchWrapper phase, before FML has added ordinary mod jars to the
 * classpath. Any mixin in it that targets a class belonging to a regular mod finds nothing, and
 * Mixin drops it with a warning rather than an error:
 *
 *     [mixin]: Error loading class: some/mod/SomeClass (ClassNotFoundException)
 *     [mixin]: @Mixin target some.mod.SomeClass was not found ...
 *
 * The boot continues and the patch silently never applies. Late configs are registered by
 * GTNHMixins (shipped inside UniMixins) at LoaderState.CONSTRUCTING, by which point mod classes
 * are loadable.
 *
 * MOD IDS ARE NOT RELIABLY CASED, so the check here is case-insensitive. OreSpawn is the example
 * that proved it: its mcmod.info declares "modid": "orespawn" while the @Mod annotation on
 * danger.orespawn.OreSpawnMain declares modid = "OreSpawn", and it is the annotation that reaches
 * this method. An exact-match check against the lowercase spelling silently requested nothing and
 * the leaf patch never applied — the log said `Preparing mixins.sondplaytweaks.late.json (0)` and
 * nothing else. Hence also the log line at the end of getMixins: a gate that silently declines is
 * indistinguishable from a gate that was never asked.
 */
@LateMixin
public class LateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.sondplaytweaks.late.json";
    }

    /**
     * Requested purely on whether the target mod is present. The config is deliberately not
     * consulted here: this runs at CONSTRUCTING and the config file is not read until preInit, so
     * anything read here would be the compiled-in default rather than what the player set. The
     * config switches are honoured at runtime, inside each mixin.
     */
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<String>();

        boolean oreSpawn = has(loadedMods, "orespawn");
        if (oreSpawn) {
            mixins.add("MixinOreSpawnLeaves");
        }

        Log.info("late mixins: OreSpawn " + (oreSpawn ? "present" : "absent")
                + " -> requesting " + mixins);
        return mixins;
    }

    /** Mod ids come from @Mod annotations written by many different people. Do not trust the case. */
    private static boolean has(Set<String> loadedMods, String modid) {
        if (loadedMods == null) return false;
        if (loadedMods.contains(modid)) return true;
        for (String id : loadedMods) {
            if (id != null && id.equalsIgnoreCase(modid)) return true;
        }
        return false;
    }
}
