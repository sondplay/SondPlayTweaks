package com.sondplay.tweaks.mixins.early;

import com.sondplay.tweaks.Cfg;
import com.sondplay.tweaks.Log;
import com.sondplay.tweaks.Stats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * Gives OreSpawn entities the path-recompute cooldown and failure backoff that vanilla mobs have
 * and this mod skipped.
 *
 * THE PROBLEM, MEASURED. A 20-minute profile with hundreds of OreSpawn bosses fighting, server
 * thread at 75.96 ms/tick against a 50 ms budget:
 *
 *     GiantRobot.updateAITasks  7.87 ms  ->  tryMoveToEntityLiving  7.12 ms
 *     Hammerhead.updateAITasks  4.40 ms  ->  tryMoveToEntityLiving  4.04 ms
 *     Godzilla.updateAITasks    3.36 ms  ->  tryMoveToEntityLiving  1.82 ms
 *                                            ----------------------------
 *                                            12.98 ms/tick from three classes
 *
 * All three have the same shape underneath: findPathOptions -> getSafePoint -> getVerticalOffset
 * -> func_82565_a -> World.getBlock, 8.57 ms of the 12.98 spent reading blocks inside A*.
 *
 * WHY IT COSTS THAT MUCH. It is not that OreSpawn paths more often than vanilla — its callers are
 * gated behind their own nextInt rolls. It is that when a target cannot be reached, **the cost
 * never goes down**. Vanilla's EntityAIAttackOnCollide keeps a delay counter and a failure
 * penalty: each attempt whose path stops short of the target adds 10 ticks to the next wait, so a
 * mob stuck behind a wall degrades to trying roughly every 19, then 34, then 49 ticks. OreSpawn
 * calls PathNavigate directly out of updateAITasks with neither, so an unreachable target is
 * re-pathed at full rate forever. With hundreds of bosses shoving each other, most targets are
 * unreachable most of the time.
 *
 * WHAT THIS DOES. Reproduces vanilla's arithmetic at the navigator level, for OreSpawn entities
 * only:
 *
 *     path only when   delay <= 0 && (no remembered target
 *                                     || target moved >= 1 block
 *                                     || rand < 0.05)
 *     after pathing    delay = failPenalty + 4 + rand(7)
 *                      +10 if the target is beyond 32 blocks, +5 beyond 16
 *                      +15 if the call itself returned false
 *     failPenalty      += 10 when the path stopped short of the target
 *                      =  0  when it reached
 *
 * The 5% roll is not decoration. Without it a mob whose target sits still would hold a stale path
 * until the delay expired; it is the only thing that breaks a cooldown early, and dropping it is
 * how a naive cooldown turns into a frozen mob.
 *
 * WHEN SKIPPING, THIS RETURNS !noPath() RATHER THAN false. The return value means "am I moving
 * toward it", and vanilla's own EntityAIAttackOnCollide reads it to decide whether to add its
 * 15-tick penalty. Answering a flat false would tell every caller the mob had given up while it
 * was in fact still walking a perfectly good path.
 *
 * SCOPE. The gate only engages for entities whose class sits under danger.orespawn. Every other
 * mob in the game reaches this method, so the check is a single byte field read after the first
 * call per navigator, and vanilla AI keeps its own untouched backoff.
 *
 * DEVIATION FROM VANILLA. failPenalty is capped. Vanilla lets it grow without bound and gets away
 * with it because acquiring a new target restarts the AI task and resets the counter. There is no
 * equivalent signal at this level, so an permanently unreachable target would otherwise push the
 * delay up forever and eventually stop the mob from pathing at all.
 */
@Mixin(PathNavigate.class)
public abstract class MixinPathNavigateThrottle {

    /** theEntity */
    @Shadow private EntityLiving field_75515_a;

    /** noPath() */
    @Shadow public abstract boolean func_75500_f();

    /** getPath() */
    @Shadow public abstract PathEntity func_75505_d();

    private static final Random SONDPLAYTWEAKS$RNG = new Random();

    /** Ticks of delay left before another path is allowed. Vanilla calls this field_75445_i. */
    private int sondplaytweaks$delay;

    /** Vanilla's failedPathFindingPenalty. Capped — see the class comment. */
    private int sondplaytweaks$failPenalty;
    private static final int SONDPLAYTWEAKS$MAX_PENALTY = 100;

    /** 0 = not yet determined, 1 = OreSpawn entity, 2 = anything else. */
    private byte sondplaytweaks$scope;

    private boolean sondplaytweaks$haveLastTarget;
    private double sondplaytweaks$lastX;
    private double sondplaytweaks$lastY;
    private double sondplaytweaks$lastZ;

    @Inject(method = "func_75497_a", at = @At("HEAD"), cancellable = true)
    private void sondplaytweaks$gateEntity(Entity target, double speed,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (target == null) return;
        sondplaytweaks$gate(target.field_70165_t, target.field_70163_u, target.field_70161_v, cir);
    }

    @Inject(method = "func_75492_a", at = @At("HEAD"), cancellable = true)
    private void sondplaytweaks$gateXYZ(double x, double y, double z, double speed,
                                        CallbackInfoReturnable<Boolean> cir) {
        sondplaytweaks$gate(x, y, z, cir);
    }

    @Inject(method = "func_75497_a", at = @At("RETURN"))
    private void sondplaytweaks$recordEntity(Entity target, double speed,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (sondplaytweaks$scope != 1 || target == null) return;
        sondplaytweaks$record(target.field_70165_t, target.field_70163_u, target.field_70161_v,
                              cir.getReturnValueZ());
    }

    @Inject(method = "func_75492_a", at = @At("RETURN"))
    private void sondplaytweaks$recordXYZ(double x, double y, double z, double speed,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (sondplaytweaks$scope != 1) return;
        sondplaytweaks$record(x, y, z, cir.getReturnValueZ());
    }

    /**
     * Decides whether this call is allowed to run A*. Cancels with the honest "am I still moving"
     * answer when it is not.
     */
    private void sondplaytweaks$gate(double tx, double ty, double tz,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!Cfg.orespawnPathThrottle) return;

        if (sondplaytweaks$scope == 0) {
            EntityLiving e = this.field_75515_a;
            boolean scoped = e != null && e.getClass().getName().startsWith("danger.orespawn.");
            sondplaytweaks$scope = scoped ? (byte) 1 : (byte) 2;
            if (scoped) {
                Stats.pathNavigatorsScoped.increment();
                Log.verbose("path: now throttling " + e.getClass().getSimpleName());
            }
        }
        if (sondplaytweaks$scope != 1) return;

        if (sondplaytweaks$delay > 0) sondplaytweaks$delay--;

        boolean allowed = sondplaytweaks$delay <= 0
                && (!sondplaytweaks$haveLastTarget
                    || sondplaytweaks$distSq(tx, ty, tz,
                                             sondplaytweaks$lastX,
                                             sondplaytweaks$lastY,
                                             sondplaytweaks$lastZ) >= 1.0D
                    || SONDPLAYTWEAKS$RNG.nextFloat() < 0.05F);

        if (allowed) {
            Stats.pathRan.increment();
        } else {
            Stats.pathSkipped.increment();
            if (Cfg.verbose) {
                Log.verbose("path: skipped for " + sondplaytweaks$name()
                        + " delay=" + sondplaytweaks$delay
                        + " penalty=" + sondplaytweaks$failPenalty);
            }
            cir.setReturnValue(!this.func_75500_f());
        }
    }

    /** Runs only when the gate let the call through. Sets the next delay from the outcome. */
    private void sondplaytweaks$record(double tx, double ty, double tz, boolean result) {
        sondplaytweaks$lastX = tx;
        sondplaytweaks$lastY = ty;
        sondplaytweaks$lastZ = tz;
        sondplaytweaks$haveLastTarget = true;

        // Vanilla computes the delay from the penalty as it stood *before* this attempt.
        sondplaytweaks$delay = sondplaytweaks$failPenalty + 4 + SONDPLAYTWEAKS$RNG.nextInt(7);

        boolean reached = false;
        PathEntity path = this.func_75505_d();
        if (path != null) {
            PathPoint end = path.func_75870_c();
            if (end != null) {
                reached = sondplaytweaks$distSq(end.field_75839_a, end.field_75837_b,
                                                end.field_75838_c, tx, ty, tz) < 1.0D;
            }
        }
        if (reached) {
            sondplaytweaks$failPenalty = 0;
            Stats.pathReached.increment();
        } else {
            if (sondplaytweaks$failPenalty < SONDPLAYTWEAKS$MAX_PENALTY) {
                sondplaytweaks$failPenalty += 10;
            }
            Stats.pathFailed.increment();
        }
        if (sondplaytweaks$failPenalty > Stats.pathPeakPenalty) {
            Stats.pathPeakPenalty = sondplaytweaks$failPenalty;
        }

        EntityLiving self = this.field_75515_a;
        if (self != null) {
            double d = sondplaytweaks$distSq(self.field_70165_t, self.field_70163_u,
                                             self.field_70161_v, tx, ty, tz);
            if (d > 1024.0D) sondplaytweaks$delay += 10;
            else if (d > 256.0D) sondplaytweaks$delay += 5;
        }

        if (!result) sondplaytweaks$delay += 15;

        if (Cfg.verbose) {
            Log.verbose("path: ran for " + sondplaytweaks$name()
                    + " reached=" + reached
                    + " result=" + result
                    + " -> delay=" + sondplaytweaks$delay
                    + " penalty=" + sondplaytweaks$failPenalty);
        }
    }

    private String sondplaytweaks$name() {
        EntityLiving e = this.field_75515_a;
        return e == null ? "?" : e.getClass().getSimpleName();
    }

    private static double sondplaytweaks$distSq(double ax, double ay, double az,
                                                double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }
}
