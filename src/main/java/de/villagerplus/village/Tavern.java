package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Meals;
import net.minecraft.world.entity.npc.villager.Villager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Optional tavern evening stop. It only activates when a loaded modded tavern/inn block is nearby. */
public final class Tavern {
	private record Found(BlockPos pos, long checkedAt) { }
	private static final Map<Long, Found> CACHE = new HashMap<>();
	private static final long RESCAN_TICKS = 1200;

	private Tavern() { }

	/** true means the villager is travelling, being served, or waiting for its service cooldown. */
	public static boolean visit(Ctx c) {
		VillageRef ref = c.village();
		// Leave first-meal initialization to the normal household routine; hungry() initializes an unset clock.
		if (ref == null || c.mem().lastMeal == 0 || !Meals.hungry(c)) return false;
		BlockPos tavern = find(c.level(), ref, c.v(), c.now());
		if (tavern == null) return false;

		// A tavern meal costs one emerald from this villager's own inventory; communal funds never mint currency.
		if (JobUtil.count(c.v().getInventory(), s -> s.is(Items.EMERALD)) < 1 || !hasPantryFood(c.level(), ref)) return false;
		c.setActivity("isst in der Taverne");
		if (!JobUtil.approach(c, tavern, 2.5)) return true;

		List<Container> pantry = Specials.containers(c.level(), ref, Blueprints.GRANARY);
		for (Container box : pantry) {
			if (serve(c, box)) {
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 200;
				return true;
			}
		}
		return false;
	}

	/** Charges once for a real prepared meal; failed service refunds the resident's emerald. */
	private static boolean serve(Ctx c, Container pantry) {
		if (JobUtil.take(c.v().getInventory(), s -> s.is(Items.EMERALD), 1) == 0) return false;
		if (!Meals.eatFrom(c, pantry, Meals::isPreparedFood)) {
			JobUtil.give(c.level(), c.v(), new net.minecraft.world.item.ItemStack(Items.EMERALD));
			return false;
		}
		long revenue = c.village().bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L);
		if (revenue < Long.MAX_VALUE) c.village().bellEntity().setAttached(VillagerPlus.TAVERN_REVENUE, revenue + 1L);
		c.village().dirty();
		return true;
	}

	private static boolean hasPantryFood(net.minecraft.server.level.ServerLevel level, VillageRef ref) {
		for (Container box : Specials.containers(level, ref, Blueprints.GRANARY)) {
			if (JobUtil.count(box, Meals::isPreparedFood) > 0) return true;
		}
		return false;
	}

	private static BlockPos find(net.minecraft.server.level.ServerLevel level, VillageRef ref, Villager villager, long now) {
		long key = 31L * level.dimension().identifier().hashCode() + ref.bell().asLong();
		Found cached = CACHE.get(key);
		if (cached != null && now - cached.checkedAt() < RESCAN_TICKS
			&& (cached.pos() == null || reachableFloor(level, villager, cached.pos()))) return cached.pos();

		BlockPos center = Market.of(ref).plaza().orElse(ref.bell());
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int minChunkX = (center.getX() - 64) >> 4, maxChunkX = (center.getX() + 64) >> 4;
		int minChunkZ = (center.getZ() - 64) >> 4, maxChunkZ = (center.getZ() + 64) >> 4;
		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (int cx = minChunkX; cx <= maxChunkX; cx++) for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			var chunk = level.getChunkSource().getChunkNow(cx, cz);
			if (chunk == null) continue;
			for (var entry : chunk.getAllStarts().entrySet()) {
				StructureStart start = entry.getValue();
				if (!start.isValid()) continue;
				var id = structures.getKey(entry.getKey());
				String path = id.getPath().toLowerCase(java.util.Locale.ROOT);
				boolean tavernStructure = path.contains("tavern") || path.equals("inn") || path.startsWith("inn_") || path.contains("_inn_") || path.endsWith("_inn");
				if (id.getNamespace().equals("minecraft") || !tavernStructure) continue;
				var bounds = start.getBoundingBox();
				int sx = Math.max(1, (bounds.maxX() - bounds.minX()) / 5);
				int sz = Math.max(1, (bounds.maxZ() - bounds.minZ()) / 5);
				for (int y = bounds.minY() + 1; y <= bounds.minY() + 4; y++) {
					for (int x = bounds.minX() + 1; x < bounds.maxX(); x += sx) {
						for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z += sz) {
							BlockPos floor = new BlockPos(x, y, z);
							double distance = floor.distSqr(villager.blockPosition());
							if (distance > 96 * 96 || distance >= bestDistance || !reachableFloor(level, villager, floor)) continue;
							best = floor;
							bestDistance = distance;
						}
					}
				}
			}
		}
		CACHE.put(key, new Found(best, now));
		if (CACHE.size() > 256) CACHE.entrySet().removeIf(e -> now - e.getValue().checkedAt() > RESCAN_TICKS * 4);
		return best;
	}

	/** A tavern target must be a supported, empty feet/head position with an actual navigation path. */
	private static boolean reachableFloor(net.minecraft.server.level.ServerLevel level, Villager villager, BlockPos feet) {
		BlockPos below = feet.below(), head = feet.above();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(below) || !level.hasChunkAt(head)) return false;
		var floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, net.minecraft.core.Direction.UP) || !floor.getFluidState().isEmpty()) return false;
		var body = level.getBlockState(feet);
		var upper = level.getBlockState(head);
		if (!body.getFluidState().isEmpty() || !upper.getFluidState().isEmpty()
			|| !body.getCollisionShape(level, feet).isEmpty() || !upper.getCollisionShape(level, head).isEmpty()) return false;
		var path = villager.getNavigation().createPath(feet, 0);
		return path != null && path.canReach();
	}
}
