@echo off
REM Compila REDXela sin Maven (requiere JDK 17 o superior) y genera redxela.jar
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\clases
dir /s /b src\main\java\*.java > build\fuentes.txt
javac --release 17 -encoding UTF-8 -d build\clases @build\fuentes.txt || goto :error
xcopy /e /i /q src\main\resources build\clases > nul
echo Main-Class: gt.usac.cunoc.redxela.RedXela> build\MANIFEST.MF
jar cfm redxela.jar build\MANIFEST.MF -C build\clases . || goto :error
echo Listo: redxela.jar  -^>  java -jar redxela.jar
goto :eof
:error
echo Error de compilacion
exit /b 1
