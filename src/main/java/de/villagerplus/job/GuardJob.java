package de.villagerplus.job;

/** Patrouilliert Türme, Straßen und Bahn, kämpft und läutet bei Gefahr oder Raid. */
public final class GuardJob implements Job {
	@Override
	public boolean primary(Ctx c) { return GuardDuty.work(c); }

	@Override
	public boolean secondary(Ctx c) { return GuardDuty.work(c); }
}
