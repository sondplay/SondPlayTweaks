package com.sondplay.tweaks;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /spt — prints the counters on demand and clears them.
 *
 * The periodic summary tells you what has been happening on average. This tells you what happened
 * during the thing you just did: clear it, spawn what you want to watch, run it again.
 *
 * Implemented against ICommand directly rather than CommandBase because CommandBase carries a pile
 * of static helpers this does not need, and every one of its members would have to be named in SRG
 * anyway.
 */
public class StatsCommand implements ICommand {

    /** getCommandName */
    @Override
    public String func_71517_b() {
        return "spt";
    }

    /** getCommandUsage */
    @Override
    public String func_71518_a(ICommandSender sender) {
        return "/spt [reset] — SondPlayTweaks counters since the last summary";
    }

    /** getCommandAliases */
    @Override
    public List func_71514_a() {
        return new ArrayList<String>(Arrays.asList("sondplaytweaks"));
    }

    /** processCommand */
    @Override
    public void func_71515_b(ICommandSender sender, String[] args) {
        if (args.length > 0 && "reset".equalsIgnoreCase(args[0])) {
            Stats.drain(0);
            send(sender, "counters cleared");
            return;
        }
        String summary = Stats.drain(0);
        if (summary == null) {
            send(sender, "nothing counted since the last summary — "
                       + "either no patch had anything to do, or all three are off");
            return;
        }
        for (String line : summary.split("\n")) {
            send(sender, line);
        }
    }

    /** canCommandSenderUseCommand */
    @Override
    public boolean func_71519_b(ICommandSender sender) {
        return true;
    }

    /** addTabCompletionOptions */
    @Override
    public List func_71516_a(ICommandSender sender, String[] args) {
        if (args.length == 1) return new ArrayList<String>(Arrays.asList("reset"));
        return null;
    }

    /** isUsernameIndex */
    @Override
    public boolean func_82358_a(String[] args, int index) {
        return false;
    }

    @Override
    public int compareTo(Object other) {
        return func_71517_b().compareTo(((ICommand) other).func_71517_b());
    }

    private static void send(ICommandSender sender, String msg) {
        sender.func_145747_a(new ChatComponentText("[SondPlayTweaks] " + msg));
    }
}
