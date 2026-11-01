package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Builder;
import de.villagerplus.village.Balance;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bauer: Vormittags ernten + neu pflanzen. Nachmittags Brot backen und neue Felder anlegen (wenn Saatgut in der Dorfkiste liegt).
 */
public class FarmerJob implements Job {
	private static final int CLUSTER_SEEDS = 8;
	private static final int CLUSTER_FENCES = 15;
	private static final int MAX_CLUSTER_RADIUS = 36;
	private static final List<SeedCrop> SEED_CROPS = List.of(
		new SeedCrop(Items.WHEAT_SEEDS, Blocks.WHEAT), new SeedCrop(Items.CARROT, Blocks.CARROTS),
		new SeedCrop(Items.POTATO, Blocks.POTATOES), new SeedCrop(Items.BEETROOT_SEEDS, Blocks.BEETROOTS));
	private record SeedCrop(Item seed, Block crop) { }
	private record FenceKit(Item fenceItem, Block fence, Item gateItem, Block gate) { }
	private record Taken(Container source, Item item, int count) { }
	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Brot", () -> new ItemStack(Items.BREAD), Craft.of(Items.WHEAT, 3)));

	private static boolean isCrop(Item i) {
		return i == Items.WHEAT || i == Items.CARROT || i == Items.POTATO || i == Items.BEETROOT || i == Items.POISONOUS_POTATO || i == Items.BREAD || i == Items.WHEAT_SEEDS || i == Items.BEETROOT_SEEDS;
	}

	private static boolean mature(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
	}

	/** Population-scaled reserve target used to decide when additional crop production is needed. */
	public static int foodTarget(int residents) {
		return Math.max(0, residents) * 5;
	}

	public static boolean belowFoodTarget(long storedFood, int residents) {
		return storedFood < foodTarget(residents);
	}

	public static int farmLimit(int residents) {
		return Math.min(Config.MAX_FARMS, 1 + Math.max(0, residents) / 4);
	}

	public static boolean shouldExpand(int currentFarms, int residents) {
		return currentFarms < farmLimit(residents);
	}

	/** Includes the village food stores, especially the granary, rather than counting only the public chest. */
	public static boolean belowFoodTarget(Ctx c) {
		if (c.village() == null) return false;
		int residents = de.villagerplus.village.Villages.residents(c.level(), c.village(), 96, v -> true).size();
		List<Container> stores = Balance.storage(c.level(), c.village());
		long edible = Balance.stock(stores, Balance.Kind.FOOD);
		// Wheat is an ingredient rather than edible by itself; count only the flour/bread-equivalent reserve.
		for (Container store : stores) edible += JobUtil.count(store, stack -> stack.is(Items.WHEAT)) / 3;
		return belowFoodTarget(edible, residents);
	}

	/** Validate a complete 3x3 irrigated plot with a walkable fenced perimeter without replacing existing blocks. */
	public static boolean safeClusterSite(ServerLevel level, BlockPos cropLevel) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			BlockPos p = cropLevel.offset(dx, 0, dz);
			if (!level.hasChunkAt(p.below()) || !level.hasChunkAt(p.above())) return false;
			BlockState floor = level.getBlockState(p.below());
			boolean perimeter = Math.abs(dx) == 2 || Math.abs(dz) == 2;
			if (perimeter) {
				if (!floor.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
					|| !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) return false;
			} else {
				if (dx == 0 && dz == 0) {
					if (!farmableSoil(floor) || !level.getBlockState(p).isAir()) return false;
				} else if (!farmableSoil(floor)
					|| !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) return false;
			}
		}
		return true;
	}

	private static boolean farmableSoil(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.GRASS_BLOCKS) || state.is(Blocks.FARMLAND);
	}

	@Override
	public Predicate<ItemStack> produce() {
		return s -> isCrop(s.getItem());
	}

	@Override
	public int keep() {
		return Config.FARM_KEEP;
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		boolean foodDeficit = belowFoodTarget(c);
		if (JobUtil.depositSurplus(c, produce(), foodDeficit ? 1 : Config.FARM_DEPOSIT_THRESHOLD,
			foodDeficit ? 0 : Config.FARM_KEEP)) {
			return true;
		}
		JobState st = c.st();
		ServerLevel level = c.level();
		BlockPos target = st.target;
		if (target == null || !mature(level, target)) {
			st.clearTarget();
			target = find(c);
			if (target == null) {
				c.setIdle(0, 200);
				return false;
			}
			st.target = target;
		}
		c.setActivity("erntet auf dem Feld");
		Anim.hold(c, Items.IRON_HOE);
		if (!JobUtil.approach(c, target, 2.6)) {
			return true;
		}
		BlockState state = level.getBlockState(target);
		CropBlock crop = (CropBlock) state.getBlock();
		List<ItemStack> drops = Block.getDrops(state, level, target, null);
		for (ItemStack d : drops) {
			JobUtil.give(level, c.v(), d);
		}
		// Erfahrene Bauern ernten oefter doppelt.
		if (!drops.isEmpty() && c.v().getRandom().nextFloat() < 0.1F * Skills.level(c.mem())) {
			JobUtil.give(level, c.v(), drops.get(0).copy());
		}
		level.setBlock(target, crop.getStateForAge(0), 3);
		level.levelEvent(2001, target, Block.getId(state));
		level.playSound(null, target, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
		JobUtil.swing(c.v());
		VillagerMemory.remember(c.mem().workSpots, target, Config.MEMORY_MAX_WORK_SPOTS);
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 20;
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		VillageRef ref = c.village();
		boolean legacyBuilder = ref == null || ref.data().hasPlan() || farmZones(ref).isEmpty();
		if (!legacyBuilder) {
			// Builder also creates pens. Preserve that path while new zoned fields use the bounded cluster search below.
			int residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true).size();
			legacyBuilder = ref.data().extra.pen.isEmpty() && ref.data().farms >= 1 && residents >= Config.PEN_POP;
		}
		if ((legacyBuilder && Builder.tick(c, false)) || expandCluster(c) || Livestock.tick(c) || Craft.run(c, RECIPES)) {
			return true;
		}
		c.setIdle(1, 300);
		return false;
	}

	/** Creates a compact farm only when shared village food is below the resident reserve target. */
	private static boolean expandCluster(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.now() < c.st().materialWaitUntil || !belowFoodTarget(c)) return false;
		var data = ref.data();
		var stores = Balance.storage(c.level(), ref);
		if (stores.isEmpty() || c.now() < data.extra.nextFarm || data.hasPlan()) return false;
		List<Villager> residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true);
		if (residents.isEmpty() || residents.stream().filter(de.villagerplus.village.Housing::hasHome).count() < 2) return false;
		if (!shouldExpand(data.farms, residents.size())) return false;
		FenceKit kit = fenceKit(stores);
		SeedCrop crop = SEED_CROPS.stream().filter(sc -> count(stores, sc.seed()) >= CLUSTER_SEEDS)
			.max(Comparator.comparingInt(sc -> count(stores, sc.seed()))).orElse(null);
		if (kit == null || crop == null || count(stores, Items.WATER_BUCKET) < 1) {
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		BlockPos site = findClusterSite(c, ref);
		if (site == null) {
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		c.setActivity("legt bei knappen Vorräten ein bewässertes Feld an");
		Anim.hold(c, Items.IRON_HOE);
		if (!JobUtil.approach(c, site, 3.0)) return true;
		if (!clusterAllowedByZones(ref, site) || !safeClusterSite(c.level(), site)) return false;
		List<Taken> taken = new ArrayList<>();
		if (!take(stores, crop.seed(), CLUSTER_SEEDS, taken)
			|| !take(stores, kit.fenceItem(), CLUSTER_FENCES, taken)
			|| !take(stores, kit.gateItem(), 1, taken)
			|| !take(stores, Items.WATER_BUCKET, 1, taken)) {
			restore(taken);
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		Map<BlockPos, BlockState> before = new java.util.LinkedHashMap<>();
		boolean built = true;
		for (int dx = -2; dx <= 2 && built; dx++) for (int dz = -2; dz <= 2 && built; dz++) {
			if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
			BlockPos pos = site.offset(dx, 0, dz);
			before.put(pos, c.level().getBlockState(pos));
			BlockState fence = dx == 0 && dz == 2 ? kit.gate().defaultBlockState() : kit.fence().defaultBlockState();
			built = c.level().setBlock(pos, fence, 3);
		}
		for (int dx = -1; dx <= 1 && built; dx++) for (int dz = -1; dz <= 1 && built; dz++) {
			BlockPos pos = site.offset(dx, 0, dz);
			BlockPos soil = pos.below();
			before.put(soil, c.level().getBlockState(soil));
			if (dx == 0 && dz == 0) {
				BlockPos water = pos;
				before.put(water, c.level().getBlockState(water));
				built = c.level().setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3)
					&& c.level().setBlock(water, Blocks.WATER.defaultBlockState(), 3);
			} else {
				BlockPos cropPos = pos;
				before.put(cropPos, c.level().getBlockState(cropPos));
				built = c.level().setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3)
					&& c.level().setBlock(cropPos, crop.crop().defaultBlockState(), 3);
			}
		}
		if (!built) {
			before.forEach((pos, state) -> c.level().setBlock(pos, state, 3));
			restore(taken);
			return false;
		}
		Taken bucket = taken.stream().filter(t -> t.item() == Items.WATER_BUCKET).findFirst().orElseThrow();
		ItemStack emptyBucket = new ItemStack(Items.BUCKET);
		ItemStack remainder = HopperBlockEntity.addItem(null, bucket.source(), emptyBucket, null);
		if (!remainder.isEmpty()) {
			// The water bucket slot was freed by consumption, so this indicates an unexpected container mutation.
			JobUtil.give(c.level(), c.v(), remainder);
		}
		data.farms++;
		data.extra.nextFarm = c.now() + Config.BUILD_COOLDOWN;
		ref.dirty();
		de.villagerplus.village.Journal.add(c.level(), ref, "Landwirtschaft", c.mem().name,
			"legt ein bewässertes und umzäuntes Feld an", 0);
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 80;
		return true;
	}

	private static BlockPos findClusterSite(Ctx c, VillageRef ref) {
		BlockPos center = c.v().getBrain().getMemory(MemoryModuleType.JOB_SITE)
			.filter(p -> p.dimension() == c.level().dimension()).map(GlobalPos::pos).orElse(ref.bell());
		return findClusterSite(c.level(), ref, center);
	}

	/** Farm zones reserve the entire crop, irrigation and fence footprint; no outside fallback is allowed while one exists. */
	static BlockPos findClusterSite(ServerLevel level, VillageRef ref, BlockPos center) {
		List<ZonePolicy.Zone> zones = farmZones(ref);
		if (!zones.isEmpty()) {
			java.util.Set<Long> seen = new java.util.HashSet<>();
			List<BlockPos> candidates = new ArrayList<>();
			for (ZonePolicy.Zone zone : zones) {
				for (int x = zone.minX() + 2; x <= zone.maxX() - 2; x++) {
					for (int z = zone.minZ() + 2; z <= zone.maxZ() - 2; z++) {
						if (seen.add(BlockPos.asLong(x, 0, z))) candidates.add(new BlockPos(x, center.getY(), z));
					}
				}
			}
			candidates.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(center))
				.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
			for (BlockPos candidate : candidates) {
				if (!level.hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) continue;
				BlockPos site = new BlockPos(candidate.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					candidate.getX(), candidate.getZ()), candidate.getZ());
				if (safeClusterSite(level, site)) return site;
			}
			return null;
		}
		// Keep the established no-zone search order and radius for existing worlds.
		for (int radius = 8; radius <= MAX_CLUSTER_RADIUS; radius += 4) {
			for (int dx = -radius; dx <= radius; dx += 4) for (int dz = -radius; dz <= radius; dz += 4) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				int x = center.getX() + dx, z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos candidate = new BlockPos(x, y, z);
				if (safeClusterSite(level, candidate)) return candidate;
			}
		}
		return null;
	}

	/** Re-read persisted zones at commit time so an edited zone cannot authorize an old walking target. */
	static boolean clusterAllowedByZones(VillageRef ref, BlockPos site) {
		List<ZonePolicy.Zone> zones = farmZones(ref);
		return zones.isEmpty() || zones.stream().anyMatch(zone -> site.getX() - 2 >= zone.minX()
			&& site.getX() + 2 <= zone.maxX() && site.getZ() - 2 >= zone.minZ() && site.getZ() + 2 <= zone.maxZ());
	}

	private static List<ZonePolicy.Zone> farmZones(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.ZONES, List.of()).stream()
			.filter(zone -> "farm".equals(zone.kind()) && ZonePolicy.validBounds(ref, zone.kind(),
				zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

	private static FenceKit fenceKit(List<Container> stores) {
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.is(ItemTags.WOODEN_FENCES)) continue;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			String path = id.getPath();
			if (!path.endsWith("_fence")) continue;
			Identifier gateId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 6) + "_fence_gate");
			Item gateItem = BuiltInRegistries.ITEM.getOptional(gateId).orElse(Items.AIR);
			Block fence = Block.byItem(stack.getItem()), gate = Block.byItem(gateItem);
			if (gateItem != Items.AIR && gate.defaultBlockState().is(BlockTags.FENCE_GATES)
				&& count(stores, stack.getItem()) >= CLUSTER_FENCES && count(stores, gateItem) >= 1)
				return new FenceKit(stack.getItem(), fence, gateItem, gate);
		}
		return null;
	}

	private static int count(List<Container> stores, Item item) {
		return stores.stream().mapToInt(box -> JobUtil.count(box, stack -> stack.is(item))).sum();
	}

	private static boolean take(List<Container> stores, Item item, int amount, List<Taken> taken) {
		int left = amount;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.is(item)) continue;
			int n = Math.min(left, stack.getCount());
			box.removeItem(slot, n);
			box.setChanged();
			taken.add(new Taken(box, item, n));
			left -= n;
		}
		return left == 0;
	}

	private static void restore(List<Taken> taken) {
		for (Taken part : taken) {
			ItemStack remainder = HopperBlockEntity.addItem(null, part.source(), new ItemStack(part.item(), part.count()), null);
			if (!remainder.isEmpty()) throw new IllegalStateException("Farm cluster rollback could not restore communal stock");
		}
	}

	/** Erst die gemerkten Felder pruefen (billig), sonst die Umgebung des Arbeitsplatzes absuchen. */
	private static BlockPos find(Ctx c) {
		ServerLevel level = c.level();
		Villager v = c.v();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : c.mem().workSpots) {
			if (mature(level, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d < bestDist) {
					best = p;
					bestDist = d;
				}
			}
		}
		if (best != null) {
			return best;
		}
		BlockPos center = v.blockPosition();
		GlobalPos job = v.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
		if (job != null && job.dimension() == level.dimension()) {
			center = job.pos();
		}
		int r = Config.FARM_RADIUS;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, 2, r))) {
			if (mature(level, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d < bestDist) {
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		return best;
	}
}
