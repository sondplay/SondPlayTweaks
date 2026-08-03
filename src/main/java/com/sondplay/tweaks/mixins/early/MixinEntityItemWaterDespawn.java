package com.sondplay.tweaks.mixins.early;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shortens the despawn timer for worthless drops floating in water.
 *
 * Items in 1.7.10 live for five minutes and tick the whole time. Water collects them: currents
 * carry drops together and nothing removes them early, so a body of water near any mob farm or
 * fight accumulates stacks that no one will ever pick up. Each one still runs a full entity tick.
 *
 * An item that qualifies has its age set to 5400, leaving it 30 seconds instead of 5 minutes.
 *
 * WHAT IS NEVER TOUCHED. The filter is deliberately conservative, because deleting an item a
 * player wanted is worse than any tick it costs:
 *
 *   dropped by a player      has a thrower set
 *   enchanted                isItemEnchanted
 *   named in an anvil        hasDisplayName
 *   unstackable              maxStackSize <= 1 — tools, armour, weapons, boss drops
 *   anything from OreSpawn   scales, bones and the rest are crafting materials
 *
 * The check itself only runs when ticksExisted % 40 == 20, so it costs one modulo on 39 of every
 * 40 ticks, and an item that already qualified returns on the first line.
 *
 * Inherited from the pack's earlier patch jar, rewritten only to drop reflection and to be
 * explicit about which SRG members it touches.
 */
@Mixin(value = EntityItem.class, remap = false)
public abstract class MixinEntityItemWaterDespawn {

    /** age */
    @Shadow public int field_70292_b;

    private static final int SONDPLAYTWEAKS$AGE_30_SECONDS = 5400;

    @Inject(method = "func_70071_h_", at = @At("HEAD"))
    private void sondplaytweaks$despawnJunkInWater(CallbackInfo ci) {
        if (this.field_70292_b >= SONDPLAYTWEAKS$AGE_30_SECONDS) return;

        Entity self = (Entity) (Object) this;
        if (self.field_70173_aa % 40 != 20) return;   // ticksExisted
        if (!self.func_70090_H()) return;             // isInWater

        EntityItem item = (EntityItem) (Object) this;

        String thrower = item.func_145800_j();        // getThrower
        if (thrower != null && thrower.length() > 0) return;

        ItemStack stack = item.func_92059_d();        // getEntityItem
        if (stack == null) return;
        if (stack.func_77976_d() <= 1) return;        // getMaxStackSize
        if (stack.func_77948_v()) return;             // isItemEnchanted
        if (stack.func_82837_s()) return;             // hasDisplayName

        Item type = stack.func_77973_b();             // getItem
        if (type != null && type.getClass().getName().startsWith("danger.orespawn.")) return;

        this.field_70292_b = SONDPLAYTWEAKS$AGE_30_SECONDS;
    }
}
