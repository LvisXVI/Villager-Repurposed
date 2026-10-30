package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mahlzeiten: Dorfbewohner essen morgens und abends zu Hause und mittags am Dorfplatz. */
public final class Meals {
	private static final Set<Item> EXTRA_FOOD = Set.of(
		Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD,
		Items.COOKED_SALMON, Items.BAKED_POTATO, Items.APPLE, Items.MELON_SLICE, Items.SWEET_BERRIES, Items.COOKIE,
		Items.PUMPKIN_PIE, Items.DRIED_KELP);

	private Meals() {
	}

	public static boolean isFood(ItemStack s) {
		return !s.isEmpty() && (s.has(DataComponents.FOOD) || Villager.FOOD_POINTS.containsKey(s.getItem()) || EXTRA_FOOD.contains(s.getItem()));
	}

	/** Prepared meals that the cook can produce and a tavern may serve. */
	public static boolean isPreparedFood(ItemStack s) {
		if (!isFood(s)) return false;
		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().toLowerCase(java.util.Locale.ROOT);
		return path.startsWith("cooked_") || path.contains("bread") || path.contains("soup") || path.contains("stew")
			|| path.contains("pie") || path.contains("cookie") || path.contains("cake") || path.contains("meal")
			|| path.contains("feast") || path.contains("salad") || path.contains("sandwich") || path.contains("roast")
			|| path.contains("pudding") || path.contains("casserole");
	}

	/** Isst eine Portion aus dem eigenen Inventar oder, falls vorhanden, aus {@code source}. Nur wenn wieder Hunger da ist. */
	public static boolean eat(Ctx c, Container source) {
		long last = c.mem().lastMeal;
		if (last != 0 && c.now() - last < 3000L) {
			return false;
		}
		ItemStack eaten = firstFood(c.v().getInventory(), Meals::isFood);
		if (JobUtil.take(c.v().getInventory(), Meals::isFood, 1) == 0) {
			eaten = source == null ? null : firstFood(source, Meals::isFood);
			if (source == null || JobUtil.take(source, Meals::isFood, 1) == 0) {
				// Die Nahrung des Dorfes liegt im Kornspeicher, sobald es einen gibt.
				eaten = null;
				boolean got = false;
				if (c.village() != null) {
					for (Container g : de.villagerplus.village.Specials.containers(c.level(), c.village(), de.villagerplus.build.Blueprints.GRANARY)) {
						ItemStack f = firstFood(g, Meals::isFood);
						if (f != null && JobUtil.take(g, Meals::isFood, 1) > 0) {
							eaten = f;
							got = true;
							break;
						}
					}
				}
				if (!got) {
					return false;
				}
			}
		}
		return finishMeal(c, eaten);
	}

	/** Eat only from the specified stock source; used by services where inventory food is not what is being purchased. */
	public static boolean eatFrom(Ctx c, Container source, Predicate<ItemStack> filter) {
		long last = c.mem().lastMeal;
		if (source == null || (last != 0 && c.now() - last < 3000L)) return false;
		ItemStack eaten = firstFood(source, s -> isFood(s) && filter.test(s));
		if (eaten == null || JobUtil.take(source, s -> isFood(s) && filter.test(s), 1) == 0) return false;
		return finishMeal(c, eaten);
	}

	private static boolean finishMeal(Ctx c, ItemStack eaten) {
		if (eaten != null) {
			Anim.hold(c, eaten.getItem());
			Anim.eatParticles(c.level(), c.v(), eaten.getItem());
			var useRemainder = eaten.get(DataComponents.USE_REMAINDER);
			var craftingRemainder = eaten.getItem().getCraftingRemainder();
			ItemStack remainder = useRemainder != null ? useRemainder.convertInto().create()
				: craftingRemainder == null ? ItemStack.EMPTY : craftingRemainder.create();
			if (!remainder.isEmpty()) JobUtil.give(c.level(), c.v(), remainder);
		}
		if (eaten != null && isPreparedFood(eaten)) c.v().setAttached(de.villagerplus.VillagerPlus.PREPARED_MEAL, c.now());
		c.v().heal(2.0F);
		c.level().playSound(null, c.v().blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().lastMeal = c.now();
		return true;
	}

	private static ItemStack firstFood(Container c, Predicate<ItemStack> filter) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			if (filter.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
		}
		return null;
	}

	/** Lange nichts gegessen? Dann arbeitet der Dorfbewohner nur noch halb so schnell. */
	public static boolean hungry(Ctx c) {
		long last = c.mem().lastMeal;
		if (last == 0) {
			c.mem().lastMeal = c.now();
			return false;
		}
		return c.now() - last > Config.HUNGER_AFTER;
	}
}
