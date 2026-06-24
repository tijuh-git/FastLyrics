s, maintoin@echo off
REM Script de compilation et installation sur le téléphone
REM Utiliser: double-cliquer sur ce fichier ou: cmd /c build_and_install.bat

cd /d "%~dp0"
echo.
echo ===========================================
echo Compilation FastLyrics Fork - Debug APK
echo ===========================================
echo.

REM Nettoyage complet
echo Nettoyage du cache...
call gradlew.bat clean

REM Compilation
echo.
echo Compilation en cours (cela peut prendre 2-3 minutes)...
call gradlew.bat assembleDebug

REM Vérification du résultat
echo.
echo Recherche de l'APK...
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo ✓ APK créée avec succès!
    echo Chemin: %cd%\app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo Prochaines étapes:
    echo 1. Branche ton téléphone en USB
    echo 2. Active le "Debug USB" dans les options développeur
    echo 3. Double-clique sur l'APK ci-dessus pour installer
    echo.
    pause
) else (
    echo.
    echo ✗ Erreur: l'APK n'a pas pu être créée.
    echo Vérifie la sortie du build ci-dessus pour les erreurs.
    echo.
    pause
    exit /b 1
)

