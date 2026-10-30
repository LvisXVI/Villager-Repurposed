package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Pro Dorf gespeicherte Daten (haengen als Attachment an der Dorfglocke). */
public class VillageData {
	/** Stollen-Mine: Eingang an der Oberflaeche, Treppe nach unten, dann waagerechter Stollen. */
	public static class Mine {
		public static final Codec<Mine> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("entrance").forGetter(m -> m.entrance),
			Codec.INT.optionalFieldOf("dir", 0).forGetter(m -> m.dir),
			Codec.INT.optionalFieldOf("index", 0).forGetter(m -> m.index),
			Codec.INT.optionalFieldOf("target_y", 0).forGetter(m -> m.targetY),
			Codec.BOOL.optionalFieldOf("done", false).forGetter(m -> m.done)
		).apply(i, Mine::new));

		public final BlockPos entrance;
		public final int dir;
		public int index;
		public final int targetY;
		public boolean done;

		public Mine(BlockPos entrance, int dir, int index, int targetY, boolean done) {
			this.entrance = entrance;
			this.dir = dir;
			this.index = index;
			this.targetY = targetY;
			this.done = done;
		}
	}

	/** Weitere Dorfdaten: Tiergehege, Abklingzeiten fuer Felder und Handelskarawanen. */
	public static class Extra {
		public static final Codec<Extra> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.optionalFieldOf("pen").forGetter(e -> e.pen),
			Codec.LONG.optionalFieldOf("next_caravan", 0L).forGetter(e -> e.nextCaravan),
			Codec.LONG.optionalFieldOf("next_farm", 0L).forGetter(e -> e.nextFarm),
			Codec.LONG.optionalFieldOf("next_care", 0L).forGetter(e -> e.nextCare),
			Mine.CODEC.listOf().optionalFieldOf("old_mines", List.of()).forGetter(e -> e.oldMines),
			Codec.INT.optionalFieldOf("decor", 0).forGetter(e -> e.decor),
			Codec.LONG.optionalFieldOf("next_guests", 0L).forGetter(e -> e.nextGuests),
			BlockPos.CODEC.optionalFieldOf("board").forGetter(e -> e.board),
			BlockPos.CODEC.optionalFieldOf("lager").forGetter(e -> e.lager),
			BlockPos.CODEC.listOf().optionalFieldOf("lager_more", List.of()).forGetter(e -> e.lagerMore),
			Codec.STRING.optionalFieldOf("exp_active").forGetter(e -> e.expActive),
			Codec.STRING.listOf().optionalFieldOf("exp_log", List.of()).forGetter(e -> e.expLog),
			Codec.INT.listOf().optionalFieldOf("exp_stats", List.of()).forGetter(e -> e.expStats),
			Codec.LONG.optionalFieldOf("exp_next", 0L).forGetter(e -> e.expNext),
			BlockPos.CODEC.optionalFieldOf("school").forGetter(e -> e.school),
			BlockPos.CODEC.listOf().optionalFieldOf("lager_chests", List.of()).forGetter(e -> e.lagerChests)
		).apply(i, Extra::new));

		/** Mittelpunkt des Tiergeheges (leer = noch keins). */
		public Optional<BlockPos> pen;
		public long nextCaravan;
		public long nextFarm;
		/** Naechste Fuetterungsrunde im Gehege. */
		public long nextCare;
		/** Fruehere (ausgebeutete) Minen: Eingaenge bleiben bekannt, damit niemand im Stollen stecken bleibt. */
		public final List<Mine> oldMines = new ArrayList<>();
		/** Anzahl der bereits aufgestellten Reichtums-Aufbauten (Stufe des Dorfreichtums). */
		public int decor;
		public long nextGuests;
		/** Position der Dorftafel (Schild in der Dorfmitte). */
		public Optional<BlockPos> board = Optional.empty();
		/** Mittelpunkt des Lagerschuppens (Ueberschuss, wenn die Dorfkiste voll ist). */
		public Optional<BlockPos> lager = Optional.empty();
		/** Weitere Lagerschuppen (das Lager wird erweitert, wenn alles voll ist). */
		public final List<BlockPos> lagerMore = new ArrayList<>();
		/** Laufende Expedition als Text "uuid,uuid;endeZeit;zielIndex" (leer = keine). */
		public Optional<String> expActive = Optional.empty();
		/** Die letzten Expeditionsberichte (neueste zuletzt). */
		public final List<String> expLog = new ArrayList<>();
		/** Statistik: [gestartet, heimgekehrt, gefallen, Beutestuecke]. */
		public final List<Integer> expStats = new ArrayList<>();
		public long expNext;
		/** Mittelpunkt der Dorfschule (dort lernen die Kinder). */
		public Optional<BlockPos> school = Optional.empty();
		/** Alle Kisten der Lagerhaeuser (bei Doppelkisten die linke Haelfte, eine Position je Kiste). */
		public final List<BlockPos> lagerChests = new ArrayList<>();

		public Extra() {
			this(Optional.empty(), 0L, 0L, 0L, List.of(), 0, 0L, Optional.empty(), Optional.empty(), List.of(), Optional.empty(), List.of(), List.of(), 0L,
				Optional.empty(), List.of());
		}

		public Extra(Optional<BlockPos> pen, long nextCaravan, long nextFarm, long nextCare, List<Mine> oldMines, int decor, long nextGuests,
			Optional<BlockPos> board, Optional<BlockPos> lager, List<BlockPos> lagerMore, Optional<String> expActive, List<String> expLog,
			List<Integer> expStats, long expNext, Optional<BlockPos> school, List<BlockPos> lagerChests) {
			this.school = school;
			this.lagerChests.addAll(lagerChests);
			this.board = board;
			this.lager = lager;
			this.lagerMore.addAll(lagerMore);
			this.expActive = expActive;
			this.expLog.addAll(expLog);
			this.expStats.addAll(expStats);
			this.expNext = expNext;
			this.decor = decor;
			this.nextGuests = nextGuests;
			this.oldMines.addAll(oldMines);
			this.pen = pen;
			this.nextCaravan = nextCaravan;
			this.nextFarm = nextFarm;
			this.nextCare = nextCare;
		}
	}

	private static final int FLAG_WELL = 1;
	private static final int FLAG_HALL = 2;
	private static final int FLAG_WALL = 4;

	public static final Codec<VillageData> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.optionalFieldOf("chest").forGetter(d -> d.chest),
		Codec.STRING.optionalFieldOf("plan_type", "").forGetter(d -> d.planType),
		BlockPos.CODEC.optionalFieldOf("plan_origin").forGetter(d -> d.planOrigin),
		Codec.INT.optionalFieldOf("plan_dir", 0).forGetter(d -> d.planDir),
		Codec.INT.optionalFieldOf("plan_step", 0).forGetter(d -> d.planStep),
		Codec.INT.optionalFieldOf("plan_variant", 0).forGetter(d -> d.planVariant),
		Codec.LONG.optionalFieldOf("next_build", 0L).forGetter(d -> d.nextBuild),
		Codec.INT.optionalFieldOf("flags", 0).forGetter(VillageData::flags),
		Codec.INT.optionalFieldOf("houses", 0).forGetter(d -> d.houses),
		Codec.INT.optionalFieldOf("farms", 0).forGetter(d -> d.farms),
		BlockPos.CODEC.optionalFieldOf("plan_source").forGetter(d -> d.planSource),
		Codec.INT.optionalFieldOf("pop_at_last_house", 0).forGetter(d -> d.popAtLastHouse),
		Codec.LONG.optionalFieldOf("plan_progress", 0L).forGetter(d -> d.planProgress),
		Mine.CODEC.optionalFieldOf("mine").forGetter(d -> d.mine),
		Codec.STRING.listOf().optionalFieldOf("wanted", List.of()).forGetter(d -> d.wanted),
		Extra.CODEC.optionalFieldOf("extra").forGetter(d -> Optional.of(d.extra))
	).apply(i, VillageData::new));

	/** Linke Haelfte der Doppelkiste in der Dorfmitte. */
	public Optional<BlockPos> chest = Optional.empty();
	/** Aktueller Bauauftrag: "house", "farm", "well", "townhall", "wall", "pen" oder leer. */
	public String planType = "";
	public Optional<BlockPos> planOrigin = Optional.empty();
	public int planDir;
	public int planStep;
	public int planVariant;
	public long nextBuild;
	public boolean wellBuilt;
	public boolean townHallBuilt;
	public boolean wallBuilt;
	public int houses;
	public int farms;
	/** Bett eines bestehenden Hauses, das als Vorlage fuer den Bau kopiert wird (leer = Standardhaus im Dorfstil). */
	public Optional<BlockPos> planSource = Optional.empty();
	/** Einwohnerzahl, als das letzte Haus geplant wurde. Ein neues Haus gibt es nur, wenn die Bevoelkerung darueber liegt. */
	public int popAtLastHouse;
	/** Zeitpunkt (Spielzeit) des letzten Baufortschritts - Wachhund gegen haengende Bauauftraege. */
	public long planProgress;
	public Optional<Mine> mine = Optional.empty();
	/** Fehlende Baumaterialien als "item_id#anzahl": sagt Schreinern, Steinmetzen, Bergleuten und Holzfaellern, was gebraucht wird. */
	public final List<String> wanted = new ArrayList<>();
	public Extra extra = new Extra();

	public VillageData() {
	}

	private VillageData(Optional<BlockPos> chest, String planType, Optional<BlockPos> planOrigin, int planDir, int planStep,
		int planVariant, long nextBuild, int flags, int houses, int farms, Optional<BlockPos> planSource, int popAtLastHouse,
		long planProgress, Optional<Mine> mine, List<String> wanted, Optional<Extra> extra) {
		this.chest = chest;
		this.planType = planType;
		this.planOrigin = planOrigin;
		this.planDir = planDir;
		this.planStep = planStep;
		this.planVariant = planVariant;
		this.nextBuild = nextBuild;
		this.wellBuilt = (flags & FLAG_WELL) != 0;
		this.townHallBuilt = (flags & FLAG_HALL) != 0;
		this.wallBuilt = (flags & FLAG_WALL) != 0;
		this.houses = houses;
		this.farms = farms;
		this.planSource = planSource;
		this.popAtLastHouse = popAtLastHouse;
		this.planProgress = planProgress;
		this.mine = mine;
		this.wanted.addAll(wanted);
		this.extra = extra.orElseGet(Extra::new);
	}

	private int flags() {
		return (wellBuilt ? FLAG_WELL : 0) | (townHallBuilt ? FLAG_HALL : 0) | (wallBuilt ? FLAG_WALL : 0);
	}

	public boolean hasPlan() {
		return !planType.isEmpty() && planOrigin.isPresent();
	}

	public void clearPlan() {
		planType = "";
		planOrigin = Optional.empty();
		planSource = Optional.empty();
		planStep = 0;
		planVariant = 0;
	}
}
