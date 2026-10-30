# Villagers: Repurposed

- Fabric, Minecraft 26.2, Java 25, offizielle Namen. Versionsangaben in `gradle.properties` und `build.gradle` sind maßgeblich.
- Gemeinsamer Stand: zuerst `AI-HANDOFF.md`, dann `docs/plans/2026-10-29__master-features.md` lesen. Der vollständige Master-Prompt bleibt beauftragt; offene Punkte bleiben als offen markiert.
- GitHub-Repository: https://github.com/LvisXVI/Villager-Repurposed (privat, Hauptbranch `main`). Vorhandene Änderungen anderer Agenten bewahren; Dateibesitz bei paralleler Arbeit vereinbaren.
- Build: `gradlew.bat build --no-daemon` mit Java 25. Im Entwicklungsbetrieb werden Klassen direkt aus `build/classes` geladen. Vor jedem Compile oder Build den eigenen Dev-Server und Dev-Client stoppen; während des Spieltests kompiliert kein anderer Agent.
- Integration: eigene Welt `run/ctov`, verbundener Dev-Client, `time set 5000`, dann `/vptest090 run`. Nach Neustart `/vptest090 verify`. Die synthetische Testfläche ist kein Nachweis eines natürlich gewachsenen CTOV-Dorfs.
- Material-, Lebensmittel-, Geld- und Transportvorgänge müssen reale Inventare erhalten. Private Kisten sind geschützt. Produktion prüft geladene Rezepte und Zielkapazität.
- Gespeicherte Welten und laufende Bau-Cursor bewahren. Neue Zustände benötigen lesbare Defaults und sichere Neustart-/Abbruchpfade.
- `outputs` liegt im übergeordneten Workspace. JAR erst nach Prüfung kopieren und SHA-256 mit `build/libs` vergleichen.
- Ohne neue ausdrückliche Nutzeranweisung keine Computersteuerung (CUA). Datei-, Code-, RCON- und dokumentierte Dev-Testhooks bleiben für die Modarbeit nutzbar.
- Vor Abschluss `AI-HANDOFF.md` und aktive Taskdateien mit tatsächlichen Prüfungen und dem nächsten konkreten Schritt aktualisieren. Vollpack-Kompatibilität nur nach entsprechendem Lauf behaupten.

## Cursor Cloud specific instructions

- JDK 25 (Temurin 25.0.5+8) ist unter `/usr/lib/jvm/temurin-25` installiert und das Standard-`java`. `JAVA_HOME` setzt `/etc/profile.d/java25.sh`. Der Windows-Pfad `..\_tools\jdk` existiert hier nicht.
- Linux-Build im Repo-Root: `./gradlew build --no-daemon`. Wenn die Shell `gradlew` nicht ausführt: `chmod +x ./gradlew`. Dev-Server und Dev-Client vorher stoppen.
- Dev-Server: `./gradlew runServer`. Der erste Start legt `run/` an und verlangt `eula=true` in `run/eula.txt`. `/vptest090` läuft nur in namentlich freigegebenen Entwicklungswelten und startet nicht beim Boot.
- Keine Secrets. Gradle lädt Abhängigkeiten von `services.gradle.org`, `maven.fabricmc.net` und Mojang.

