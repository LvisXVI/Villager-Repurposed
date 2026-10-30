package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Werkstatt: Wenn dem Dorf fuer einen Bau ein bestimmtes Material fehlt ({@code VillageData.wanted}), versucht der
 * Handwerker, es aus vorhandenen Rohstoffen der Dorfkiste herzustellen (Bretter aus Stamm, Treppen/Halbstufen/Mauern/
 * Zaeune aus dem Grundblock, Tueren aus Brettern, Glasscheiben aus Glas, Steinziegel aus Stein ...). Die Regeln arbeiten
 * ueber die Item-Namen und funktionieren deshalb auch fuer Bloecke aus Mods, die der Vanilla-Benennung folgen.
 * Nicht herstellbares (Bruchstein, Sandstein ...) muss abgebaut werden ({@link Mining}).
 */
public final class Workshop {
	private Workshop() {
	}

	private record Rule(Predicate<ItemStack> input, int in, int out, String label) { }

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().wanted.isEmpty()) {
			return false;
		}
		BlockPos chest = JobUtil.chest(c);
		Container box = chest == null ? null : JobUtil.box(c);
		if (box == null) {
			return false;
		}
		if (fetchFromLager(c, ref, box)) {
			return true;
		}
		for (String entry : new ArrayList<>(ref.data().wanted)) {
			String[] parts = entry.split("#");
			Optional<Item> target = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (target.isEmpty()) {
				continue;
			}
			int wanted = parseInt(parts.length > 1 ? parts[1] : "1");
			int missing = wanted - JobUtil.count(box, s -> s.is(target.get()));
			if (missing <= 0) {
				continue;
			}
			if (RecipeCrafting.craft(c, box, stack -> stack.is(target.get()), wanted)) {
				return true;
			}
			Rule rule = ruleFor(target.get(), box);
			if (rule == null) {
				continue;
			}
			int batches = Math.min(Math.max(1, (missing + rule.out() - 1) / rule.out()), Math.min(8, JobUtil.count(box, rule.input()) / rule.in()));
			if (batches <= 0) {
				continue;
			}
			c.setActivity("stellt her: " + rule.label());
			Anim.hold(c, Items.IRON_AXE);
			if (!JobUtil.approach(c, chest, 3.0)) {
				return true;
			}
			JobUtil.take(box, rule.input(), rule.in() * batches);
			JobUtil.insert(c, box, new ItemStack(target.get(), rule.out() * batches));
			JobUtil.swing(c.v());
			c.st().cooldownUntil = c.now() + 60;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	/**
	 * Fehlt der Dorfkiste etwas aus der Wunschliste, das im Lagerschuppen liegt, holt der Handwerker es dort ab (fuer Bretter
	 * auch Staemme). Nur wenn in der Dorfkiste Platz ist.
	 */
	private static boolean fetchFromLager(Ctx c, VillageRef ref, Container box) {
		VillageData d = ref.data();
		if (d.extra.lager.isEmpty()) {
			return false;
		}
		List<Container> lagers = de.villagerplus.village.Lager.containers(c.level(), d);
		for (String entry : new ArrayList<>(d.wanted)) {
			String[] parts = entry.split("#");
			Optional<Item> target = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (target.isEmpty()) {
				continue;
			}
			int missing = parseInt(parts.length > 1 ? parts[1] : "1") - JobUtil.count(box, s -> s.is(target.get()));
			if (missing <= 0) {
				continue;
			}
			boolean planks = parts[0].endsWith("_planks");
			Predicate<ItemStack> match = s -> s.is(target.get()) || (planks && s.is(ItemTags.LOGS));
			int inLager = 0;
			for (Container l : lagers) {
				inLager += JobUtil.count(l, match);
			}
			if (inLager == 0) {
				continue;
			}
			c.setActivity("holt Material aus dem Lager");
			if (!JobUtil.approach(c, d.extra.lager.get(), 4.5)) {
				return true;
			}
			// Dorfkiste voll: erst einen Platz frei raeumen (unwichtige Ware ins Lager), nichts wird weggeworfen.
			if (!de.villagerplus.village.Lager.freeSlot(c.level(), d, box, s -> {
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
				return d.wanted.stream().anyMatch(w -> w.startsWith(id + "#")) || s.is(Items.EMERALD) || de.villagerplus.build.Builder.planNeeds(d, s);
			})) {
				continue;
			}
			int left = Math.min(Math.max(missing, planks ? 16 : 1), 4096);
			for (Container l : lagers) {
				for (int i = 0; i < l.getContainerSize() && left > 0; i++) {
					ItemStack stack = l.getItem(i);
					if (stack.isEmpty() || !match.test(stack)) {
						continue;
					}
					ItemStack part = stack.copyWithCount(Math.min(left, stack.getCount()));
					int before = part.getCount();
					ItemStack rest = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, box, part, null);
					int moved = before - rest.getCount();
					stack.shrink(moved);
					l.setChanged();
					left -= moved;
					if (!rest.isEmpty()) {
						left = 0;
					}
				}
			}
			JobUtil.swing(c.v());
			c.st().cooldownUntil = c.now() + 60;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static Predicate<ItemStack> ofIds(Container box, String ns, String... paths) {
		List<Item> items = new ArrayList<>();
		for (String p : paths) {
			BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(ns, p)).ifPresent(items::add);
		}
		if (items.isEmpty()) {
			return null;
		}
		return s -> items.stream().anyMatch(s::is);
	}

	/** Findet ein Herstellungsrezept fuer {@code target}, dessen Zutat in der Kiste liegt (sonst null). */
	private static Rule ruleFor(Item target, Container box) {
		Identifier id = BuiltInRegistries.ITEM.getKey(target);
		String ns = id.getNamespace();
		String path = id.getPath();

		if (path.endsWith("_planks")) {
			String wood = path.substring(0, path.length() - "_planks".length());
			return pick(box, new Rule(ofIds(box, ns, wood + "_log", wood + "_stem", wood + "_wood", wood + "_hyphae", "stripped_" + wood + "_log"),
				1, 4, "Bretter"));
		}
		String[][] derived = {
			{"_stairs", "1"}, {"_slab", "2"}, {"_wall", "1"}, {"_fence", "1"}, {"_fence_gate", "1"}, {"_pressure_plate", "1"},
			{"_button", "1"}, {"_trapdoor", "1"}, {"_door", "1"}, {"_sign", "1"}
		};
		for (String[] d : derived) {
			if (path.endsWith(d[0])) {
				String prefix = path.substring(0, path.length() - d[0].length());
				Predicate<ItemStack> in = ofIds(box, ns, prefix + "_planks", prefix, prefix + "s", prefix + "_block", prefix + "_bricks");
				int cost = d[0].equals("_door") || d[0].equals("_trapdoor") ? 2 : 1;
				return pick(box, new Rule(in, cost, Integer.parseInt(d[1]), "Zuschnitt"));
			}
		}
		if (path.equals("glass_pane")) {
			return pick(box, new Rule(ofIds(box, ns, "glass"), 1, 2, "Glasscheiben"));
		}
		if (path.endsWith("_bricks") || path.equals("stone_bricks")) {
			String prefix = path.substring(0, path.length() - "_bricks".length());
			return pick(box, new Rule(ofIds(box, ns, prefix.isEmpty() ? "stone" : prefix, "stone"), 1, 1, "Steinziegel"));
		}
		if (path.startsWith("cut_") || path.startsWith("smooth_") || path.startsWith("polished_") || path.startsWith("chiseled_")) {
			String base = path.substring(path.indexOf('_') + 1);
			return pick(box, new Rule(ofIds(box, ns, base, base + "_block"), 1, 1, "Steinmetzarbeit"));
		}
		return null;
	}

	private static Rule pick(Container box, Rule r) {
		return r.input() == null ? null : r;
	}
}
