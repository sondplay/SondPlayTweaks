package com.sondplay.tweaks;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registers the late-phase mixin config.
 *
 * WHY THIS EXISTS. A mixin config listed in the jar manifest under MixinConfigs is processed
 * during the LaunchWrapper phase, before FML has added ordinary mod jars to the classpath. Any
 * mixin in it that targets a class belonging to a regular mod finds nothing, and Mixin drops it
 * with a warning rather than an error:
 *
 *     [mixin]: Error loading class: some/mod/SomeClass (ClassNotFoundException)
 *     [mixin]: @Mixin target some.mod.SomeClass was not found ...
 *
 * The boot silently continues and the patch never applies. This was observed in this very pack
 * with another mod's mixin targeting morph.common.morph.MorphState — the class is present in the
 * Morph jar, but not yet on the classpath when the early config is read.
 *
 * Late configs are registered by GTNHMixins (shipped inside UniMixins) at LoaderState.CONSTRUCTING,
 * by which point mod classes are loadable. Every mixin in this mod targets a mod class, so they
 * all belong here.
 *
 * getMixins also receives the set of loaded mod ids, which lets each patch be gated on its target
 * mod actually being installed instead of relying on a not-found warning.
 */
@LateMixin
public class LateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.sondplaytweaks.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<String>();
        if (loadedMods.contains("orespawn")) {
            mixins.add("MixinOreSpawnLeaves");
        }
        return mixins;
    }
}
