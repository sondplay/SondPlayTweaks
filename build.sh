#!/bin/bash
# Build do SondPlayTweaks — javac na mao, sem gradle.
#
# Por que sem gradle: o ForgeGradle 1.2 oficial esta morto (403 nos assets desde
# 2022) e o RetroFuturaGradle baixaria MC+MCP inteiro. Nao precisamos: os mixins
# miram classes de MOD (nao ofuscadas) e metodos SRG que ja aparecem literais no
# bytecode, entao nao ha o que remapear e o refmap fica vazio.
#
# -proc:none desliga o annotation processor do Mixin de proposito — ele so serve
# pra gerar esse refmap.
#
# O layout e src/main/java + src/main/resources justamente pra que migrar pro
# RetroFuturaGradle um dia seja so adicionar o build.gradle.
set -e
cd "$(dirname "$0")"

# Manter em sincronia com SondPlayTweaks.VERSION (o script confere abaixo).
MCVER='1.7.10'
VERSION='0.5.3'

# ORDEM DO CLASSPATH IMPORTA.
#
# Em producao o 1.7.10 roda com nomes de CLASSE em MCP (net.minecraft.entity.Entity) mas nomes de
# MEMBRO em SRG (func_145782_y, field_70173_aa). Como nao geramos refmap, o bytecode que emitimos
# precisa ja citar SRG — senao da NoSuchMethodError em runtime.
#
#   deobfed.jar   vanilla com membros SRG      <- TEM QUE VIR PRIMEIRO
#   forgeSrc.jar  vanilla com membros MCP, e as classes do Forge/FML
#
# As duas trazem net.minecraft.*; com o deobfed na frente, o javac resolve vanilla pelo SRG. As
# classes de cpw.mods.fml.* e net.minecraftforge.* so existem no forgeSrc, e nao sao ofuscadas,
# entao vem de la sem problema.
#
# (Era isso que o suspatch antigo contornava com reflexao em tudo: sem um jar SRG no classpath,
#  chamar um metodo do vanilla direto nao tinha como funcionar.)
DEOBF='C:/PolyMC/instances/Paraiso Fiscal/HeapCleaner/forge-src/build/tmp/deobfuscateJar/deobfed.jar'
FORGE='C:/Users/$ondPlay/.gradle/caches/minecraft/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forgeSrc-1.7.10-10.13.4.1614-1.7.10.jar'
LOG4J='C:/PolyMC/libraries/org/apache/logging/log4j/log4j-api/2.0-beta9-fixed/log4j-api-2.0-beta9-fixed.jar'
MODS='C:/PolyMC/instances/Modpack Edredom/.minecraft/mods'
MIXIN="$MODS/+unimixins-all-1.7.10-0.3.1.jar"
ORESPAWN="$MODS/Ore-Spawn-Mod-1.7.10.jar"

SRC='src/main/java'
RES='src/main/resources'
OUTJAR="../SondPlayTweaks-$MCVER-$VERSION.jar"

# guarda contra jar publicado anunciando versao diferente da do build
DECLARADA=$(grep -oP 'VERSION\s*=\s*"\K[^"]+' "$SRC/com/sondplay/tweaks/SondPlayTweaks.java")
if [ "$DECLARADA" != "$VERSION" ]; then
    echo "ERRO: build.sh diz $VERSION, SondPlayTweaks.java diz $DECLARADA"
    exit 1
fi

for f in "$DEOBF" "$FORGE" "$LOG4J" "$MIXIN" "$ORESPAWN"; do
    [ -f "$f" ] || { echo "FALTANDO no classpath: $f"; exit 1; }
done

echo "== compilando =="
rm -rf OUT && mkdir -p OUT
find "$SRC" -name '*.java' > .sources
javac -encoding UTF-8 -source 8 -target 8 -nowarn -proc:none \
      -cp "$DEOBF;$FORGE;$LOG4J;$MIXIN;$ORESPAWN" -d OUT @.sources
rm -f .sources

echo "== empacotando =="
cp -r "$RES"/. OUT/
rm -f "$OUTJAR"
jar cfm "$OUTJAR" MANIFEST.MF -C OUT .

echo "== conteudo =="
unzip -l "$OUTJAR" | grep -E '\.class|\.json|MANIFEST'
echo ""
echo "OK -> SondPlayTweaks-$MCVER-$VERSION.jar"
