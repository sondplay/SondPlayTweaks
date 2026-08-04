package com.sondplay.tweaks;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for what the patches are actually doing, and the periodic summary that prints them.
 *
 * WHY COUNTERS AND NOT LOG LINES. Every patch in this mod lives in a method called thousands of
 * times per tick. A log call in any of them would cost more than the patch saves and drown the
 * log file. What is affordable there is an increment; what is useful is the ratio between them,
 * printed occasionally.
 *
 * LongAdder rather than long: ASMEventHandler.invoke runs on both the client and the server
 * thread on an integrated server, so plain increments would silently lose counts. LongAdder is
 * built for exactly this — contended increments are cheap, reads are not, and reads happen once
 * a minute.
 *
 * This class is deliberately free of Minecraft imports. It is referenced from mixins that are
 * applied during the LaunchWrapper phase, so it has to be loadable before Minecraft is.
 */
public final class Stats {

    private Stats() {}

    // --- OreSpawn leaves -------------------------------------------------------------------
    /** Leaf removals blocked. A nonzero value is the proof the patch is live. */
    public static final LongAdder leafBlocked = new LongAdder();

    // --- Superheroes guard -----------------------------------------------------------------
    /** Distinct event handlers identified as belonging to the mod. Grows once, then settles. */
    public static final LongAdder shsHandlersMatched = new LongAdder();
    /** Handler calls cancelled because the entity is not a player. */
    public static final LongAdder shsSkippedNotPlayer = new LongAdder();
    /** Handler calls cancelled because the player is not carrying the mod's gear. */
    public static final LongAdder shsSkippedNoGear = new LongAdder();
    /** Handler calls let through. */
    public static final LongAdder shsAllowed = new LongAdder();
    /** Inventory rescans — should be roughly one per player per second, not per call. */
    public static final LongAdder shsGearScans = new LongAdder();

    // --- Path throttle ---------------------------------------------------------------------
    /** Navigators identified as belonging to an OreSpawn entity. Settles at the mob count. */
    public static final LongAdder pathNavigatorsScoped = new LongAdder();
    /** Path requests refused by the cooldown. */
    public static final LongAdder pathSkipped = new LongAdder();
    /** Path requests that went on to run A*. */
    public static final LongAdder pathRan = new LongAdder();
    /** Attempts whose path stopped short of the target, so the penalty grew. */
    public static final LongAdder pathFailed = new LongAdder();
    /** Attempts whose path reached the target, so the penalty reset. */
    public static final LongAdder pathReached = new LongAdder();
    /** Highest failure penalty seen since the last summary. Written from the server thread only. */
    public static volatile int pathPeakPenalty = 0;

    /**
     * Formats one summary and clears every counter.
     *
     * Returns null when nothing happened at all, so a quiet server does not print a wall of zeroes
     * once a minute.
     */
    public static String drain(int seconds) {
        long leaf = leafBlocked.sumThenReset();

        long shsMatched = shsHandlersMatched.sumThenReset();
        long shsMob = shsSkippedNotPlayer.sumThenReset();
        long shsGear = shsSkippedNoGear.sumThenReset();
        long shsOk = shsAllowed.sumThenReset();
        long shsScans = shsGearScans.sumThenReset();

        long navs = pathNavigatorsScoped.sumThenReset();
        long skipped = pathSkipped.sumThenReset();
        long ran = pathRan.sumThenReset();
        long failed = pathFailed.sumThenReset();
        long reached = pathReached.sumThenReset();
        int peak = pathPeakPenalty;
        pathPeakPenalty = 0;

        if (leaf == 0 && shsMob == 0 && shsGear == 0 && shsOk == 0
                && skipped == 0 && ran == 0 && navs == 0 && shsMatched == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("--- last ").append(seconds).append("s ---");

        if (Cfg.orespawnLeaves) {
            sb.append("\n  leaves       blocked ").append(leaf).append(" removal(s)");
            if (leaf == 0) sb.append("   (no OreSpawn leaf ticked here — expected outside its dimensions)");
        }

        if (Cfg.superheroesGuard) {
            long shsTotal = shsMob + shsGear + shsOk;
            sb.append("\n  superheroes  ").append(shsMatched).append(" new handler(s) matched | ")
              .append("skipped ").append(shsMob).append(" not-player + ").append(shsGear).append(" no-gear")
              .append(" | allowed ").append(shsOk);
            if (shsTotal > 0) {
                sb.append(" | ").append(pct(shsMob + shsGear, shsTotal)).append(" skipped");
            }
            sb.append("\n               gear rescans ").append(shsScans)
              .append("  (expect ~1/player/second — a large number means the cache is not holding)");
        }

        if (Cfg.orespawnPathThrottle) {
            long total = skipped + ran;
            sb.append("\n  pathfinding  ").append(navs).append(" new OreSpawn navigator(s) | ")
              .append("skipped ").append(skipped).append(", ran A* ").append(ran);
            if (total > 0) {
                sb.append(" | ").append(pct(skipped, total)).append(" skipped");
            }
            sb.append("\n               of those that ran: ").append(reached).append(" reached the target, ")
              .append(failed).append(" stopped short")
              .append(" | peak penalty ").append(peak).append(" ticks");
            if (ran > 0 && failed > ran / 2) {
                sb.append("\n               most paths are not reaching — this is the case the backoff exists for");
            }
        }

        return sb.toString();
    }

    private static String pct(long part, long whole) {
        if (whole <= 0) return "0%";
        return (part * 100L / whole) + "%";
    }
}
