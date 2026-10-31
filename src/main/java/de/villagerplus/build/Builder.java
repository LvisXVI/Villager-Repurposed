package de.villagerplus.build;

import de.villagerplus.Config;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Development;
import de.villagerplus.village.Housing;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Wealth;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Plant und baut Bauwerke des Dorfes (Haeuser, Felder, Brunnen). Der Bauauftrag liegt am Dorf (Glocke), damit
 * mehrere Handwerker gemeinsam daran weiterbauen und er einen Neustart ueberlebt.
 *
 * <p>Haeuser entstehen nur, wenn die Bevoelkerung waechst: wenn jemand kein Bett hat oder alle Betten belegt sind und
 * es mehr Einwohner gibt als beim letzten Hausbau. Als Bauplan dient eines der naechstgelegenen bestehenden Haeuser
 * (Struktur-Teil bei generierten Doerfern, also auch Overhauled Villages & Co.). Hoehenunterschiede am Bauplatz bleiben
 * auf einen Block begrenzt. Fehlendes Material landet als Wunschliste am Dorf, damit Handwerker, Holzfaeller und Bergleute es beschaffen.
 */
public final class Builder {
	private static final Map<Long, VillageStyle> STYLES = new HashMap<>();
	private static final Map<Long, List<Step>> STEPS = new HashMap<>();
	private static final Map<Long, String> PLAN_DIAGNOSTICS = new HashMap<>();
	private static final Map<Long, Optional<HouseTemplate>> TEMPLATES = new HashMap<>();
	private static final long STUCK_TICKS = 36000L;
	private record BootstrapBuilder(java.util.UUID villager, long until) { }
	private static final Map<String, BootstrapBuilder> BOOTSTRAP_BUILDERS = new HashMap<>();
	private static long lastDiag;
	private static long lastPlanDiag = -100000;
	/** Warum der Bau gerade steht (nur fuer das Diagnose-Log). */
	private static String why = "";
	/** Ein Lager-Anbau fand keinen Platz: bis dahin nicht erneut versuchen (sonst blockiert er alle anderen Bauten). */
	private static final java.util.Map<Long, Long> STORAGE_BLOCKED = new java.util.HashMap<>();

	/** Diese Gebaeude werden als Kopie eines Dorfhauses gebaut (gleicher Stil wie die Umgebung). */
	private static boolean templated(String type) {
		// Wohnhaeuser uebernehmen wieder die naechstgelegene echte Dorfvorlage. Architect-Haeuser
		// wirkten in CTOV-Siedlungen oft wie fremde, blockige Neubauten statt wie lokale Bauweise.
		// Funktionsgebaeude bleiben generierte Entwuerfe und damit von Betten/POIs unabhaengig.
		return type.equals(Blueprints.HOUSE);
	}

	/** Gebaeude, die der Architect im Dorfstil entwirft (jedes Mal anders). */
	private static boolean generated(String type) {
		return architect(type) || Landmarks.handles(type);
	}

	/** Naechster fehlender Pflicht-Meilenstein (null = alle vorhanden oder der Weiler hat noch weniger als zwei Bewohner). */
	public static String nextSpecial(VillageRef ref, int pop) {
		if (pop >= 2 && !de.villagerplus.village.Specials.has(ref, Blueprints.GRANARY)) {
			return Blueprints.GRANARY;
		}
		if (pop >= 2 && de.villagerplus.village.Specials.has(ref, Blueprints.GRANARY) && !de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED)) {
			return Blueprints.TOOLSHED;
		}
		if (pop >= 2 && de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED) && ref.data().extra.school.isEmpty()) {
			return Blueprints.SCHOOL;
		}
		if (pop >= 2 && ref.data().extra.school.isPresent() && de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED)
			&& !de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN)) {
			return Blueprints.KITCHEN;
		}
		// Spaetphase: Bahnhof, sobald ein Nachbardorf Metropolen-Verbund (+90) erreicht hat und die Kueche steht.
		if (pop >= Roads_POP && de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN) && !de.villagerplus.village.Specials.has(ref, Blueprints.STATION)
			&& de.villagerplus.village.Network.of(ref.bellEntity()).links().stream().anyMatch(l -> l.relation() >= de.villagerplus.village.Roads.METROPOLIS)) {
			return Blueprints.STATION;
		}
		return null;
	}

	/** Mandatory civic milestones continue immediately after each completion instead of waiting a full house-build cooldown. */
	static boolean cooldownBlocksBuild(VillageRef ref, int population, long homeless, long now) {
		if (nextSpecial(ref, population) != null) return false;
		return now < ref.data().nextBuild;
	}

	private static final int Roads_POP = de.villagerplus.village.Roads.RAIL_POP;

	/** Gebaeude, die der Architect entwirft (und deren Instandhaltung gemerkt wird). */
	private static boolean architect(String type) {
		return type.equals(Blueprints.HOUSE) || type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type);
	}

	private static Architect.Kind kindOf(String type) {
		return switch (type) {
			case Blueprints.SCHOOL -> Architect.Kind.SCHOOL;
			case Blueprints.STORAGE -> Architect.Kind.STORAGE;
			case Blueprints.GRANARY -> Architect.Kind.GRANARY;
			case Blueprints.TOOLSHED -> Architect.Kind.TOOLSHED;
			case Blueprints.KITCHEN -> Architect.Kind.KITCHEN;
			case Blueprints.STATION -> Architect.Kind.STATION;
			default -> Architect.Kind.HOUSE;
		};
	}

	private static final Map<Long, Palette> PALETTES = new HashMap<>();
	/** Je Bauplatz: urspruengliche Gelaendehoehen der vorbereiteten Saeulen (fuer das Wiederherstellen nach einem Abbruch). */
	private static final Map<Long, Map<Long, Integer>> PREP_RECORD = new HashMap<>();
	/** Von /vpplan angelegte Testauftraege: werden nicht zugunsten eines dringenden Lagers abgebrochen. */
	private static final java.util.Set<Long> DEBUG_PLANS = new java.util.HashSet<>();

	/** Grundflaechen-Saeulen je Bauplan (siehe {@link Prep#footprint}). */
	private static final Map<Long, java.util.Set<Long>> FOOT = new HashMap<>();

	/**
	 * Schmuck ausserhalb der Grundflaeche (Blumen, Fass, Weg ...) wird an die tatsaechliche Bodenhoehe angepasst statt das Gelaende
	 * einzuebnen; null = weglassen (Hang zu steil oder kein natuerlicher Boden).
	 */
	private static Step snap(ServerLevel level, BlockPos origin, Step s) {
		int x = s.pos().getX();
		int z = s.pos().getZ();
		if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
			return s;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (Math.abs(surface - origin.getY()) > 3) {
			return null;
		}
		BlockState below = level.getBlockState(new BlockPos(x, surface - 1, z));
		if (!VillageStyle.natural(below) && !below.is(Blocks.DIRT_PATH)) {
			return null;
		}
		BlockPos np = new BlockPos(x, surface + (s.pos().getY() - origin.getY()), z);
		return new Step(np, s.item(), s.tag(), s.cost(), s.optional(), s.linkedTo(), s.make());
	}

	private static boolean storageFree(Ctx c, VillageRef ref) {
		return c.now() >= STORAGE_BLOCKED.getOrDefault(ref.bell().asLong(), 0L);
	}
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

	private Builder() {
	}

	/** Bausteine eines frueher gebauten Gebaeudes (fuer Abriss und Instandhaltung), aus Entwurf und gespeicherter Palette. */
	public static List<Step> stepsOfBuilt(ServerLevel level, VillageRef ref, Built b, Villager v) {
		if (!architect(b.type())) {
			return List.of();
		}
		Palette palette = Palette.ofIds(b.palette());
		if (palette == null) {
			palette = PALETTES.computeIfAbsent(b.origin().asLong(), k -> Palette.detect(level, ref.bell(), v));
		}
		return Architect.build(Architect.design(kindOf(b.type()), b.variant() >>> 2), palette, b.origin(), Direction.from2DDataValue(b.dir()));
	}

	public static List<Built> builtOf(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of());
	}

	/** Gebaeude aus der Instandhaltungsliste austragen (nach dem Abriss). */
	public static void forget(VillageRef ref, Built b) {
		List<Built> list = new ArrayList<>(builtOf(ref));
		list.removeIf(x -> x.origin().equals(b.origin()));
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, List.copyOf(list));
		ref.dirty();
	}

	/** Maurer bauen Haeuser und Brunnen, Bauern legen Felder an. true = beschaeftigt. */
	public static boolean tick(Ctx c, boolean mason) {
		VillageRef ref = c.village();
		if (ref == null) {
			return false;
		}
		if (mason && de.villagerplus.village.Demolition.work(c, ref)) {
			return true;
		}
		// Gebaut wird aus Dorfkiste UND Lager (gemeinsam gezaehlt und bezahlt).
		Container box = Lager.combined(c.level(), ref);
		if (box == null) {
			return false;
		}
		VillageData d = ref.data();
		if (d.hasPlan()) {
			if (c.now() - d.planProgress > STUCK_TICKS && d.wanted.isEmpty()) {
				// Haengender Auftrag (Platz nicht erreichbar, Chunk entladen ...): aufgeben.
				abort(d, ref, c.now());
				return false;
			}
			boolean farmerPlan = d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN);
			if (mason == farmerPlan) {
				return false;
			}
			if (mason) {
				String special = nextSpecial(ref, villagers(c.level(), ref).size());
				if (special != null && !special.equals(d.planType)
					&& (d.planType.equals(Blueprints.STORAGE) || d.planType.equals(Blueprints.HOUSE) || d.planType.equals(Blueprints.DECOR))) {
					abort(d, ref, c.now());
					return false;
				}
			}
			boolean result = d.planType.equals(Blueprints.WALL) ? continueWall(c, ref, box) : continuePlan(c, ref, box);
			if (c.now() - lastDiag > 1200 && d.planStep == 0) {
				lastDiag = c.now();
				LOGGER.info("Villagers: Repurposed: Bau '{}' Schritt {} ({}) - Ergebnis {}, Wunschliste {}", d.planType, d.planStep, why, result, d.wanted);
			}
			return result;
		}
		if (!d.wanted.isEmpty()) {
			// Ohne Bauauftrag gibt es keine Wuensche mehr (alte Eintraege lassen sonst Bergleute und Haendler ins Leere arbeiten).
			d.wanted.clear();
			ref.dirty();
		}
		if (c.now() < c.st().buildCheckAt) {
			return false;
		}
		c.st().buildCheckAt = c.now() + 600;
		// Ausbessern hat Vorrang vor Neubauten (sonst kaeme es in einem wachsenden Dorf nie dazu); gepruft wird nur alle 3000 Ticks.
		if (mason && c.now() >= REPAIR_AT.getOrDefault(ref.bell().asLong(), 0L)) {
			REPAIR_AT.put(ref.bell().asLong(), c.now() + 3000L);
			if (startRepair(c, ref)) {
				return true;
			}
		}
		return startPlan(c, ref, box, mason);
	}

	/** Elect one resident as a temporary builder when a small village has no mason yet. */
	public static boolean bootstrapBuilder(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.v().isBaby() || de.villagerplus.village.Guests.isGuest(c.v())
			|| de.villagerplus.village.Expedition.isAway(c.v()) || de.villagerplus.village.Market.shop(ref, c.v().getUUID()) != null) return false;
		String key = c.level().dimension().identifier() + "/" + ref.bell().asLong();
		BootstrapBuilder cached = BOOTSTRAP_BUILDERS.get(key);
		if (cached == null || c.now() >= cached.until() || c.level().getEntity(cached.villager()) == null) {
			var residents = villagers(c.level(), ref);
			boolean hasMason = residents.stream().anyMatch(v -> !v.isBaby() && !de.villagerplus.village.Expedition.isAway(v)
				&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null
				&& "mason".equals(de.villagerplus.Compat.jobKey(v.getVillagerData().profession())));
			if (hasMason) {
				BOOTSTRAP_BUILDERS.remove(key);
				return false;
			}
			Villager chosen = residents.stream().filter(v -> !v.isBaby() && !de.villagerplus.village.Expedition.isAway(v)
				&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null)
				.min(Comparator.comparing(Villager::getUUID)).orElse(null);
			if (chosen == null) {
				BOOTSTRAP_BUILDERS.remove(key);
				return false;
			}
			cached = new BootstrapBuilder(chosen.getUUID(), c.now() + 200L);
			BOOTSTRAP_BUILDERS.put(key, cached);
		}
		return cached.villager().equals(c.v().getUUID());
	}

	/** Bauauftrag von aussen abbrechen (Dorftafel): Plan, Wunschliste und Zwischenstaende werden verworfen, das Dorf plant spaeter neu. */
	public static void cancel(VillageRef ref, long now) {
		if (ref.data().hasPlan()) {
			abort(ref.data(), ref, now);
		}
	}

	private static void abort(VillageData d, VillageRef ref, long now) {
		if (d.planOrigin.isPresent()) {
			ConstructionSite site = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			if (site != null && site.origin().equals(d.planOrigin.get()) && ref.bellEntity().getLevel() instanceof ServerLevel sl) {
				ConstructionSite remaining = ConstructionSite.cleanup(sl, site, Lager.combined(sl, ref), ref);
				if (remaining.temporary().isEmpty()) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
				else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, remaining);
			} else {
				ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
			}
			Map<Long, Integer> dug = PREP_RECORD.remove(d.planOrigin.get().asLong());
			if (dug != null && d.planStep == 0 && ref.bellEntity().getLevel() instanceof ServerLevel sl) {
				// Bau kam nicht ueber die Vorbereitung hinaus: Gelaende wieder herrichten (keine verlassenen Gruben).
				Prep.restore(sl, d.planOrigin.get(), dug);
			}
			STEPS.remove(d.planOrigin.get().asLong());
			PLAN_DIAGNOSTICS.remove(d.planOrigin.get().asLong());
			STYLES.remove(d.planOrigin.get().asLong());
			REPAIRS.remove(d.planOrigin.get().asLong());
			REPAIRS.remove(d.planOrigin.get().asLong() ^ WALL_KEY);
			FOOT.remove(d.planOrigin.get().asLong());
		}
		boolean farm = d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN);
		d.wanted.clear();
		d.clearPlan();
		if (farm) {
			d.extra.nextFarm = now + 1200;
		} else {
			d.nextBuild = now + 1200;
		}
		ref.dirty();
	}

	// --- Planung --------------------------------------------------------------------------------

	private static List<Villager> villagers(ServerLevel level, VillageRef ref) {
		return de.villagerplus.village.Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v));
	}

	private static boolean startPlan(Ctx c, VillageRef ref, Container box, boolean mason) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		ConstructionSite staleSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (staleSite != null) {
			ConstructionSite remaining = ConstructionSite.cleanup(level, staleSite, box, ref);
			if (!remaining.temporary().isEmpty()) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, remaining);
				return false;
			}
			ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		}
		List<Villager> all = villagers(level, ref);
		int pop = all.size();
		if (d.popAtLastHouse == 0) {
            var net=de.villagerplus.village.Network.of(ref.bellEntity());
            de.villagerplus.village.Network.save(ref.bellEntity(),net.withCooldown("population_settle_until",c.now()+1200));
			d.popAtLastHouse = pop;
			ref.dirty();
		}
		long homeless = all.stream().filter(v -> !Housing.hasHome(v)).count();
		String type;
		int variant = 0;
		if (mason) {
			if (c.now() - lastPlanDiag > 2400) {
				lastPlanDiag = c.now();
				LOGGER.info("Villagers: Repurposed: Planpruefung: Einwohner {}, obdachlos {}, naechster Bau in {} Ticks, Kiste hat Platz {}, Lager {}",
					pop, homeless, d.nextBuild - c.now(), JobUtil.hasEmptySlot(box), d.extra.lager.isPresent());
			}
			if (cooldownBlocksBuild(ref, pop, homeless, c.now())) {
				return false;
			}
			long homes = level.getPoiManager().getCountInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS,
				PoiManager.Occupancy.ANY);
			if (d.popAtLastHouse == 0) { d.popAtLastHouse = pop; ref.dirty(); }
			boolean needHouse = pop < Config.MAX_VILLAGERS && (homeless > 0 || Development.needsHousing(homes, pop));
			String special = nextSpecial(ref, pop);
			if (Blueprints.GRANARY.equals(special)) {
				// The first food depot starts even while some generated residents still lack a bed.
				type = special;
			} else if (Development.needsStorage(box, d, level) && pop >= 4 && storageFree(c, ref)) {
				// Dorfkiste ist voll: zuerst ein Lagerschuppen, damit nichts liegen bleibt oder verloren geht.
				type = Blueprints.STORAGE;
			} else if (special != null) {
				// Toolshed, school and kitchen follow even when a few generated villagers have no recorded bed.
				type = special;
			} else if (needHouse) {
				type = Blueprints.HOUSE;
			} else if (homeless == 0 && !d.townHallBuilt && pop >= Config.TOWNHALL_POP) {
				type = Blueprints.TOWNHALL;
			} else if (!d.wellBuilt && pop >= 3) {
				type = Blueprints.WELL;
			} else if (homeless == 0 && d.extra.lager.isEmpty() && pop >= Config.STORAGE_POP) {
				type = Blueprints.STORAGE;
			} else if (homeless == 0 && d.townHallBuilt && pop >= Config.WALL_POP && (!d.wallBuilt || wallTooSmall(level, ref))) {
				return startWall(c, ref, box, all);
			} else if (homeless == 0 && Wealth.tier(Wealth.score(level, ref, pop)) > d.extra.decor) {
				type = Blueprints.DECOR;
				variant = d.extra.decor;
			} else {
				return false;
			}
		} else {
			// Felder nur, wenn niemand obdachlos ist (Haeuser haben Vorrang) und der Abstand zum letzten Feld passt.
			if (homeless > 0 || c.now() < d.extra.nextFarm) {
				return false;
			}
			if (d.extra.pen.isEmpty() && d.farms >= 1 && pop >= Config.PEN_POP) {
				type = Blueprints.PEN;
			} else if (d.farms < Math.min(Config.MAX_FARMS, 1 + pop / 4)) {
				type = Blueprints.FARM;
				variant = -1;
				for (int i = 0; i < Blueprints.seedKinds(); i++) {
					Item seed = Blueprints.seed(i);
					if (JobUtil.count(box, s -> s.is(seed)) >= Config.FARM_MIN_SEEDS) {
						// Felder wachsen mit dem Dorf: 7x7, ab dem 3. Feld 9x9, ab dem 5. Feld 11x11.
						variant = i + 10 * (3 + Math.min(2, d.farms / 2));
						break;
					}
				}
				if (variant < 0) {
					return false;
				}
			} else {
				return false;
			}
		}

		VillageStyle style = VillageStyle.detect(level, ref.bell(), c.v());
		Direction dir = null;
		BlockPos site = null;
		BlockPos source = null;
		List<Step> steps = null;
		List<Integer> constructionPhaseEnds = List.of();
		List<String> constructionPaletteIds = List.of();

		if (templated(type)) {
			List<BlockPos> beds = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS,
				PoiManager.Occupancy.ANY).map(r -> r.getPos().immutable()).collect(Collectors.toCollection(ArrayList::new));
			for (int attempt = 0; attempt < 40 && site == null && !beds.isEmpty(); attempt++) {
				BlockPos cand = type.equals(Blueprints.HOUSE) ? ZoneAwareSites.candidate(level, ref, type, c.v().getRandom(), 10, 40)
					: ZoneAwareSites.candidate(level, ref, type, c.v().getRandom(), 6, 28);
				if (cand == null) {
					continue;
				}
				// Vorlagen aus den Haeusern, die dem Bauplatz am naechsten stehen -> der Stil der Umgebung.
				// Das naechste gueltige Haus bestimmt die Vorlage; zufaelliges Mischen liess Neubauten optisch aus
				// einem anderen Viertel stammen, obwohl dort passende lokale Vorlagen standen.
				List<BlockPos> nearest = beds.stream().sorted(Comparator.comparingDouble(b -> b.distSqr(cand))).limit(6).collect(Collectors.toCollection(ArrayList::new));
				int mirror = c.v().getRandom().nextBoolean() ? 1 : 0;
				for (BlockPos bed : nearest) {
					HouseTemplate t = TEMPLATES.computeIfAbsent(bed.asLong(), k -> Optional.ofNullable(HouseTemplate.capture(level, bed, ref.bell()))).orElse(null);
					if (t == null) {
						continue;
					}
					Direction towards = Sites.facingTowards(cand, ref.bell());
					HouseTemplate.Bounds b = t.bounds(towards, mirror == 1);
					BlockPos anchor = ZoneAwareSites.tolerantAnchor(level, ref, type, cand, b.minX(), b.maxX(), b.minZ(), b.maxZ(), b.maxY() + 2);
					if (anchor != null && t.supportedFoundation(level, anchor, towards, mirror == 1)) {
						List<Step> candidateSteps = t.steps(anchor, towards, Config.MATERIAL_COST_PERCENT, mirror == 1, bl -> bl, type.equals(Blueprints.HOUSE));
						if (!ZoneAwareSites.allowsPlan(ref, type, candidateSteps)) continue;
						site = anchor;
						dir = towards;
						source = t.sourceBed;
						variant = mirror;
						steps = candidateSteps;
						break;
					}
				}
			}
			if (site == null) {
				// Kein passendes Vorlagenhaus: Standardhaus im erkannten Dorfstil auf ebenem Grund.
				String fallbackType = type;
				int fallbackVariant = variant;
				site = ZoneAwareSites.find(level, ref, type, c.v().getRandom(), 10, 40, Blueprints.half(type), Blueprints.headroom(type), 30,
					p -> ZoneAwareSites.allowsPlan(ref, fallbackType, Blueprints.create(fallbackType, style, p, Sites.facingTowards(p, ref.bell()), fallbackVariant)));
				if (site == null) {
					return false;
				}
				dir = Sites.facingTowards(site, ref.bell());
				steps = Blueprints.create(type, style, site, dir, variant);
			}
		} else {
			int min = type.equals(Blueprints.WELL) || type.equals(Blueprints.DECOR) ? 5 : type.equals(Blueprints.TOWNHALL) ? 7 : type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 6 : 10;
			int max = type.equals(Blueprints.WELL) ? 14 : type.equals(Blueprints.DECOR) ? 16 : type.equals(Blueprints.TOWNHALL) ? 22 : type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 72 : 36;
			int half = type.equals(Blueprints.FARM) ? variant / 10 : Blueprints.half(type);
			int headroom = Blueprints.headroom(type);
			Architect.Design design = null;
			// Bis zu 4 Entwuerfe probieren: ein kleinerer passt oft noch auf einen engen oder huegeligen Platz.
			int variantBase = variant;
			for (int attempt = 0; attempt < (architect(type) ? 4 : 1) && site == null; attempt++) {
				design = null;
				variant = variantBase;
				if (architect(type)) {
					// Frischer Entwurf (Groesse, Dach, Fachwerk ...): Der Seed steht im Plan, damit der Bau nach einem Neustart gleich bleibt.
					long seed = ((long) c.v().getRandom().nextInt(1 << 22) << 6) | Palette.profileCode(level, ref.bell());
					design = Architect.design(kindOf(type), seed);
					variant = (int) (seed << 2);
					half = design.half();
					headroom = design.headroom();
				} else if (Landmarks.handles(type)) {
					long seed = type.equals(Blueprints.DECOR) ? Landmarks.decorSeed(variant, c.v().getRandom().nextInt(1 << 24))
						: c.v().getRandom().nextInt(1 << 24);
					variant = (int) (seed << 2);
					half = Landmarks.half(type);
					headroom = Landmarks.headroom(type);
				}
				// Die ersten Versuche halten 3 Bloecke Abstand zu Nachbarn, erst der letzte nimmt mit 2 vorlieb (sehr enge Doerfer).
				// Aufgelockerte Parzellen: die ersten Versuche halten 5 Bloecke Abstand (Vorgarten, Freiflaeche), spaeter 3, zuletzt 2.
				Sites.margin = attempt >= 3 ? 2 : attempt >= 2 ? 3 : 5;
				int searchMax = Math.min(Config.VILLAGE_RADIUS - 8, max + attempt * 8);
				int normalAttempts = architect(type) ? 48 : 30;
				int tolerantAttempts = architect(type) ? 96 : 60;
				String selectedType = type;
				int selectedVariant = variant;
				Architect.Design selectedDesign = design;
				java.util.function.Predicate<BlockPos> fits = p -> !rejectedPlot(ref, p) && ZoneAwareSites.allowsPlan(ref, selectedType,
					sitingSteps(c, ref, selectedType, selectedVariant, selectedDesign, style, p));
				if (type.equals(Blueprints.TOWNHALL)) {
					BlockPos center = de.villagerplus.village.Development.center(level, ref);
					site = ZoneAwareSites.findCentral(level, ref, type, center, c.v().getRandom(), 32,
						-half, half, -half, half, headroom, fits);
					if (site == null) site = ZoneAwareSites.findTolerantCentral(level, ref, type, center, c.v().getRandom(), 32,
						-half, half, -half, half, headroom + 1, fits);
				}
				if (site == null) {
						site = ZoneAwareSites.find(level, ref, type, c.v().getRandom(), min, searchMax, half, headroom, normalAttempts, fits);
				}
				if (site == null) {
					// Huegeliges Gelaende (z.B. Overhauled Villages): leicht unebene Plaetze werden eingeebnet.
					site = ZoneAwareSites.findTolerant(level, ref, type, c.v().getRandom(), min, searchMax, -half, half, -half, half,
						headroom + 1, tolerantAttempts, fits);
				}
				Sites.margin = 3;

			}
			if (site == null && architect(type)) {
				// Dense CTOV fortifications can defeat random placement even when a safe outer plot exists.
				// Search the loaded village area systematically once before postponing this milestone.
				Sites.margin = 2;
				int systematicMax = Config.VILLAGE_RADIUS - 8;
				int systematicHalf = half;
				int systematicHeadroom = headroom;
				int systematicVariant = variant;
				Architect.Design systematicDesign = design;
				java.util.function.Predicate<BlockPos> systematicFits = p -> !rejectedPlot(ref, p) && ZoneAwareSites.allowsPlan(ref, type,
					sitingSteps(c, ref, type, systematicVariant, systematicDesign, style, p));
				site = ZoneAwareSites.findSystematic(level, ref, type, c.v().getRandom(), min, systematicMax,
					-systematicHalf, systematicHalf, -systematicHalf, systematicHalf, systematicHeadroom, systematicFits);
				if (site == null) {
					site = ZoneAwareSites.findTolerantSystematic(level, ref, type, c.v().getRandom(), min, systematicMax,
						-systematicHalf, systematicHalf, -systematicHalf, systematicHalf, systematicHeadroom + 1, systematicFits);
				}
				Sites.margin = 3;
			}
			if (site == null) {
				if (c.now() - lastDiag > 1200) {
					lastDiag = c.now();
					LOGGER.info("Villagers: Repurposed: Kein Bauplatz fuer '{}' (halbe Kante {}, Hoehe {})", type, half, headroom);
				}
				if (type.equals(Blueprints.STORAGE)) {
					STORAGE_BLOCKED.put(ref.bell().asLong(), c.now() + 24000L);
				}
				return false;
			}
			if (dir == null) {
				dir = Sites.facingTowards(site, ref.bell());
			}
			if (design != null) {
				Palette palette = Palette.detect(level, ref.bell(), c.v(), variant >>> 2, site);
				PALETTES.put(site.asLong(), palette);
				constructionPaletteIds = palette.ids();
				steps = Architect.build(design, palette, site, dir);
			} else if (Landmarks.handles(type)) {
				Palette palette = Palette.detect(level, ref.bell(), c.v(), variant >>> 2, site);
				PALETTES.put(site.asLong(), palette);
				steps = Landmarks.build(type, variant >>> 2, palette, site, dir);
			} else {
				steps = Blueprints.create(type, style, site, dir, variant);
			}
		}

		// Re-read current persisted zones before publishing the plan, including roof overhang, courtyard decoration and setup space.
		if (!ZoneAwareSites.allowsPlan(ref, type, steps)) return false;
		if (architect(type) && source == null && steps != null) {
			Palette palette = PALETTES.get(site.asLong());
			Architect.Design design = Architect.design(kindOf(type), variant >>> 2);
			steps = ConstructionSite.order(steps, site, design, palette);
			constructionPhaseEnds = ConstructionSite.phaseEnds(steps, site, design, palette);
		}
		if (!type.equals(Blueprints.FARM)) {
			// Der Auftrag wird sofort festgelegt (ein Haus, ein Platz); fehlendes Material wird waehrenddessen beschafft.
			updateWanted(c, ref, type, steps, box, true);
		}
		d.planType = type;
		d.planOrigin = Optional.of(site);
		d.planDir = dir.get2DDataValue();
		d.planStep = 0;
		d.planVariant = variant;
		d.planSource = Optional.ofNullable(source);
		d.planProgress = c.now();
		if (architect(type) && source == null && !constructionPhaseEnds.isEmpty()) {
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION,
				new ConstructionSite(site, List.of(), constructionPhaseEnds, false, constructionPaletteIds));
		}
		ref.dirty();
		STYLES.put(site.asLong(), style);
		STEPS.put(site.asLong(), steps);
		return true;
	}

	/** Build the exact candidate footprint before accepting a zoned site; Palette only changes materials at these coordinates. */
	private static List<Step> sitingSteps(Ctx c, VillageRef ref, String type, int variant, Architect.Design design, VillageStyle style, BlockPos site) {
		Direction facing = Sites.facingTowards(site, ref.bell());
		if (design != null) {
			Palette palette = Palette.detect(c.level(), ref.bell(), c.v(), variant >>> 2, site);
			return Architect.build(design, palette, site, facing);
		}
		if (Landmarks.handles(type)) {
			Palette palette = Palette.detect(c.level(), ref.bell(), c.v(), variant >>> 2, site);
			return Landmarks.build(type, variant >>> 2, palette, site, facing);
		}
		return Blueprints.create(type, style, site, facing, variant);
	}

	/**
	 * Wird von der Handelskarawane nach dem Einkauf aufgerufen: Fehlt dem laufenden Hausbau danach immer noch Material,
	 * das das Dorf weder herstellen noch abbauen kann, weicht der Plan auf beschaffbares Ersatzmaterial aus.
	 */
	public static void caravanVisited(ServerLevel level, VillageRef ref, Container box) {
		VillageData d = ref.data();
		if (!d.hasPlan() || !generated(d.planType) || d.planStep != 0 || (d.planVariant & 2) != 0 || d.planOrigin.isEmpty()) {
			return;
		}
		boolean unmet = false;
		for (String w : d.wanted) {
			String[] parts = w.split("#");
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(parts[0]));
			if (item.isPresent() && !Supply.obtainable(Block.byItem(item.get()))
				&& JobUtil.count(box, s -> s.is(item.get())) < (parts.length > 1 ? Integer.parseInt(parts[1]) : 1)) {
				unmet = true;
			}
		}
		if (unmet) {
			d.planVariant |= 2;
			STEPS.remove(d.planOrigin.get().asLong());
			ref.dirty();
		}
	}

	/** Anzahl der Bausteine des laufenden Plans (0 = unbekannt/kein Plan). */
	public static int planSize(VillageData d) {
		return planSize(d, null);
	}

	/** Use persisted phase totals while the transient step cache is being rebuilt after world load. */
	public static int planSize(VillageData d, ConstructionSite savedSite) {
		if (!d.hasPlan() || d.planOrigin.isEmpty()) {
			return 0;
		}
		List<Step> steps = STEPS.get(d.planOrigin.get().asLong());
		if (steps != null) return steps.size();
		if (savedSite != null && savedSite.origin().equals(d.planOrigin.get()) && !savedSite.phaseEnds().isEmpty()) {
			return savedSite.phaseEnds().get(savedSite.phaseEnds().size() - 1);
		}
		return 0;
	}
	/** Braucht der laufende Bauauftrag diesen Gegenstand irgendwo (auch wenn er gerade vollzaehlig in der Kiste liegt)? */
	public static boolean planNeeds(VillageData d, ItemStack stack) {
		if (!d.hasPlan() || d.planOrigin.isEmpty()) {
			return false;
		}
		List<Step> steps = STEPS.get(d.planOrigin.get().asLong());
		if (steps == null) {
			return false;
		}
		for (Step s : steps) {
			if (!s.free() && s.linkedTo() == null && ((s.item() != null && stack.is(s.item())) || (s.tag() != null && stack.is(s.tag())))) {
				return true;
			}
		}
		return false;
	}
	/** Pflegt die Wunschliste fuer den Bauauftrag. true = alles Material ist da. */
	private static boolean updateWanted(Ctx c, VillageRef ref, String type, List<Step> steps, Container box, boolean announce) {
		VillageData d = ref.data();
		List<Gap> missing = missing(steps, box);
		d.wanted.clear();
		if (missing.isEmpty()) {
			ref.dirty();
			return true;
		}
		for (Gap g : missing) {
			if (g.item() != null && d.wanted.size() < 256) {
				// Wunschliste = Gesamtbedarf (Werkstatt, Haendler und Holzfaeller ziehen den Bestand selbst ab).
				d.wanted.add(BuiltInRegistries.ITEM.getKey(g.item()) + "#" + g.total());
			}
		}
		if (announce) {
			MutableComponent msg = Component.literal("Für " + label(type) + " fehlt in der Dorfkiste (die Dorfbewohner beschaffen es): ");
			int shown = Math.min(7, missing.size());
			for (int i = 0; i < shown; i++) {
				msg.append(i == 0 ? Component.empty() : Component.literal(", ")).append(missing.get(i).text());
			}
			if (missing.size() > shown) {
				msg.append(Component.literal(" …"));
			}
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 32, msg);
		}
		ref.dirty();
		return false;
	}

	private static String label(String type) {
		return switch (type) {
			case Blueprints.HOUSE -> "ein neues Haus";
			case Blueprints.FARM -> "ein neues Feld";
			case Blueprints.PEN -> "ein Tiergehege";
			case Blueprints.DECOR -> "einen Aufbau fuer den Dorfreichtum";
			case Blueprints.STORAGE -> "einen Lagerschuppen";
			case Blueprints.SCHOOL -> "die Dorfschule";
			case Blueprints.GRANARY -> "den Kornspeicher";
			case Blueprints.TOOLSHED -> "die Werkzeug- und Rüstungskammer";
			case Blueprints.KITCHEN -> "die Dorfküche mit Bäckerei";
			case Blueprints.STATION -> "den Bahnhof";
			case Blueprints.TOWNHALL -> "das Rathaus";
			case Blueprints.WALL -> "die Stadtmauer";
			default -> "einen Brunnen";
		};
	}

	// --- Bauen ----------------------------------------------------------------------------------

	private static List<Step> stepsFor(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir, Ctx c) {
		VillageData d = ref.data();
		List<Step> steps = STEPS.get(origin.asLong());
		if (steps != null) {
			return steps;
		}
		if (generated(d.planType) && d.planSource.isEmpty()) {
			ConstructionSite savedSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			Palette savedPalette = savedSite != null && savedSite.origin().equals(origin) ? Palette.ofIds(savedSite.paletteIds()) : null;
			Palette palette = PALETTES.computeIfAbsent(origin.asLong(), k -> savedPalette != null ? savedPalette
				: Palette.detect(level, ref.bell(), c == null ? null : c.v(), d.planVariant >>> 2, origin));
			if (savedSite != null && savedSite.origin().equals(origin) && savedSite.paletteIds().isEmpty()) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION,
					new ConstructionSite(savedSite.origin(), savedSite.temporary(), savedSite.phaseEnds(), savedSite.prepared(), palette.ids()));
				ref.dirty();
			}
			if ((d.planVariant & 2) != 0) {
				palette = palette.obtainable();
			}
			steps = architect(d.planType) ? Architect.build(Architect.design(kindOf(d.planType), d.planVariant >>> 2), palette, origin, dir)
				: Landmarks.build(d.planType, d.planVariant >>> 2, palette, origin, dir);
			ConstructionSite site = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			if (architect(d.planType)) steps = ConstructionSite.forPlan(steps, origin, Architect.design(kindOf(d.planType), d.planVariant >>> 2), palette, site);
		} else if (d.planSource.isPresent()) {
			HouseTemplate t = HouseTemplate.capture(level, d.planSource.get(), ref.bell());
			if (t == null) {
				return null;
			}
			VillageStyle style = STYLES.computeIfAbsent(origin.asLong(), k -> VillageStyle.detect(level, ref.bell(), c.v()));
			// Bit 0 = gespiegelt, Bit 1 = Ersatzmaterial (erst wenn die Haendler das Fehlende nicht liefern konnten).
			boolean substitute = (d.planVariant & 2) != 0;
			steps = t.steps(origin, dir, Config.MATERIAL_COST_PERCENT, (d.planVariant & 1) != 0,
				bl -> substitute ? Supply.substitute(bl, style) : bl, d.planType.equals(Blueprints.HOUSE));
		} else {
			VillageStyle style = STYLES.computeIfAbsent(origin.asLong(), k -> VillageStyle.detect(level, ref.bell(), c.v()));
			steps = Blueprints.create(d.planType, style, origin, dir, d.planVariant);
		}
		STEPS.put(origin.asLong(), steps);
		return steps;
	}

	private static boolean continuePlan(Ctx c, VillageRef ref, Container box) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		BlockPos origin = d.planOrigin.get();
		Direction dir = Direction.from2DDataValue(d.planDir);
		List<Step> steps = stepsFor(level, ref, origin, dir, c);
		// Material zuerst pruefen (noch vor dem Weg zur Baustelle): fehlt etwas, beschaffen es die anderen Handwerker.
		if (steps != null && d.planStep == 0 && !d.planType.equals(Blueprints.FARM) && c.now() < c.st().materialWaitUntil) {
			d.planProgress = c.now();
			why = "Materialwartezeit";
			return false;
		}
		if (steps != null && d.planStep == 0 && !d.planType.equals(Blueprints.FARM) && !updateWanted(c, ref, d.planType, steps, box, false)) {
			c.st().materialWaitUntil = c.now() + 100;
			why = "Material fehlt";
			// Wartet der Bau auf Material, waehrend die Dorfkiste (und das Lager) voll ist, kommen Lieferungen nicht an:
			// zuerst das Lager bauen (bzw. erweitern), der Hausbau wird danach neu geplant.
			boolean needsStorage = storageFree(c, ref) && !JobUtil.hasEmptySlot(box) && (d.extra.lager.isEmpty()
				|| (Lager.chestWithRoom(level, d) == null && d.extra.lagerMore.size() < Lager.MAX_EXTRA_SHEDS));
			// School follows granary and toolshed in the milestone queue. Keeping their waiting plan and wishlist
			// lets suppliers satisfy the shortage instead of repeatedly aborting the prerequisite buildings.
			if (needsStorage && !d.planType.equals(Blueprints.STORAGE) && !d.planType.equals(Blueprints.SCHOOL)
				&& !DEBUG_PLANS.contains(origin.asLong())) {
				abort(d, ref, c.now());
				d.nextBuild = c.now();
				ref.dirty();
				return false;
			}
			d.planProgress = c.now();
			ref.dirty();
			return false;
		}
		why = "baut";
		c.setActivity("baut " + label(d.planType));
		if (steps != null) {
			// Nicht mitten im Bauwerk stehen (sonst mauert sich der Baumeister ein): Standort vor dem Gebaeude.
			BlockPos stand = workStand(c, origin, dir, steps);
			if (stand != null && stand.getY() < ref.bell().getY() - 12) {
				rejectPlot(ref, origin);
				abort(d, ref, c.now());
				why = "Baustand liegt unter dem Dorf";
				return false;
			}
			if (stand == null) {
				why = "kein erreichbarer sicherer Arbeitsplatz";
				c.setActivity("wartet auf einen erreichbaren sicheren Arbeitsplatz");
				c.v().getNavigation().stop();
				c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
				c.st().cooldownUntil = c.now() + 40;
				if (c.v().blockPosition().distSqr(origin) > 24 * 24) {
					rejectPlot(ref, origin);
					abort(d, ref, c.now());
					why = "kein Weg zum Bauplatz";
					return false;
				}
				return true;
			}
			if (!JobUtil.approach(c, stand, 6.0)) {
				// Vanilla path searches stop around 16 blocks, so hand the brain the next node on the
				// already verified route instead of the distant stand itself.
				BlockPos waypoint = routeWaypoint(c, stand);
				c.v().getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
					new net.minecraft.world.entity.ai.memory.WalkTarget(waypoint, (float) Math.min(0.55, 0.45 * de.villagerplus.job.Skills.speed(c.mem())), 1));
				if (c.st().lastMoveAt == c.now()) d.planProgress = c.now();
				else if (d.planStep == 0 && c.now() - d.planProgress > 2000) {
					rejectPlot(ref, origin);
					abort(d, ref, c.now());
					why = "Bauplatz nicht erreicht";
					return false;
				}
				why = "geht zur Baustelle " + stand.toShortString() + " von " + c.v().blockPosition().toShortString();
				return true;
			}
			if (insideFootprint(c, steps)) {
				c.v().teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			}
		} else if (!JobUtil.approach(c, origin, 9.0)) {
			return true;
		}
		if (steps == null) {
			// Vorlagenhaus ist verschwunden: Auftrag abbrechen.
			abort(d, ref, c.now());
			return false;
		}
		if (d.planStep == 0 && !d.planType.equals(Blueprints.FARM) && !updateWanted(c, ref, d.planType, steps, box, false)) {
			// Material fehlt noch: Wunschliste pflegen und warten - die anderen Handwerker beschaffen es.
			d.planProgress = c.now();
			ref.dirty();
			return false;
		}
		java.util.Set<Long> fp = d.planType.equals(Blueprints.FARM) ? null : FOOT.computeIfAbsent(origin.asLong(), k -> Prep.footprint(steps, origin));
		if (d.planStep == 0 && !REPAIRS.contains(origin.asLong()) && !Prep.run(level, origin, steps, 48, fp, PREP_RECORD.computeIfAbsent(origin.asLong(), k -> new HashMap<>()))) {
			why = "bereitet Gelaende vor";
			d.planProgress = c.now();
			ref.dirty();
			JobUtil.swing(c.v());
			return true;
		}
		ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (construction != null && construction.origin().equals(origin) && d.planStep == 0) {
			ConstructionSite prepared = ConstructionSite.prepare(level, construction, steps, origin, dir, box);
			if (!prepared.equals(construction)) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, prepared);
				ref.dirty();
			}
		}

		int paidBudget = Config.BUILD_BLOCKS_PER_STEP;
		int freeBudget = 40;
		while (paidBudget > 0 && freeBudget > 0) {
			if (d.planStep >= steps.size()) {
				finish(c, ref, origin);
				return true;
			}
			Step s = steps.get(d.planStep);
			PLAN_DIAGNOSTICS.put(origin.asLong(), describeStep(level, s, d.planStep, steps.size()));
			if (fp != null && s.free() && !Prep.contains(fp, s.pos().getX(), s.pos().getZ())) {
				s = snap(level, origin, s);
				if (s == null) {
					d.planStep++;
					freeBudget--;
					continue;
				}
			}
			BlockState cur = level.getBlockState(s.pos());
			boolean ground = s.pos().getY() < origin.getY();
			boolean replaceable = cur.isAir() || cur.canBeReplaced() || (ground && !cur.hasBlockEntity());
			boolean done;
			if (s.linkedTo() != null) {
				done = cur.is(level.getBlockState(s.linkedTo()).getBlock());
			} else if (s.free()) {
				done = cur.is(s.make().apply(Blocks.AIR).getBlock());
			} else {
				done = cur.is(Block.byItem(s.item()));
			}

			boolean placed = false;
			if (!done && replaceable && occupied(level, s.pos())) {
				// Ein Lebewesen steht im Weg: warten, nichts einmauern.
				List<String> blockers = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
					new AABB(s.pos()).inflate(0.1), e -> true).stream()
					.map(e -> e.getName().getString() + "[" + e.blockPosition().toShortString() + "]").limit(3).toList();
				PLAN_DIAGNOSTICS.put(origin.asLong(), PLAN_DIAGNOSTICS.get(origin.asLong()) + "; blockiert von " + String.join(", ", blockers));
				return true;
			}
			if (!done && replaceable) {
				Block paid = Blocks.AIR;
				if (s.linkedTo() != null) {
					paid = level.getBlockState(s.linkedTo()).getBlock();
				} else if (!s.free()) {
					paid = pay(s, box);
				}
				if (paid == null) {
					if (!s.optional()) {
						JobUtil.tellNearbyPlayers(level, c.v(), 32,
							Component.literal("Dem Baumeister geht das Material aus – die Dorfbewohner beschaffen Nachschub."));
						addWanted(d, s, box);
						d.planProgress = c.now();
						ref.dirty();
						c.st().cooldownUntil = c.now() + 600;
						return true;
					}
				} else {
					BlockState ns = s.make().apply(paid);
					level.setBlock(s.pos(), ns, 3);
					if (s.pos().getY() <= origin.getY() && s.pos().getY() >= origin.getY() - 1 && !s.free() && s.linkedTo() == null) {
						// Fundament: unter dem Boden des Gebaeudes wird bis zum festen Grund aufgefuellt (nichts schwebt am Hang).
						Palette pal = PALETTES.get(origin.asLong());
						BlockState under = pal != null ? pal.base().defaultBlockState() : ns;
						for (int fy = s.pos().getY() - 1; fy >= s.pos().getY() - 14; fy--) {
							BlockPos q = new BlockPos(s.pos().getX(), fy, s.pos().getZ());
							BlockState cs = level.getBlockState(q);
							if (cs.isAir() || cs.canBeReplaced() || !cs.getFluidState().isEmpty()) {
								level.setBlock(q, under, 2);
							} else {
								break;
							}
						}
					}
					level.playSound(null, s.pos(), ns.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
					level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, ns),
						s.pos().getX() + 0.5, s.pos().getY() + 0.5, s.pos().getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.05);
					Anim.hold(c, s.item() != null ? s.item() : Items_BRICK());
					JobUtil.swing(c.v());
					placed = true;
				}
			}
			if (placed && !s.free()) {
				paidBudget--;
			} else {
				freeBudget--;
			}
			d.planStep++;
			d.planProgress = c.now();
		}
		ref.dirty();
		return true;
	}

	private static Item Items_BRICK() {
		return net.minecraft.world.item.Items.BRICK;
	}

	/** Bounded perimeter search: the building's anchor height says nothing about the ground outside it. */
	public static BlockPos constructionStand(Ctx c) {
		VillageData data = c.village().data();
		if (!data.hasPlan() || data.planOrigin.isEmpty()) return null;
		BlockPos origin = data.planOrigin.get();
		List<Step> steps = STEPS.get(origin.asLong());
		if (steps == null) return null;
		return workStand(c, origin, Direction.from2DDataValue(data.planDir), steps);
	}

	private static final java.util.Map<Long, java.util.Set<Long>> REJECTED_PLOTS = new java.util.HashMap<>();

	/** Remember a plot the builder could not reach so the next search chooses somewhere else. */
	static void rejectPlot(VillageRef ref, BlockPos plot) {
		if (ref == null || plot == null) return;
		java.util.Set<Long> plots = REJECTED_PLOTS.computeIfAbsent(ref.bell().asLong(), key -> new java.util.HashSet<>());
		if (plots.size() > 64) plots.clear();
		plots.add(BlockPos.asLong(plot.getX(), 0, plot.getZ()));
	}

	static boolean rejectedPlot(VillageRef ref, BlockPos plot) {
		java.util.Set<Long> plots = ref == null ? null : REJECTED_PLOTS.get(ref.bell().asLong());
		return plots != null && plots.contains(BlockPos.asLong(plot.getX(), 0, plot.getZ()));
	}

	static BlockPos workStand(Ctx c, BlockPos origin, Direction dir, List<Step> steps) {
		int minX = origin.getX(), maxX = minX, minZ = origin.getZ(), maxZ = minZ, ext = 2;
		for (Step step : steps) {
			BlockPos p = step.pos();
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
			ext = Math.max(ext, (p.getX() - origin.getX()) * dir.getStepX() + (p.getZ() - origin.getZ()) * dir.getStepZ());
		}
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(origin.relative(dir, ext + 3));
		int midX = minX + (maxX - minX) / 2, midZ = minZ + (maxZ - minZ) / 2;
		for (int x : new int[] {midX, minX, maxX}) {
			candidates.add(new BlockPos(x, origin.getY(), minZ - 3));
			candidates.add(new BlockPos(x, origin.getY(), maxZ + 3));
		}
		for (int z : new int[] {midZ, minZ, maxZ}) {
			candidates.add(new BlockPos(minX - 3, origin.getY(), z));
			candidates.add(new BlockPos(maxX + 3, origin.getY(), z));
		}
		for (int gap = 4; gap <= 8; gap++) {
			candidates.add(new BlockPos(midX, origin.getY(), minZ - gap));
			candidates.add(new BlockPos(midX, origin.getY(), maxZ + gap));
			candidates.add(new BlockPos(minX - gap, origin.getY(), midZ));
			candidates.add(new BlockPos(maxX + gap, origin.getY(), midZ));
		}
		List<BlockPos> floors = new ArrayList<>();
		for (BlockPos column : candidates) {
			if (!c.level().hasChunkAt(column)) continue;
			BlockPos feet = standOnColumn(c.level(), column.getX(), column.getZ(), origin.getY());
			if (feet == null) continue;
			if (feet.getX() >= minX && feet.getX() <= maxX && feet.getZ() >= minZ && feet.getZ() <= maxZ) continue;
			floors.add(feet);
		}
		if (floors.isEmpty()) return null;
		int[] heights = floors.stream().mapToInt(BlockPos::getY).sorted().toArray();
		int median = heights[heights.length / 2];
		floors.removeIf(feet -> median - feet.getY() > 4);
		for (BlockPos feet : floors) {
			double distance = c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
			if (distance <= 36.0) return feet;
			var path = workPath(c, feet);
			if (path != null && path.canReach()) return feet;
		}
		return null;
	}

	/** Ground the worker can stand on: the heightmap, or the street under a roof when the roof is above the build level. */
	static BlockPos standOnColumn(ServerLevel level, int x, int z, int preferY) {
		if (!level.hasChunkAt(new BlockPos(x, preferY, z))) return null;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos surface = new BlockPos(x, top, z);
		if (!safeWorkFloor(level, surface)) return null;
		if (surface.getY() > preferY + 2) {
			BlockPos lower = shelteredFloor(level, surface, preferY);
			if (lower != null) return lower;
		}
		return surface;
	}

	/** A 2-block gap under a roof, so the stand is the street rather than the roof ridge. */
	private static BlockPos shelteredFloor(ServerLevel level, BlockPos roofFeet, int preferY) {
		int solids = 0;
		for (int y = roofFeet.getY() - 1; y >= Math.max(preferY - 2, roofFeet.getY() - 8); y--) {
			BlockPos feet = new BlockPos(roofFeet.getX(), y, roofFeet.getZ());
			if (safeWorkFloor(level, feet)) return feet;
			if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && ++solids > 2) return null;
		}
		return null;
	}

	/** Next node on a verified route, about eight blocks ahead, so vanilla navigation can recompute it. */
	public static BlockPos routeWaypoint(Ctx c, BlockPos goal) {
		var route = workPath(c, goal);
		if (route == null || !route.canReach() || route.getNodeCount() == 0) return goal;
		BlockPos waypoint = null;
		for (int i = 0; i < route.getNodeCount(); i++) {
			BlockPos node = route.getNodePos(i);
			if (c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(node)) > 100.0) break;
			waypoint = node;
		}
		return waypoint != null ? waypoint : goal;
	}

	/** Independent bounded search, leaving navigation range and node-budget settings untouched. */
	public static net.minecraft.world.level.pathfinder.Path workPath(Ctx c, BlockPos feet) {
		double distance = c.v().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
		if (distance > 192.0) return null;
		int length = Math.min(256, Math.max(32, (int)Math.ceil(distance) + 32));
		BlockPos from = c.v().blockPosition();
		var region = new net.minecraft.world.level.PathNavigationRegion(c.level(),
			from.offset(-length - 8, -length - 8, -length - 8), from.offset(length + 8, length + 8, length + 8));
		var finder = new net.minecraft.world.level.pathfinder.PathFinder(c.v().getNavigation().getNodeEvaluator(), length * 32);
		return finder.findPath(region, c.v(), java.util.Set.of(feet), length, 0, 1.0F);
	}

	static boolean safeWorkFloor(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below(), head = feet.above();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(below) || !level.hasChunkAt(head)) return false;
		BlockState ground = level.getBlockState(below), body = level.getBlockState(feet), upper = level.getBlockState(head);
		return ground.isFaceSturdy(level, below, Direction.UP) && ground.getFluidState().isEmpty()
			&& body.getFluidState().isEmpty() && upper.getFluidState().isEmpty()
			&& body.getCollisionShape(level, feet).isEmpty() && upper.getCollisionShape(level, head).isEmpty();
	}

	static void addWanted(VillageData d, Step s, Container box) {
		Item requested = s.item();
		if (requested == null && s.tag() != null) {
			for (net.minecraft.core.Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(s.tag())) {
				requested = holder.value();
				break;
			}
		}
		if (requested != null && d.wanted.size() < 256) {
			final Item wantedItem = requested;
			// Gesamtbedarf = Bestand + 16 (die Wunschliste enthaelt immer die Zielmenge in der Kiste).
			String entry = BuiltInRegistries.ITEM.getKey(wantedItem) + "#" + (JobUtil.count(box, st -> st.is(wantedItem)) + 16);
			if (d.wanted.stream().noneMatch(w -> w.startsWith(BuiltInRegistries.ITEM.getKey(wantedItem) + "#"))) {
				d.wanted.add(entry);
			}
		}
	}

	private static String describeStep(ServerLevel level, Step s, int index, int total) {
		BlockState state = level.getBlockState(s.pos());
		String need = s.item() != null ? BuiltInRegistries.ITEM.getKey(s.item()).toString()
			: s.tag() != null ? "Tag " + s.tag().location() : s.linkedTo() != null ? "verbundener Block" : "kostenlos";
		return "Nächster Block " + (index + 1) + "/" + total + " bei " + s.pos().toShortString()
			+ ": benötigt " + need + ", aktuell " + BuiltInRegistries.BLOCK.getKey(state.getBlock());
	}

	/** Kurzdiagnose des nächsten konkreten Blockschritts, zur Anzeige auf der Dorftafel. */
	public static String planStepDiagnostic(VillageData data) {
		return data.planOrigin.map(p -> PLAN_DIAGNOSTICS.get(p.asLong())).orElse(null);
	}

	/** Befindet sich ein Lebewesen in diesem Block? (dann wird dort nicht gebaut) */
	private static boolean occupied(ServerLevel level, BlockPos p) {
		return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(p).inflate(0.1), e -> true).isEmpty();
	}

	/** Steht der Dorfbewohner innerhalb der Bloecke, die noch gesetzt werden? */
	private static boolean insideFootprint(Ctx c, List<Step> steps) {
		BlockPos here = c.v().blockPosition();
		for (Step s : steps) {
			if (s.pos().equals(here) || s.pos().equals(here.above())) {
				return true;
			}
		}
		return false;
	}

	private static void finish(Ctx c, VillageRef ref, BlockPos origin) {
		VillageData d = ref.data();
		ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (construction != null && construction.origin().equals(origin)) {
			ConstructionSite remaining = ConstructionSite.cleanup(c.level(), construction, Lager.combined(c.level(), ref), ref);
			if (remaining.temporary().isEmpty()) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
			else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, remaining);
		}
		PREP_RECORD.remove(origin.asLong());
		PLAN_DIAGNOSTICS.remove(origin.asLong());
		if (REPAIRS.remove(origin.asLong())) {
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 40, Component.literal("Ausgebessert: " + label(d.planType) + "."));
			STEPS.remove(origin.asLong());
			d.wanted.clear();
			d.clearPlan();
			d.nextBuild = c.now() + 1200;
			ref.dirty();
			c.mem().jobsDone++;
			return;
		}
		if (architect(d.planType)) {
			List<Built> list = new ArrayList<>(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of()));
			Palette used = PALETTES.get(origin.asLong());
			if (used == null) {
				used = Palette.detect(c.level(), ref.bell(), c.v(), d.planVariant >>> 2, origin);
			}
			if ((d.planVariant & 2) != 0) {
				used = used.obtainable();
			}
			list.add(new Built(d.planType, origin, d.planDir, d.planVariant, used.ids()));
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, list);
		}
		de.villagerplus.village.Journal.add(c.level(),ref,"Bauen",c.mem().name,label(d.planType)+" fertiggestellt",0);
		switch (d.planType) {
			case Blueprints.HOUSE -> {
				d.houses++;
				d.popAtLastHouse = villagers(c.level(),ref).size();
				
				List<Step> houseSteps = STEPS.get(origin.asLong());
				BlockPos entrance = Paths.entrance(houseSteps, origin, Direction.from2DDataValue(d.planDir));
				Paths.lay(c.level(), entrance, ref.bell());
				Paths.garden(c.level(), origin, Direction.from2DDataValue(d.planDir), ref);
			}
			case Blueprints.FARM -> d.farms++;
			case Blueprints.PEN -> d.extra.pen = Optional.of(origin);
			case Blueprints.SCHOOL -> {
				d.extra.school = Optional.of(origin);
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					VillageStyle st = STYLES.get(origin.asLong());
					Furnisher.furnishSchool(c.level(), Furnisher.boxOf(built), st == null ? null : st.stairs());
				}
			}
			case Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.KITCHEN, Blueprints.STATION -> {
				de.villagerplus.village.Specials.set(ref, d.planType, origin);
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					Furnisher.furnishSpecial(c.level(), Furnisher.boxOf(built), d.planType);
				}
			}
			case Blueprints.STORAGE -> {
				if (d.extra.lager.isEmpty()) {
					d.extra.lager = Optional.of(origin);
				} else {
					d.extra.lagerMore.add(origin);
				}
				// Innen mit Doppelkisten einrichten und alle Kisten des Gebaeudes als Lagerkisten merken.
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					net.minecraft.world.level.levelgen.structure.BoundingBox bb = Furnisher.boxOf(built);
					Furnisher.placeDoubleChests(c.level(), bb, 8);
					for (BlockPos chest : Furnisher.collectChests(c.level(), bb)) {
						if (!d.extra.lagerChests.contains(chest)) {
							d.extra.lagerChests.add(chest);
						}
					}
				}
			}
			case Blueprints.DECOR -> d.extra.decor++;
			case Blueprints.TOWNHALL -> d.townHallBuilt = true;
			default -> d.wellBuilt = true;
		}
		JobUtil.tellNearbyPlayers(c.level(), c.v(), 40, Component.literal("Fertig: " + label(d.planType) + " – das Dorf wächst!"));
		STYLES.remove(origin.asLong());
		STEPS.remove(origin.asLong());
		Lager.invalidate();
		if (d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN)) {
			d.extra.nextFarm = c.now() + Config.BUILD_COOLDOWN;
		} else {
			d.nextBuild = c.now() + Config.BUILD_COOLDOWN;
		}
		d.wanted.clear();
		d.clearPlan();
		ref.dirty();
		c.mem().jobsDone++;
	}

	// --- Bezahlen -------------------------------------------------------------------------------

	/** Nimmt die Kosten aus der Kiste und gibt den zu setzenden Block zurueck (null = nicht bezahlbar). */
	private static Block pay(Step s, Container box) {
		if (s.item() != null && JobUtil.count(box, st -> st.is(s.item())) >= s.cost()) {
			JobUtil.take(box, st -> st.is(s.item()), s.cost());
			return Block.byItem(s.item());
		}
		if (s.tag() != null) {
			for (int i = 0; i < box.getContainerSize(); i++) {
				ItemStack stack = box.getItem(i);
				if (!stack.isEmpty() && stack.is(s.tag())) {
					Item item = stack.getItem();
					if (JobUtil.count(box, st -> st.is(item)) >= s.cost()) {
						JobUtil.take(box, st -> st.is(item), s.cost());
						return Block.byItem(item);
					}
				}
			}
		}
		return null;
	}

	private record Key(Item item, TagKey<Item> tag) { }

	/** Fehlendes Material: Gegenstand (oder Tag), Menge und Anzeigetext. */
	private record Gap(Item item, int amount, int total, Component text) { }

	/** Welche Materialien fehlen in der Kiste fuer den kompletten Bau? (groesste Luecken zuerst) */
	private static List<Gap> missing(List<Step> steps, Container box) {
		Map<Key, Integer> need = new HashMap<>();
		for (Step s : steps) {
			if (!s.free() && !s.optional() && s.linkedTo() == null && s.cost() > 0) {
				need.merge(new Key(s.item(), s.tag()), s.cost(), Integer::sum);
			}
		}
		List<Gap> gaps = new ArrayList<>();
		for (Map.Entry<Key, Integer> e : need.entrySet()) {
			Key k = e.getKey();
			int have = JobUtil.count(box, st -> (k.item() != null && st.is(k.item())) || (k.tag() != null && st.is(k.tag())));
			if (have < e.getValue()) {
				int amount = e.getValue() - have;
				Component name = k.item() != null ? new ItemStack(k.item()).getHoverName()
					: Component.literal(k.tag().equals(ItemTags.BEDS) ? "Bett" : "Holztür");
				// Bett/Tuer mit Tag sind nur dann "Wunsch", wenn es keinen exakten Gegenstand gibt - Betten baut der Schaefer.
				Item request=k.tag()!=null && k.tag().equals(ItemTags.BEDS)?Items.BED.white() : k.item()!=null?k.item():Items.OAK_DOOR;
                gaps.add(new Gap(request, amount, e.getValue(), Component.literal(amount + "x ").append(name)));
			}
		}
		gaps.sort(Comparator.comparingInt(Gap::amount).reversed());
		return gaps;
	}

	// --- Instandhaltung -------------------------------------------------------------------------

	private static final java.util.Set<Long> REPAIRS = new java.util.HashSet<>();
	private static final Map<Long, Long> REPAIR_AT = new HashMap<>();
	private static final Map<Long, Integer> REPAIR_IDX = new HashMap<>();
	private static final long WALL_KEY = 0x57414C4CL;

	/** Maurer pruefen reihum ein eigenes Gebaeude bzw. die Stadtmauer und bessern fehlende Bloecke aus. true = Ausbesserung gestartet. */
	private static boolean startRepair(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		long key = ref.bell().asLong();
		int n = REPAIR_IDX.merge(key, 1, Integer::sum);
		List<Built> list = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of());
		if (d.wallBuilt && (list.isEmpty() || n % 4 == 0)) {
			return startWallRepair(c, ref);
		}
		if (list.isEmpty()) {
			return false;
		}
		Built b = list.get(n % list.size());
		if (!level.hasChunkAt(b.origin())) {
			return false;
		}
		// Die damals benutzte Palette (gespeichert); nur ohne Eintrag wird neu gelesen (neuere Bauten wuerden sie verfaelschen).
		Palette palette = Palette.ofIds(b.palette());
		if (palette == null) {
			palette = PALETTES.computeIfAbsent(b.origin().asLong(), k -> Palette.detect(level, ref.bell(), c.v()));
			if ((b.variant() & 2) != 0) {
				palette = palette.obtainable();
			}
		}
		Direction dir = Direction.from2DDataValue(b.dir());
		List<Step> all = Architect.build(Architect.design(kindOf(b.type()), b.variant() >>> 2), palette, b.origin(), dir);
		List<Step> damaged = new ArrayList<>();
		int paid = 0;
		for (Step s : all) {
			BlockState cur = level.getBlockState(s.pos());
			if (s.free()) {
				continue;
			}
			paid++;
			boolean done = s.linkedTo() != null ? !cur.isAir() : cur.is(Block.byItem(s.item()));
			if (!done && (cur.isAir() || cur.canBeReplaced())) {
				damaged.add(s);
			}
		}
		if (damaged.isEmpty()) {
			return false;
		}
		if (damaged.size() * 2 > paid) {
			// Mehr als die Haelfte fehlt: abgerissen oder zerstoert - nicht von selbst neu bauen, Eintrag vergessen.
			List<Built> rest = new ArrayList<>(list);
			rest.remove(b);
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, rest);
			return false;
		}
		d.planType = b.type();
		d.planOrigin = Optional.of(b.origin());
		d.planDir = b.dir();
		d.planStep = 0;
		d.planVariant = b.variant();
		d.planSource = Optional.empty();
		d.planProgress = c.now();
		STEPS.put(b.origin().asLong(), damaged);
		REPAIRS.add(b.origin().asLong());
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Ausbesserung von '{}' bei {}: {} Bloecke fehlen", b.type(), b.origin().toShortString(), damaged.size());
		return true;
	}

	/** Radius der Stadtmauer: sucht das Nord-Tor (Torgasse 4 hoch frei, Sturz darueber). 0 = nicht gefunden. */
	private static int detectWallRadius(ServerLevel level, BlockPos bell, VillageStyle style) {
		for (int r = Config.WALL_MIN_VILLAGE_RADIUS; r <= Development.MAX_WALL_RADIUS; r++) {
			int x = bell.getX();
			int z = bell.getZ() - r;
			if (!level.hasChunkAt(new BlockPos(x, bell.getY(), z))) {
				continue;
			}
			int h = Wall.groundHeight(level, x, z + 1, style.wall());
			if (level.getBlockState(new BlockPos(x, h + 4, z + 1)).is(style.wall()) && level.getBlockState(new BlockPos(x, h + 5, z + 1)).is(style.wall())
				&& !level.getBlockState(new BlockPos(x, h + 2, z + 1)).is(style.wall())) {
				return r;
			}
		}
		return 0;
	}

	private static boolean startWallRepair(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		int r = detectWallRadius(level, ref.bell(), style);
		if (r == 0) {
			return false;
		}
		boolean damaged = false;
		for (int t = 0; t < 8 * r && !damaged; t++) {
			damaged = !columnIntact(level, ref.bell(), style, r, t);
		}
		if (!damaged) {
			return false;
		}
		d.planType = Blueprints.WALL;
		d.planOrigin = Optional.of(ref.bell());
		d.planStep = 0;
		d.planVariant = r;
		d.planProgress = c.now();
		REPAIRS.add(ref.bell().asLong() ^ WALL_KEY);
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Ausbesserung der Stadtmauer (Radius {})", r);
		return true;
	}

	/** A column is complete only when its available plan and every structural/decorative cell match. */
	static boolean columnIntact(ServerLevel level, BlockPos bell, VillageStyle style, int r, int t) {
		if (r <= 0 || t < 0 || t >= 8 * r) return false;
		int[] o = Wall.ringOffset(r, t);
		if (!level.hasChunkAt(new BlockPos(bell.getX() + o[0], bell.getY(), bell.getZ() + o[1]))) {
			return false;
		}
		List<Wall.Cell> cells = Wall.column(level, bell, style.wall(), style.stairs(), style.fence(), r, t);
		if (cells.isEmpty()) return false;
		for (Wall.Cell cell : cells) if (!Wall.matches(cell, level.getBlockState(cell.pos()))) return false;
		return true;
	}

	/**
	 * Nur fuer die Entwicklungsumgebung: legt einen Bauauftrag an einer festen Stelle an, den die Maurer dann ganz normal abarbeiten
	 * (zum Testen des echten Bauablaufs). {@code seed} = Entwurfs-Seed; bei "wall" der Radius.
	 */
	public static boolean debugPlan(ServerLevel level, VillageRef ref, String type, BlockPos hintSite, Direction hintDir, long seed, Villager hint) {
		VillageData d = ref.data();
		if (d.hasPlan()) {
			return false;
		}
		d.wanted.clear();
		if (type.equals(Blueprints.WALL)) {
			d.planType = type;
			d.planOrigin = Optional.of(ref.bell());
			d.planStep = 0;
			d.planVariant = (int) seed;
			d.planProgress = level.getGameTime();
			ref.dirty();
			return true;
		}
		int half;
		int headroom;
		if (architect(type)) {
			Architect.Design design = Architect.design(kindOf(type), seed);
			half = design.half();
			headroom = design.headroom();
		} else if (Landmarks.handles(type)) {
			half = Landmarks.half(type);
			headroom = Landmarks.headroom(type);
		} else {
			return false;
		}
		// Platzsuche genau wie beim echten Bau (Abstand zu Nachbarn, tragender Boden, Huegeltoleranz).
		net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(seed * 31 + 7);
		BlockPos site = Sites.find(level, ref.bell(), rnd, 8, 44, half, headroom, 120);
		if (site == null) {
			site = Sites.findTolerant(level, ref.bell(), rnd, 8, 44, -half, half, -half, half, headroom + 1, 200);
		}
		if (site == null) {
			return false;
		}
		Direction dir = Sites.facingTowards(site, ref.bell());
		Palette palette = Palette.detect(level, ref.bell(), hint, seed, site);
		PALETTES.put(site.asLong(), palette);
		List<Step> steps = architect(type) ? Architect.build(Architect.design(kindOf(type), seed), palette, site, dir)
			: Landmarks.build(type, seed, palette, site, dir);
		STEPS.put(site.asLong(), steps);
		DEBUG_PLANS.add(site.asLong());
		d.planType = type;
		d.planOrigin = Optional.of(site);
		d.planDir = dir.get2DDataValue();
		d.planStep = 0;
		d.planVariant = (int) (seed << 2);
		d.planSource = Optional.empty();
		d.planProgress = level.getGameTime();
		ref.dirty();
		return true;
	}

	/** Nur fuer die Entwicklungsumgebung: baut die komplette Stadtmauer sofort (zum Betrachten). */
	public static int debugWall(ServerLevel level, VillageRef ref, int r, Villager hint) {
		VillageStyle style = wallStyle(level, ref.bell(), hint);
		int placed = 0;
		for (int t = 0; t < 8 * r; t++) {
			for (Wall.Cell cell : Wall.column(level, ref.bell(), style.wall(), style.stairs(), style.fence(), r, t)) {
				level.setBlock(cell.pos(), cell.state(), 2);
				placed++;
			}
		}
		return placed;
	}

	// --- Stadtmauer -----------------------------------------------------------------------------

	/**
	 * Stil der Stadtmauer: eine Burgmauer ist aus Stein (Steinziegel, sonst Bruchstein; in Wuesten Sandstein) - nicht aus dem
	 * Wandmaterial der Haeuser (das in manchen Doerfern Terrakotta ist). Zaun und Tuer bleiben wie im Dorf.
	 */
	private static VillageStyle wallStyle(ServerLevel level, BlockPos bell, Villager hint) {
		VillageStyle style = VillageStyle.detect(level, bell, hint);
		Block wall;
		Block stairs;
		if (Palette.biomeKind(level, bell).equals("desert")) {
			wall = Blocks.SANDSTONE;
			stairs = Blocks.SANDSTONE_STAIRS;
		} else if (Supply.obtainable(Blocks.STONE_BRICKS)) {
			wall = Blocks.STONE_BRICKS;
			stairs = Blocks.STONE_BRICK_STAIRS;
		} else {
			wall = Blocks.COBBLESTONE;
			stairs = Blocks.COBBLESTONE_STAIRS;
		}
		return new VillageStyle(wall, style.floor(), style.roof(), stairs, style.door(), style.fence());
	}

	private static final Map<Long, VillageStyle> WALL_STYLES = new HashMap<>();
	private static final Map<Long, Long> WALL_CHECKED = new HashMap<>();

	/** Stehen inzwischen Haeuser ausserhalb (bzw. zu nah an) der Mauer? Geprueft hoechstens alle 6000 Ticks. */
	private static boolean wallTooSmall(ServerLevel level, VillageRef ref) {
		long now = level.getGameTime();
		if (now < WALL_CHECKED.getOrDefault(ref.bell().asLong(), 0L)) {
			return false;
		}
		WALL_CHECKED.put(ref.bell().asLong(), now + 6000L);
		int needed = neededWallRadius(level, ref);
		if (needed == 0) {
			return false;
		}
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), null));
		int current = detectWallRadius(level, ref.bell(), style);
		return current > 0 && needed > current + 4;
	}

	/** Radius, den die Mauer bei der jetzigen Dorfgroesse braucht (aeusserstes Haus + 10), sonst 0 (Dorf zu klein/zu verstreut). */
	private static int neededWallRadius(ServerLevel level, VillageRef ref) {
		return Development.neededWallRadius(level, ref);
	}

	private static final int WALL_HEIGHT = 5;

	/** Position der Mauersaeule t auf dem quadratischen Ring mit Radius r um die Glocke (Seiten im Uhrzeigersinn). */
	private static int[] ringOffset(int r, int t) {
		int side = t / (2 * r);
		int k = t % (2 * r);
		return switch (side) {
			case 0 -> new int[] {-r + k, -r};
			case 1 -> new int[] {r, -r + k};
			case 2 -> new int[] {r - k, r};
			default -> new int[] {-r, r - k};
		};
	}

	private static boolean isGate(int r, int t) {
		int k = t % (2 * r);
		return Math.abs(k - r) <= 1;
	}

	private static boolean startWall(Ctx c, VillageRef ref, Container box, List<Villager> all) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		int radius = neededWallRadius(level, ref);
		if (radius == 0) {
			c.st().buildCheckAt = c.now() + 6000;
			return false;
		}
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		int old = d.wallBuilt ? detectWallRadius(level, ref.bell(), style) : 0;
		if (old >= radius) {
			c.st().buildCheckAt = c.now() + 6000;
			return false;
		}
		Item wallItem = style.wall().asItem();
		d.wanted.clear();
		if (JobUtil.count(box, s -> s.is(wallItem)) < 192) {
			d.wanted.add(BuiltInRegistries.ITEM.getKey(wallItem) + "#900");
			ref.dirty();
			c.st().buildCheckAt = c.now() + 1800;
			return false;
		}
		d.planType = Blueprints.WALL;
		d.planOrigin = Optional.of(ref.bell());
		d.planStep = 0;
		// Untere 8 Bit: neuer Radius, daruber der alte (wird nach Fertigstellung abgetragen).
		d.planVariant = radius | (old << 8);
		d.planProgress = c.now();
		ref.dirty();
		JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal(old > 0
			? "Das Dorf ist gewachsen: Die Stadtmauer wird weiter draussen neu gebaut (Radius " + old + " -> " + radius + ")."
			: "Das Dorf beginnt mit dem Bau einer Stadtmauer (Radius " + radius + ")."));
		return true;
	}

	private static boolean continueWall(Ctx c, VillageRef ref, Container box) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		int r = d.planVariant & 0xFF;
		int oldR = (d.planVariant >> 8) & 0xFF;
		if (r <= 0 || r > Development.MAX_WALL_RADIUS) { abort(d, ref, c.now()); return false; }
		de.villagerplus.village.Anchor.keepWallWorkLoaded(level, ref, c.v().blockPosition());
		VillageStyle wallStyle = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		for (int skip = 0; skip < 300 && d.planStep < 8 * r && columnIntact(level, ref.bell(), wallStyle, r, d.planStep); skip++) {
			d.planStep++;
		}
		de.villagerplus.village.Anchor.keepWallWorkLoaded(level, ref, c.v().blockPosition());
		if (d.planStep >= 8 * r && oldR > 0 && oldR != r) {
			// Neue Mauer steht: die alte (innen) wird Saeule fuer Saeule abgetragen, das Material kommt zurueck in die Kiste.
			int t = d.planStep - 8 * r;
			if (t < 8 * oldR) {
				c.setActivity("baut die alte Stadtmauer ab");
				for (int col = 0; col < 3 && t < 8 * oldR; col++, t++) {
					List<Wall.Cell> oldCells = Wall.column(level, ref.bell(), wallStyle.wall(), wallStyle.stairs(), wallStyle.fence(), oldR, t);
					if (oldCells.isEmpty()) return waitForWall(c, ref, "wartet auf den geladenen Abschnitt der alten Stadtmauer");
					for (Wall.Cell cell : oldCells) {
						BlockState cur = level.getBlockState(cell.pos());
						if (!cell.state().isAir() && Wall.matches(cell, cur)) {
							level.setBlock(cell.pos(), Blocks.AIR.defaultBlockState(), 3);
							if (cell.paid()) {
								JobUtil.depositTo(box, new net.minecraft.world.item.ItemStack(wallStyle.wall().asItem()));
							}
						}
					}
					d.planStep++;
				}
				d.planProgress = c.now();
				ref.dirty();
				JobUtil.swing(c.v());
				return true;
			}
		}
		if (d.planStep >= 8 * r) {
			if (REPAIRS.remove(ref.bell().asLong() ^ WALL_KEY)) {
				d.clearPlan();
				d.nextBuild = c.now() + 1200;
				d.wanted.clear();
				ref.dirty();
				JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Die Stadtmauer wurde ausgebessert."));
				return true;
			}
			d.wallBuilt = true;
			d.clearPlan();
			d.nextBuild = c.now() + Config.BUILD_COOLDOWN;
			d.wanted.clear();
			ref.dirty();
			JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Die Stadtmauer ist fertig!"));
			c.mem().jobsDone++;
			return true;
		}
		VillageStyle style = wallStyle;
		Item wallItem = style.wall().asItem();
		c.setActivity("baut die Stadtmauer");
		Anim.hold(c, wallItem);
		int[] off = Wall.ringOffset(r, d.planStep);
		int bx = ref.bell().getX() + off[0];
		int bz = ref.bell().getZ() + off[1];
		if (!level.hasChunkAt(new BlockPos(bx, ref.bell().getY(), bz))) {
			// Chunk nicht geladen: Saeule spaeter, Auftrag wartet.
			return waitForWall(c, ref, "wartet auf den geladenen Mauerabschnitt");
		}
		// Etwas Abstand zur Mauer (innen), nie auf der Mauer selbst und nicht auf der Treppe.
		net.minecraft.core.Direction in = Wall.inward(r, d.planStep);
		int sx = bx + in.getStepX() * 11;
		int sz = bz + in.getStepZ() * 11;
		BlockPos standSpot = new BlockPos(sx, Wall.groundHeight(level, sx, sz, style.wall()), sz);
		if (!JobUtil.approach(c, standSpot, 7.0)) {
			return true;
		}
		BlockPos selfPos = c.v().blockPosition();
		if (Math.abs(selfPos.getX() - bx) < 5 && Math.abs(selfPos.getZ() - bz) < 5 && Math.abs(selfPos.getY() - standSpot.getY()) < 12) {
			// Zu nah an der Baustelle (z. B. auf dem Wehrgang): zurueck auf festen Boden.
			c.v().teleportTo(standSpot.getX() + 0.5, standSpot.getY(), standSpot.getZ() + 0.5);
			return true;
		}
		for (int col = 0; col < 2 && d.planStep < 8 * r; col++) {
			int t = d.planStep;
			List<Wall.Cell> cells = Wall.column(level, ref.bell(), style.wall(), style.stairs(), style.fence(), r, t);
			if (cells.isEmpty()) return waitForWall(c, ref, "wartet auf einen geladenen, tragfähigen Mauerabschnitt");
			// Do not consume resources or advance the cursor through a foreign building/obstacle.
			for (Wall.Cell cell : cells) {
				BlockState current = level.getBlockState(cell.pos());
				if (!Wall.matches(cell, current) && (!Wall.mayReplace(cell, current) || occupied(level, cell.pos())))
					return waitForWall(c, ref, "Mauerabschnitt blockiert bei " + cell.pos().toShortString());
			}
			if (t == 0 || t % 40 == 0) {
				LOGGER.info("Villagers: Repurposed: Stadtmauer Saeule {} von {} (Radius {})", t, 8 * r, r);
			}
			for (Wall.Cell cell : cells) {
				if (cell.paid()) {
					if (!placeWall(c, ref, box, style, cell.pos())) {
						return true;
					}
				} else {
					BlockState cur = level.getBlockState(cell.pos());
					if (!Wall.matches(cell, cur) && Wall.mayReplace(cell, cur) && !occupied(level, cell.pos())) {
						level.setBlock(cell.pos(), cell.state(), 3);
					}
				}
			}
			if (!columnIntact(level, ref.bell(), style, r, t)) return waitForWall(c, ref, "wartet auf die Fertigstellung der Mauersäule");
			JobUtil.swing(c.v());
			d.planStep++;
			d.planProgress = c.now();
		}
		ref.dirty();
		return true;
	}

	private static boolean waitForWall(Ctx c, VillageRef ref, String activity) {
		c.setActivity(activity); c.st().cooldownUntil = c.now() + 20;
		ref.data().planProgress = c.now(); ref.dirty(); return true;
	}

	/** Setzt einen Mauerblock, bezahlt aus der Dorfkiste. false = Material fehlt oder Platz belegt (Bau wartet). */
	private static boolean placeWall(Ctx c, VillageRef ref, Container box, VillageStyle style, BlockPos p) {
		ServerLevel level = c.level();
		BlockState cur = level.getBlockState(p);
		if (cur.is(style.wall())) return true;
		if (!(cur.isAir() || cur.canBeReplaced() || !cur.getFluidState().isEmpty())) return false;
		if (occupied(level, p)) {
			return false;
		}
		Item wallItem = style.wall().asItem();
		if (JobUtil.take(box, s -> s.is(wallItem), 1) < 1) {
			addWanted(ref.data(), new Step(p, wallItem, null, 1, false, null, b -> style.wall().defaultBlockState()), box);
			ref.dirty();
			JobUtil.tellNearbyPlayers(level, c.v(), 32, Component.literal("Für die Stadtmauer fehlt Material – die Dorfbewohner beschaffen Nachschub."));
			ref.data().planProgress = c.now();
			c.st().cooldownUntil = c.now() + 600;
			return false;
		}
		BlockState ns = style.wall().defaultBlockState();
		if (!level.setBlock(p, ns, 3)) {
			ItemStack refund = new ItemStack(wallItem); JobUtil.depositTo(box, refund);
			if (!refund.isEmpty()) JobUtil.give(level, c.v(), refund);
			return false;
		}
		level.playSound(null, p, ns.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
		return true;
	}
	// --- Wege -----------------------------------------------------------------------------------

	/** Streut einen Trampelpfad (nur auf Gras/Erde) von einem Haus bis zur Dorfglocke. */
	static final class Paths {
		private Paths() {
		}

		/** Vorgarten: zwei kleine Beete beidseits des Wegs vor dem Haus (Blumen und Setzling auf Gras, Kaktus/Strauch auf Sand). */
		static void garden(ServerLevel level, BlockPos origin, Direction dir, VillageRef ref) {
			java.util.Random r = new java.util.Random(origin.asLong());
			Direction side = dir.getClockWise();
			net.minecraft.world.level.block.Block[] flowers = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.ALLIUM, Blocks.OXEYE_DAISY};
			for (int s : new int[] {-4, 4}) {
				BlockPos base = origin.relative(dir, 8).relative(side, s);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos col = base.relative(dir, dz).relative(side, dx);
						if (!ZoneAwareSites.allowsRect(ref, Blueprints.HOUSE, col, 0, 0, 0, 0)) continue;
						if (!level.hasChunkAt(col)) {
							continue;
						}
						int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
						BlockPos p = new BlockPos(col.getX(), y, col.getZ());
						BlockState ground = level.getBlockState(p.below());
						if (!level.getBlockState(p).isAir()) {
							continue;
						}
						boolean grass = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT);
						boolean sand = ground.is(net.minecraft.tags.BlockTags.SAND);
						if (grass) {
							level.setBlock(p, dx == 0 && dz == 0 ? Blocks.OAK_SAPLING.defaultBlockState() : flowers[r.nextInt(flowers.length)].defaultBlockState(), 3);
						} else if (sand && r.nextInt(2) == 0) {
							level.setBlock(p, dx == 0 && dz == 0 ? Blocks.CACTUS.defaultBlockState() : Blocks.DEAD_BUSH.defaultBlockState(), 3);
						}
					}
				}
			}
		}

		static void lay(ServerLevel level, BlockPos from, BlockPos to) {
			// Bounded A*: start at the exterior door, follow supported ground and route around houses, fields and cliffs.
			int minX = Math.min(from.getX(), to.getX()) - 8, maxX = Math.max(from.getX(), to.getX()) + 8;
			int minZ = Math.min(from.getZ(), to.getZ()) - 8, maxZ = Math.max(from.getZ(), to.getZ()) + 8;
			BlockPos start = pathSurface(level, from.getX(), from.getZ());
			if (start == null) return;
			long startKey = BlockPos.asLong(start.getX(), 0, start.getZ());
			java.util.PriorityQueue<PathNode> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
			Map<Long, Double> costs = new HashMap<>();
			Map<Long, Long> parent = new HashMap<>();
			Map<Long, BlockPos> surfaces = new HashMap<>();
			costs.put(startKey, 0.0); surfaces.put(startKey, start);
			open.add(new PathNode(startKey, 0, pathHeuristic(start, to)));
			long goal = Long.MIN_VALUE;
			int expanded = 0;
			while (!open.isEmpty() && expanded++ < 4096) {
				PathNode node = open.poll();
				BlockPos current = surfaces.get(node.key());
				if (current == null || node.cost() > costs.getOrDefault(node.key(), Double.POSITIVE_INFINITY)) continue;
				if (Math.abs(current.getX() - to.getX()) + Math.abs(current.getZ() - to.getZ()) <= 1) { goal = node.key(); break; }
				for (Direction step : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
					int x = current.getX() + step.getStepX(), z = current.getZ() + step.getStepZ();
					if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
					BlockPos next = pathSurface(level, x, z);
					if (next == null || Math.abs(next.getY() - current.getY()) > 1) continue;
					long key = BlockPos.asLong(x, 0, z);
					double stepCost = 1.0 + Math.abs(next.getY() - current.getY()) * 0.8
						+ (level.getBlockState(next).is(Blocks.DIRT_PATH) ? -0.35 : 0.0);
					double candidate = node.cost() + stepCost;
					if (candidate >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
					costs.put(key, candidate); parent.put(key, node.key()); surfaces.put(key, next);
					open.add(new PathNode(key, candidate, candidate + pathHeuristic(next, to)));
				}
			}
			if (goal == Long.MIN_VALUE) return;
			List<BlockPos> route = new ArrayList<>();
			for (long key = goal; key != startKey; key = parent.getOrDefault(key, Long.MIN_VALUE)) {
				BlockPos p = surfaces.get(key);
				if (p == null) return;
				route.add(p);
			}
			java.util.Collections.reverse(route);
			route.add(0, start);
			for (BlockPos p : route) {
				BlockPos above = p.above();
				BlockState obstruction = level.getBlockState(above);
				if (!obstruction.isAir() && obstruction.canBeReplaced() && !obstruction.hasBlockEntity()) {
					level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
				}
				level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3);
			}
		}

		private record PathNode(long key, double cost, double score) { }

		private static double pathHeuristic(BlockPos from, BlockPos to) {
			return Math.max(0, Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ()) - 1);
		}

		private static BlockPos pathSurface(ServerLevel level, int x, int z) {
			BlockPos column = new BlockPos(x, level.getMinY(), z);
			if (!level.hasChunkAt(column)) return null;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			BlockPos ground = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(ground);
			if (!state.getFluidState().isEmpty() || state.is(Blocks.FARMLAND) || state.hasBlockEntity()) return null;
			boolean soil = state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
				|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT_PATH);
			if (!soil || !state.isFaceSturdy(level, ground, Direction.UP)) return null;
			BlockState above = level.getBlockState(ground.above());
			if (!above.isAir() && (!above.canBeReplaced() || above.hasBlockEntity())) return null;
			return ground;
		}

		static BlockPos entrance(List<Step> steps, BlockPos origin, Direction facing) {
			if (steps != null) for (Step step : steps) {
				BlockState state = step.make().apply(Blocks.AIR);
				if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
					&& state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
					return step.pos().relative(facing);
				}
			}
			return origin.relative(facing, 5);
		}
	}
}
