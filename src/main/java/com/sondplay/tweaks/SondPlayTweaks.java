package com.sondplay.tweaks;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import java.io.File;

/**
 * SondPlayTweaks — patches for Minecraft 1.7.10.
 *
 * The patches themselves are all Mixins, declared in mixins.sondplaytweaks.json (targets that
 * exist at LaunchWrapper time) and mixins.sondplaytweaks.late.json (targets belonging to other
 * mods). This class does the three things a mixin cannot do for itself: read the config, print the
 * periodic summary, and register the command.
 */
@Mod(modid = SondPlayTweaks.MODID,
     name = SondPlayTweaks.NAME,
     version = SondPlayTweaks.VERSION,
     acceptableRemoteVersions = "*")
public class SondPlayTweaks {

    public static final String MODID   = "sondplaytweaks";
    public static final String NAME    = "SondPlayTweaks";
    public static final String VERSION = "0.5.1";

    private int tickCounter = 0;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Log.info(NAME + " " + VERSION);
        Cfg.load(new File(event.getModConfigurationDirectory(), MODID + ".cfg"));
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new StatsCommand());
        Log.info("/spt prints the counters on demand");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        int interval = Cfg.statsIntervalSeconds;
        if (interval <= 0) return;
        if (++tickCounter < interval * 20) return;
        tickCounter = 0;

        String summary = Stats.drain(interval);
        if (summary == null) return;
        for (String line : summary.split("\n")) {
            Log.info(line);
        }
    }
}
