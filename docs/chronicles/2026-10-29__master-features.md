---
type: decision
description: Gründe für die schrittweise Erweiterung der bestehenden Dorfmod.
status: active
archived: false
work_status: in-progress
plan: docs/plans/2026-10-29__master-features
---

# Entscheidungen zum Master-Prompt

## Auftrag

Der Nutzer fordert: „Ja, dann mach doch für alles weiter.“ Maßgeblich bleibt sein vollständiger Master-Prompt mit neun Funktionsbereichen. GPT-6.1 Sol integriert; GPT-6 Luna mit Medium ist für Subagents ausdrücklich gewünscht.

## Entscheidungen

- Bestehende Welten müssen ladbar bleiben. Laufende Baupläne behalten ihre bisherige Schrittfolge. Neue Phasen benötigen eine dauerhafte Kennzeichnung.
- Gerüstpositionen müssen einen Neustart überstehen und dürfen beim Aufräumen keine fremden Blöcke entfernen.
- Produktion und Transport verändern echte Inventare. Fehlende Rezepte, Zutaten oder Kapazität führen zum Warten statt zu erzeugten Waren.
- Die Dorfkarte behält ihre Datenauflösung. Zoom und Verschieben verändern nur Darstellung und Koordinatenumrechnung im Client.
- Fortschritt wird pro nachgewiesener Funktion im Plan erfasst. Ein erfolgreicher Build bestätigt keine vollständige Spielmechanik oder Modpack-Kompatibilität.

## Nützliche Fehlernachweise

Der erste Frachttest prüfte eine alte Kistenreferenz nach dem Platzumzug. Die aktuelle Dorfkiste enthielt die Ware korrekt; der Test wurde auf die aktuelle Referenz berichtigt. Die frühere Phasenanzeige beruhte auf Prozenten und behauptete ein noch fehlendes Gerüst. Diese Anzeige wurde vor der eigentlichen Phasenimplementierung korrigiert.
