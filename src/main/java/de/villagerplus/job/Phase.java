package de.villagerplus.job;

import de.villagerplus.Config;

/** Fester Tagesplan der Dorfbewohner. */
public enum Phase {
	MORNING("Morgenrunde", true),
	WORK_A("Arbeit (Vormittag)", false),
	BREAK("Pause", true),
	LUNCH("Mittagspause am Dorfplatz", true),
	WORK_B("Arbeit (Nachmittag)", false),
	EVENING("Feierabend zu Hause", true),
	NIGHT("Nachtruhe", false);

	public final String label;
	/** Routine-Phasen (Essen, Abliefern, Gespraeche) statt Arbeit. */
	public final boolean routine;

	Phase(String label, boolean routine) {
		this.label = label;
		this.routine = routine;
	}

	public static Phase of(long dayTime) {
		long t = Math.floorMod(dayTime, 24000L);
		if (t < Config.MORNING_END) {
			return MORNING;
		} else if (t < Config.WORK_A1_END) {
			return WORK_A;
		} else if (t < Config.BREAK_A_END) {
			return BREAK;
		} else if (t < Config.WORK_A_END) {
			return WORK_A;
		} else if (t < Config.LUNCH_END) {
			return LUNCH;
		} else if (t < Config.WORK_B1_END) {
			return WORK_B;
		} else if (t < Config.BREAK_B_END) {
			return BREAK;
		} else if (t < Config.WORK_B_END) {
			return WORK_B;
		} else if (t < Config.EVENING_END) {
			return EVENING;
		}
		return NIGHT;
	}
}
