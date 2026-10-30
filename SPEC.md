# SPEC – Master-Prompt: Feature-Erweiterung für „Villages Repurposed“ (`villagers-repurposed-0.9.0.jar`)

Quelle: Nutzer-Prompt (Nummer-1-Fahrplan). Bilder `image_2` (Dorftafel-Mockup) und `image_3`/`image_4` (Biom-Gebäudestile) sind **nur konzeptionelle Beispiele**; maßgeblich sind die in-game generierten CTOV-Assets/Paletten.

Erweitere die bestehende Fabric-Mod **Villages Repurposed** (v0.9.0) modular um tiefgreifende Dorfverwaltungs-, Wirtschafts-, Bau- und Verhaltensmechaniken. Die Erweiterungen müssen nahtlos mit den installierten Modpack-Komponenten interagieren (u. a. *ChoiceTheorem's Overhauled Village*, *Farmer’s Delight Refabricated*, *Chef’s Delight*, *More Villagers: Re-employed*, *Fresh Animations / EMF / ETF*, *Actually 3D Stuff*, *Serene Seasons*, *Towns and Towers*, *Village Taverns*).

## 1. Village Generation, Layout, Abstände & Meilenstein-Bauten
- **Strukturierte, aufgelockerte Abstände:** Dörfer (inkl. CTOV-Strukturen) generieren geordnet und organisch. Dorfhäuser stehen bewusst **etwas weiter auseinander** (Vorgärten, Freiflächen, Bäume, kleine Weiden, realistische Parzellen) statt dicht gedrängt.
- **Pflicht-Meilensteine für jedes Dorf** – selbst ein Weiler mit 2–3 Bewohnern besitzt von Beginn an den algorithmischen Bauauftrag, schrittweise auf spezialisierte Kerngebäude hinzuarbeiten:
  1. **Zentrallager für Baustoffe (Dorfkiste):** nimmt ausschließlich Baumaterialien auf (Holz, Stein, Ziegel etc.).
  2. **Kornspeicher / Lebensmittellager:** nur rohe und gekochte Lebensmittel, Feldfrüchte, Fleisch, Zutaten.
  3. **Werkzeug- & Rüstungskammer (Tool Shed):** neue, gebrauchte, zu reparierende Werkzeuge, Waffen, Rüstungsteile.
  4. **Dorfschule:** Ausbildungsstätte für Kinder (Klassenzimmer/Bänke), Lehrlingssystem, XP für Berufsqualifikationen.
  5. **Dorfküche / Bäckerei:** Kochtöpfe, Schneidebretter, Öfen für den Koch.
  6. **Bahnhof / Lokschuppen (Spätphase):** Terminal für Schienenverbindungen bei fortgeschrittener Zivilisationsstufe.
- **Biom-Paletten & dynamischer Variantenreichtum:** Mischung unterschiedlicher Gebäudegrößen/-typen (Einzimmerhütten, mittelgroße Wohnhäuser mit Anbauten, mehrstöckige Werkstätten, Funktionsgebäude). Orientierung primär an in-game CTOV-Assets/Paletten.
- **Intelligente Zentrierung:** Marktplatz und Rathaus suchen zentrale Knotenpunkte und blockieren keine primären Expansionsflächen.
- **Gebäudekorrektur, Abriss & Renaturierung:** Bauten, die Wege blockieren oder fehlerhaft platziert sind, werden von Villagern physisch Block für Block abgetragen; Abbruchareal geebnet (Schotter/Erde) und renaturiert, falls kein direkter Neubau folgt; wiederverwendbare Materialien in den Baustoffspeicher; Neubau an passender Stelle mit Freiraum.
- **Minen-Lifecycle & „Verschlossen“-Mechanik:** Mineneingänge terrassiert; Teleportations-Glitches und Pathfinding-Hänger vollständig beseitigt. **Erschöpfte Minen werden niemals abgerissen**: Eingang mit Holzplanken/Zäunen verbarrikadiert, gut sichtbares Schild `[VERSCHLOSSEN - ERSCHÖPFT]`. Dorftafel/Karte stellen `Mine` automatisch auf `Alte Mine` um.
- **Dynamische Namensgebung & Handelsdiplomatie:** prozeduraler, biomabhängiger Dorfname. Unbekannte Nachbardörfer zunächst unentdeckt; erst bei erfolgreichem Handelskontakt (Karawane oder Spieler) wird der Name dauerhaft aufgedeckt und in der Nachbarschaftsübersicht registriert.

## 2. Städte-Diplomatie, Überlandstraßen & Eisenbahnnetz
- **Beziehungssystem:** je Dorf ein Beziehungskonto zu allen bekannten Nachbarsiedlungen (−100…+100). Stufen: *Unbekannt → Neutral → Handelspartner (+30) → Bündnispartner (+60) → Metropolen-Verbund (+90)*.
- **Stufe 1 Überlandstraße (ab Handelspartner):** Straßenbauer **beider** Dörfer arbeiten sich von ihren Ortsgrenzen entgegen: 3 Blöcke breiter Korridor gerodet und planiert; feste Trasse aus Biom-Pflasterstein, Kies und Randsteinen; einfache Holz-/Steinbrücken über Flüsse/Schluchten; Laternenanzünder setzen in regelmäßigen Abständen Laternen/Pfosten gegen Mob-Spawns. Karawanen nutzen fortan diese Straße.
- **Stufe 2 Eisenbahn (ab Metropolen-Verbund & Zivilisations-Upgrade):** Voraussetzung: Mindesteinwohnerzahl beider Dörfer, ausreichend Schmiede-/Bergwerksressourcen (Eisen/Kohle), Zentrallager. Schienenbauer/Steinmetze rüsten die Trasse mit Schienen, Antriebsschienen und Redstone-Energie auf; überdachte Bahnhöfe an den Dorfenden. Automatische Güterzüge (Truhenloren) transportieren Ernten, Holz, Barren; Personenzüge für Pendler/Marktbesucher.

## 3. Physischer Bau- & Abbauablauf (sichtbar, beobachtbar, detailliert)
- **Eigenständiges Crafting von Arbeitsblöcken:** Benötigt ein Beruf eine Station (Ofen/Kochtopf, Amboss/Schmelzofen, Werkbank, Steinsägetisch), holt er Rohstoffe aus der Baustoffkiste, craftet den Block autonom an einer Werkbank und platziert ihn.
- **Beobachtbarer Hausbau in 5 Phasen:** Vorbereitung (Gerüstbau, Werkbank vor Ort) → Phase 1 Fundamentaushub & Bruchsteine → Phase 2 Tragwerk & Stützbalken → Phase 3 Wände, Fenster & Türen → Phase 4 Dachstuhl & Eindeckung → Phase 5 Inneneinrichtung, Deko & Gerüstabbau.
- **Sichtbares Platzieren & Abbauen:** Platzieren mit Armschwung, Sound, Partikeln; Abbauen kontinuierlich mit sichtbaren Block-Rissen (Stufen 0–9) und Werkzeug-Schwung.
- **Dorfschilder & Privateigentum:** jedes Haus ein detailreiches Schild (Name, Funktion, Bewohner, Status); jeder Bewohner eine **private Truhe** (`Personal Chest`), an die Gemeinschaftsträger nicht herangehen dürfen.

## 4. Neues Karawanen-Design, Esel-Logistik & Rastplätze
- **Eigenständiges Händler-Design:** eigener Skin für Überland-Händler (Reisekleidung, Rucksack, Hut), losgelöst vom Standard-Villager.
- **Esel mit Truhen (keine Llamas mehr):** Karawanen reisen mit bepackten `Chested Donkeys` an der Leine.
- **Karawanen-Camp & Rastplatz:** temporäres Lager am Dorfrand (Zeltplane, Feuerstelle, Anbindepflöcke) samt bewaffneter Begleitwache.
- **Sichtbares Be- und Entladen:** Mangelgüter physisch aus den Eselstruhen in die Dorfkiste getragen; Überproduktion bar mit Smaragden angekauft.

## 5. Berufs-Logikmatrizen & Skalierung (Regelkreis Bedarf → Schwellenwert → Arbeitszyklus → Selbsterweiterung → Wartung)
- **Farmer:** Beete proportional zu Einwohnern (`Nahrungsvorrat < Einwohner × 5`); fällt der Vorrat, planiert/erweitert er neue Feld-Cluster samt Wassergräben und Zäunen; Ernte direkt ins Lebensmittellager.
- **Koch / Chefkoch (Farmer’s Delight / Chef’s Delight):** craftet Küchenblöcke selbst, holt Zutaten aus dem Lebensmittellager, kocht vollwertige Mahlzeiten, beliefert Tavernen und Haushalte (Stimmungs-/Effizienzboni).
- **Metzger:** füttert Tiere bis Gehege-Cap (>8); bei Limit baut er sichtbare Stallanbauten, schlachtet kontrolliert, Fleisch ins Lebensmittellager.
- **Schmied:** repariert Werkzeuge am Amboss, schmilzt Erze, fertigt Ersatzwerkzeuge fürs Depot, produziert Schienen/Loren.
- **Holzfäller:** schlägt gezielt Bäume mit sichtbarem Abbaufortschritt, sammelt Drops, pflanzt Setzlinge nach, ignoriert verbaute Dorfhölzer, legt bei Kahlchlag geordnete Plantagen an.
- **Steinmetz / Bergmann:** baut in der aktiven Mine ab, sichert Gänge mit Stützbalken/Fackeln, bricht Schotter für Eisenbahn-/Straßenunterbau.
- **Träger:** Baumaterial → Dorfkiste; Nahrung → Lebensmittellager; Werkzeuge → Werkzeugkammer; belädt Transportloren am Bahnhof.
- **Straßen- & Schienenbauer:** bessert Dorfwege aus, baut Überlandstraßen zu verbündeten Dörfern, verlegt Schienentrassen samt Brücken/Stützpfeilern.
- **Köhler / Brenner:** Restholz im Schmelzofen zu Holzkohle; beliefert Schmieden, Küchen, Beleuchter, Dampfloren.
- **Laternenanzünder:** prüft Lichtwerte (<1) im Dorfgebiet und entlang der Überlandstraßen, setzt Fackeln/Laternen.
- **Sanitäter / Heiler:** heilt verletzte Einheiten mit Braustand-Tränken, versorgt entkräftete Villager mit Nahrung.
- **Abenteurer / Wache / Lookout:** besetzt Wachtürme, patrouilliert Straßenverbindungen, sichert Bahntrassen, schlägt bei Raids die Alarmglocke.

## 6. Dorftafel: Redesign & Verwaltung
- **Optisches Redesign (In-World) & UI** (Mockup `image_2`): rustikale Holzleiste mit dynamisch gerendertem Dorfnamen statt starrem Banner, unebener Pinnwand-Look mit Pergamentzetteln/Aushängen, 3D-Laternen/Eisenbeschläge an den Pfosten, interaktive zoom- und verschiebbare Karte.
- **UI-Kopfzeile:** `[Icon] <Dorfname> (Koordinaten) [Umbenennen ✎]` inkl. manueller Umbenennung.
- **Karte & Bauverwaltung:** Klick auf Gebäude (Zuweisungen, Abriss, Verschieben); Anzeige von Mine, Schule, Lagern, Baustellen; neue Zonen per Drag & Drop markierbar.
- **Diplomatie-Tab („Nachbarn & Verträge“):** bekannte Partnerstädte mit Beziehungswert, Routenstatus (Keine / Straße im Bau / Straße fertig / Bahnstrecke aktiv), Handelsbilanz; Spieler genehmigt Großbauprojekte.
- **Arbeitsmarkt, Lehre & Lager-Filter:** Schieberegler für Berufsquoten, Lehrlingszuteilung in der Dorfschule; getrennte Übersicht und Bestandsregelung für Baustoffe, Lebensmittel, Werkzeuge; Schwellenwert-Regelung (Dorfbewohner liefern nur bis zum Sollwert ab; Überschüsse bleiben in der Privattruhe).

## 7. Dynamischer Arbeitsmarkt, Anti-Stuck & Alltagsroutinen
- **Anti-Hängetask:** Zielblock nach 3 Versuchen nicht erreichbar → Task abbrechen, Cooldown, alternative Handlung.
- **Job-Timeout:** kann ein Beruf lange nicht ausgeübt werden → temporär Wechsel in den dringendsten Job.
- **Alltagsroutinen & Glocken-Signal:** morgens läutet die Glocke den Arbeitsbeginn ein; bei Unwetter/Gewitter Arbeiten im Freien einstellen, Indoor-Handwerk oder Unterstand.
- **Survival-Aktivierungsgarantie:** Dorfbewohner in Survival-Welten dürfen nicht tatenlos in der Dorfmitte stehen.

## 8. Valides Crafting- & Rezept-Subsystem
- **Dynamische Fabric-Rezeptprüfung:** Zugriff auf Vanilla-/Fabric-Rezept-Registry, damit Villager alle Items (inkl. Schienen, Loren, Hebel) herstellen können, deren Zutaten vorhanden sind; Behebung falscher NBT-/Tag-Bedingungen.
- **Failover:** fehlen Rohstoffe dauerhaft → Biom-Ersatzpalette oder Esel-Karawane liefert die Ware und legt sie sichtbar ab.

## 9. Marktplatz-Ökonomie, RPG-Werte & Visuelles
- **Marktstände & B2B-Handel:** Villager mieten Stände, stellen Waren physisch aus, kaufen untereinander mit Privatsmaragden Bedarfsgegenstände.
- **Hunger & Stimmung:** beeinflussen Arbeitstempo; Feierabend in der Taverne (*Village Taverns*), dort Verzehr gekochter Gerichte.
- **Talk-Tab (erweiterter Dialog):** aktueller Task, Zielkoordinaten, Hunger/Stimmung, Privatkisteninhalt, fehlende Baumaterialien.
- **Visuelles:** sichtbare Werkzeuge/Fracht in den Händen; eigene Skins (Händler, Karawanen-Wachen, Lokführer, Abenteurer); volle Animationsunterstützung (Fresh Animations, EMF, ETF).

## Zusatzwünsche des Nutzers (Sitzung 29.10.2026)
- Dorftafel = **Live-Tracker** ohne manuelles Aktualisieren.
- **Expeditionsaufbruch sichtbar** (Abenteurer verlassen das Dorf zu Fuß).
- Bauen **live sehen**, Task/Bauauftrag **abbrechen** können.
- Villager sollen sich am Dorfplatz **in Gruppen verteilen**, nicht stapeln.
- **Läden, in denen der Spieler Items kaufen kann.**
- Dorf soll sich **automatisch entwickeln** und **Jobs automatisch verteilen**.
- **Dorfkarte** (Tafel-Übersicht) schöner gestalten.
