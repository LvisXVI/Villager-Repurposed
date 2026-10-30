# Phase 3 – Rail E2E (offen)

Stand: 30.10.2026, Branch `cursor/master-prompt-phase3-ea40`.

## Produktionsfixes (umgesetzt)

- **`RoadRoute.ensure`**: Wenn A*-Planung scheitert, Fallback auf Manhattan-Legacy statt dauerhaft leerer Geometrie.
- **`RoadRoute.ensureWithRetry`**: Bei aktivem `routeRetryAt` weiterhin Legacy-/gespeicherte Geometrie zulassen (kein stilles Blockieren von `advanceRail`).
- **`Roads.findRailSite`**: Vertikale Suche mit **Glocken-/Plattform-Y** zusätzlich zur Heightmap (erhöhte Dorfplattformen).
- **`Roads.findStationRailStart`**: Befestigung über `RailSite`-Footing statt falscher Heightmap-Oberfläche (Gras/Glocke über Kies).
- **`Roads.isProtectedLaneCell`**: Kisten/Fässer nicht überpflastern (`repairable` + E2E-Korridor).
- **`RailResources.hasMetallurgyStock`**: Erfordert jetzt auch **8× Schiene** im Dorfvorrat (konsistent zu `canPay`).

## Headless `VP_AUTOTEST=railmetro`

Letzter Lauf: **12/13 Checks** – Metropolen-Cursor > 0, **2/3** verbundene Segmente auf dem Korridor (Schwelle ≥ 3 unverändert).

Vermutung: drittes Segment bricht an `advanceRail` (Join/`canPay`/lokales Gleisbett) ab; **kein Test-Abschwächung**.

## Nächster Schritt

`advanceRail`-Abbruch an Cursor > `stationStart` mit vollem Log/Assertion in Dev; ggf. StationShed-Kollision getrennt testen (G08), nicht in derselben E2E-Schleife vermischen.
