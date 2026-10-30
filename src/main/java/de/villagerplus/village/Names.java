package de.villagerplus.village;

import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

public final class Names {
	private static final String[] FIRST = {
		"Hans", "Greta", "Otto", "Liese", "Karl", "Frieda", "Emil", "Berta", "Fritz", "Hilde", "Ludwig", "Ida", "Anton", "Martha",
		"Heinz", "Clara", "Paul", "Elsa", "Willi", "Anna", "Jakob", "Rosa", "Theo", "Helga", "Gustav", "Lotte", "Walter", "Emma",
		"Konrad", "Marie", "Felix", "Ilse", "Bruno", "Erna", "Viktor", "Gerda", "Albert", "Mia", "Rudolf", "Nele", "Josef", "Hanna",
		"Matthias", "Luise", "Benno", "Trude", "Leo", "Sophie", "Ernst", "Wilma"
	};

	private Names() {
	}

	public static String random(RandomSource r) {
		return FIRST[r.nextInt(FIRST.length)];
	}

	private static final String[][] VILLAGE_PREFIX = {
		{"Eichen", "Linden", "Weiden", "Korn", "Wiesen", "Birken", "Rosen", "Klee", "Bach", "Mühlen", "Lerchen", "Hasel"},
		{"Sand", "Dünen", "Oasen", "Palmen", "Sonnen", "Skarabäus", "Wüsten", "Kamel", "Salz", "Zedern"},
		{"Rot", "Kupfer", "Mesa", "Canyon", "Ton", "Rost", "Glut", "Adler", "Terra", "Feuer"},
		{"Tannen", "Fichten", "Frost", "Eis", "Schnee", "Bären", "Wolfs", "Nordwind", "Raben", "Harz"},
		{"Akazien", "Steppen", "Savannen", "Löwen", "Gold", "Dornen", "Zebra", "Staub", "Sonnen", "Gras"},
		{"Moor", "Sumpf", "Farn", "Nebel", "Lianen", "Dschungel", "Frosch", "Pilz", "Mangroven", "Tau"}
	};
	private static final String[][] VILLAGE_SUFFIX = {
		{"quell", "bach", "au", "dorf", "furt", "heim", "hain", "tal", "wiese", "feld"},
		{"hain", "furt", "brunnen", "stadt", "tor", "stein", "mark", "quell"},
		{"fels", "stein", "kamm", "grund", "burg", "wand", "tal", "mark"},
		{"wald", "hütte", "fall", "tal", "berg", "heim", "pass", "see", "stein"},
		{"mark", "hügel", "furt", "dorf", "kraal", "tal", "heim", "brunnen"},
		{"bruch", "wasser", "furt", "heim", "inseln", "dorf", "hain", "see"}
	};

	/** Prozeduraler, biomabhaengiger Dorfname; stabil fuer dieselbe Glockenposition. */
	public static String village(String biomePath, long seed) {
		int group = 0;
		if (biomePath.contains("desert")) {
			group = 1;
		} else if (biomePath.contains("badlands") || biomePath.contains("mesa")) {
			group = 2;
		} else if (biomePath.contains("taiga") || biomePath.contains("snow") || biomePath.contains("frozen") || biomePath.contains("ice") || biomePath.contains("grove")) {
			group = 3;
		} else if (biomePath.contains("savanna")) {
			group = 4;
		} else if (biomePath.contains("jungle") || biomePath.contains("swamp") || biomePath.contains("mangrove")) {
			group = 5;
		}
		java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L + 0x7F4A7C15L);
		return VILLAGE_PREFIX[group][r.nextInt(VILLAGE_PREFIX[group].length)] + VILLAGE_SUFFIX[group][r.nextInt(VILLAGE_SUFFIX[group].length)];
	}

	/** Deutscher Berufsname. */
	public static String profession(Holder<VillagerProfession> prof) {
		String key = prof.unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
		return switch (key) {
			case "armorer" -> "Rüstungsschmied";
			case "butcher" -> "Metzger";
			case "cartographer" -> "Kartograph";
			case "cleric" -> "Kleriker";
			case "farmer" -> "Bauer";
			case "fisherman" -> "Fischer";
			case "fletcher" -> "Pfeilmacher";
			case "leatherworker" -> "Gerber";
			case "librarian" -> "Bibliothekar";
			case "mason" -> "Maurer";
			case "nitwit" -> "Dorftrottel";
			case "shepherd" -> "Schäfer";
			case "toolsmith" -> "Werkzeugschmied";
			case "weaponsmith" -> "Waffenschmied";
			case "none" -> "Träger";
			default -> key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
		};
	}
}
