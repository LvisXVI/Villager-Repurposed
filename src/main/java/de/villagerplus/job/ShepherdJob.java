package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Schaefer: Vormittags Schafe scheren, Wolle abliefern. Nachmittags aus Wolle + Brettern Betten bauen. */
public class ShepherdJob implements Job {
	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Bett", () -> new ItemStack(Items.BED.white()), Craft.of(ItemTags.WOOL, 3), Craft.of(ItemTags.PLANKS, 3)));

	@Override
	public Predicate<ItemStack> produce() {
		return s -> s.is(ItemTags.WOOL);
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		JobUtil.collectDrops(c, 4.0, 100);
		if (JobUtil.depositSurplus(c, produce(), Config.WOOL_DEPOSIT_THRESHOLD, 0)) {
			return true;
		}
		JobState st = c.st();
		ServerLevel level = c.level();
		Sheep sheep = st.targetEntity instanceof Sheep s && s.isAlive() && s.readyForShearing() ? s : null;
		if (sheep == null) {
			st.clearTarget();
			List<Sheep> found = level.getEntitiesOfClass(Sheep.class, c.v().getBoundingBox().inflate(Config.SHEEP_RADIUS),
				s -> s.isAlive() && s.readyForShearing());
			sheep = found.stream().min(Comparator.comparingDouble(s -> s.distanceToSqr(c.v()))).orElse(null);
			if (sheep == null) {
				c.setIdle(0, 300);
				return false;
			}
			st.targetEntity = sheep;
		}
		c.setActivity("schert Schafe");
		Anim.hold(c, Items.SHEARS);
		if (!JobUtil.approach(c, sheep.position(), 3.4)) {
			return true;
		}
		sheep.shear(level, SoundSource.NEUTRAL, ItemStack.EMPTY);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 40;
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		if (Craft.run(c, RECIPES) || Livestock.tick(c)) {
			return true;
		}
		c.setIdle(1, 400);
		return false;
	}
}
