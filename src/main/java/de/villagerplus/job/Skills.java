package de.villagerplus.job;

import de.villagerplus.VillagerMemory;

/**
 * Fertigkeiten: Jede erledigte Aufgabe gibt Erfahrung. Mit der Stufe arbeiten Villager schneller (kuerzere Pausen zwischen
 * den Handgriffen), laufen zuegiger und erzielen Zusatzertraege (Bauer: Extra-Ernte, Fischer: oefter Fang, Schmied: staerkere Reparatur).
 */
public final class Skills {
	/** Bewusst langsam: Stufe 1 nach einigen Tagen, Stufe 5 erst nach vielen Spielwochen. */
	private static final int[] THRESHOLDS = {0, 150, 500, 1200, 2600, 5000};
	private static final String[] TITLES = {"Lehrling", "Geselle", "Fachmann", "Meister", "Großmeister", "Legende"};

	private Skills() {
	}

	/** Stufe 0..5 aus den bisher erledigten Aufgaben. */
	public static int level(VillagerMemory mem) {
		int level = 0;
		for (int i = 0; i < THRESHOLDS.length; i++) {
			if (mem.jobsDone >= THRESHOLDS[i]) {
				level = i;
			}
		}
		return level;
	}

	public static String title(int level) {
		return TITLES[Math.max(0, Math.min(level, TITLES.length - 1))];
	}

	/** Fortschritt zur naechsten Stufe in Prozent (Hoechststufe: 100). */
	public static int progress(VillagerMemory mem) {
		int level = level(mem);
		if (level >= THRESHOLDS.length - 1) {
			return 100;
		}
		int from = THRESHOLDS[level];
		int to = THRESHOLDS[level + 1];
		return Math.max(0, Math.min(100, 100 * (mem.jobsDone - from) / (to - from)));
	}

	/** Tempofaktor: 1.0 bei Stufe 0, +12 % je Stufe (bis 1.6). */
	public static double speed(VillagerMemory mem) {
		return 1.0 + 0.12 * level(mem);
	}
}
