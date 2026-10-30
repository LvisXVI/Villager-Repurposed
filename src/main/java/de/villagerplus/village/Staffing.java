package de.villagerplus.village;

import de.villagerplus.Compat;
import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.AABB;

/**
 * Berufspflege des Dorfes:
 * <ul>
 *   <li><b>Mod-Berufe ersetzen Vanilla-Berufe</b> (wenn eine andere Mod einen Beruf mit gleicher Taetigkeit mitbringt, z. B.
 *       "othermod:farmer", wird der Vanilla-Bauer zu diesem; gibt es keinen, bleibt alles Vanilla; abschaltbar mit
 *       {@code Config.PREFER_MODDED_PROFESSIONS}).</li>
 *   <li><b>Nachbesetzung</b>: Faellt der letzte Maurer, Pfeilmacher (Holz) oder Bauer aus (gestorben, auf Expedition verloren ...),
 *       wird ein erwachsener Arbeitsloser - sonst ein Ueberzaehliger aus einem Beruf, den es mindestens dreimal gibt - in den
 *       fehlenden Beruf eingearbeitet. Erst ab 8 Einwohnern, hoechstens einer je Durchlauf. Verglichen wird nach Taetigkeit,
 *       ein Mod-Bauer zaehlt also als Bauer.</li>
 * </ul>
 */
public final class Staffing {
	private static final Map<Long, Long> LAST = new HashMap<>();
	private static final List<ResourceKey<VillagerProfession>> CRITICAL = List.of(VillagerProfession.MASON, VillagerProfession.FLETCHER, VillagerProfession.FARMER);
	private static final List<String> JOB = List.of("mason", "fletcher", "farmer");
	private static final List<String> LABEL = List.of("Maurer", "Pfeilmacher", "Bauer");

	private Staffing() {
	}

	public static void tick(ServerLevel level, VillageRef ref, long now) {
		Long last = LAST.get(ref.bell().asLong());
		if (last != null && now - last < 400L) {
			return;
		}
		LAST.put(ref.bell().asLong(), now);
		List<Villager> residents = Villages.residents(level, ref, Config.VILLAGE_RADIUS,
			v -> !Guests.isGuest(v) && Market.shop(ref, v.getUUID()) == null);

		// 1) Mod-Berufe ersetzen Vanilla-Berufe, wo es ein Gegenstueck gibt.
		for (Villager v : residents) {
			if (v.isBaby() || v.isTrading() || v.getVillagerXp() > 0 || Expedition.isAway(v)) {
				continue;
			}
			Holder<VillagerProfession> modded = Compat.counterpart(level, v.getVillagerData().profession());
			if (modded != null && modded.unwrapKey().isPresent()) {
				// Stufe mindestens 2: Der Villager behaelt den Beruf auch dann, wenn am Dorf noch kein passender Arbeitsblock steht.
				v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), modded.unwrapKey().get())
					.withLevel(Math.max(2, v.getVillagerData().level())));
			}
		}

		// 2) Fehlende Schluesselberufe nachbesetzen.
		if (residents.size() < 3) {
			return;
		}
		for (int i = 0; i < CRITICAL.size(); i++) {
			String job = JOB.get(i);
			if (residents.stream().anyMatch(v -> job.equals(Compat.jobKey(v.getVillagerData().profession())))) {
				continue;
			}
			Villager candidate = residents.stream()
				.filter(v -> !v.isBaby() && v.getVillagerData().profession().is(VillagerProfession.NONE) && !v.isSleeping() && !v.isTrading() && !Expedition.isAway(v))
				.findFirst().orElse(null);
			if (candidate == null) {
				// Keine Arbeitslosen: ein Ueberzaehliger aus einem Beruf, der mindestens dreimal vorkommt, lernt um.
				Map<Object, Integer> counts = new HashMap<>();
				for (Villager v : residents) {
					counts.merge(v.getVillagerData().profession().unwrapKey().orElse(null), 1, Integer::sum);
				}
				candidate = residents.stream()
					.filter(v -> !v.isBaby() && !v.isSleeping() && !v.isTrading() && !Expedition.isAway(v) && !v.getVillagerData().profession().is(VillagerProfession.NITWIT)
						&& JOB.stream().noneMatch(j -> j.equals(Compat.jobKey(v.getVillagerData().profession())))
						&& counts.getOrDefault(v.getVillagerData().profession().unwrapKey().orElse(null), 0) >= 3)
					.findFirst().orElse(null);
			}
			if (candidate == null) {
				continue;
			}
			// Mod-Variante des Berufs, falls vorhanden (sonst Vanilla). Stufe 2: ohne Handel behaelt er den Beruf auch ohne Arbeitsblock.
			ResourceKey<VillagerProfession> key = Compat.preferredKey(level, job, CRITICAL.get(i));
			candidate.setVillagerData(candidate.getVillagerData().withProfession(level.registryAccess(), key).withLevel(2));
			String name = candidate.getAttachedOrCreate(VillagerPlus.MEMORY).name;
			JobUtil.tellNearbyPlayers(level, candidate, 48, Component.literal((name.isEmpty() ? "Ein Dorfbewohner" : name) + " übernimmt die Arbeit als " + LABEL.get(i) + "."));
			return;
		}
		// Arbeitsmarkt: bis zu 3 Umbesetzungen je Durchlauf (alle 400 Ticks), damit ein frisches Dorf in wenigen Minuten Berufe verteilt bekommt.
		if (residents.size() >= 6) {
			for (int n = 0; n < 3; n++) {
				List<Villager> fresh = Villages.residents(level, ref, Config.VILLAGE_RADIUS,
					v -> !Guests.isGuest(v) && Market.shop(ref, v.getUUID()) == null);
				if (!rebalance(level, ref, fresh)) {
					break;
				}
			}
		}
	}

	/** Sollanteile je Beruf (Prozent der Einwohner); Berufe ohne Eintrag (Dorftrottel, Traeger) werden nicht angefasst. */
	private static final String[][] SHARE = {{"farmer", "16", "Bauer"}, {"mason", "7", "Maurer"}, {"fletcher", "5", "Pfeilmacher"}, {"butcher", "7", "Metzger"},
		{"shepherd", "6", "Schäfer"}, {"toolsmith", "4", "Werkzeugschmied"}, {"weaponsmith", "3", "Waffenschmied"}, {"armorer", "3", "Rüstungsschmied"},
		{"cleric", "5", "Kleriker"}, {"librarian", "3", "Bibliothekar"}, {"cartographer", "3", "Kartograph"}, {"fisherman", "5", "Fischer"}, {"leatherworker", "7", "Gerber"}};

	private static String keyOf(Villager v) {
		// Vanilla-Berufe werden nach ihrem echten Namen gezaehlt (Compat.jobKey fasst z. B. alle Schmiede zu "smith" zusammen).
		var key = v.getVillagerData().profession().unwrapKey();
		if (key.isPresent() && key.get().identifier().getNamespace().equals("minecraft")) {
			return key.get().identifier().getPath();
		}
		String job = Compat.jobKey(v.getVillagerData().profession());
		return job != null ? job : key.map(k -> k.identifier().getPath()).orElse("");
	}

	/**
	 * Arbeitsmarkt: Ein Beruf, der deutlich ueber seinem Sollanteil liegt (z. B. viele Gerber, weil jedes Haus einen Kessel hat), gibt
	 * einen Einwohner an den Beruf mit dem groessten Fehlbestand ab. Hoechstens einer je Durchlauf (1200 Ticks); Ladeninhaber,
	 * Kinder, Schlafende und Handelnde bleiben unberuehrt.
	 */
	private static boolean rebalance(ServerLevel level, VillageRef ref, List<Villager> residents) {
		int pop = residents.size();
		Map<String, Integer> have = new HashMap<>();
		for (Villager v : residents) {
			have.merge(keyOf(v), 1, Integer::sum);
		}
		String over = null, under = null, underLabel = "";
		int bestOver = 1, bestUnder = 0;
		// Arbeitslose (Traeger) ueber 10 % des Dorfes gelten als Ueberschuss und werden zuerst vermittelt.
		int idle = have.getOrDefault("none", 0) - Math.max(1, (int) Math.round(pop * 0.10));
		if (idle > 0) {
			over = "none";
			bestOver = idle + 100;
		}
		for (String[] s : SHARE) {
			int quota = quotaOf(ref, s, pop);
			int diff = have.getOrDefault(s[0], 0) - quota;
			if (diff > bestOver && !"none".equals(over)) {
				bestOver = diff;
				over = s[0];
			}
			if (-diff > bestUnder) {
				bestUnder = -diff;
				under = s[0];
				underLabel = s[2];
			}
		}
		if (over == null || under == null) {
			return false;
		}
		final String overKey = over;
		Villager candidate = residents.stream()
			.filter(v -> !v.isBaby() && !v.isSleeping() && !v.isTrading() && !Expedition.isAway(v) && v.getOffers().isEmpty() && keyOf(v).equals(overKey)
				&& v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getNamespace().equals("minecraft")).orElse(false))
			.findFirst().orElse(null);
		if (candidate == null) {
			return false;
		}
		ResourceKey<VillagerProfession> vanilla = ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.withDefaultNamespace(under));
		ResourceKey<VillagerProfession> key = Compat.preferredKey(level, under, vanilla);
		if (level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION).get(key).isEmpty()) {
			return false;
		}
		candidate.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		candidate.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		candidate.setVillagerData(candidate.getVillagerData().withProfession(level.registryAccess(), key).withLevel(2));
		candidate.setOffers(null);
		String name = candidate.getAttachedOrCreate(VillagerPlus.MEMORY).name;
		JobUtil.tellNearbyPlayers(level, candidate, 48, Component.literal((name.isEmpty() ? "Ein Dorfbewohner" : name) + " schult um zum " + underLabel + " (das Dorf braucht mehr davon)."));
		return true;
	}

	/** Job-Timeout: ein lange untaetiger Erwachsener wechselt in den Beruf mit dem groessten Fehlbestand. */
	public static boolean reassignIdle(ServerLevel level, VillageRef ref, Villager v) {
		if (v.isBaby() || v.isTrading() || v.getVillagerXp() > 0 || Expedition.isAway(v) || Market.shop(ref, v.getUUID()) != null
			|| v.getVillagerData().profession().is(VillagerProfession.NITWIT)
			|| !v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getNamespace().equals("minecraft")).orElse(false)) {
			return false;
		}
		var priorAssignment = v.getAttached(VillagerPlus.TEMPORARY_JOB);
		if (priorAssignment != null && !priorAssignment.original().isEmpty()) return false;
		List<Villager> residents = Villages.residents(level, ref, Config.VILLAGE_RADIUS, x -> !Guests.isGuest(x));
		int pop = residents.size();
		Map<String, Integer> have = new HashMap<>();
		for (Villager r : residents) {
			have.merge(keyOf(r), 1, Integer::sum);
		}
		String own = keyOf(v), under = null, label = "";
		int bestUnder = 0;
		for (String[] sh : SHARE) {
			int quota = quotaOf(ref, sh, pop);
			int missing = quota - have.getOrDefault(sh[0], 0);
			if (missing > bestUnder && !sh[0].equals(own)) {
				bestUnder = missing;
				under = sh[0];
				label = sh[2];
			}
		}
		if (under == null) {
			return false;
		}
		ResourceKey<VillagerProfession> vanilla = ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION, net.minecraft.resources.Identifier.withDefaultNamespace(under));
		ResourceKey<VillagerProfession> key = Compat.preferredKey(level, under, vanilla);
		if (level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION).get(key).isEmpty()) {
			return false;
		}
		var original = v.getVillagerData().profession().unwrapKey().orElse(null);
		var targetId = key.identifier();
		if (original == null) return false;
		var memory = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		v.setAttached(VillagerPlus.TEMPORARY_JOB, new TemporaryAssignment(original.identifier().toString(), targetId.toString(), v.getVillagerData().level(), level.getGameTime() + 24000L));
		v.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), key).withLevel(2));
		String name = memory.name;
		JobUtil.tellNearbyPlayers(level, v, 48, Component.literal((name.isEmpty() ? "Ein Dorfbewohner" : name) + " hilft vorübergehend als " + label + " aus."));
		return true;
	}

	/** Stellt eine Notfall-Berufszuweisung nach einem Ingame-Tag wieder her, solange niemand den Beruf inzwischen geändert hat. */
	public static boolean restoreTemporary(ServerLevel level, Villager v, de.villagerplus.VillagerMemory memory, long now) {
		var assignment = v.getAttached(VillagerPlus.TEMPORARY_JOB);
		if (assignment == null || assignment.original().isEmpty() || assignment.target().isEmpty() || now < assignment.until()) return false;
		var current = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse("");
		if (!current.equals(assignment.target()) || v.getVillagerXp() > 0) {
			clearTemporary(v);
			return false;
		}
		var originalId = net.minecraft.resources.Identifier.tryParse(assignment.original());
		if (originalId == null) {
			clearTemporary(v);
			return false;
		}
		var originalKey = ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION, originalId);
		var original = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION).get(originalKey);
		if (original.isEmpty()) {
			clearTemporary(v);
			return false;
		}
		v.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
		v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), originalKey).withLevel(Math.max(1, Math.min(5, assignment.level()))));
		clearTemporary(v);
		String name = memory.name;
		JobUtil.tellNearbyPlayers(level, v, 48, Component.literal((name.isEmpty() ? "Ein Dorfbewohner" : name) + " kehrt zu seiner früheren Arbeit zurück."));
		return true;
	}

	private static void clearTemporary(Villager v) {
		v.removeAttached(VillagerPlus.TEMPORARY_JOB);
	}

	/** Sollanzahl eines Berufs: vom Spieler eingestellter Prozentsatz, sonst Standard; 0 % = bewusst keiner. */
	private static int quotaOf(VillageRef ref, String[] share, int pop) {
		var custom = sanitizedQuotas(ref);
		int pct = custom.getOrDefault(share[0], Integer.parseInt(share[1]));
		return pct <= 0 ? 0 : Math.max(1, (int) Math.round(pop * pct / 100.0));
	}

	private static Map<String, Integer> sanitizedQuotas(VillageRef ref) {
		Map<String, Integer> raw = ref.bellEntity().getAttachedOrElse(VillagerPlus.QUOTAS, Map.of());
		Map<String, Integer> valid = new HashMap<>();
		for (String[] entry : SHARE) {
			Integer value = raw.get(entry[0]);
			if (value != null) valid.put(entry[0], Math.max(0, Math.min(40, value)));
		}
		return valid;
	}

	/** Spielereinstellung: Berufsquote um delta Prozentpunkte (0..40) aendern. */
	public static void changeQuota(VillageRef ref, String job, int delta) {
		String[] entry = null;
		for (String[] s : SHARE) {
			if (s[0].equals(job)) {
				entry = s;
			}
		}
		if (entry == null) {
			return;
		}
		var m = new HashMap<>(sanitizedQuotas(ref));
		int cur = m.getOrDefault(job, Integer.parseInt(entry[1]));
		m.put(job, Math.max(0, Math.min(40, cur + Math.max(-40, Math.min(40, delta)))));
		ref.bellEntity().setAttached(VillagerPlus.QUOTAS, Map.copyOf(m));
		ref.dirty();
	}

	/** Zeilen fuer den Arbeitsmarkt-Reiter: {@code schluessel|name|anzahl|sollprozent|standardprozent}. */
	public static List<String> quotaRows(ServerLevel level, VillageRef ref) {
		List<Villager> residents = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v));
		Map<String, Integer> have = new HashMap<>();
		for (Villager v : residents) {
			have.merge(keyOf(v), 1, Integer::sum);
		}
		var custom = sanitizedQuotas(ref);
		List<String> rows = new java.util.ArrayList<>();
		for (String[] s : SHARE) {
			int pct = custom.getOrDefault(s[0], Integer.parseInt(s[1]));
			rows.add(s[0] + "|" + s[2] + "|" + have.getOrDefault(s[0], 0) + "|" + pct + "|" + s[1]);
		}
		rows.add("none|Träger / ohne Beruf|" + have.getOrDefault("none", 0) + "|10|10");
		return rows;
	}
}
