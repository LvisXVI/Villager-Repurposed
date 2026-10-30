package de.villagerplus.job;

/** Laternenanzünder: hält Dorf und Überlandachsen bei Blocklicht unter 1. */
public final class LamplighterJob implements Job {
	@Override
	public boolean primary(Ctx c) { return Lighting.work(c); }

	@Override
	public boolean secondary(Ctx c) { return Lighting.work(c); }

	@Override
	public boolean indoorsDuringStorm() { return false; }
}
