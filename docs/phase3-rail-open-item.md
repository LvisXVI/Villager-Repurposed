# Phase 3 – Rail E2E und CTOV

Stand: 31.10.2026, Branch `cursor/master-prompt-phase3-ea40`.

## Produktionsfixes

- `RoadRoute`: Manhattan-Fallback, wenn A* scheitert; `ensureWithRetry` blockiert nicht mehr auf einem abgelaufenen Retry.
- `Roads.findRailSite` / Bahnhofszugang: Plattform-Y plus Heightmap; Kies-Footing statt Grasoberfläche.
- `Roads.isProtectedLaneCell`: Dorfkisten werden nicht überpflastert.
- `RailResources.hasMetallurgyStock` verlangt Schienen, damit `railReady` und `canPay` zusammenpassen.
- Bewohnerzählung über geladene Entitäten und Treffpunkt an der eigenen Glocke.

## Nachweise

- `./gradlew build` JDK 25 grün.
- `VP_AUTOTEST=headless`: **220 PASS** (einschließlich `railmetro` 13 und `caravandelivery` 6).
- `VP_AUTOTEST=ctov` auf Welt `vp-ctov-headless`: **37 PASS**, natürliche CTOV-Glocke `-312, 81, -330`, 14 Dorfbewohner.
- Neustart `VP_AUTOTEST=ctov-verify`: Dorf-Attachment `planType=ctov-headless` überlebt.

Bahnhof im Rail-E2E steht neben der Mittelachse, damit die Bahnhofskiste nicht das Gleisbett ist. Die Schwelle „mindestens drei verbundene Segmente“ ist unverändert.
