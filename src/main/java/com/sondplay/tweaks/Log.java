package com.sondplay.tweaks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One logger, one tag, so everything this mod says is greppable as [SondPlayTweaks].
 *
 * No Minecraft imports: this is reachable from mixins applied during the LaunchWrapper phase, and
 * log4j is one of the few things already up at that point.
 */
public final class Log {

    private Log() {}

    private static final Logger LOG = LogManager.getLogger("SondPlayTweaks");

    public static void info(String msg) {
        LOG.info(msg);
    }

    public static void warn(String msg) {
        LOG.warn(msg);
    }

    public static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }

    /**
     * Only prints when verbose is on, and callers still have to guard the call themselves if
     * building the message costs anything — a string concatenation in a per-tick hot path is a
     * cost whether or not the line is ever printed.
     */
    public static void verbose(String msg) {
        if (Cfg.verbose) LOG.info("[v] " + msg);
    }
}
