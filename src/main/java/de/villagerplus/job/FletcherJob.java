package de.villagerplus.job;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Pfeilmacher: faellt Baeume in der Umgebung (Holz fuer das Dorf), stellt Bretter/Zuschnitte her und fertigt Pfeile
 * (Feuerstein + Stock + Feder) und Stoecke.
 */
public class FletcherJob implements Job {
	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Pfeile", () -> new ItemStack(Items.ARROW, 4),
			Craft.of(Items.FLINT, 1), Craft.of(Items.STICK, 1), Craft.of(Items.FEATHER, 1)),
		Craft.recipe("Stöcke", () -> new ItemStack(Items.STICK, 4), Craft.of(ItemTags.PLANKS, 2)));

	@Override
	public Predicate<ItemStack> produce() {
		return s -> !Meals.isFood(s);
	}

	private boolean wood(Ctx c) {
		if (c.village() == null) {
			return false;
		}
		if (JobUtil.depositSurplus(c, produce(), 48, 0)) {
			return true;
		}
		return Woodcutting.tick(c);
	}

	@Override
	public boolean primary(Ctx c) {
		return wood(c) || Workshop.tick(c) || craft(c);
	}

	@Override
	public boolean secondary(Ctx c) {
		return Workshop.tick(c) || craft(c) || wood(c);
	}

	private boolean craft(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		if (Craft.run(c, RECIPES)) {
			Anim.hold(c, Items.ARROW);
			return true;
		}
		c.setIdle(1, 400);
		return false;
	}
}
