package com.sondplay.tweaks.mixins.early;

import com.sondplay.tweaks.Cfg;
import com.sondplay.tweaks.Log;
import com.sondplay.tweaks.Stats;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.ASMEventHandler;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stops Superheroes Unlimited's LivingUpdateEvent handlers from running for entities that can
 * never be affected by them.
 *
 * Superheroes Unlimited registers on the order of 250 listeners for LivingUpdateEvent. Forge posts
 * that event once per living entity per tick and dispatches it to every listener, so the listener
 * count multiplies against the entity count. None of those listeners do anything for a mob, and
 * none of them do anything for a player who is not carrying the mod's gear.
 *
 * The guard runs at the head of ASMEventHandler.invoke and cancels the call when the handler
 * belongs to that mod and the event's entity cannot be affected.
 *
 * WHAT A PROFILE CAN AND CANNOT SHOW HERE. The listeners this cancels do not appear in a profile,
 * because they never run. Their cost is invisible, so no profile can be used to argue this guard
 * pays for itself. What a profile *can* show is the guard's own cost, and that is the reason this
 * class exists in this form: the original implementation reached the event's entity through
 * java.lang.reflect.Field.get and spent 1.94 ms/tick doing it, measured, more than every listener
 * that survived the filter put together. LivingEvent.entityLiving is public and final. There was
 * never a reason to reflect on it.
 *
 * Every reflective access in the original is gone:
 *
 *   ASMEventHandler.owner, .readable    private   -> @Shadow, direct field access
 *   LivingEvent.entityLiving            public    -> direct field access   (was 1.94 ms/tick)
 *   "is it a player"                    getClass().getName().contains()  -> instanceof
 *   EntityPlayer.inventory              public    -> direct field access
 *   InventoryPlayer.field_70460_b      public    -> direct field access
 *   ItemStack.getItem()                 public    -> direct call
 *
 * The mod is still identified by string, because its item and listener classes are the only
 * reliable handle on it and hard-linking them would make this mod depend on it being installed.
 */
@Mixin(value = ASMEventHandler.class, remap = false)
public abstract class MixinASMEventHandler {

    @Shadow private ModContainer owner;
    @Shadow private String readable;

    /** Mod ids that register the listeners being filtered. Confirmed against the FML mod list. */
    private static final Set<String> sondplaytweaks$SHS_MODS =
            new HashSet<String>(Arrays.asList("sus", "susaddon", "ironman", "legends"));

    /**
     * Whether a given handler belongs to that mod. Keyed by handler identity and computed once —
     * a handler's owner never changes, so this is a permanent answer, not a cache with a lifetime.
     * Bounded by the number of registered listeners.
     */
    private static final Map<Object, Boolean> sondplaytweaks$isTargetHandler =
            new IdentityHashMap<Object, Boolean>(512);

    /**
     * Whether a player is carrying the mod's gear, by entity id, refreshed at most once a second.
     *
     * This one does need a lifetime: a player equips and unequips. Without it, every one of those
     * ~250 handlers would rescan the player's armour and hotbar every tick.
     */
    private static final Map<Integer, long[]> sondplaytweaks$playerGear =
            new java.util.HashMap<Integer, long[]>(8);
    private static final long sondplaytweaks$GEAR_TTL_MS = 1000L;
    private static long sondplaytweaks$invokes = 0L;

    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true)
    private void sondplaytweaks$skipUnaffectedEntities(Event event, CallbackInfo ci) {
        if (!Cfg.superheroesGuard) return;
        if (!(event instanceof LivingEvent.LivingUpdateEvent)) return;

        Boolean isTarget = sondplaytweaks$isTargetHandler.get(this);
        if (isTarget == null) {
            isTarget = sondplaytweaks$computeIsTargetHandler();
            sondplaytweaks$isTargetHandler.put(this, isTarget);
            if (isTarget) {
                Stats.shsHandlersMatched.increment();
                Log.verbose("superheroes: matched handler " + this.readable);
            }
        }
        if (!isTarget) return;

        // Was Field.get() on a public final field — 1.94 ms/tick, for nothing.
        EntityLivingBase entity = ((LivingEvent) event).entityLiving;
        if (entity == null) { Stats.shsSkippedNotPlayer.increment(); ci.cancel(); return; }

        // Was getClass().getName().contains("EntityPlayer") on every invoke.
        if (!(entity instanceof EntityPlayer)) {
            Stats.shsSkippedNotPlayer.increment();
            ci.cancel();
            return;
        }

        // Drop stale entries for players who disconnected.
        // The original wrote (++n & 0x2710) == 0, which is a bitwise AND, not a modulo: it is true
        // for n = 1..15 and then only sporadically, so the map was wiped almost continuously at
        // first and unpredictably after.
        if (++sondplaytweaks$invokes % 10000L == 0L) {
            sondplaytweaks$playerGear.clear();
        }

        EntityPlayer player = (EntityPlayer) entity;
        int id = player.func_145782_y();
        long now = System.currentTimeMillis();
        long[] entry = sondplaytweaks$playerGear.get(id);
        if (entry == null) {
            // [0] is the timestamp of the last scan and starts at 0, which reads as "never".
            //
            // Not Long.MIN_VALUE, which is what this said in 0.5.0 and 0.5.1: now - Long.MIN_VALUE
            // is about 9.22e18 + 1.75e12, past Long.MAX_VALUE, so it overflowed to a negative
            // number, the "> TTL" test was never true, the scan never ran, and the entry stayed 0
            // forever — cancelling every handler for every player including one wearing the gear.
            // The counters caught it: 123352 cancelled as "no gear" against 0 inventory scans.
            entry = new long[2];
            sondplaytweaks$playerGear.put(id, entry);
        }
        if (now - entry[0] > sondplaytweaks$GEAR_TTL_MS) {
            entry[0] = now;
            Stats.shsGearScans.increment();
            entry[1] = sondplaytweaks$hasTargetGear(player) ? 1L : 0L;
            Log.verbose("superheroes: rescanned " + player.getClass().getSimpleName()
                    + " id " + id + " -> gear=" + (entry[1] == 1L));
        }
        if (entry[1] == 0L) {
            Stats.shsSkippedNoGear.increment();
            ci.cancel();
        } else {
            Stats.shsAllowed.increment();
        }
    }

    private boolean sondplaytweaks$computeIsTargetHandler() {
        if (this.owner != null && sondplaytweaks$SHS_MODS.contains(this.owner.getModId())) {
            return true;
        }
        // Fallback for listeners registered without a mod container.
        String r = this.readable;
        return r != null
                && (r.contains("tihyo") || r.contains("superheroes") || r.contains("SuperHero"));
    }

    /** Armour plus the hotbar — where the mod's gear has to be to do anything. */
    private static boolean sondplaytweaks$hasTargetGear(EntityPlayer player) {
        if (player.field_71071_by == null) return true; // unknown: let the handlers run
        ItemStack[] armor = player.field_71071_by.field_70460_b;
        if (armor != null) {
            for (ItemStack stack : armor) {
                if (sondplaytweaks$isTargetStack(stack)) return true;
            }
        }
        ItemStack[] main = player.field_71071_by.field_70462_a;
        if (main != null) {
            for (int i = 0; i < Math.min(9, main.length); i++) {
                if (sondplaytweaks$isTargetStack(main[i])) return true;
            }
        }
        return false;
    }

    private static boolean sondplaytweaks$isTargetStack(ItemStack stack) {
        if (stack == null) return false;
        Item item = stack.func_77973_b();
        if (item == null) return false;
        String cls = item.getClass().getName();
        return cls.contains("superheroes") || cls.contains("tihyo");
    }
}
