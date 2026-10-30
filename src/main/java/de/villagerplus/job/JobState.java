package de.villagerplus.job;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Fluechtiger Laufzeit-Zustand eines Jobs (geht beim Neustart bewusst verloren). */
public class JobState {
 public de.villagerplus.village.Roads.Repair roadRepair;
 public long roadScanAt;
 public long roadBuildScanAt;
 public boolean roadBuilding;
 public long nextHousing;
	/** Nach einer Aktion: bis dahin passiert nichts. */
	public long cooldownUntil;
	/** Pro Teilaufgabe (0 = primaer, 1 = sekundaer): bis dahin wurde nichts zu tun gefunden. */
	public final long[] idleUntil = new long[2];
	public BlockPos target;
	public Entity targetEntity;
	public long walkStart;
	public long chestCheckAt;
	public long lightAt;
	public long buildCheckAt;
	public long mineCheckAt;
	/** Cached anvil in the registered tool shed and next equipment-repair scan. */
	public BlockPos toolRepairAnvil;
	public long toolRepairCheckAt;
	public long toolRepairNextWorkAt;
	public BlockPos toolCraftBench;
	public long toolCraftScanAt;
	/** Dorfkiste war voll: bis dahin nicht erneut versuchen, Waren abzuliefern. */
	public long depositBlockedUntil;
	/** Zuletzt bekannte Fertigkeitsstufe (-1 = noch nicht gesetzt) fuer die Aufstiegsmeldung. */
	public int lastSkill = -1;
	/** Fuer die Haengen-Erkennung beim Hinlaufen. */
	public net.minecraft.world.phys.Vec3 lastPos;
	public long lastMoveAt;
	/** Seit wann der Dorfbewohner nichts Sinnvolles zu tun hat (0 = beschaeftigt); Grundlage des Job-Timeouts. */
	public long idleSince;
	/** Fehlversuche beim Hinlaufen zum selben Ziel (Anti-Haengetask: beim dritten wird abgebrochen). */
	public int stuckCount;
	/** Next patrol waypoint for custom guard/lookout jobs. */
	public int patrolIndex;
	public double bestDist;
	/** Bis dahin zaehlt der Dorfbewohner als arbeitender Bergmann (darf in der Mine bleiben). */
	public long miningUntil;
	public long woodCheckAt;
	/** Bau wartet auf Material: bis dahin wird die (teure) Materialpruefung nicht wiederholt. */
	public long materialWaitUntil;
	/** Werkzeug/Gegenstand, der dem Dorfbewohner gerade in der Hand gezeigt wird (Animation). */
	public net.minecraft.world.item.Item tool;
	public long toolUntil;
	public boolean holding;
	public Phase routinePhase;
	public int stage;
	public net.minecraft.world.entity.npc.villager.Villager chatPartner;
	public boolean chatInitiator;
	public long chatUntil;
	public long chatCooldown;
	public String activity = "";

	public void clearTarget() {
		target = null;
		targetEntity = null;
		walkStart = 0;
	}
}
