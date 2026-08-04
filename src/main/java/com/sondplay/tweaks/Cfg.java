package com.sondplay.tweaks;

import java.io.File;

/**
 * Per-patch switches and logging settings, read from config/sondplaytweaks.cfg.
 *
 * WHY EACH PATCH HAS A SWITCH. Turning one patch off and seeing whether a symptom moves is the
 * only reliable way to attribute a change to a patch in a pack this size. Without that, every
 * report is a guess about which of several mods did it.
 *
 * TIMING. Mixins are applied during the LaunchWrapper phase, long before this file is read at
 * preInit, but the code they inject does not *run* until the game is playing. So the defaults
 * here are what a mixin sees only in the impossible case of being invoked before preInit, and the
 * real values are in place by the time anything ticks.
 *
 * A patch switched off still has its mixin applied; the injected code returns immediately. That
 * costs a static boolean read, which is the price of being able to bisect a problem without
 * rebuilding the jar.
 *
 * Loading is done by hand rather than through Forge's Configuration class so that this stays
 * loadable from an early mixin without dragging Forge in behind it.
 */
public final class Cfg {

    private Cfg() {}

    public static boolean orespawnLeaves = true;
    public static boolean superheroesGuard = true;
    public static boolean orespawnPathThrottle = true;

    /** Seconds between summaries. 0 turns the summary off. */
    public static int statsIntervalSeconds = 60;

    /**
     * Log every single decision instead of counting them.
     *
     * This writes a line from inside methods that run thousands of times per tick. It will cost
     * more than every patch in this mod saves and it will produce log files measured in hundreds
     * of megabytes. It exists for pinning down one specific misbehaving entity, for a minute, on
     * purpose.
     */
    public static boolean verbose = false;

    private static final String FILE_HEADER =
            "# SondPlayTweaks\n"
          + "#\n"
          + "# Each patch can be switched off independently. Turning one off and seeing whether a\n"
          + "# symptom moves is the only reliable way to attribute a change to a patch.\n"
          + "#\n"
          + "# orespawnLeaves        stops OreSpawn leaves from deleting themselves\n"
          + "# superheroesGuard      skips Superheroes Unlimited event handlers for entities they\n"
          + "#                       cannot affect\n"
          + "# orespawnPathThrottle  gives OreSpawn entities vanilla's path recompute cooldown and\n"
          + "#                       failure backoff\n"
          + "#\n"
          + "# statsIntervalSeconds  seconds between summary lines in the log; 0 disables them\n"
          + "# verbose               log every decision instead of counting them. Writes from inside\n"
          + "#                       methods that run thousands of times per tick. Expect it to cost\n"
          + "#                       more than every patch here saves, and to produce log files in\n"
          + "#                       the hundreds of megabytes. For pinning down one entity, briefly.\n"
          + "\n";

    public static void load(File file) {
        java.util.Properties p = new java.util.Properties();
        if (file.isFile()) {
            java.io.InputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                p.load(in);
            } catch (Exception e) {
                Log.warn("could not read " + file.getName() + ", using defaults: " + e);
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        }

        orespawnLeaves       = bool(p, "orespawnLeaves", orespawnLeaves);
        superheroesGuard     = bool(p, "superheroesGuard", superheroesGuard);
        orespawnPathThrottle = bool(p, "orespawnPathThrottle", orespawnPathThrottle);
        statsIntervalSeconds = integer(p, "statsIntervalSeconds", statsIntervalSeconds);
        verbose              = bool(p, "verbose", verbose);

        save(file);

        Log.info("config: leaves=" + orespawnLeaves
                + " superheroes=" + superheroesGuard
                + " pathThrottle=" + orespawnPathThrottle
                + " statsInterval=" + statsIntervalSeconds + "s"
                + " verbose=" + verbose);
        if (verbose) {
            Log.warn("VERBOSE LOGGING IS ON. This logs from inside per-tick hot paths and will "
                   + "cost more than these patches save. Turn it off when you are done.");
        }
    }

    private static void save(File file) {
        java.io.Writer w = null;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8");
            w.write(FILE_HEADER);
            w.write("orespawnLeaves=" + orespawnLeaves + "\n");
            w.write("superheroesGuard=" + superheroesGuard + "\n");
            w.write("orespawnPathThrottle=" + orespawnPathThrottle + "\n");
            w.write("\n");
            w.write("statsIntervalSeconds=" + statsIntervalSeconds + "\n");
            w.write("verbose=" + verbose + "\n");
        } catch (Exception e) {
            Log.warn("could not write " + file.getName() + ": " + e);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean bool(java.util.Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(java.util.Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            Log.warn(key + "=" + v + " is not a number, using " + def);
            return def;
        }
    }
}
