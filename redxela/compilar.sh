#!/usr/bin/env bash
# Compila REDXela sin Maven (solo requiere JDK 17 o superior) y genera redxela.jar
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/clases
javac --release 17 -encoding UTF-8 -d build/clases $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/clases/
printf "Main-Class: gt.usac.cunoc.redxela.RedXela\n" > build/MANIFEST.MF
jar cfm redxela.jar build/MANIFEST.MF -C build/clases .
echo "Listo: redxela.jar  ->  java -jar redxela.jar"
