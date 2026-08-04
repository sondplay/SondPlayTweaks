package com.sondplay.tweaks.mixins.late;

import com.sondplay.tweaks.Cfg;
import com.sondplay.tweaks.Log;
import com.sondplay.tweaks.Stats;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Impede que as folhas do OreSpawn se apaguem sozinhas.
 *
 * PROBLEMA (confirmado em bytecode, comparado com o vanilla):
 *   As 4 classes de folha do OreSpawn sobrescrevem func_149674_a e implementam
 *   um decay proprio que difere do vanilla em dois pontos:
 *
 *     vanilla                              OreSpawn
 *     -----------------------------------  ------------------------------------
 *     if ((meta & 8) == 0) return;         (nao existe — roda todo random tick)
 *     alcance 4, com flood fill BFS        alcance 2, linha direta Manhattan <= 3
 *     folha encadeia por outras folhas     folha precisa ver o tronco direto
 *
 *   Numa arvore colossal do mod, a folha da copa esta a mais de 3 de distancia
 *   Manhattan de qualquer tronco e nao tem como encadear. Ela nao decai porque
 *   algo aconteceu — decai porque nasceu longe demais. Toda a copa de todas as
 *   arvores grandes esta nessa condicao ao mesmo tempo, e o resultado e o
 *   sintoma relatado: arvores se desmontando sozinhas, sem interacao nenhuma.
 *
 * POR QUE CANCELAR removeLeaves E NAO func_149674_a:
 *   Os drops e as transformacoes do bloco (garrafa de XP das Experience Leaves,
 *   ciclo Scary <-> Apple pela hora do dia) estao DENTRO do ramo "achei tronco"
 *   do proprio func_149674_a. Cancelar o metodo inteiro removeria essas features
 *   junto. Cancelando so removeLeaves, tudo continua funcionando e a folha
 *   simplesmente nunca e apagada.
 *
 *   NOTA: isto NAO reduz a varredura de ~63 getBlock por random tick. Essa parte
 *   fica em aberto de proposito — nunca foi medido quanto dos 17,48% de
 *   updateBlocks e folha do OreSpawn, e cortar a varredura exigiria reescrever
 *   os 4 corpos a mao (risco de errar limite de rand / hora do dia).
 *
 * As 4 classes declaram `private void removeLeaves(World, int, int, int)` com
 * assinatura identica, entao um mixin so cobre todas.
 *
 * Nao ha outro mod no pack tocando nessas classes (verificado nos 188 jars),
 * entao nao ha risco de colisao de injecao.
 */
@Mixin(targets = {
        "danger.orespawn.BlockAppleLeaves",
        "danger.orespawn.BlockCrystalLeaves",
        "danger.orespawn.BlockExperienceLeaves",
        "danger.orespawn.BlockScaryLeaves"
})
public class MixinOreSpawnLeaves {

    @Inject(method = "removeLeaves", at = @At("HEAD"), cancellable = true)
    private void sondplaytweaks$manterFolha(World world, int x, int y, int z, CallbackInfo ci) {
        if (!Cfg.orespawnLeaves) return;
        Stats.leafBlocked.increment();
        if (Cfg.verbose) {
            Log.verbose("leaves: blocked removal of " + this.getClass().getSimpleName()
                    + " at " + x + "," + y + "," + z
                    + " dim " + (world.field_73011_w == null ? "?" : world.field_73011_w.field_76574_g));
        }
        ci.cancel();
    }
}
