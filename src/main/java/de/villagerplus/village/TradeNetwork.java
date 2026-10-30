package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Handelsnetz eines Dorfes (haengt als eigenes Attachment an der Glocke; {@code VillageData.Extra} ist mit 16 Codec-Feldern voll):
 * die drei fiktiven Partnerorte der Karawanen samt Beziehung, die mit echten Dorfern verbundenen Nachbarn, Abklingzeiten je Aktion
 * und Paar sowie die Gruss-Stimmung. Alle Records sind unveraenderlich; Aenderungen erzeugen ein neues Objekt (siehe {@link Network}).
 */
public record TradeNetwork(List<Partner> partners, List<Link> links, Map<String, Long> cooldowns, int moodBonus, long moodBonusUntil, Map<String, Long> tradeBalance) {
	public static final int MAX_LINKS = 12;
	public TradeNetwork(List<Partner> partners,List<Link> links,Map<String,Long> cooldowns,int moodBonus,long moodBonusUntil) {
		this(partners,links,cooldowns,moodBonus,moodBonusUntil,Map.of());
	}

	/** Spezialitaeten der fiktiven Partnerorte. */
	public static final String[] SPECIALTIES = {"Erze & Werkzeug", "Stoffe & Wolle", "Lebensmittel", "Holz & Baumaterial"};

	/** Fiktiver Handelspartner: Name, Spezialitaet (Index in {@link #SPECIALTIES}), Beziehung -100..100, Tag des letzten Besuchs. */
	public record Partner(String name, int specialty, int relation, long lastVisitDay) {
		public static final Codec<Partner> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(Partner::name),
			Codec.INT.fieldOf("specialty").forGetter(Partner::specialty),
			Codec.INT.optionalFieldOf("relation", 0).forGetter(Partner::relation),
			Codec.LONG.optionalFieldOf("last_visit_day", -1L).forGetter(Partner::lastVisitDay)).apply(i, Partner::new));

		public Partner withRelation(int r) {
			return new Partner(name, specialty, Math.max(-100, Math.min(100, r)), lastVisitDay);
		}

		public Partner visited(long day) {
			return new Partner(name, specialty, relation, day);
		}
	}

	/** Verbindung zu einem echten Dorf (dimensionsqualifiziert), mit letztem Einwohnerstand. */
	public record Link(String dimension, BlockPos bell, String name, int relation, boolean route, int population, long populationDay) {
		public static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(Link::dimension),
			BlockPos.CODEC.fieldOf("bell").forGetter(Link::bell),
			Codec.STRING.fieldOf("name").forGetter(Link::name),
			Codec.INT.optionalFieldOf("relation", 10).forGetter(Link::relation),
			Codec.BOOL.optionalFieldOf("route", false).forGetter(Link::route),
			Codec.INT.optionalFieldOf("population", -1).forGetter(Link::population),
			Codec.LONG.optionalFieldOf("population_day", -1L).forGetter(Link::populationDay)).apply(i, Link::new));

		public Link withRelation(int r) {
			return new Link(dimension, bell, name, Math.max(-100, Math.min(100, r)), route, population, populationDay);
		}

		public Link withRoute(boolean on) {
			return new Link(dimension, bell, name, relation, on, population, populationDay);
		}

		public Link withPopulation(int pop, long day) {
			return new Link(dimension, bell, name, relation, route, pop, day);
		}
	}

	public static final Codec<TradeNetwork> CODEC = RecordCodecBuilder.create(i -> i.group(
		Partner.CODEC.listOf().optionalFieldOf("partners", List.of()).forGetter(TradeNetwork::partners),
		Link.CODEC.listOf().optionalFieldOf("links", List.of()).forGetter(TradeNetwork::links),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("cooldowns", Map.of()).forGetter(TradeNetwork::cooldowns),
		Codec.INT.optionalFieldOf("mood_bonus", 0).forGetter(TradeNetwork::moodBonus),
		Codec.LONG.optionalFieldOf("mood_bonus_until", 0L).forGetter(TradeNetwork::moodBonusUntil),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("trade_balance", Map.of()).forGetter(TradeNetwork::tradeBalance)).apply(i, TradeNetwork::new));

	public TradeNetwork withPartners(List<Partner> p) {
		return new TradeNetwork(List.copyOf(p), links, cooldowns, moodBonus, moodBonusUntil, tradeBalance);
	}

	public TradeNetwork withLinks(List<Link> l) {
		return new TradeNetwork(partners, List.copyOf(l), cooldowns, moodBonus, moodBonusUntil, tradeBalance);
	}

	public TradeNetwork withCooldown(String key, long until) {
		Map<String, Long> c = new HashMap<>(cooldowns);
		c.put(key, until);
		return new TradeNetwork(partners, links, Map.copyOf(c), moodBonus, moodBonusUntil, tradeBalance);
	}

	public TradeNetwork withMood(int bonus, long until) {
		return new TradeNetwork(partners, links, cooldowns, bonus, until, tradeBalance);
	}

	/** Persistent cumulative village-side balance: positive items received, negative items shipped. */
	public TradeNetwork withTradeDelta(String itemId,long delta) {
		if(itemId==null||itemId.isBlank()||delta==0)return this;
		Map<String,Long> balances=new HashMap<>(tradeBalance);
		long old=balances.getOrDefault(itemId,0L),next;
		try { next=Math.addExact(old,delta); } catch(ArithmeticException ignored) { next=delta>0?Long.MAX_VALUE:Long.MIN_VALUE; }
		balances.put(itemId,next);
		return new TradeNetwork(partners,links,cooldowns,moodBonus,moodBonusUntil,Map.copyOf(balances));
	}

	public int linkIndex(String dimension, BlockPos bell) {
		for (int i = 0; i < links.size(); i++) {
			if (links.get(i).dimension().equals(dimension) && links.get(i).bell().equals(bell)) {
				return i;
			}
		}
		return -1;
	}

	/** Frisches Netz mit drei deterministisch aus der Glockenposition gewuerfelten Partnerorten. */
	public static TradeNetwork create(BlockPos bell) {
		String[] firsts = {"Fern", "Nebel", "Sonnen", "Eisen", "Raben", "Linden", "Wolken", "Stein", "Birken", "Mond", "Falken", "Bären", "Silber", "Eichen"};
		String[] lasts = {"hausen", "tal", "furt", "bach", "stein", "au", "heim", "dorf", "brück", "wald", "hain", "mark"};
		java.util.Random r = new java.util.Random(bell.asLong() * 6364136223846793005L + 1442695040888963407L);
		List<Partner> ps = new ArrayList<>();
		int spec = r.nextInt(SPECIALTIES.length);
		while (ps.size() < 3) {
			String name = firsts[r.nextInt(firsts.length)] + lasts[r.nextInt(lasts.length)];
			if (ps.stream().noneMatch(p -> p.name().equals(name))) {
				ps.add(new Partner(name, (spec + ps.size()) % SPECIALTIES.length, 5 + r.nextInt(10), -1L));
			}
		}
		return new TradeNetwork(ps, List.of(), Map.of(), 0, 0L);
	}
}
