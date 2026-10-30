package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Mining;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Alle Dorfstatistiken als Textzeilen (fuer die Dorftafel und den Statusbefehl). */
public final class VillageStats {
	private VillageStats() {
	}

	public static List<String> lines(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		List<Villager> people = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true);
		List<Villager> residents = people.stream().filter(v -> !Guests.isGuest(v)).toList();
		long guests = people.size() - residents.size();
		int pop = residents.size();

		Map<String, Integer> jobs = new TreeMap<>();
		int homeless = 0;
		int moodSum = 0;
		for (Villager v : residents) {
			jobs.merge(Names.profession(v.getVillagerData().profession()), 1, Integer::sum);
			if (!Housing.hasHome(v)) {
				homeless++;
			}
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			moodSum += Wellbeing.of(level, v, mem).mood();
		}
		int score = Wealth.score(level, ref, pop);

		List<String> out = new ArrayList<>();
		out.add("=== Dorftafel ===");
		out.add("Einwohner: " + pop + (guests > 0 ? " (+ " + guests + " Gäste)" : "") + ", ohne Haus: " + homeless
			+ ", Stimmung Ø " + (pop == 0 ? 0 : moodSum / pop) + "%");
		out.add("Berufe: " + jobs);
		out.add("Bauwerke: " + d.houses + " neue Häuser, " + d.farms + " Felder, Brunnen " + yes(d.wellBuilt) + ", Rathaus " + yes(d.townHallBuilt)
			+ ", Stadtmauer " + yes(d.wallBuilt) + ", Gehege " + yes(d.extra.pen.isPresent()));
		out.add("Dorfreichtum: " + score + " Punkte (Stufe " + Wealth.tier(score) + "), Aufbauten: " + d.extra.decor);
		Container box = ref.chest(level);
		if (box != null) {
			out.add("Dorfkiste: " + JobUtil.count(box, s -> s.is(Items.EMERALD)) + " Smaragde, " + JobUtil.count(box, s -> s.is(Items.IRON_INGOT))
				+ " Eisen, " + JobUtil.count(box, s -> s.is(Items.COBBLESTONE)) + " Bruchstein, " + JobUtil.count(box, de.villagerplus.job.Meals::isFood) + " Nahrung");
		}
		out.add("Dorfkasse: " + Network.treasuryEmeralds(ref) + " Smaragde");
		if (d.extra.lager.isPresent()) {
			int[] f = Lager.fill(level, d);
			out.add("Lager: " + Lager.centers(d).size() + " Schuppen, " + f[0] + " von " + f[1] + " Plätzen belegt");
		}
		if (d.extra.pen.isPresent()) {
			long animals = level.getEntitiesOfClass(Animal.class, new AABB(d.extra.pen.get()).inflate(6, 3, 6), Animal::isAlive).size();
			out.add("Tiere im Gehege: " + animals);
		}
		out.add("Aktueller Bau: " + (d.hasPlan() ? d.planType + " (Schritt " + d.planStep + ")" : "keiner") + ", Mine: " + Mining.describe(d));
		out.addAll(liveSite(level, ref, residents));
		// Nur die noch FEHLENDEN Sorten stehen auf der Wunschliste; alles Uebrige liegt schon bereit. Angezeigt: vorhanden/benoetigt.
		var savedConstruction = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		int planSize = de.villagerplus.build.Builder.planSize(d, savedConstruction);
		out.add("Bauplan: " + (d.hasPlan() ? planSize + " Bausteine" : "keiner") + ", fehlende Sorten: " + d.wanted.size());
		List<Container> storage = Balance.storage(level, ref);
		List<String> missing = new ArrayList<>();
		for (String w : d.wanted.stream().limit(8).toList()) {
			String[] parts = w.split("#");
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(parts[0]));
			if (item.isPresent()) {
				int have = 0;
				for (Container c : storage) {
					have += JobUtil.count(c, s -> s.is(item.get()));
				}
				missing.add(new net.minecraft.world.item.ItemStack(item.get()).getHoverName().getString() + " " + have + "/" + (parts.length > 1 ? parts[1] : "?"));
			}
		}
		out.add("Gesuchtes Material (vorhanden/benötigt): " + (missing.isEmpty() ? "nichts fehlt" : String.join(", ", missing)));
		long caravanIn = Math.max(0, (d.extra.nextCaravan - level.getGameTime()) / 20 / 60);
		out.add("Nächste Handelskarawane in ca. " + caravanIn + " Minuten");
		long fest = Festival.daysUntil(level);
		out.add("Dorffest: " + (fest == 0 ? "heute Abend!" : "in " + fest + " Tagen") + " (alle " + de.villagerplus.Config.FESTIVAL_PERIOD_DAYS + " Tage, ab " + de.villagerplus.Config.FESTIVAL_MIN_POP + " Einwohnern)");
		out.addAll(Market.lines(ref));
		return out;
	}

	private static String yes(boolean b) {
		return b ? "ja" : "nein";
	}

	/** Baustelle live: Planschritte, Bauarbeiter und Stillstand; Aktualisierung alle zwei Sekunden. */
	private static List<String> liveSite(ServerLevel level, VillageRef ref, List<Villager> residents) {
		VillageData d = ref.data();
		List<String> out = new ArrayList<>();
		if (!d.hasPlan()) {
			out.add("Baustelle: keine – es wird nichts gebaut.");
			return out;
		}
		var savedConstruction = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		int size = de.villagerplus.build.Builder.planSize(d, savedConstruction);
		int pct = size <= 0 ? 0 : Math.min(100, d.planStep * 100 / size);
		StringBuilder bar = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			bar.append(i < pct / 5 ? '█' : '░');
		}
		out.add("Baustelle LIVE: " + d.planType + " " + bar + " " + pct + " % (" + d.planStep + "/" + size + ")");
		de.villagerplus.build.ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (construction != null && d.planOrigin.filter(construction.origin()::equals).isPresent()) {
			int phase = construction.phaseFor(d.planStep, size);
			out.add("  Bauphase " + (phase + 1) + "/5: " + de.villagerplus.build.ConstructionSite.phaseName(phase));
		} else {
			out.add(d.planStep == 0 ? "  Materialprüfung und Geländevorbereitung" : "  Blockbau nach Bauplan");
		}
		String stepDiagnostic = de.villagerplus.build.Builder.planStepDiagnostic(d);
		if (stepDiagnostic != null) out.add("  " + stepDiagnostic);
		d.planOrigin.ifPresent(o -> out.add("  Ort: " + o.getX() + " " + o.getY() + " " + o.getZ()));
		List<String> workers = new ArrayList<>();
		for (Villager v : residents) {
			String act = de.villagerplus.job.JobManager.activityOf(v, level);
			if (act != null && (act.startsWith("baut") || act.contains("Ausbess"))) {
				String n = v.getAttachedOrCreate(VillagerPlus.MEMORY).name;
				workers.add(n.isEmpty() ? "Bauarbeiter" : n);
			}
		}
		out.add("  Bauarbeiter vor Ort: " + (workers.isEmpty() ? "niemand" : String.join(", ", workers)));
		long idle = (level.getGameTime() - d.planProgress) / 20;
		if (idle >= 30) {
			out.add("  ⚠ Stillstand seit " + idle + " s – wenn nichts passiert, Bau abbrechen (Knopf unten).");
		}
		return out;
	}
}
