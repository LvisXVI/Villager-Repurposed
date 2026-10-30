package de.villagerplus.village;

import de.villagerplus.job.JobUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;

/**
 * Dorfreichtum: ein Punktwert aus Einwohnern, Bauwerken und den Schaetzen der Dorfkiste. Mit steigendem Reichtum stellen die
 * Dorfbewohner repraesentative Aufbauten auf (Laternenpfaehle, Marktstand, Denkmal, Springbrunnen, Festplatz).
 */
public final class Wealth {
	private static final int[] TIERS = {60, 110, 170, 250, 350};

	private Wealth() {
	}

	public static int score(ServerLevel level, VillageRef ref, int population) {
		VillageData d = ref.data();
		int score = population * 2 + d.houses * 5 + d.farms * 4;
		score += d.wellBuilt ? 8 : 0;
		score += d.townHallBuilt ? 25 : 0;
		score += d.wallBuilt ? 40 : 0;
		score += d.extra.pen.isPresent() ? 10 : 0;
		Container box = ref.chest(level);
		if (box != null) {
			score += JobUtil.count(box, s -> s.is(Items.EMERALD)) * 2;
			score += JobUtil.count(box, s -> s.is(Items.DIAMOND)) * 20;
			score += JobUtil.count(box, s -> s.is(Items.GOLD_INGOT)) * 3;
			score += JobUtil.count(box, s -> s.is(Items.IRON_INGOT)) / 2;
		}
		return score;
	}

	/** Wie viele Reichtums-Aufbauten stehen dem Dorf zu (0..5)? */
	public static int tier(int score) {
		int t = 0;
		while (t < TIERS.length && score >= TIERS[t]) {
			t++;
		}
		return t;
	}
}
