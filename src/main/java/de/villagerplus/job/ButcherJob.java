package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Metzger: Vormittags Tierbestaende klein halten und Fleisch sammeln. Nachmittags Rohes in der Dorfkiste garen. */
public class ButcherJob implements Job {
	private static final Set<EntityType<?>> LIVESTOCK = Set.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN);

	/** A named, leashed, courting, juvenile, or dead animal is never a culling candidate. */
	public static boolean cullable(Animal animal) {
		return animal != null && animal.isAlive() && !animal.isBaby() && LIVESTOCK.contains(animal.getType())
			&& !animal.hasCustomName() && !animal.isLeashed() && !animal.isInLove();
	}

	private static final List<Craft.Recipe> RECIPES = List.of(
		cook(Items.BEEF, Items.COOKED_BEEF), cook(Items.PORKCHOP, Items.COOKED_PORKCHOP), cook(Items.CHICKEN, Items.COOKED_CHICKEN),
		cook(Items.MUTTON, Items.COOKED_MUTTON), cook(Items.RABBIT, Items.COOKED_RABBIT), cook(Items.COD, Items.COOKED_COD),
		cook(Items.SALMON, Items.COOKED_SALMON), cook(Items.POTATO, Items.BAKED_POTATO));

	private static Craft.Recipe cook(Item raw, Item cooked) {
		return Craft.recipe("Gegartes", () -> new ItemStack(cooked), Craft.of(raw, 1));
	}

	@Override
	public Predicate<ItemStack> produce() {
		return s -> !Villager.FOOD_POINTS.containsKey(s.getItem());
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		JobUtil.collectDrops(c, 5.0, 100);
		if (JobUtil.depositSurplus(c, produce(), 8, 0)) {
			return true;
		}
		if (PenExpansion.work(c)) {
			return true;
		}
		JobState st = c.st();
		ServerLevel level = c.level();
		Animal victim = st.targetEntity instanceof Animal a && a.isAlive() ? a : null;
		if (victim == null) {
			st.clearTarget();
			victim = pickVictim(c);
			if (victim == null) {
				c.setIdle(0, 400);
				return false;
			}
			st.targetEntity = victim;
		}
		c.setActivity("schlachtet Überschuss");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, victim.position(), 3.4)) {
			return true;
		}
		victim.hurtServer(level, level.damageSources().mobAttack(c.v()), 20.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 60;
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
		c.setIdle(1, 300);
		return false;
	}

	private static Animal pickVictim(Ctx c) {
		List<Animal> herd = c.level().getEntitiesOfClass(Animal.class, c.v().getBoundingBox().inflate(Config.CULL_RADIUS),
			a -> a.isAlive() && !a.isBaby() && LIVESTOCK.contains(a.getType()));
		for (EntityType<?> type : LIVESTOCK) {
			List<Animal> sameKind = herd.stream().filter(a -> a.getType() == type).toList();
			if (sameKind.size() <= Config.CULL_CAP) {
				continue;
			}
			var candidate = sameKind.stream()
				.filter(ButcherJob::cullable)
				.min(Comparator.comparingDouble(a -> a.distanceToSqr(c.v())));
			if (candidate.isPresent()) {
				return candidate.get();
			}
		}
		return null;
	}
}
