package de.villagerplus.job;

import de.villagerplus.village.VillageMessages;
import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;

public final class JobUtil {
	private JobUtil() {
	}

	/** Laeuft zum Ziel. Gibt true zurueck, sobald der Dorfbewohner nah genug ist. */
	public static boolean approach(Ctx c, Vec3 pos, double reach) {
		JobState st = c.st();
		if (c.v().position().distanceToSqr(pos) <= reach * reach) {
			st.stuckCount = 0;
			st.walkStart = 0;
			st.bestDist = 0.0;
			return true;
		}
		if (st.walkStart == 0) {
			st.walkStart = c.now();
		} else if (c.now() - st.walkStart > 1500) {
			// Kommt nicht hin: aufgeben und spaeter neu versuchen.
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			st.clearTarget();
			st.stuckCount = 0;
			st.bestDist = 0.0;
			st.cooldownUntil = c.now() + 400;
			st.idleUntil[0] = c.now() + 400;
			st.idleUntil[1] = c.now() + 400;
			return false;
		}
		// Haengen-Erkennung: kommt der Dorfbewohner dem Ziel ueber lange Zeit nicht merklich naeher (z.B. weil die
		// Wegfindung scheitert und er nur herumirrt), wird er sicher in die Naehe des Ziels gesetzt.
		Vec3 here = c.v().position();
		double dist = here.distanceTo(pos);
		if (st.walkStart == c.now() || st.bestDist <= 0.0 || dist < st.bestDist - 1.5) {
			st.bestDist = dist;
			st.lastMoveAt = c.now();
		} else if (c.now() - st.lastMoveAt > 240) {
			// Anti-Haengetask: kein Teleport mehr. Zwei Neuversuche der Wegfindung, beim dritten Mal wird der Task abgebrochen,
			// bekommt Abklingzeit und der Dorfbewohner waehlt eine andere Aufgabe.
			st.lastMoveAt = c.now();
			st.stuckCount++;
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			if (st.stuckCount >= 3) {
				st.stuckCount = 0;
				st.walkStart = 0;
				st.bestDist = 0.0;
				st.clearTarget();
				st.cooldownUntil = c.now() + 1200;
				st.idleUntil[0] = c.now() + 1200;
				st.idleUntil[1] = c.now() + 1200;
				return false;
			}
		}		c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, (float) Math.min(0.55, 0.45 * Skills.speed(c.mem())), 1));
		return false;
	}

	/** Nahe am Ziel eine Stelle, an der ein Dorfbewohner (2 Bloecke hoch) frei stehen kann und die Luft/Boden hat. */
	public static Vec3 safeSpot(ServerLevel level, Vec3 target) {
		BlockPos base = BlockPos.containing(target);
		for (int dy = 0; dy <= 6; dy++) {
			BlockPos feet = base.above(dy);
			if (!level.hasChunkAt(feet)) {
				return null;
			}
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) {
				return Vec3.atBottomCenterOf(feet);
			}
		}
		return null;
	}

	public static boolean approach(Ctx c, BlockPos pos, double reach) {
		return approach(c, Vec3.atBottomCenterOf(pos), reach);
	}

	/** Uses the builder's bounded long-route search to feed short waypoints to vanilla navigation. */
	public static boolean approachLong(Ctx c, BlockPos pos, double reach) {
		if (approach(c,pos,reach)) return true;
		if (c.st().cooldownUntil>c.now()) return false;
		var route=de.villagerplus.build.Builder.workPath(c,pos);
		if(route==null||!route.canReach()) return false;
		BlockPos waypoint=pos;
		for(int i=0;i<route.getNodeCount();i++) {
			BlockPos node=route.getNodePos(i);
			if(c.v().position().distanceToSqr(Vec3.atBottomCenterOf(node))>100.0) break;
			waypoint=node;
		}
		var walk=c.v().getBrain().getMemory(MemoryModuleType.WALK_TARGET);
		if(walk.isPresent()) c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
			new WalkTarget(waypoint,walk.get().getSpeedModifier(),1));
		return false;
	}

	public static void swing(Villager v) {
		v.swing(InteractionHand.MAIN_HAND);
	}

	/** Steckt das Item ins Inventar, Reste landen auf dem Boden. */
	public static void give(ServerLevel level, Villager v, ItemStack stack) {
		ItemStack rest = v.getInventory().addItem(stack);
		if (!rest.isEmpty()) {
			v.spawnAtLocation(level, rest);
		}
	}

	public static int count(Container c, Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && p.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** Entnimmt bis zu {@code amount} Items. Gibt die entnommene Menge zurueck. */
	public static int take(Container c, Predicate<ItemStack> p, int amount) {
		int taken = 0;
		for (int i = 0; i < c.getContainerSize() && taken < amount; i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && p.test(s)) {
				int n = Math.min(amount - taken, s.getCount());
				c.removeItem(i, n);
				taken += n;
			}
		}
		if (taken > 0) {
			c.setChanged();
		}
		return taken;
	}

	/** Legt Items in einen Behaelter; was nicht passt, faellt neben den Dorfbewohner. */
	public static void insert(Ctx c, Container box, ItemStack stack) {
		ItemStack rest = insertRemainder(c, box, stack);
		if (!rest.isEmpty()) {
			give(c.level(), c.v(), rest);
		}
	}

	/** Performs a quota-aware insert and returns every unaccepted item to the caller. */
	public static ItemStack insertRemainder(Ctx c, Container box, ItemStack stack) {
		return c.village() == null ? HopperBlockEntity.addItem(null, box, stack, null)
			: de.villagerplus.village.StockPolicy.insertVillage(c.level(), c.village(), stack);
	}

	/** Sammelt frisch gedroppte Items in der Naehe auf. */
	public static void collectDrops(Ctx c, double radius, int maxAge) {
		List<ItemEntity> items = c.level().getEntitiesOfClass(ItemEntity.class, c.v().getBoundingBox().inflate(radius),
			e -> e.isAlive() && e.getAge() < maxAge);
		for (ItemEntity e : items) {
			give(c.level(), c.v(), e.getItem().copy());
			e.discard();
		}
	}

	/** Dorfkiste der Dorfmitte, sonst eine gemerkte/nahe Truhe. */
	public static BlockPos chest(Ctx c) {
		if (c.village() != null) {
			return communalDeliveryChest(c);
		}
		return findNearbyChest(c);
	}

	public static Container box(Ctx c) {
		BlockPos p = chest(c);
		return p == null ? null : HopperBlockEntity.getContainerAt(c.level(), p);
	}

	private static BlockPos findNearbyChest(Ctx c) {
		ServerLevel level = c.level();
		Villager v = c.v();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : c.mem().containers) {
			if (level.getBlockState(p).getBlock() instanceof ChestBlock && unownedChest(level, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d <= 40.0 * 40.0 && d < bestDist) {
					best = p;
					bestDist = d;
				}
			}
		}
		if (best != null || c.now() < c.st().chestCheckAt) {
			return best;
		}
		c.st().chestCheckAt = c.now() + 600;
		int r = Config.CHEST_SEARCH_RADIUS;
		BlockPos center = v.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 4, r))) {
			if (level.getBlockState(p).getBlock() instanceof ChestBlock && unownedChest(level, p)) {
				double d = p.distSqr(center);
				if (d < bestDist) {
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		if (best != null) {
			VillagerMemory.remember(c.mem().containers, best, Config.MEMORY_MAX_CONTAINERS);
		}
		return best;
	}

	private static boolean unownedChest(ServerLevel level, BlockPos pos) {
		var be = level.getBlockEntity(pos);
		return be == null || be.getAttached(de.villagerplus.VillagerPlus.OWNER) == null;
	}

	/**
	 * Bringt Ueberschuss zur Dorfkiste. Gibt true zurueck, solange der Dorfbewohner damit beschaeftigt ist.
	 *
	 * @param keep soviele passende Items behaelt der Dorfbewohner selbst
	 */
	public static boolean depositSurplus(Ctx c, Predicate<ItemStack> matches, int threshold, int keep) {
		int total = count(c.v().getInventory(), matches);
		if (total < threshold || total <= keep || c.now() < c.st().depositBlockedUntil) {
			return false;
		}
		Container inv = c.v().getInventory();
		int toMove = total - keep;
		java.util.Map<net.minecraft.world.item.Item, Integer> roomByItem = new java.util.IdentityHashMap<>();
		java.util.Map<net.minecraft.world.item.Item, Integer> carriedByItem = new java.util.IdentityHashMap<>();
		if (c.village() != null) {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty() || !matches.test(stack)) continue;
				var item = stack.getItem();
				carriedByItem.merge(item, stack.getCount(), Integer::sum);
			}
			roomByItem.putAll(de.villagerplus.village.StockPolicy.deliveryRooms(c.level(), c.village(), carriedByItem.keySet()));
			int policyAllowed = 0;
			for (var entry : carriedByItem.entrySet()) policyAllowed += Math.min(entry.getValue(), roomByItem.getOrDefault(entry.getKey(), 0));
			toMove = Math.min(toMove, policyAllowed);
		}
		if (toMove <= 0) return false;
		BlockPos chest = c.village() == null ? chest(c) : communalDeliveryChest(c);
		if (chest == null) {
			return false;
		}
		// Meilenstein-Gebaeude haben Vorrang: Nahrung -> Kornspeicher, Werkzeug/Ruestung -> Werkzeugkammer.
		String depotLabel = null;
		String destinationDepot = c.village() == null ? null : de.villagerplus.build.Blueprints.STORAGE;
		if (c.village() != null) {
			ItemStack sample = ItemStack.EMPTY;
			Container invNow = c.v().getInventory();
			for (int i = 0; i < invNow.getContainerSize() && sample.isEmpty(); i++) {
				ItemStack candidate = invNow.getItem(i);
				if (matches.test(candidate) && roomByItem.getOrDefault(candidate.getItem(), Integer.MAX_VALUE) > 0) {
					sample = candidate;
				}
			}
			String depot = de.villagerplus.village.Specials.depotType(sample);
			BlockPos special = depot == null ? null : de.villagerplus.village.Specials.depotWithRoom(c.level(), c.village(), depot, sample);
			if (special != null) {
				chest = special;
				destinationDepot = depot;
				depotLabel = depot.equals(de.villagerplus.build.Blueprints.GRANARY) ? "bringt Waren zum Kornspeicher" : "bringt Waren zur Werkzeugkammer";
			}
		}
		// Ist die Dorfkiste voll, geht der Ueberschuss in den Lagerschuppen (falls das Dorf einen hat).
		boolean toLager = false;
		Container home = HopperBlockEntity.getContainerAt(c.level(), chest);
		if (depotLabel == null && home != null && !hasEmptySlot(home) && c.village() != null && c.village().data().extra.lager.isPresent()) {
			BlockPos lagerChest = de.villagerplus.village.Lager.chestWithRoom(c.level(), c.village().data());
			if (lagerChest != null) {
				chest = lagerChest;
				toLager = true;
			}
		}
		// Keep the walk/anti-stuck state for this destination across job ticks. Clearing it
		// unconditionally here restarted the movement timer every tick, so a long route
		// could never time out or accumulate the three stuck retries.
		if (!chest.equals(c.st().target) || c.st().targetEntity != null) {
			c.st().target = chest.immutable();
			c.st().targetEntity = null;
			c.st().walkStart = 0;
			c.st().bestDist = 0.0;
			c.st().lastMoveAt = 0;
			c.st().stuckCount = 0;
		}
		c.setActivity(depotLabel != null ? depotLabel : toLager ? "bringt Waren ins Lager" : "bringt Waren zur Dorfkiste");
		if (!approachLong(c, chest, 3.0)) {
			return true;
		}
		Container box = HopperBlockEntity.getContainerAt(c.level(), chest);
		if (box == null) {
			return false;
		}
		// Stock may have changed while this worker was walking to the chest. Recount at commit time so two
		// workers cannot both consume the same remaining target allowance.
		if (c.village() != null) {
			int plannedBeforeArrival = toMove;
			roomByItem.clear();
			roomByItem.putAll(de.villagerplus.village.StockPolicy.deliveryRooms(c.level(), c.village(), carriedByItem.keySet()));
			toMove = 0;
			for (var entry : carriedByItem.entrySet()) toMove += Math.min(entry.getValue(), roomByItem.getOrDefault(entry.getKey(), 0));
			toMove = Math.min(toMove, plannedBeforeArrival);
		}
		for (int i = 0; i < inv.getContainerSize() && toMove > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !matches.test(s)) {
				continue;
			}
			int itemRoom = c.village() == null ? Integer.MAX_VALUE : roomByItem.getOrDefault(s.getItem(), 0);
			int planned = Math.min(toMove, Math.min(s.getCount(), itemRoom));
			if (planned <= 0) continue;
			ItemStack part = s.copyWithCount(planned);
			int before = part.getCount();
			ItemStack rest = c.village() == null ? HopperBlockEntity.addItem(null, box, part, null)
				: de.villagerplus.village.StockPolicy.insertInto(c.level(), c.village(), destinationDepot, box, part);
			int moved = before - rest.getCount();
			s.shrink(moved);
			toMove -= moved;
			if (c.village() != null) roomByItem.computeIfPresent(s.getItem(), (item, room) -> Math.max(0, room - moved));
			if (!rest.isEmpty()) {
				break;
			}
		}
		inv.setChanged();
		swing(c.v());
		c.st().cooldownUntil = c.now() + 100;
		if (toMove > 0) {
			// Alles voll (Dorfkiste und ggf. Lager): nichts wird weggeworfen. Spaeter erneut versuchen statt alle Arbeit zu blockieren;
			// das Dorf baut bei voller Kiste vorrangig einen Lagerschuppen.
			c.st().depositBlockedUntil = c.now() + 2400;
			return false;
		} else {
			c.mem().jobsDone++;
		}
		return true;
	}

	/** Resolves only village-owned storage; a nearby private house chest is never a communal delivery destination. */
	public static BlockPos communalDeliveryChest(Ctx c) {
		if (c.village() == null) return null;
		BlockPos home = c.village().chestPos(c.level());
		if (home != null) return home;
		return de.villagerplus.village.Lager.chestWithRoom(c.level(), c.village().data());
	}

	/** Legt einen Stapel in die Kiste (erst auf gleiche Stapel, dann in freie Plaetze); Rest geht verloren (nur fuer Rueckbau-Material). */
	public static void depositTo(Container box, ItemStack stack) {
		for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack cur = box.getItem(i);
			if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), cur.getMaxStackSize() - cur.getCount());
				cur.grow(move);
				stack.shrink(move);
				box.setChanged();
			}
		}
		for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
			if (box.getItem(i).isEmpty()) {
				box.setItem(i, stack.copy());
				stack.setCount(0);
			}
		}
	}

	public static boolean hasEmptySlot(Container box) {
		for (int i = 0; i < box.getContainerSize(); i++) {
			if (box.getItem(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public static void tellNearbyPlayers(ServerLevel level, Entity at, double radius, Component msg) {
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(at) <= radius * radius) {
				VillageMessages.overlay(p, msg);
			}
		}
	}
}
