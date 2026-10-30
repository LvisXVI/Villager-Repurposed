package de.villagerplus.job;

import java.util.List;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handwerks-Berufe, die in der Dorfmitte aus Vorraeten der Dorfkiste Waren herstellen. */
public class CraftOnlyJob implements Job {
	private final List<Craft.Recipe> recipes;

	public CraftOnlyJob(List<Craft.Recipe> recipes) {
		this.recipes = recipes;
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		if (Craft.run(c, recipes)) {
			return true;
		}
		c.setIdle(0, 400);
		return false;
	}

	@Override
	public boolean secondary(Ctx c) {
		return primary(c);
	}

	/** Gerber: Buecher aus Leder + Papier. */
	public static CraftOnlyJob leatherworker() {
		return new CraftOnlyJob(List.of(
			Craft.recipe("Buch", () -> new ItemStack(Items.BOOK), Craft.of(Items.LEATHER, 1), Craft.of(Items.PAPER, 3))));
	}

	/** Kartograph: Papier aus Zuckerrohr. */
	public static CraftOnlyJob cartographer() {
		return new CraftOnlyJob(List.of(
			Craft.recipe("Papier", () -> new ItemStack(Items.PAPER, 3), Craft.of(Items.SUGAR_CANE, 3))));
	}

	/** Bibliothekar: Buecherregale aus Brettern + Buechern. */
	public static CraftOnlyJob librarian() {
		return new CraftOnlyJob(List.of(
			Craft.recipe("Bücherregal", () -> new ItemStack(Items.BOOKSHELF), Craft.of(ItemTags.PLANKS, 6), Craft.of(Items.BOOK, 3))));
	}
}
