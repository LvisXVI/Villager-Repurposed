package de.villagerplus;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/**
 * Kompatibilitaet mit anderen Mods und Datapacks:
 * <ul>
 *   <li>Entity-Tag {@code villagerplus:ignored}: Villager-Typen (auch aus anderen Mods), die dieser Mod nicht verwalten soll.</li>
 *   <li>Berufs-Tags {@code villagerplus:job/<farmer|shepherd|butcher|smith|cleric|mason|fisherman|fletcher|leatherworker|cartographer|librarian>}:
 *       ordnen beliebige (auch modifizierte) Berufe einer der Taetigkeiten zu.</li>
 *   <li>Ohne Tag: Der Berufsname wird auf Schluesselwoerter geprueft ("farm" -> Bauer, "smith" -> Schmied ...); alles andere
 *       arbeitet als Traeger.</li>
 * </ul>
 * Fremde Villager-Klassen (Unterklassen von {@code Villager}) werden automatisch mitverwaltet; Namen aus anderen Mods bleiben erhalten.
 */
public final class Compat {
	public static final TagKey<EntityType<?>> IGNORED = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "ignored"));

	public static final String[] JOBS = {"farmer", "shepherd", "butcher", "smith", "cleric", "mason", "fisherman", "fletcher", "leatherworker",
		"cartographer", "librarian"};

	/** Schluesselwoerter im Berufsnamen -> Taetigkeit (Reihenfolge = Prioritaet). */
	private static final String[][] KEYWORDS = {
		{"farm", "farmer"}, {"crop", "farmer"}, {"gardener", "farmer"}, {"botan", "farmer"}, {"baker", "farmer"}, {"herbal", "farmer"},
		{"shepherd", "shepherd"}, {"herder", "shepherd"}, {"weaver", "shepherd"}, {"wool", "shepherd"},
		{"butcher", "butcher"}, {"meat", "butcher"}, {"rancher", "butcher"}, {"hunter", "butcher"},
		{"fisher", "fisherman"}, {"angler", "fisherman"}, {"fish", "fisherman"},
		{"smith", "smith"}, {"armor", "smith"}, {"weapon", "smith"}, {"tool", "smith"}, {"forge", "smith"}, {"metal", "smith"},
		{"cleric", "cleric"}, {"priest", "cleric"}, {"alchem", "cleric"}, {"brew", "cleric"}, {"healer", "cleric"}, {"witch", "cleric"},
		{"mason", "mason"}, {"miner", "mason"}, {"mine", "mason"}, {"stone", "mason"}, {"builder", "mason"}, {"quarry", "mason"},
		{"fletch", "fletcher"}, {"lumber", "fletcher"}, {"wood", "fletcher"}, {"carpent", "fletcher"}, {"archer", "fletcher"},
		{"leather", "leatherworker"}, {"tanner", "leatherworker"}, {"cobbler", "leatherworker"},
		{"cartograph", "cartographer"}, {"map", "cartographer"}, {"explorer", "cartographer"},
		{"librar", "librarian"}, {"book", "librarian"}, {"scribe", "librarian"}, {"scholar", "librarian"}, {"teacher", "librarian"}
	};

	private Compat() {
	}

	private static final java.util.Map<String, java.util.Optional<Holder<VillagerProfession>>> COUNTERPART = new java.util.HashMap<>();

	/** Mod-Beruf (anderer Namespace als minecraft) fuer eine Taetigkeit, falls einer installiert ist; sonst leer. */
	private static java.util.Optional<Holder<VillagerProfession>> moddedFor(net.minecraft.server.level.ServerLevel level, String job) {
		return COUNTERPART.computeIfAbsent(job, j -> {
			var registry = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
			return registry.listElements()
				.filter(h -> !h.key().identifier().getNamespace().equals("minecraft") && j.equals(jobKey(h)))
				.min(java.util.Comparator.comparing(h -> h.key().identifier().toString()))
				.map(h -> (Holder<VillagerProfession>) h);
		});
	}

	/**
	 * Mod-Gegenstueck zu einem Vanilla-Beruf: Gibt es einen installierten Beruf einer anderen Mod mit derselben Taetigkeit
	 * (z. B. "othermod:farmer"), ersetzt er den Vanilla-Beruf. Gibt es keinen, bleibt es beim Vanilla-Beruf (null).
	 */
	public static Holder<VillagerProfession> counterpart(net.minecraft.server.level.ServerLevel level, Holder<VillagerProfession> prof) {
		if (!Config.PREFER_MODDED_PROFESSIONS || prof.unwrapKey().map(k -> !k.identifier().getNamespace().equals("minecraft")).orElse(true)) {
			return null;
		}
		String job = jobKey(prof);
		return job == null ? null : moddedFor(level, job).orElse(null);
	}

	/** Beruf, der fuer eine Taetigkeit vergeben werden soll: die Mod-Variante, sonst der Vanilla-Beruf. */
	public static ResourceKey<VillagerProfession> preferredKey(net.minecraft.server.level.ServerLevel level, String job, ResourceKey<VillagerProfession> vanilla) {
		if (!Config.PREFER_MODDED_PROFESSIONS) {
			return vanilla;
		}
		return moddedFor(level, job).flatMap(Holder::unwrapKey).orElse(vanilla);
	}

	public static boolean ignored(Villager v) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(v.getType()).is(IGNORED);
	}

	public static TagKey<VillagerProfession> jobTag(String job) {
		return TagKey.create(Registries.VILLAGER_PROFESSION, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/" + job));
	}

	/** Taetigkeit fuer einen fremden Beruf (Tag oder Schluesselwort), sonst null. */
	public static String jobKey(Holder<VillagerProfession> prof) {
		for (String job : JOBS) {
			if (prof.is(jobTag(job))) {
				return job;
			}
		}
		String path = prof.unwrapKey().map(k -> k.identifier().getPath()).orElse("").toLowerCase(java.util.Locale.ROOT);
		for (String[] kw : KEYWORDS) {
			if (path.contains(kw[0])) {
				return kw[1];
			}
		}
		return null;
	}
}
