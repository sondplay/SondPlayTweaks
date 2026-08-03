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
VERSION='0.2.0'

FORGE='C:/Users/$ondPlay/.gradle/caches/minecraft/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forgeSrc-1.7.10-10.13.4.1614-1.7.10.jar'
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

for f in "$FORGE" "$MIXIN" "$ORESPAWN"; do
    [ -f "$f" ] || { echo "FALTANDO no classpath: $f"; exit 1; }
done

echo "== compilando =="
rm -rf OUT && mkdir -p OUT
find "$SRC" -name '*.java' > .sources
javac -encoding UTF-8 -source 8 -target 8 -nowarn -proc:none \
      -cp "$FORGE;$MIXIN;$ORESPAWN" -d OUT @.sources
rm -f .sources

echo "== empacotando =="
cp -r "$RES"/. OUT/
rm -f "$OUTJAR"
jar cfm "$OUTJAR" MANIFEST.MF -C OUT .

echo "== conteudo =="
unzip -l "$OUTJAR" | grep -E '\.class|\.json|MANIFEST'
echo ""
echo "OK -> SondPlayTweaks-$MCVER-$VERSION.jar"
