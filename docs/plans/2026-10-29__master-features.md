---
type: plan
description: Fortsetzung aller offenen Funktionen des Villages-Repurposed-Master-Prompts.
status: active
archived: false
work_status: in-progress
chronicle: docs/chronicles/2026-10-29__master-features
---

# Master-Prompt fortsetzen

Gründe: [Entscheidungen](../chronicles/2026-10-29__master-features.md).

## Ergebnis und Grenzen

Der Nutzer autorisierte am 29.10.2026 ausdrücklich die Fortsetzung aller offenen Punkte. Bestehender Fabric-26.2-Code und gespeicherte Welten bleiben Ausgangspunkt. Ältere Handoff-Aussagen sind kein Funktionsnachweis. Kein Git-Repository; keine Veröffentlichung beauftragt. Referenzbilder 2–4 liegen diesem Chat nicht vor; visuelle Umsetzung orientiert sich bis dahin an vorhandenen Ingame-Assets.

## Arbeit

- [x] Karawanenfracht echt laden/entladen und Beziehungs-/Schulfehler beheben. Dateien: `village/Caravan.java`, `job/School.java`, `job/JobManager.java`. Nachweis: 311 Serverassertionen und realer Saatguttransfer.
- [x] Nachbarbewohner aus überlappenden Dorf-Radien herausfiltern, Cache bei Meeting-Point-Wechsel invalidieren; Ein-Bewohner-Weiler wählen einen autonomen Bauhelfer. Kleine Dörfer planen ab zwei Bewohnern Kornspeicher → Werkzeugkammer → Schule → Küche; Bahnhof bleibt Spätphase. Nachweis: `DevelopmentChecks` und 387 Serverchecks.
- [x] Plaza um symmetrische Blumenbeete ergänzen; Karte auf die erweiterte, gespeicherte Siedlungsgrenze zentrieren und Dekor-Caches nach Dimension trennen. Nachweis: aktueller CTOV-Karten-/Plaza-Screenshot und Karten-Clientcheck.
- [ ] Dauerhafte Baustellenverwaltung, tatsächliche Bauphasen und Gerüstabbau. Dateien: `build/ConstructionSite.java`, `build/Builder.java`, `village/VillageStats.java`. Prüfung: alte Cursor unverändert, Neustart/Abbruch, Materialerhaltung.
- [ ] Rezeptverwaltung statt ausschließlich fester Rezeptlisten. Dateien: `job/Craft.java`, `job/Workshop.java`, neue Rezeptadapter. Prüfung: Tags, Mengen, Restbehälter, fehlende Zutaten, voller Zielbestand.
- [ ] Koch-/Tavernenversorgung. Dateien: `job/CookJob.java`, `village/Tavern.java`, `job/JobManager.java`, `job/Routine.java`. Prüfung: echte Zutaten/Portionen, erkannte Taverne, kein Warenduplizieren.

## Fortschritt 30.10.2026 – Karawanen-/Ablieferungswege

- [x] Allgemeine Warenablage verliert ihren Timer nicht mehr bei jedem Tick; lange Dorfwege nutzen `approachLong`.
- [x] Karawanenträger wählen neben Esel/Depot einen freien Standplatz, den die begrenzte Wegsuche erreichen kann; Entity-Unload ist vor Abholung null-sicher.
- [x] Auf `run/vp-k04-fresh17-2026-10-30`: JDK-25-Build erfolgreich, `/vptest090 run` **847 Checks PASS**, sauberer Neustart + `/vptest090 verify`: **`VP090 persistence PASS`**. Welt erhalten; `server.properties` zurückgesetzt.
- [ ] Natürlichen CTOV-Porterweg samt passendem Kornspeicher bis zur Karawanenabreise verfolgen. Seedware bleibt ohne registriertes Food-Depot ungeliefert.
- [ ] CTOV-Höhlen-/Minen-Erstickungsfälle untersuchen; danach Meilensteinfolge und Mauer um ursprüngliche Bebauung plus Neubauten weiter live verfolgen.
- [ ] Gesamt-Audit: weiterhin **8/62 vollständig, 54/62 teilweise/offen**; vollständige Umsetzung nicht behaupten.

## Fortschritt 30.10.2026 – sichere Minenbewegung

- [x] `Mining.teleport` lehnt Mine-Zellen ab, wenn Boden, Flüssigkeiten oder die vollständige Villager-Kollisionsbox unsicher sind. Ein blockierter Abstieg schließt die Mine; Rückwege suchen einen freien Oberflächenausstieg.
- [x] JDK-25 `compileJava` mit stabilem Temp-Build: **BUILD SUCCESSFUL**. Weltprüfung für diese Änderung steht noch aus.
- [ ] Die zwei geloggten Erstickungsfälle waren auf der synthetischen Prüffläche `[12024, 201, 12000]`, nicht in einer CTOV-Mine. Ursache und möglicher Testaufbau-Blocker bleiben offen; der Minen-Kollisionsschutz ist dafür kein bestätigter Fix.
- [ ] Im frischen CTOV-Weltlauf sichere Minenbewegung und natürlichen Porter-/Karawanenablauf prüfen; danach Meilenstein-Baukette und Mauer um Alt- und Neubauten verfolgen.
- [x] Kartenzoom, Pan und Reset in `client/BoardScreen.java`; vorheriger Client-Event-Check bestand. Visuelle Rand-/Auswahlprüfung im Client bleibt offen.
- [ ] Berufsmatrizen vervollständigen: Landwirtschaft/Metzger-Erweiterung, Schmiedreparaturen, Köhler, Sanitäter, Straßenbauer, Wache. Dateien: `job/*`, `village/Staffing.java`. Je Beruf Bedarf, tatsächliche Arbeit, Wartung und Alternativtask prüfen.
- [ ] Überlandbau und Bahnlogistik mit beidseitigen Ressourcen-/Einwohnerbedingungen, echten Güter-/Personenloren. Dateien: `village/Roads.java`, `village/TradeNetwork.java`, neue Transportverwaltung. Prüfung: Neustart, beide Endpunkte, Kapazität, Frachtbilanz.
- [x] Farmzonen binden Farmer-Clusterplanung an die komplette Parzelle; ohne gültige, freie Zone kein Außen-Fallback. Wohn-/Arbeits-/Weidezonen steuern Bauplätze; PEN-Blueprints verwenden `pasture`; die Tierhaltung zählt/füttert nur Tiere innerhalb der Weidezone. Farmer-Sekundärarbeit baut ein Feld tatsächlich im Tick. Nachweis: zwölf Bauzonenfälle einschließlich PEN-Suche, acht Farmplanungsfälle, drei Farmer-Zyklusfälle und acht Livestock-Tick-Fälle im 740er Lauf; Neustart-Persistenz bestanden. Die visuelle Zonenbearbeitung und eine natürliche CTOV-Farm/Tierhaltung bleiben offen.
- [x] Transaktionaler Gebäudeumzug: zwölf In-World-Checks decken Granary auf Naturboden, nachträglich eingerichtete Truhe, Material-/Drop-Bilanz, Registrierungen, blockiertes Ziel, Fehler nach Teilplatzierung mit vollständigem Rollback sowie überlappenden Ein-Block-Shift ab. Der Tafel-Clientfluss/HOME-JOB_SITE-Beobachtung bleibt offen.
- [ ] Dorf-Layout/Meilensteine auch für kleine Dörfer, Minenzugang und Terrain-Lifecycle vervollständigen. Dateien: `build/Builder.java`, `village/Sites.java`, `job/Mining.java`, `village/Demolition.java`. Prüfung im frischen CTOV-Dorf.
- [ ] Rollenoptik/Tafelblock und Modpack-Integration vervollständigen. Dateien: `client/*`, `mixin/*`, `assets/villagerplus/*`, `Compat.java`. Prüfung mit tatsächlich installierten Modversionen; keine pauschale EMF/ETF-Freigabe ohne Lauf.

## Zusatzanforderung aus der Fortsetzung

- [x] Ausschließlich Villages: Repurposed-System-/Overlaymeldungen erhalten goldfarbenes ◆ und grünes `DORF`-Badge; Client zeigt zusätzlich eine animierte HUD-Karte nur für den Mod-Prefix. Vanilla-/Spielerchat bleibt unverändert. Dev-Client-Sichtprüfung: `run/screenshots/2026-10-30_16.32.56.png`.
- [x] G03/G04-Konventionstag-Routing auf frischer CTOV-Welt `vp-k04-fresh10-2026-10-30`: 824 Checks PASS; danach Neustartprüfung `VP090 persistence PASS`. Esel-Persistenz beim Laden erneut setzen.
- [ ] G04/G05: konkrete Farmer’s-/Chef’s-Delight-Rezepte (derzeit nicht im installierten Test-Pack), Ersatzherstellung und kompatible Mod-Ausrüstung Ende-zu-Ende ergänzen.
- [x] G05-Schmiedereparatur: `EquipmentMaintenance` repariert beschädigte Ausrüstung aus der registrierten Werkzeugkammer an einem echten Amboss und verbraucht ein kompatibles Teil. Drei Reparaturchecks bestanden im frischen Weltlauf `vp-k04-fresh11-2026-10-30`.
- [x] Karawanen-Neustart-Fixture korrigiert: in `Checks090` wird der entfernte Esel-Chunk gezielt geladen und bis zur Neustartprüfung erzwungen; der ItemDisplay wird nach dem Reload direkt über die Esel-Passagiere geprüft. Frische CTOV-Welt `vp-k04-fresh12-2026-10-30`: `/vptest090 run` **828 Checks PASS**, sauberer Stopp/Neustart plus `/vptest090 verify`: **`VP090 persistence PASS`**. Drei vollständige JDK-25-Gradle-Builds nach den Fixture-Anpassungen erfolgreich.
- [ ] Der Fixture-Chunk bleibt für den geprüften Neustart erzwungen; normales Chunk-Entladen mit späterem Neuladen ist durch diesen Lauf nicht belegt.

## Integrierte Umsetzung 29.10.2026

- [x] Zeitsperre für Notfall-Berufswechsel mit persistentem, eigenständigem Attachment; ursprünglicher Beruf/Level wird nach einem Tag wiederhergestellt. `StaffingChecks` im vollständigen Lauf.
- [x] Erschöpfte Mine bleibt als alte Mine markiert; beschilderte, gewachste Barrikade und Kartenmarker getestet.
- [x] Karten-Zonen, sichere Gebäudeversetzungs-API, Berufsquoten, Großbau-Zustimmung und Handelsbilanz in den Tafel-/Netzwerkfluss eingebunden; gezielte Persistenz-/Grenzchecks.
- [x] Einheitliche Straßenroute, beidseitige Bahnfreigabe mit Metallurgiebestand, echte Gütertruhenloren und physischer Villager-Passagiertransport implementiert; Routen-/Frachtlogik und Persistenz geprüft.
- [x] Feldbedarfsgrenze/Feldcluster, Tierfutter- und Acht-Tier-Zuchtgrenze, geschützte natürliche Holzernte, Kohleofen-Chargen und stufenweiser Abbruchfortschritt ergänzt.
- [x] Integrierter JDK-25-Build und `/vptest090 run` (740 Checks) in `run/vp-master-test-2026-10-29`; `/vptest090 verify` nach sauberem Serverneustart bestanden. Enthält acht Farmplanungs-, zwölf Haus-/Arbeitszonen-, eine PEN-Bauplatz-, drei Farmer-Zyklus-, acht Livestock-Zonen- sowie zwölf Gebäudeversetzungs-Worldchecks; die Prüfwelt ist gespeichert; Dev-Prozesse gestoppt.

Diese Arbeiten erfüllen nicht das gesamte Master-Prompt. Besonders physische Bahnfahrten, erfolgreiche Gebäudeverschiebung im Tafel-Client, mehrteilige Berufs-End-to-End-Zyklen, vollständige sichtbare Bauphasen im Spiel, fehlende Rollen/Skins und ungeprüfte Modpack-Integrationen bleiben offen.

## Prüfungen und aktueller Schritt

Nächster Schritt: den Tafel-Clientfluss des Gebäudeumzugs samt HOME/JOB_SITE-Änderung beobachten; danach in einem 2–3-Bewohner-CTOV-Dorf die Kerngebäude-Folge und den Mauerbau über generierte Struktur plus Neubauten praktisch bis zum Abschluss verfolgen. JDK-25-Build erfolgreich; integrierter Testweltlauf **740 Checks**, danach sauberer Neustart und Persistenzprüfung bestanden. Ein einzelnes CTOV-Haus wurde nach einer Mittagspause fertig gebaut. Ein Teil der CTOV-Generation besitzt mehrere nahe Glocken; die Tafel pro Glocke zeigt nur deren tatsächlich zugeordnete Bewohner. Vollständiger Modpack-Test und Survival-Echtlauf stehen aus.

## Ergänzung 30.10.2026 – Meilensteinwartefehler
- [x] Verhindert, dass die Schule fehlende Kornspeicher-/Werkzeugkammermaterialien durch Abbruch der Voraussetzungsschritte endlos preemptet; volle Dorfkiste behält ihren Lager-Vorrang. Fünf echte `Builder.tick`-Checks in `build/MilestoneWaitingChecks.java`.
- [x] JDK-25-Build; `/vptest090 run` 745 Checks und Neustart-`verify` bestanden.
- [ ] Natürlichen 2–3-Bewohner-CTOV-Kernzyklus bis Küche praktisch fertig beobachten; Materialien sind bisher nur für einen partiellen Kornspeichertest bereitgestellt.
- [ ] Stadtmauer inkl. CTOV-Bestand/Neubauten durch Radius-, Chunklade- und Spaltenintegritätstests absichern; physischer automatischer Bau bleibt ungeprüft.

## Ergänzung 30.10.2026 – Arbeitsstand und Händler-Modell
- [x] Baustellenstand vom Anker-Y entkoppelt: bis zu 13 erreichbare, trockene, unterstützte Standplätze am Gebäude; kein Material-/Cursorverlust, wenn keiner erreichbar ist. Acht Worldchecks und echter `Builder.tick`.
- [x] Eigenständiger Karawanenhändler als registrierte Entity mit Clientrenderer, eigenem Modell/Textur (Hut, Mantel, Pack) und DE/EN-Namen; visueller Quickplay-Clientcheck.
- [x] JDK-25-Build, `/vptest090 run`: 779 Checks; nach sauberem Neustart `/vptest090 verify`: `VP090 persistence PASS`.
- [ ] Tatsächliches KI-Laufen zum neuen Baustellenstand und Werkzeugkammerabschluss im CTOV-Weiler beobachten. Neue Karawanenoptik mit Fresh Animations/EMF/ETF prüfen.

## Ergänzung 30.10.2026 – Entfernungslimit und Gesamt-Backlog
- [x] Root cause des fernen Builder-Standorts gefunden: Vanilla `PathNavigation` begrenzt die Villager-Wegsuche auf FOLLOW_RANGE=16. `Builder.workPath` nutzt einen separaten begrenzten PathFinder, `continuePlan` gibt dem Brain kurze erreichbare Wegpunkte; keine dauerhafte Änderung an Nav-Attributen.
- [x] Vier Worldchecks für 59-Block-Route, erreichbaren Wegpunkt/Vanilla-Neuberechnung und unveränderten Zustand während Anlauf. JDK-25-Build erfolgreich; `/vptest090 run`: **783 Checks PASS**.
- [x] Live im gespeicherten CTOV-Testdorf: Maurer Ernst lief ohne Teleport vom Ursprung ca. `[18,70,301]` zum Baugelände `[73,65,275]`; derselbe Hausplan stieg Cursor 0→222.
- [ ] Hausabschluss, Meilensteinregistrierung sowie nachfolgende Kornspeicher-/Werkzeugkammer-/Schul-/Küchenfolge und Mauer end-to-end weiter prüfen.
- [x] Prüfwelt-Uhr fortgesetzt, gespeichert, sauber neu gestartet; `/vptest090 verify`: `VP090 persistence PASS`.
- [x] `docs/master-prompt-audit.md` als vollständige 62-ID-Matrix explizit markiert und nach zusammenhängenden Umsetzungsetappen geordnet. Bilanz: 8 implemented, 54 partial/offen.

## Fortsetzung 30.10.2026 – Karawanenpersistenz
- [x] `vp-k04-fresh12-2026-10-30`: vollständiger Lauf mit **828 Checks PASS**. Nach sauberem Stopp/Neustart bestand `/vptest090 verify` mit **`VP090 persistence PASS`**; 7 Eisen bleiben im Fixture-Esel und genau ein Frachtmarker ist direkt als Esel-Passagier wiederhergestellt.
- [x] `run/server.properties` auf `level-name=vp-master-test-2026-10-30` zurückgesetzt; Testwelt `fresh12` bleibt erhalten; Dev-Server und -Client beendet.
- [ ] Master-Prompt offen. Als Nächstes G04/G05-Rezept-/Werkzeugersatz ergänzen und danach die natürliche CTOV-Meilensteinfolge samt Mauerbau weiter bis zu einem praktischen End-to-End-Nachweis verfolgen.

## Fortsetzung 30.10.2026 – echte Schmiedeschmelze
- [x] `Workshop` erzeugt Stein/Glas nicht mehr durch eine synthetische Sofortumwandlung ohne Ofen.
- [x] `SmithSmelting` verwendet geladene Server-`SmeltingRecipe`-Rezepte, einen echten markierten Küchenofen, einen persistenten erwarteten Output und die Lager-/Sollmengen-Policy. Fremde Inputs und offene Köhlerchargen bleiben geschützt; bei voller Lagerkapazität bleibt der Output im Ofen.
- [x] Saubere Welt `run/vp-k04-fresh14-2026-10-30`: JDK-25-Gradle-Build, nach `time set 5000` `/vptest090 run` **835 Checks PASS** und nach sauberem Neustart `/vptest090 verify`: `VP090 persistence PASS`. Die sieben Ofenchecks umfassen Rezeptausgabe, Claim-Isolation, volle/teilweise Lagerkapazität und exakte Restzustellung. `server.properties` ist auf `vp-master-test-2026-10-30` zurückgesetzt; Welt bleibt gespeichert; Server/Client gestoppt.
- [ ] Noch kein laufender Schmied wurde beim tatsächlichen Schmelzen in einem CTOV-Weiler beobachtet. Werkzeugersatz, Schienen/Lorenherstellung, Kochtopfrezepte und zusätzliche Smoker-/Blast-Furnace-Rezepte bleiben offen.
- [ ] Nächster Schritt: realen Ofenlauf im Dorf beobachten, danach Werkzeugersatz über geladene Crafting-Rezepte und echte Werkzeugkammer-Kapazität ergänzen; anschließend zurück zur natürlichen Meilensteinfolge und Mauer.

## Fortsetzung 30.10.2026 – Modmeldungen im Chat
- [x] System- und Overlaymeldungen mit dem exakten `◆ DORF  ›`-Prefix bekommen eine eigene HUD-Toast-Karte: goldene Dorfmarke, dunkle Karte, slide-in, kurze Anzeige und fade-out. Der originale formatierte Systemchat bleibt erhalten; normale Spieler-/Vanilla-Meldungen laufen nicht durch den Toast.
- [x] Frischer Dev-Client-Sichtcheck mit eigener Testmeldung und Screenshot `run/screenshots/2026-10-30_16.32.56.png`; Gradle-Build erfolgreich.
- [ ] Der Toast bleibt auf Deutsch markiert („DORF“, „NEU“); Mehrsprachigkeit und Optionen für Position/Dauer sind nicht enthalten.

## Fortsetzung 30.10.2026 – frische CTOV-Diagnose
- [x] Frische Welt `run/vp-k04-fresh15-2026-10-30` gestartet; `#minecraft:village` fand `ctov:small/village_taiga_fortified` bei `[368, ~, 640]`. Das zugeordnete Dorf hatte 13 gezählte Bewohner, 0 Obdachlose, darunter Werkzeugschmied und Rüstungsschmied.
- [x] Builder-Log live geprüft: `Planpruefung: Einwohner 13, obdachlos 0` gefolgt von `Kein Bauplatz für 'granary' (halbe Kante 4, Höhe 6)`. Ohne registrierten Kornspeicher, Werkzeugkammer, Schule und Küche konnte der echte Schmied keine Küchenofencharge starten. Das ist ein belegter Bauplatz-Blocker, keine bestätigte Ofenfehlfunktion.
- [x] Neue Welt gespeichert; `server.properties` auf `vp-master-test-2026-10-30` zurückgestellt; Dev-Server und Dev-Client sauber beendet. Keine Quellcodeänderung und kein Build in dieser Diagnoserunde.
- [x] Systematische Suche nach Bauplätzen implementiert und im selben fresh15-Dorf verifiziert; `/vptest090 run`: 836 Checks PASS, echter granary-Plan mit neun Materialwünschen angelegt.

## Fortsetzung 30.10.2026 – systematische Bauplatzsuche
- [x] Begrenzte deterministische verteilte Suche für geladene Chunks ergänzt; Zonen, Rechteckprüfung und tolerante Suche auf unebenem Gelände bleiben berücksichtigt.
- [x] Frische CTOV-Welt `run/vp-k04-fresh15-2026-10-30`: `/vptest090 run` bestand mit **836 Checks PASS**. Der Fehler `Kein Bauplatz für granary` trat nach dem Fix nicht mehr auf; der Live-Builder legte im befestigten Taigadorf einen Kornspeicherplan mit neun konkreten Materialarten an.
- [x] Welt gespeichert, Dev-Server/-Client beendet und `run/server.properties` auf `vp-master-test-2026-10-30` zurückgesetzt.
- [ ] Echte Lieferungen und Bauabschluss verfolgen; danach Folge bis Werkzeugkammer/Schule/Küche, Mauerumfang und Neuladenachweis im selben Dorf prüfen. `/vptest090 verify` wurde in diesem Lauf nicht ausgeführt.
## Codex-Fortsetzung 30.10.2026 – Karawanenlager-Kategorien
- Der Live-Lauf auf `vp-k04-fresh15-2026-10-30` reproduzierte: ein Träger nahm den Smaragd aus dem ersten Esel-Slot, während 10 gewünschte Fichtenbretter im nächsten Slot lagen. Die bisherige Zustellung umging `StockPolicy` und zielte pauschal auf die Dorfkiste.
- `Caravan.porterDelivery` sucht nun einen lieferbaren Slot, ignoriert `OTHER` für Depottransporte und routet Baumaterial zur Dorfkiste bzw. ins Lager, Nahrung zum Kornspeicher sowie Ausrüstung zur Werkzeugkammer. Der physische Commit verwendet `StockPolicy.insertInto`; der reale Stapel bleibt bis zur Ankunft auf dem Esel.
- Wiederholbare Prüfplattform: `/vptest090 run` räumt jetzt nur die markierten Einheiten der synthetischen Testglocke auf. Frische Welt `run/vp-k04-fresh16-2026-10-30`: JDK-25-Build erfolgreich, **836 Checks PASS**, sauberer Serverneustart plus `/vptest090 verify`: **`VP090 persistence PASS`**. `server.properties` ist auf `vp-master-test-2026-10-30` zurückgestellt; beide Dev-Prozesse sind gestoppt.
- Das Audit bleibt bei **8/62 implemented, 54/62 partial/offen**: Smaragde haben noch keinen sicheren Dorftresor. Nächster Schritt: persistente Kassenbuchung implementieren, dann Transport und Baukette erneut in einem natürlichen CTOV-Dorf verfolgen.

## Codex-Fortsetzung 30.10.2026 – persistente Karawanenkasse
- Smaragd-Zustellung nutzt denselben sichtbaren Porter-Weg zur Glocke; erst bei sicherer Ankunft werden die physischen Stücke aus dem Esel entfernt und im persistenten `TradeNetwork.tradeBalance` als Dorfkasse verbucht. Die Materialkiste bleibt smaragdfrei.
- Künftige Karawanen und der Tierzukauf können den Kassenbestand zusätzlich zu vorhandenen Kisten-Smaragden ausgeben. Der Stand wird in der Dorftafel-Statistik angezeigt. `Network.of` überschreibt bei Partner-Reparatur keine vorhandene Bilanz oder Kasse.
- Erstlauf auf frischer CTOV-Welt fand und korrigierte die Slotreihenfolge: lieferbare Ware geht vor den Emeralds zur Kasse.
- `run/vp-k04-fresh17-2026-10-30`: `/vptest090 run` **842 Checks PASS**, sauberer Serverneustart + `/vptest090 verify`: **`VP090 persistence PASS`**, Dorfkasse danach exakt 5 Emeralds. Kaufprüfung: 32 Kies werden für einen real verbuchten Emerald geladen.
- Welt aufbewahrt, Dev-Server/-Client gestoppt und `server.properties` auf `vp-master-test-2026-10-30` zurückgesetzt. Nächster Schritt: vollständigen Händler- und Porterablauf in einem natürlich generierten CTOV-Dorf bis zum Karawanenabzug beobachten.
- Master-Audit bleibt 8/62 vollständig, 54/62 teilweise/offen; Gesamtziel weiterhin unvollständig.

## Fortsetzung 30.10.2026 – Minenabschluss, fresh18
- [x] Schacht wird auch bei frühem Abbruch erhalten; Verschluss wartet auf vollständig geladene, leere Haupt-/Seitenstollen. Keine lebende Einheit wird absichtlich eingeschlossen. Blockierte Standzellen/Wege nutzen geprüften Oberflächenausstieg.
- [x] Elf neue Weltchecks bestehen; Gesamtsuite auf neuer CTOV-Welt `vp-k04-fresh18-2026-10-30`: **858 Checks PASS**. Vollständiger JDK-25-Build und bestehende Neustartsuite **VP090 persistence PASS**.
- [x] fresh18 in beiden Entwicklungswelt-Freigaben ergänzt; sechs stillstehende Nachfolge-Fixtures gegen Erstickung geschützt. Die vorher geloggten Koordinaten stimmen mit ihrer Spawnreihe überein.
- [ ] Natürliche CTOV-Minenbewegung/Gelände und Minenzustand nach Reload gezielt beobachten; elf synthetische Weltchecks ersetzen diese Nachweise nicht. G12 bleibt partial.
- [ ] Nächster Funktionsschritt: echte Karawanenlieferung mit registriertem Kornspeicher bis zur Abreise, danach Meilensteinbaukette/Mauer. Gesamtstand 8/62 vollständig, 54/62 partial/offen.

## Fortsetzung 30.10.2026 – fresh20-Persistenz
- [x] Ausstehendes `/vptest090 verify` auf `run/vp-k04-fresh20-2026-10-30` nach echtem Serverneustart und Quickplay-Verbindung: **`VP090 persistence PASS`**. Bestätigt sind Frachtesel (7 Eisen, ein Ladungsmarker), Bauphasen-Metadaten, Netzwerklinks, Kasse (5), Plaza, Ladenkarriere und Verkäufe.
- [x] JDK-25 `runServer` mit stabilem Temp-Build-Redirect; `compileJava` war UP-TO-DATE. Welt gespeichert, Dev-Prozesse beendet, `run/server.properties` auf `vp-master-test-2026-10-30` zurückgesetzt.
- [ ] Nächster Schritt: natürliche CTOV-Karawanenlieferung mit registriertem Kornspeicher bis zur Abreise beobachten, danach Meilensteinfolge und Mauer um Alt- und Neubauten prüfen. Die vier Claude-Scratchpad-Patches bleiben vor Integration zu reviewen.

## Fortsetzung 30.10.2026 – Bauplatz, Hausstil und Wege
- [x] Screenshot-Rückmeldung ausgewertet: tolerante Haus-/Gebäudestandorte füllten bis zu 3 Blöcke Hang auf; kopierte Hauswege begannen am Bett-Anker im Inneren; Vorlagen wurden unter den nächsten sechs Häusern zufällig gewählt; lokaler Stil-Scan war nicht nach Entfernung sortiert.
- [x] Bauplatzneigung auf höchstens einen Block begrenzt. Nächste Hausvorlage wird zuerst gewählt; lokale Stil-Erkennung betrachtet die nächsten Betten.
- [x] Hausweg startet am Außenstand vor der Haustür und sucht per begrenztem A* einen begehbaren, tragfähigen Pfad um Häuser, Felder, Wasser und steile Kanten. Lokale Wege räumen ersetzbare Pflanzen und akzeptieren zusätzliche natürliche Bodenarten.
- [x] Java-25-`compileJava` mit stabilem Temp-Build-Redirect erfolgreich.
- [ ] Keine Ingame-/Screenshotprüfung nach diesen Änderungen. Nächster Schritt: frische CTOV-Siedlung live prüfen, Fundamentkante und Weganschluss am fertigen Haus visuell bestätigen; danach Regression für steile Bauplätze und Wegkontinuität ausführen.

## Fortsetzung 30.10.2026 – R01 Vanilla-Thermalrezepte
- [x] Rezeptverarbeitung nach Gerätetyp erweitert: Vanilla-Ofen nutzt geladene `SMELTING`, Hochofen `BLASTING`, Räucherofen `SMOKING`-Rezepte. Schmiede verarbeiten nur Material-/Werkzeugausgaben; Köche beziehen Nahrung aus dem Kornspeicher und lagern geräucherte Mahlzeiten mit der Lager-Policy ein.
- [x] Nur von der Dorfküche platzierte Thermalgeräte werden markiert; Chargeninput/-output bleiben persistent beansprucht.
- [x] Java-25-`compileJava` erfolgreich; der bestehende `Sites`-Konflikt-Snapshot wurde für den Compile temporär aus der Quelldateiliste genommen und danach unverändert wiederhergestellt.
- [ ] Kein Spiel-/Registry-Lauf nach dieser Änderung. R01 bleibt `partial`: Farmer’s-/Chef’s-Delight-Kochtopf, externe Mod-Rezepte und Rest-/Kapazitätsfälle weiter offen. Nächster Schritt: passende Rauch-/Hochofen-Prüfungen ergänzen und in frischer CTOV-Welt ausführen.

## Fortsetzung 30.10.2026 – Wege durch Baum- und Wasserhindernisse
- [x] `Roads.surface` ermittelt bei Baumstämmen eine natürliche Bodenwurzel und nahe Baumkrone. Es räumt nur zusammenhängende, mindestens vier Blöcke hohe Stämme; gesetztes Holz ohne diese Merkmale bleibt stehen.
- [x] Dorfwege und Überlandrouten räumen nun erkannte Baumstämme aus der Oberflächensuche. Dorfwege bauen über Wasser ein Holzdeck mit Pfeilern bis zu festem Grund; bei fehlendem Grund innerhalb 24 Blöcken bleibt die Stelle unverändert.
- [x] Java-25-`compileJava` mit stabilem Temp-Build erfolgreich. Der vorhandene doppelte `Sites`-Snapshot wurde vorübergehend ausgeschlossen und unverändert zurückbenannt.
- [x] `/vptest090 roads`: **4 Weltchecks PASS** für verwurzelten Baumstamm, Schutz gesetzten Holzes, flache Wasserbrücke samt Pfeilern und unverändertes tiefes Wasser ohne erreichbaren Untergrund. Prüfflächen werden gesichert und restauriert.
- [ ] Keine natürliche CTOV-Sichtprüfung. D03/D04/J08 bleiben `partial`; volle 3-Block-Trasse, Schluchtengeometrie, Randsteine, Straßenbauer-Beruf und natürliche CTOV-Verbindung offen.

## Fortsetzung 30.10.2026 – kleine CTOV-Hausvorlagen
- `HouseTemplate.capture` verwirft kleine Häuser nicht mehr pauschal unter 30 Blöcken: mindestens 12 Baukörper, Bett und echte Tür reichen. Wenn ein Jigsaw-Teilstück kein vollständiges Haus ergibt, versucht die Erfassung einen begrenzten verbundenen Scan, bevor sie ein generisches Ersatzhaus nimmt.
- Kopierte Häuser werden nur an Standorten angenommen, an denen jeder Block der untersten Fundamentlage tragfähigen Boden hat. So entstehen keine überhängenden Fundamentkanten, auch wenn die tolerante Flächensuche eine einzelne Stufe erlaubt.
- JDK-25-`compileJava` erfolgreich. `/vptest090 houses`: **4 Weltchecks PASS**; ein kompaktes 19-Block-Haus wird erkannt und behält Fichtenpalette/Dachtreppen; eine gestützte Fläche besteht, eine absichtlich ungestützte Kante wird abgelehnt.
- Master-Audit bleibt **8/62 vollständig, 54/62 partial/offen**. Screenshot-/CTOV-Sichtnachweis für Höhenlage, Stilnähe und tatsächliche Wegkontinuität fehlt. Nächster Schritt: natürliche CTOV-Baufolge samt Türweg und Fundamentkante ansehen; danach weiter mit priorisierten offenen Master-Punkten.

## Fortsetzung 30.10.2026 – Rathaus- und Marktzentrum
- Die Plaza-Suche ist von einem Zufallsring um die Glocke auf eine deterministische Suche vom ermittelten `Development.center` umgestellt. Sie prüft jeden vollständigen Fußabdruck, Hausabstände, Boden und Entitäten, weicht bei belegt/ungeeignet nach außen aus und stoppt bei großem Abstand.
- Der Rathausplan sucht jetzt zuerst in der Mitte der gemessenen Dorfgrenzen; bestehende Plan-Fit-, Zonen-, Geländetoleranz- und Gebäudekollisionsprüfungen bleiben maßgeblich. Bei blockierter Mitte folgt der nächste geeignete Platz im inneren Dorfbereich.
- Java-25-Compile und `/vptest090 civic`: **2 Weltchecks PASS** auf CTOV/Fabric-Entwicklungsserver. Ein absichtlich blockierter Zentralpunkt wird umgangen; beide Platzierungen bleiben in der inneren Dorffläche. Die synthetische Weltfläche wurde nach dem Test restauriert.
- G10 bleibt `partial`: keine natürliche CTOV-Sichtprüfung oder Belege zu späteren Erweiterungsflächen. Audit unverändert **8/62 vollständig, 54/62 partial/offen**. Nächster Schritt bleibt natürliche Dorfmitte, Wegverbindungen und Entwicklungsspielraum vergleichen.

## Fortsetzung 30.10.2026 – Wegkontinuität um Häuser
- `BuilderPathChecks` ergänzt `/vptest090 roads` um eine Weltprüfung für die bestehende türgestützte A*-Suche: ein massiver Gebäuderiegel muss umgangen werden, beide Endbereiche müssen erreichbar sein, alle Wegblöcke müssen eine durchgängige 4-Nachbar-Verbindung bilden und das Gebäude bleibt unverändert.
- JDK-25-`compileJava` erfolgreich. CTOV-Dev-Server mit `/vptest090 roads`: **9 Checks PASS** (4 Gelände-/Wasser- und 5 Wegprüfungen); `/vptest090 houses`: **4 PASS**; `/vptest090 civic`: **2 PASS**. Welt wurde gespeichert, Server beendet, `server.properties` auf `vp-master-test-2026-10-30` zurückgesetzt; der doppelte `Sites`-Snapshot ist wiederhergestellt.
- Der neue Wegcheck beweist nur die synthetische Hindernisumfahrung und Kontinuität, nicht Stilnähe oder natürliche Türanschlüsse in gewachsenem CTOV. G01/D03/D04 bleiben partial. Nächster Schritt: Haus-/Dorfweg im natürlichen CTOV-Siedlungsbild beobachten, dann überlandige 3-Block-Trasse und Mauerfolge weiter prüfen.

## Fortsetzung 30.10.2026 – physische Straßenreparatur (J08)
- `Roads.nextRepair` prüft begrenzt und mit persistentem Scan-Cursor bereits gebaute Überlandhälften einschließlich der drei Fahrbahnspuren. `RoadBuilderJob` holt genau einen echten, komponententreuen Baustoff aus einer öffentlichen Materialkiste, trägt ihn zur Schadstelle und verbraucht ihn erst beim erfolgreichen Einbau mit Armschwung und Sound. Berufstags `job/road_builder`, `job/civil_engineer`, `job/rail_builder` und entsprechende fremde Berufsnamen nutzen die Rolle; Vanilla-Maurer warten Straßen als Nebenarbeit.
- Java-25-Gradle-`build` erfolgreich; `/vptest090 roadwork` auf CTOV-Dev-Server: **9 Checks PASS** (kein Vorrat, echte Abholung, Komponenten, kein Ferneinbau, exakter Verbrauch, intakte Straße, Feldschutz, Privatkiste, fehlender Kiesuntergrund). Separate synthetische Fixture bei `[15000,280,15000]` bleibt in der Dev-Welt; Testvillager entfernt. Server gespeichert/gestoppt, Standardwelt und `Sites`-Konfliktsnapshot wiederhergestellt. Kein vollständiger `/vptest090 run`-/Neustart- oder natürlicher Navigations-/Clientnachweis in dieser Runde.
- J08 bleibt partial: neuer Straßen-/Schienen-/Brückenbau läuft weiterhin automatisch; Schienen-/Brückenreparatur und physischer beidseitiger Bau samt Materialbilanz stehen aus. Nächster konkreter Schritt: die bestehende Baufortschrittsroutine an Arbeiterankunft und reale Straßenbaumaterialien anbinden; danach Schienen-/Brückenwartung ergänzen. Master-Audit unverändert 8/62 vollständig, 54/62 partial/offen.


## Fortsetzung 30.10.2026 – materialbezahlter Straßenneubau
- Überland-Pflasterung läuft jetzt über `Roads.nextConstruction` und `RoadBuilderJob.construct/work` statt aus `Roads.tick`. Der Arbeiter holt je Lane einen realen Pflasterblock und verbaut ihn bei Ankunft. Ein Cursor wächst erst, wenn alle drei Spuren des Querschnitts tatsächlich fertig sind; Unterbrechungen erkennen fertige Teilspuren anhand der Weltblöcke. Vorhandene Cursor bleiben erhalten. Neue Strecken starten außerhalb erfasster Dorfgrenzen; `:start` schützt den ausgelassenen Zubringer vor dem Reparaturscan und korrigiert die Fortschrittsanzeige.
- Vollständiger Java-25-Build erfolgreich. CTOV-Dev-Server: `/vptest090 roadbuild` **15 Checks PASS**, `/vptest090 roadwork` **9 PASS**. Geprüft: kein Fernbau durch Dorftick, fehlende Materialien, drei echte Abhol-/Einbauvorgänge, exakter Verbrauch von 2 Bruchstein + 1 Kies, Neustart des flüchtigen Arbeitszustands zwischen Spuren, blockierter Folgequerschnitt, kein Reparieren vor Straßenstart. Kein echter Server-Reload oder natürlicher Navigations-/Clientnachweis. Synthetische Fixture `[16000,280,16000]` bleibt erhalten; Testvillager entfernt. Server sauber gestoppt, Standardwelt und Sites-Konfliktsnapshot restauriert.
- D03/D04/J08 bleiben partial. Übergangsstand: physischer Neubau unterstützt freie tragfähige Landflächen mit höchstens einer Stufe quer zur Straße. Wasser, natürliche Baumstämme, feste Hindernisse und starke Querhänge stoppen den Cursor; die frühere kostenlose Überland-Automatik wird dafür nicht mehr aufgerufen. Nächster konkreter Schritt: materialbezahlte Brückendecks/Pfeiler sowie sichtbare Vegetationsrodung in denselben Arbeitszyklus übernehmen, dann Längsprofil/Umgehung und beidseitigen CTOV-Lauf prüfen. Schienenbau und Lampen bleiben bisherige Automatik; kein Vollpacknachweis. Master-Audit weiterhin 8/62 vollständig und 54/62 partial/offen.


## Fortsetzung 30.10.2026 – bezahlte Überlandbrücken
- `RoadBridge.plan` plant jede Wasserspur ohne Weltänderung: feste Gründung höchstens 24 Blöcke unter Deck, bezahlte Fichtenstämme von unten nach oben, danach ein bezahltes Fichtenbrett als Deck. `Roads.nextConstruction` persistiert die Deckhöhe pro Cursor/Spur. Alle drei Spuren müssen weiterhin fertig sein, bevor der Cursor wächst.
- Arbeitsauftrag enthält getrennten Einbauort und trockenen Standplatz in Deckhöhe am Ufer oder auf fertigem Deck. Worker bleibt oberhalb des Wassers; jeder Stamm/jedes Brett durchläuft echte Abholung und Einbau bei Ankunft. Vor Platzierung werden Grund, freie Standzellen und Lebewesen im Einbaublock erneut geprüft. Lava, Blockentitäten und fehlende Gründung werden abgelehnt.
- Vollständiger Java-25-Build erfolgreich. CTOV-Dev-Server: `/vptest090 roadbridge` **41 PASS** (echte Bilanz 6 Stämme + 3 Bretter für drei Spuren, kein Fernbau, trockene Arbeit, Unterbrechung zwischen Blöcken, Tiefwasser/Lava); Landneubau **15 PASS**, Reparatur **9 PASS**. Nach sauberem echten Serverneustart `/vptest090 roadbridgeverify` **8 PASS** für gespeicherte Deckhöhen, Pfeiler/Deck und Cursor. Dies prüft die fertige synthetische Brücke, nicht eine natürliche CTOV-Wasserquerung oder den Reload eines halbfertigen Pfeilers.
- Synthetische Prüffläche `[17000,280,17000]` bleibt in `run/ctov` erhalten; Testvillager entfernt. Server gespeichert/gestoppt, Standardwelt-Einstellung und Sites-Konfliktsnapshot restauriert. Keine CUA-/Clientsichtprüfung und kein Vollpacknachweis.
- D03/D04/J08 bleiben partial (Gesamtmaster 8/62 vollständig, 54/62 partial/offen). Nächster konkreter Schritt: sichtbare Rodung natürlicher Bäume/Vegetation in die Straßenaufträge einbinden; anschließend Schluchten-/Längsprofil, Brückenwartung, beidseitigen CTOV-Lauf und physische Schienenarbeit ergänzen.


- Zusätzlich im natürlichen CTOV-Lauf beobachtet: `Karawanenwache` (IronGolem) starb am 30.10.2026 20:39:52 bei etwa `[-361.5,63,-209.5]` durch Erstickung in einer Wand. Unabhängig von synthetischer Brückenfläche; Ursache noch nicht bewiesen. `Caravan.java:306` spawnt Wache ungeprüft bei `at.offset(2,0,-1)`. Als Nächstes diese Spawn-/Kollisionsprüfung untersuchen und absichern, danach Straßenrodung fortsetzen.


## Fortsetzung 30.10.2026 – Karawanen-Spawns und Lagerkollision
- `CaravanSites` prüft die vollständigen Kollisionsboxen von Händler, beiden Eseln und IronGolem. Ganze Bodenfläche, freie Körperhöhe/-breite, trockene Zellen, geladene Chunks und reservierte Nachbarplätze sind Voraussetzung. Bei blockiertem alten Versatz wird ein passender Nachbarplatz gesucht; ohne vollständige Gruppe wird der Besuch verschoben. Nach tatsächlichem Spawn wird erneut geprüft, bei Fehler wird die angefangene Gruppe abgebrochen und entfernt.
- `Caravan.put` setzt keine Lagerblöcke in lebende Einheiten oder vorhandene Blockentitäten und protokolliert nur erfolgreich gesetzte Blöcke. Damit sind insbesondere Zelt-/Pfostenblöcke im Körper der Wache ausgeschlossen. Einzelne belegte Lagerzellen werden ausgelassen; vollständiger Lagerbau/Retry bleibt separat offen.
- Vollständiger Java-25-Build erfolgreich; `/vptest090 caravansites`: **17 Weltchecks PASS**. Darunter Händler-passende Zweiblockdecke vs. Golem, seitliche Kollision, ganzer Boden, Wasser, reservierte Plätze, blockierter alter Versatz, Ablehnung ohne komplette Aufstellung, echte Golemplatzierung, Lagerzellen im Körper, Blockentitätsschutz, kein Chunknachladen sowie öffentlicher `Caravan.spawn` mit realem Händler + 2 Eseln + Wache und freier Kollisionsbox aller vier.
- Test auf CTOV-Dev-Server in separater synthetischer Fläche `[18000,280,18000]` einschließlich Ankunftsring; Fläche bleibt erhalten, erzeugte Testgruppe entfernt. Welt gespeichert, Server gestoppt, Standardwelt und Sites-Konfliktsnapshot restauriert. Kein natürlicher Langzeit-/Kampf-/Lagerabschlussnachweis und kein Vollpacktest. Ursache des früheren Erstickungstods bleibt ohne direkte Reproduktion unbewiesen; beide identifizierten Platzierungslücken sind abgesichert.
- K03 bleibt partial. Gesamtmaster weiterhin 8/62 vollständig, 54/62 partial/offen. Nächster konkreter Schritt: sichtbare natürliche Baum-/Vegetationsrodung im bezahlten Straßenzyklus, danach Straßenprofile und Brücken-/Schienenwartung; vollständigen Lageraufbau/Abbau und Wachkampf später gezielt prüfen.


## Fortsetzung 30.10.2026 – sichtbare Straßenrodung
- `RoadClearing.column` integriert natürliche Stamm-/Vegetationsaufträge vor dem bezahlten Pflastern. Erkennung verlangt anfänglich 4+ aufrechte Naturstämme auf natürlichem Boden, nicht persistentes Laub und keine nahen Bauteile/Blockentitäten/Felder. Wurzelposition und Ursprungshöhe werden je Straßen-Cursor/Spur im bestehenden ROADS-Attachment gespeichert; bereits gekürzte Stämme bleiben dadurch bearbeitbar. Einzelne Pflanzen und Naturlaub im dreiblöckigen Durchgang erhalten ebenfalls Abbauaufträge; persistentes Zierlaub bleibt geschützt.
- `RoadBuilderJob` läuft zum trockenen Standplatz, nutzt `Anim.breakStep` mit allen Rissstufen 0–9 und erzeugt echte Loot-Drops erst nach erfolgreichem Entfernen. Reste bei vollem Inventar bleiben als echte Items erhalten. Logs/Setzlinge/Stöcke sind als Berufsausgabe lieferbar. Zieländerung und neue Schutzobjekte invalidieren den Abbauauftrag. Der Stamm wird von oben nach unten abgetragen; keine globale Rodung außerhalb der betroffenen Korridorspalte.
- Gefundener Registry-Unterschied: In der geladenen MC-26.2-Installation enthält der verwendete DIRT-Tag `grass_block` nicht. Natürliche Gras-/Myzelböden sind nun ausdrücklich in der Rodung sowie in `Woodcutting.isHarvestableTree`/Nachpflanz-Bodenprüfung berücksichtigt. Ein erster Weltlauf scheiterte daran; danach zeigte die Diagnose einen fehlenden Steinunterbau der synthetischen Kiesspur. Die Prüffläche erhielt tragfähigen Untergrund, die Schutzbedingungen wurden erhalten.
- Vollständiger Java-25-Build erfolgreich; CTOV-Dev-Server: `/vptest090 roadclear` **25 PASS**, `roadbuild` **15 PASS**, `roadbridge` **41 PASS**, `roadwork` **9 PASS**. Der neue Lauf prüft alle Rissstufen für 5 Stämme, exakt 5 Inventar-Drops, Zurücksetzen des Arbeitszustands zwischen Stämmen, Übergang zur Bodenpflasterung und Schutz von Bauholz/Behältern/Zierlaub/geänderten Zielen. Holzfäller-Erkennung auf Gras bestand; tatsächliches Nachpflanzen wurde hier nicht separat ausgeführt.
- Separate synthetische Fläche `[19000,280,19000]` bleibt erhalten; Testvillager/Abbaurisse entfernt. Server gespeichert/gestoppt, Standardwelt-Einstellung und Sites-Konfliktsnapshot restauriert. Kein echter Serverneustart mitten im gekürzten Stamm, kein natürlicher CTOV-Rodungs-/Navigations-/Sichtnachweis und kein Vollpacktest.
- D03/D04/J05/J08 bleiben partial; Gesamtmaster 8/62 vollständig, 54/62 partial/offen. Nächster konkreter Schritt: gekürzten Stamm nach echtem Reload prüfen, anschließend Längsgefälle/Umgehung sowie Brücken-/Schienenwartung und beidseitigen natürlichen Ausbau weiterarbeiten.


## Fortsetzung 30.10.2026 – Routenprofile, Holzfäller-Neustart, Karawanenlager und Tafel-Slider
- `RoadRoute` ergänzt persistente, umkehrbare A*-Routen um Gebäude und ein begrenztes Längsprofil. Bau, Reparatur, Fortschritt und Karawanen-Wegpunkt können dieselbe kanonische Route verwenden; unbekannte Chunks werden nicht nachgeladen. `/vptest090 roadroute`: **675 Checks PASS** (Vier-Nachbar-Verbindung, Detour, Kurvenquerschnitt, Quer-/Längsgefälle, Cursorkopie, alte bezahlte Route, Chunk-Schutz und Retry-Cooldown).
- Holzfällerarbeit speichert Baumwurzel, aktuellen Stamm, sichtbare Rissstufe, echte Drop-Claims und Nachpflanzauftrag. `/vptest090 woodcutting`: **6 PASS**; `/vptest090 woodcutprepare`: **9 PASS**, danach echter Serverstopp/-neustart und `/vptest090 woodcutverify`: **10 PASS**. Wiederholte Verifikation bleibt idempotent (**2 PASS**). Aktive Arbeit bricht ab, wenn inzwischen neue Dorf-/Bauholzschutzflächen entstanden sind.
- Karawanenlager prüft die gesamte ebene 7×7-Fläche, setzt nur nach vollständiger Prüfung, speichert vorher ein Löschmanifest und entfernt nur unveränderte, eigene Blöcke; beladene Fässer und fremde Änderungen bleiben erhalten. `/vptest090 caravansites`: **32 PASS**, inklusive Camp-Codec/Worldchecks und echter Spawnkollision. Keine natürliche Lager-Ankunft/Abreise beobachtet.
- `BoardScreen` verwendet zugängliche Slider für Quoten (Tastatur und Drag); Lehrlingsziel wird als Entwurf mit expliziter Zuteilung übernommen. Java-25-Build erfolgreich, aber kein verbundener Client-/Sichttest; U07 bleibt partial.
- Gesamter Java-25-`build` erfolgreich (`test` ist `NO-SOURCE`). Server sauber gestoppt; `run/server.properties` zurück auf `vp-master-test-2026-10-30`, Sites-Konfliktsnapshot wiederhergestellt. Vollständiger `/vptest090 run` bleibt aus, da kein Dev-Client verbunden war; kein Vollpack- oder natürlicher CTOV-Sichtnachweis. Master-Audit bleibt **8/62 implemented, 54/62 partial/offen**.
- Nächster Schritt: gespeicherte Umgehungsroute in einer natürlichen CTOV-Verbindung samt Längsgefälle und echter Bauarbeit prüfen; danach weiter mit dem offenen Infrastruktur-/Meilensteinzyklus. Rathaus-Slider separat im verbundenen Dev-Client bedienen und U07 danach neu bewerten.


## Fortsetzung 30.10.2026 – CTOV-Hausvorlagen, Karawanenstraßen und Minenarchiv
- Neue Wohnhäuser verwenden in `Builder.templated` die nächstgelegene echte Dorf-/CTOV-Hausvorlage statt des generischen Architect-Wohnhaustyps. Die vorhandene Boden-, Zonen- und Fundamentstützprüfung bleibt aktiv. Noch kein neuer Hausbau im Spiel nach der Änderung; Bodenkontakt, Stil und Türweg brauchen Sichtnachweis.
- `Caravan.roadWaypoint` prüft aktive Straßenlinks, kanonische `RoadRoute`-Geometrie und wirklich gepflasterte Oberflächen. Es führt Karawanen in beide Richtungen über Wegpunkte und fällt bei fehlender/alter Route, unbefestigtem Weg oder ungeladenen Streckenabschnitten auf Vanillas Wegfindung zurück. `/vptest090 caravanroad`: **7 PASS**; umfasst Hin-/Rückweg, unbefestigten Fallback und gespeicherten Fernpfad ohne Nachladen.
- Abgeschlossene Minen werden in `Mining.finishMine` sofort/idempotent in `extra.oldMines` archiviert; `d.mine` wird frei, während Barrikade, Schild und Stollen erhalten bleiben. Vorhandene `done`-Altdaten werden vor einem Folgebau migriert. Die Legende auf der Karte benennt nun `▽ Alte Mine`. `/vptest090 minelifecycle`: **6 PASS** für Archiv, Idempotenz, Schild/Barrikade, erhaltenen Tunnel und Attachment-/Map-Rundlauf.
- Finaler Java-25-Build erfolgreich. CTOV-Dev-Server: `caravanroad` **7 PASS**, `minelifecycle` **6 PASS**, `roadroute` **675 PASS**, `caravansites` **32 PASS**, `woodcutting` **6 PASS**. Server sauber gestoppt; Standardwelt-Einstellung und Sites-Konfliktsnapshot restauriert. Kein vollständiger `/vptest090 run`, kein Client-Sichttest und kein Vollpacknachweis.
- G13 bleibt implemented; G01, G12 und D05 bleiben partial bis natürlicher Sicht-/Ablaufnachweis vorliegt. Audit bleibt **8/62 implemented, 54/62 partial/offen**. Nächster konkreter Schritt: diese CTOV-Neubauten und Händlerbewegung in einer natürlichen Siedlung beobachten, danach weitere offene Bau-/Infrastrukturpunkte abarbeiten.

## Fortsetzung 30.10.2026 – Abrissprüfung G11
- [x] `DemolitionWorldChecks` hängt an `/vptest090 demolish`. Der Lauf trägt einen echten Kornspeicher ab, gibt Truhen-Baumaterial ins Materiallager, behält Brot außerhalb dieses Lagers, entfernt einen ausgetauschten Bohlenblock, renaturiert die Grundfläche auf das umgebende Podsol und lässt einen Block außerhalb des Plans stehen.
- [x] Frische Dev-Welt, JDK-25-Server: `/vptest090 demolish` **15 PASS**, direkt danach erneut **15 PASS**. Kein CTOV-Dorf, kein Neubau nach dem Abriss, kein Vollpack.
- [ ] G11 bleibt partial. Nächster Schritt: denselben Ablauf an einem störenden Bau im natürlichen Dorf beobachten und den anschließenden Neubau verfolgen.

