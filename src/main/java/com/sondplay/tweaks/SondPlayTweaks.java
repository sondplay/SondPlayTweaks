package com.sondplay.tweaks;

import cpw.mods.fml.common.Mod;

/**
 * SondPlayTweaks — patches de performance e correcao do modpack Saphira III.
 *
 * Container vazio de proposito. Todo o trabalho e feito por Mixin, declarado
 * em mixins.sondplaytweaks.json. Esta classe existe so pra o FML reconhecer
 * o jar como mod (FMLCorePluginContainsFMLMod no manifest).
 *
 * O modid precisa ser plano (o Forge 1.7.10 reclama de maiuscula e de simbolo);
 * o nome de exibicao acompanha: SondPlayTweaks.
 */
@Mod(modid = SondPlayTweaks.MODID,
     name = SondPlayTweaks.NAME,
     version = SondPlayTweaks.VERSION,
     acceptableRemoteVersions = "*")
public class SondPlayTweaks {
    public static final String MODID   = "sondplaytweaks";
    public static final String NAME    = "SondPlayTweaks";
    public static final String VERSION = "0.1.0";
}
