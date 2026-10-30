package de.villagerplus.job;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Die festen Routinen im Tagesplan:
 * <ul>
 * <li>Morgen: Fruehstueck zu Hause (aus der eigenen Kiste), dann Waren zur Dorfkiste, dann Plausch.</li>
 * <li>Pause: Plausch mit Nachbarn.</li>
 * <li>Mittag: Waren abliefern, gemeinsames Essen am Dorfplatz, Plausch.</li>
 * <li>Abend: Abendessen zu Hause, persoenliche Sachen in die eigene Kiste legen (Privateigentum).</li>
 * </ul>
 */
public final class Routine {
	private Routine() {
	}

	/** true = beschaeftigt. */
	public static boolean run(Ctx c, Job job, Phase phase) {
		JobState st = c.st();
		if (st.routinePhase != phase) {
			st.routinePhase = phase;
			st.stage = 0;
			st.clearTarget();
		}
		switch (phase) {
			case MORNING -> {
				if (st.stage == 0) {
					if (homeMeal(c, "frühstückt zu Hause")) {
						return true;
					}
					st.stage = 1;
				}
				if (st.stage == 1) {
					if (deliver(c, job)) {
						return true;
					}
					st.stage = 2;
				}
				return Social.tick(c);
			}
			case LUNCH -> {
				if (st.stage == 0) {
					if (deliver(c, job)) {
						return true;
					}
					st.stage = 1;
				}
				if (st.stage == 1) {
					centerMeal(c);
					st.stage = 2;
				}
				if (gather(c)) return true;
				return Social.tick(c);
			}
			case EVENING -> {
				if (st.stage == 0) {
					if (de.villagerplus.village.Tavern.visit(c)) return true;
					if (homeMeal(c, "isst zu Abend")) {
						return true;
					}
					st.stage = 1;
				}
				if (st.stage == 1) {
					if (store(c, job)) {
						return true;
					}
					st.stage = 2;
				}
				c.setActivity("ruht sich zu Hause aus");
				return false;
			}
			case BREAK -> {
				if (gather(c)) return true;
				return Social.tick(c);
			}
			default -> {
				return false;
			}
		}
	}

 /**
  * Pausen und Mittag: kleine Gespraechsgruppen verteilt um den Platz (6 Gruppenmittelpunkte, je Person ein eigener Standplatz
  * +-2 Bloecke), ein Teil geht stattdessen in die offenen Laeden oder bummelt - nicht alle auf einem Haufen am Schrein.
  */
 private static boolean gather(Ctx c) {
  if(c.village()==null)return false;var plaza=de.villagerplus.village.Market.of(c.village()).plaza();if(plaza.isEmpty())return false;
  int h=c.v().getUUID().hashCode();
  int mode=Math.floorMod(h>>>4,5); // 0..1: Bummeln zum naechsten Laden, 2..4: Gruppe
  var shops=de.villagerplus.village.Market.of(c.village()).shops().stream().filter(s->s.opened()>0&&!s.deceased()).toList();
  BlockPos place;
  if(mode<2&&!shops.isEmpty()){
   var s=shops.get(Math.floorMod(h,shops.size()));
   place=s.origin().offset(Math.floorMod(h>>>8,5)-2,0,Math.floorMod(h>>>12,3)+3);
   c.setActivity("schaut sich die Läden an");
  } else {
   // 8 Gruppen auf Ring-Radius 6-8 (die Dorfkiste liegt nahe der Mitte und bleibt frei)
   double angle=Math.floorMod(h,8)*Math.PI/4+0.4;int radius=6+Math.floorMod(h>>>20,3);
   place=plaza.get().offset((int)Math.round(Math.cos(angle)*radius)+Math.floorMod(h>>>8,3)-1,0,(int)Math.round(Math.sin(angle)*radius)+Math.floorMod(h>>>16,3)-1);
   c.setActivity("plaudert in der Gruppe am Dorfplatz");
  }
  place=c.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,place);
  return !JobUtil.approach(c,place,2);
 }
	private static Container homeBox(Ctx c) {
		if (c.mem().homeChest.isPresent() && c.level().getBlockState(c.mem().homeChest.get()).getBlock() instanceof ChestBlock) {
			return HopperBlockEntity.getContainerAt(c.level(), c.mem().homeChest.get());
		}
		return null;
	}

	private static BlockPos homeSpot(Ctx c) {
		if (c.mem().homeChest.isPresent() && homeBox(c) != null) {
			return c.mem().homeChest.get();
		}
		return c.mem().homeBed.orElse(null);
	}

	/** Essen zu Hause aus dem eigenen Vorrat. true = noch unterwegs. */
	private static boolean homeMeal(Ctx c, String label) {
		BlockPos spot = homeSpot(c);
		if (spot == null) {
			Meals.eat(c, null);
			return false;
		}
		c.setActivity(label);
		if (!JobUtil.approach(c, spot, 3.5)) {
			return true;
		}
		if (Meals.eat(c, homeBox(c))) {
			c.st().cooldownUntil = c.now() + 60;
		}
		return false;
	}

	/** Waren der Dorfmitte uebergeben. true = noch unterwegs. */
	private static boolean deliver(Ctx c, Job job) {
		if (c.village() == null) {
			return false;
		}
		BlockPos spot = c.village().chestPos(c.level());
		if (spot == null) {
			spot = c.village().bell();
		}
		c.setActivity("liefert Waren an der Dorfmitte ab");
		if (!JobUtil.approach(c, spot, 4.5)) {
			return true;
		}
		return JobUtil.depositSurplus(c, job.produce(), 1, job.keep());
	}

	/** Gemeinsames Mittagessen; ausserdem Vorrat auffuellen (Nahrung im Inventar ermoeglicht den Nachwuchs). */
	private static void centerMeal(Ctx c) {
		Container box = JobUtil.box(c);
		Meals.eat(c, box);
		java.util.List<Container> pantry = new java.util.ArrayList<>();
		if (box != null) {
			pantry.add(box);
		}
		if (c.village() != null) {
			pantry.addAll(de.villagerplus.village.Specials.containers(c.level(), c.village(), de.villagerplus.build.Blueprints.GRANARY));
		}
		if (pantry.isEmpty()) {
			return;
		}
		int have = JobUtil.count(c.v().getInventory(), Meals::isFood);
		if (have < 3) {
			for (var food : Villager.FOOD_POINTS.keySet()) {
				for (Container b : pantry) {
					int got = JobUtil.take(b, s -> s.is(food), 3 - have);
					if (got > 0) {
						JobUtil.give(c.level(), c.v(), new ItemStack(food, got));
						return;
					}
				}
			}
		}
	}

	/** Abends persoenlichen Besitz (alles ausser Arbeitsware und etwas Proviant) in die eigene Kiste legen. */
	private static boolean store(Ctx c, Job job) {
		Container home = homeBox(c);
		if (home == null) {
			return false;
		}
		c.setActivity("räumt Besitz in die eigene Kiste");
		if (!JobUtil.approach(c, c.mem().homeChest.get(), 3.0)) {
			return true;
		}
		Container inv = c.v().getInventory();
		int keepFood = 4;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || job.produce().test(s)) {
				continue;
			}
			if (Meals.isFood(s)) {
				int keepHere = Math.min(keepFood, s.getCount());
				keepFood -= keepHere;
				if (s.getCount() == keepHere) {
					continue;
				}
				ItemStack extra = s.copyWithCount(s.getCount() - keepHere);
				ItemStack rest = JobUtil.insertRemainder(c, home, extra);
				s.setCount(keepHere + rest.getCount());
				continue;
			}
			ItemStack rest = JobUtil.insertRemainder(c, home, s.copy());
			inv.setItem(i, rest);
		}
		inv.setChanged();
		home.setChanged();
		c.st().cooldownUntil = c.now() + 40;
		return false;
	}
}
