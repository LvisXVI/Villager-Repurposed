package de.villagerplus.job;

import com.mojang.logging.LogUtils;
import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Caravan;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Housing;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

/**
 * Verwaltet alle geladenen Dorfbewohner und gibt ihnen einen festen Tagesplan:
 * Morgenrunde -> Arbeit (Hauptaufgabe) -> Mittagspause -> Arbeit (Nebenaufgabe) -> Abendrunde -> Nacht (Schlaf).
 * Bei den Runden treffen sich alle an der Dorfmitte, liefern Waren in die Dorfkiste und holen sich Essen.
 */
public final class JobManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Ein verwalteter Dorfbewohner samt Zustand und naechstem Takt (kein Hash-Zugriff pro Tick). */
	private static final class Entry {
		final Villager v;
		final JobState st;
		long nextAt;

		Entry(Villager v, JobState st) {
			this.v = v;
			this.st = st;
		}
	}

	/** Die Villager einer Dimension; Zu- und Abgaenge werden gesammelt und erst zu Tickbeginn eingearbeitet. */
	private static final class Group {
		final ArrayList<Entry> list = new ArrayList<>();
		final ArrayList<Entry> add = new ArrayList<>();
		final Set<Villager> remove = new HashSet<>();
		final Set<Villager> known = new HashSet<>();
		int cursor;
	}

	private static final Map<ServerLevel, Group> GROUPS = new java.util.IdentityHashMap<>();
	private static final Map<UUID, JobState> STATES = new HashMap<>();

	private static final Job FARMER = new FarmerJob();
	private static final Job COOK = new CookJob();
	private static final Job CHARCOAL = new CharcoalJob();
	private static final Job HEALER = new HealerJob();
	private static final Job GUARD = new GuardJob();
	private static final Job SHEPHERD = new ShepherdJob();
	private static final Job BUTCHER = new ButcherJob();
	private static final Job SMITH = new SmithJob();
	private static final Job CLERIC = new ClericJob();
	private static final Job MASON = new MasonJob();
	private static final Job FISHERMAN = new FishermanJob();
	private static final Job FLETCHER = new FletcherJob();
	private static final Job LEATHERWORKER = CraftOnlyJob.leatherworker();
	private static final Job CARTOGRAPHER = CraftOnlyJob.cartographer();
	private static final Job LIBRARIAN = CraftOnlyJob.librarian();
	private static final Job PORTER = new PorterJob();
	private static final Job ROAD_BUILDER = new RoadBuilderJob();

	private JobManager() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager v && !de.villagerplus.Compat.ignored(v)) {
				// Gaeste aus einer frueheren Sitzung sind weitergezogen (ihr Zustand liegt nur im Speicher).
				if (Guests.onLoad(v)) {
					return;
				}
				Group g = GROUPS.computeIfAbsent(level, k -> new Group());
				Entry e = new Entry(v, state(v));
				e.nextAt = level.getGameTime() + (v.getId() % Config.JOB_INTERVAL);
				g.remove.remove(v);
				g.add.add(e);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager v) {
				Group g = GROUPS.get(level);
				if (g != null) {
					g.remove.add(v);
				}
				STATES.remove(v.getUUID());
				Wellbeing.forget(v.getUUID());
				Villages.forget(v.getUUID());
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(JobManager::tickLevel);
	}

	/** Was macht der Dorfbewohner gerade? (fuer die Info-Anzeige) */
	public static String activityOf(Villager v, ServerLevel level) {
		Phase phase = Phase.of(level.getOverworldClockTime());
		JobState st = STATES.get(v.getUUID());
		if (v.isBaby()) {
			return st != null && !st.activity.isEmpty() ? st.activity : "spielt";
		}
		if (phase == Phase.NIGHT) {
			return phase.label;
		}
		return st == null || st.activity.isEmpty() ? phase.label : st.activity;
	}

	private static double avgMicros;
	private static double maxMicros;

	/** Messwerte fuer {@code /villagerplus perf}: gleitender Durchschnitt und Spitze der Zeit je Tick (Mikrosekunden). */
	public static String perf() {
		int total = 0;
		for (Group g : GROUPS.values()) {
			total += g.list.size();
		}
		return String.format("Villagers: Repurposed: Ø %.0f µs je Tick (Spitze %.0f µs), %d Villager verwaltet, Budget %d µs", avgMicros, maxMicros, total,
			Config.TICK_BUDGET_NANOS / 1000);
	}

	private static void tickLevel(ServerLevel level) {
		long t0 = System.nanoTime();
		try {
			tickLevelInner(level);
		} finally {
			double micros = (System.nanoTime() - t0) / 1000.0;
			avgMicros += (micros - avgMicros) * 0.01;
			maxMicros = Math.max(maxMicros * 0.999, micros);
		}
	}

	private static void tickLevelInner(ServerLevel level) {
		Group g = GROUPS.get(level);
		if (g == null) {
			return;
		}
		if (!g.remove.isEmpty()) {
			g.list.removeIf(e -> g.remove.contains(e.v));
			g.known.removeAll(g.remove);
			g.remove.clear();
		}
		if (!g.add.isEmpty()) {
			for (Entry e : g.add) {
				if (g.known.add(e.v)) {
					g.list.add(e);
				}
			}
			g.add.clear();
		}
		int n = g.list.size();
		if (n == 0) {
			return;
		}
		long now = level.getGameTime();
		int interval = Config.JOB_INTERVAL * loadFactor(level);
		long deadline = System.nanoTime() + Config.TICK_BUDGET_NANOS;
		int processed = 0;
		int start = g.cursor % n;
		g.cursor = 0;
		for (int k = 0; k < n; k++) {
			int idx = (start + k) % n;
			Entry e = g.list.get(idx);
			if (now < e.nextAt) {
				continue;
			}
			if (e.v.isRemoved()) {
				g.remove.add(e.v);
				continue;
			}
			e.nextAt = now + interval + (e.v.getId() & 3);
			try {
				tickVillager(level, e.v, now);
			} catch (RuntimeException ex) {
				LOGGER.warn("Villagers: Repurposed: Job von {} ist fehlgeschlagen", e.v, ex);
				e.st.cooldownUntil = now + 1200;
			}
			// Zeitbudget je Tick: bei Lastspitzen wird der Rest im naechsten Tick fortgesetzt (kein Ruckler durch viele Villager).
			if ((++processed & 3) == 0 && System.nanoTime() > deadline) {
				g.cursor = idx + 1;
				return;
			}
		}
	}

	/** Bei hoher Serverlast (Tickzeit nahe/ueber dem Soll) werden die Villager seltener getaktet: 1x, 2x oder 4x. */
	private static int loadFactor(ServerLevel level) {
		var server = level.getServer();
		float perTick = server.tickRateManager().millisecondsPerTick();
		if (perTick < 49.0F) {
			return 1;
		}
		double avgMs = server.getAverageTickTimeNanos() / 1_000_000.0;
		return avgMs > perTick * 1.2 ? 4 : avgMs > perTick * 0.85 ? 2 : 1;
	}

	/** Laufzeit-Zustand eines Dorfbewohners (wird bei Bedarf angelegt). */
	public static JobState state(Villager v) {
		return STATES.computeIfAbsent(v.getUUID(), id -> new JobState());
	}

	private static void tickVillager(ServerLevel level, Villager v, long now) {
		if (Guests.isGuest(v) || de.villagerplus.village.Expedition.isAway(v)
			|| de.villagerplus.village.RailTransport.isPassenger(v)) {
			Caravan.releasePorter(v);
			return;
		}
		VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		VillageRef village = Villages.find(level, v);
		if (de.villagerplus.village.Staffing.restoreTemporary(level, v, mem, now)) {
			JobState restored = state(v);
			restored.idleSince = 0L;
			restored.clearTarget();
			restored.cooldownUntil = now + 40L;
			restored.activity = "kehrt zur früheren Arbeit zurück";
		}

		// Name, festes Haus, private Kiste, Schild und Dorfmitte pflegen (langsamer Takt).
		JobState scheduled = state(v);
		if (now >= scheduled.nextHousing) {
			scheduled.nextHousing = now + Config.HOUSING_INTERVAL;
			Housing.update(level, v, mem);
			if (village != null) {
				de.villagerplus.village.Anchor.keepLoaded(level, village, now);
				de.villagerplus.village.Expedition.tick(level, village, now);
				de.villagerplus.village.Staffing.tick(level, village, now);
				Villages.ensureCenter(level, village, v, now);
				Caravan.maybeSpawn(level, village, v, now);
				Guests.maybeSpawn(level, village, v, now);
			}
		}

		if (v.isSleeping()) {
			mem.lastSleep = now;
			// Im Schlaf erholt sich der Dorfbewohner langsam (wichtig nach Expeditionen).
			if (v.getHealth() < v.getMaxHealth()) {
				v.heal(0.25F);
			}
		}
		if (v.isBaby()) {
			School.slowGrowth(v);
			Phase kidPhase = Phase.of(level.getOverworldClockTime());
			if (!v.isSleeping() && kidPhase != Phase.NIGHT && !busy(v)) {
				JobState ks = state(v);
				if (now >= ks.cooldownUntil || School.isSchoolActivity(ks.activity)) {
					if (School.attend(new Ctx(level, v, ks, mem, village, now), kidPhase)) {
						Anim.apply(v, ks, now);
						return;
					}
					ks.activity = "";
				}
			}
			Anim.apply(v, state(v), now);
			Caravan.releasePorter(v);
			return;
		}
		Apprentices.completeIfAdult(level, v);
		if (v.isSleeping() || v.isTrading() || busy(v)) {
			Caravan.releasePorter(v);
			Anim.apply(v, state(v), now);
			return;
		}
		Phase phase = Phase.of(level.getOverworldClockTime());
		if (phase == Phase.NIGHT) {
			Caravan.releasePorter(v);
			return;
		}
		if (level.isThundering() && village != null && !phase.routine) {
			// Gewitter: Arbeiten im Freien ruhen, die Dorfbewohner suchen Unterstand zu Hause.
			phase = Phase.EVENING;
		}
		Job job = jobFor(v);
		JobState st = state(v);
		Ctx ctx = new Ctx(level, v, st, mem, village, now);
		if (job != PORTER) Caravan.releasePorter(v);
		if (now < st.cooldownUntil) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		if (Mining.insideMine(ctx)) {
			Caravan.releasePorter(v);
			Mining.exitStep(ctx);
			return;
		}
		if (!Caravan.carryingFor(v) && rescueStranded(ctx)) {
			Caravan.releasePorter(v);
			return;
		}

		if (phase == Phase.EVENING && village != null && de.villagerplus.village.Festival.isOn(level, village) && de.villagerplus.village.Festival.attend(ctx)) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		if (phase.routine) Caravan.releasePorter(v);
		if (phase == Phase.LUNCH && de.villagerplus.village.Market.open(level) && de.villagerplus.village.Market.work(ctx)) { Anim.apply(v, st, now); return; }
		if (phase.routine && phase != Phase.MORNING && de.villagerplus.village.Market.visit(ctx)) { Anim.apply(v, st, now); return; }
		if (phase.routine) {
			Caravan.releasePorter(v);
			if (!Routine.run(ctx, job, phase)) {
				// Routine fertig: ruhig stehen bleiben, bis die naechste Phase beginnt.
				if (st.activity.isEmpty()) {
					ctx.setActivity(phase.label);
				}
			}
			Anim.apply(v, st, now);
			return;
		}

		st.routinePhase = null;
		if (School.teach(ctx, phase)) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		// Befinden wirkt auf die Arbeit: schlechte Stimmung = nur ein Teil der Zeit arbeiten; grosser Hunger = Happen essen.
		Wellbeing.Status status = Wellbeing.of(level, v, mem);
		if (status.satiety() < 25) {
			Meals.eat(ctx, null);
		}
		if ((now / Config.JOB_INTERVAL) % 4 >= Wellbeing.workQuarters(status)) {
			ctx.setActivity(status.satiety() < 25 ? "ist hungrig und arbeitet langsam" : "ist " + status.moodLabel().toLowerCase() + " und arbeitet langsam");
			Anim.apply(v, st, now);
			return;
		}
		boolean first = phase == Phase.WORK_A;
		long cooldownBefore = st.cooldownUntil;
		boolean carryingCargo = job == PORTER && Caravan.carryingFor(v) && Caravan.porterDelivery(ctx);
		boolean busy = carryingCargo || de.villagerplus.village.Market.work(ctx)
			|| (de.villagerplus.build.Builder.bootstrapBuilder(ctx) && de.villagerplus.build.Builder.tick(ctx, true))
			|| (first ? job.primary(ctx) || job.secondary(ctx) : job.secondary(ctx) || job.primary(ctx));
		// Fertigkeit: Pausen zwischen den Handgriffen werden mit der Stufe kuerzer.
		if (st.cooldownUntil > cooldownBefore && st.cooldownUntil > now) {
			st.cooldownUntil = now + (long) ((st.cooldownUntil - now) / Skills.speed(mem));
		}
		announceLevelUp(ctx);
		if (!busy) {
			ctx.setActivity("hat gerade nichts zu tun");
			if (st.idleSince == 0) {
				st.idleSince = now;
			} else if (now - st.idleSince > 4000 && village != null) {
				// Job-Timeout: kann der Beruf lange nicht ausgeuebt werden, uebernimmt er den am dringendsten benoetigten Beruf.
				st.idleSince = 0;
				de.villagerplus.village.Staffing.reassignIdle(level, village, v);
			}
		} else {
			st.idleSince = 0;
		}
		payWage(ctx);
		Anim.apply(v, st, now);
	}

	/**
	 * Gestrandete Dorfbewohner (weit vom Dorf weg oder tief unter der Dorfmitte, z. B. in einer Hoehle gefallen) werden zur
	 * Dorfkiste zurueckgeholt. Bergleute bei der Arbeit und Expeditionsmitglieder sind ausgenommen.
	 */
	private static boolean rescueStranded(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.now() < c.st().miningUntil + 400L) {
			return false;
		}
		net.minecraft.core.BlockPos home = c.mem().homeBed.orElse(ref.bell());
		double distance=Double.MAX_VALUE;
		int lowest=home.getY();
		java.util.List<net.minecraft.core.BlockPos> anchors=new java.util.ArrayList<>(java.util.List.of(home,ref.bell()));
		de.villagerplus.village.Market.of(ref).plaza().ifPresent(anchors::add);
		for(var anchor:anchors) {
			double dx=c.v().getX()-anchor.getX(),dz=c.v().getZ()-anchor.getZ();
			distance=Math.min(distance,dx*dx+dz*dz);lowest=Math.min(lowest,anchor.getY());
		}
		boolean far=distance>Config.VILLAGE_RADIUS*(double)Config.VILLAGE_RADIUS;
		boolean deep=c.v().getY()<lowest-12;

		if (!far && !deep) {
			return false;
		}
		c.v().getNavigation().stop();
		c.v().teleportTo(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5);
		c.v().resetFallDistance();
		c.st().cooldownUntil = c.now() + 40;
		LOGGER.info("Villagers: Repurposed: {} war gestrandet und wurde zur Dorfkiste zurueckgeholt", c.mem().name);
		return true;
	}

	private static void announceLevelUp(Ctx c) {
		int level = Skills.level(c.mem());
		JobState st = c.st();
		if (st.lastSkill >= 0 && level > st.lastSkill) {
			String name = c.mem().name.isEmpty() ? "Ein Dorfbewohner" : c.mem().name;
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, c.v().getX(), c.v().getY() + 2.0, c.v().getZ(), 12, 0.4, 0.4, 0.4, 0.0);
			c.level().playSound(null, c.v().blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_YES, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.2F);
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 24, net.minecraft.network.chat.Component.literal(name + " ist jetzt " + Skills.title(level) + " (Stufe " + level + ")!"));
		}
		st.lastSkill = level;
	}

	/**
	 * Lohn: alle {@code JOBS_PER_WAGE} erledigten Aufgaben ein Smaragd - aber aus der Dorfkasse (Dorfkiste), nicht aus dem
	 * Nichts. Ist keiner da, entfaellt der Lohn. So bleiben Smaragde knapp wie in Vanilla.
	 */
	private static void payWage(Ctx c) {
		int due = c.mem().jobsDone / Config.JOBS_PER_WAGE;
		if (due > c.mem().wages) {
			net.minecraft.world.Container box = c.village() == null ? null : c.village().chest(c.level());
			int paid = box == null ? 0 : JobUtil.take(box, s -> s.is(Items.EMERALD), due - c.mem().wages);
			if (paid > 0) {
				JobUtil.give(c.level(), c.v(), new ItemStack(Items.EMERALD, paid));
			}
			c.mem().wages = due;
		}
	}

	private static boolean busy(Villager v) {
		var brain = v.getBrain();
		return brain.isActive(Activity.PANIC) || brain.isActive(Activity.RAID) || brain.isActive(Activity.PRE_RAID)
			|| brain.isActive(Activity.HIDE) || brain.isActive(Activity.FIGHT);
	}
	private static Job jobFor(Villager v) {
		var prof = v.getVillagerData().profession();
		// Modded culinary professions only. Do not turn Vanilla farmers or butchers into cooks.
		boolean culinaryTag = prof.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/cook")))
			|| prof.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/chef")));
		String professionId = prof.unwrapKey().map(k -> k.identifier().toString().toLowerCase(java.util.Locale.ROOT)).orElse("");
		if (culinaryTag || (!professionId.startsWith("minecraft:") && (professionId.contains("chef") || professionId.contains("cook")))) {
			return COOK;
		}
		boolean charcoalTag = hasJobTag(prof, "charcoal");
		if(hasJobTag(prof,"road_builder")||hasJobTag(prof,"civil_engineer")||hasJobTag(prof,"rail_builder")
			||(!professionId.startsWith("minecraft:")&&(professionId.contains("road_builder")||professionId.contains("civil_engineer")||professionId.contains("rail_builder")))) return ROAD_BUILDER;
		if (charcoalTag || (!professionId.startsWith("minecraft:") && (professionId.contains("charcoal") || professionId.contains("coal_burner") || professionId.endsWith(":burner")))) return CHARCOAL;
		boolean healerTag = hasJobTag(prof, "healer") || hasJobTag(prof, "medic");
		if (healerTag || (!professionId.startsWith("minecraft:") && (professionId.contains("healer") || professionId.contains("medic")))) return HEALER;
		boolean guardTag = hasJobTag(prof, "guard") || hasJobTag(prof, "lookout") || hasJobTag(prof, "adventurer");
		if (guardTag || (!professionId.startsWith("minecraft:") && (professionId.contains("guard") || professionId.contains("lookout") || professionId.contains("adventurer")))) return GUARD;
		if (prof.is(VillagerProfession.FARMER)) {
			return FARMER;
		} else if (prof.is(VillagerProfession.SHEPHERD)) {
			return SHEPHERD;
		} else if (prof.is(VillagerProfession.BUTCHER)) {
			return BUTCHER;
		} else if (prof.is(VillagerProfession.ARMORER) || prof.is(VillagerProfession.WEAPONSMITH) || prof.is(VillagerProfession.TOOLSMITH)) {
			return SMITH;
		} else if (prof.is(VillagerProfession.CLERIC)) {
			return CLERIC;
		} else if (prof.is(VillagerProfession.MASON)) {
			return MASON;
		} else if (prof.is(VillagerProfession.FISHERMAN)) {
			return FISHERMAN;
		} else if (prof.is(VillagerProfession.FLETCHER)) {
			return FLETCHER;
		} else if (prof.is(VillagerProfession.LEATHERWORKER)) {
			return LEATHERWORKER;
		} else if (prof.is(VillagerProfession.CARTOGRAPHER)) {
			return CARTOGRAPHER;
		} else if (prof.is(VillagerProfession.LIBRARIAN)) {
			return LIBRARIAN;
		}
		// Beruf einer anderen Mod: ueber Datapack-Tag oder Schluesselwort im Namen einer Taetigkeit zuordnen.
		String key = de.villagerplus.Compat.jobKey(prof);
		if (key != null) {
			switch (key) {
				case "farmer": return FARMER;
				case "shepherd": return SHEPHERD;
				case "butcher": return BUTCHER;
				case "smith": return SMITH;
				case "cleric": return CLERIC;
				case "mason": return MASON;
				case "fisherman": return FISHERMAN;
				case "fletcher": return FLETCHER;
				case "leatherworker": return LEATHERWORKER;
				case "cartographer": return CARTOGRAPHER;
				case "librarian": return LIBRARIAN;
				default: break;
			}
		}
		return PORTER;
	}

	private static boolean hasJobTag(net.minecraft.core.Holder<VillagerProfession> profession, String job) {
		return profession.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/" + job)));
	}
}
