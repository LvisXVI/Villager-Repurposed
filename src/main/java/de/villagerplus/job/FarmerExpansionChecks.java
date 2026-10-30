package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exercises a real FarmerJob.secondary call through material reservation and field construction. */
public final class FarmerExpansionChecks {
	private FarmerExpansionChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos site = ref.bell().offset(50, 0, 50);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Villager> fixtures = new ArrayList<>();
		var oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		int oldFarms = ref.data().farms;
		var oldPen = ref.data().extra.pen;
		long oldNextFarm = ref.data().extra.nextFarm;
		var box = ref.chest(level);
		if (box == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: village chest missing");
		List<ItemStack> oldItems = new ArrayList<>();
		for (int i = 0; i < box.getContainerSize(); i++) oldItems.add(box.getItem(i).copy());
		try {
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos cell = site.offset(dx, 0, dz);
				level.getChunkAt(cell);
				for (BlockPos p : new BlockPos[]{cell.below(), cell, cell.above()}) before.putIfAbsent(p, level.getBlockState(p));
				level.setBlock(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
			}
			box.clearContent();
			put(box, 0, Items.WHEAT_SEEDS, 8);
			put(box, 1, Items.OAK_FENCE, 15);
			put(box, 2, Items.OAK_FENCE_GATE, 1);
			put(box, 3, Items.WATER_BUCKET, 1);
			ref.data().farms = 1;
			ref.data().extra.pen = Optional.of(ref.bell().south(24)); // Prevent the legacy auto-pen branch during this field check.
			ref.data().extra.nextFarm = 0;
			ZonePolicy.Zone zone = new ZonePolicy.Zone("live-farm-check", "farm", site.getX() - 6, site.getZ() - 6,
				site.getX() + 6, site.getZ() + 6);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(zone));
			ref.dirty();

			for (int i = 0; i < 4; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, site.offset(i == 0 ? 0 : i, 0, 0), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: could not spawn villager fixture");
				fixtures.add(villager);
				villager.addTag("vp090_fixture"); villager.setInvulnerable(true); villager.setNoAi(true);
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), site.offset(-12, 0, 0)));
			}
			Villager farmer = fixtures.getFirst();
			farmer.teleportTo(site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5);
			farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), site));
			JobState state = JobManager.state(farmer);
			state.idleUntil[1] = 0;
			VillagerMemory memory = farmer.getAttachedOrCreate(VillagerPlus.MEMORY);
			Ctx ctx = new Ctx(level, farmer, state, memory, ref, level.getGameTime() + 1);
			BlockPos actual = new BlockPos(site.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.getX(), site.getZ()), site.getZ());
			boolean worked = new FarmerJob().secondary(ctx);
			check(worked && ref.data().farms == 2, "a farmer's real secondary work cycle expands the field when the village food reserve is low");
			check(level.getBlockState(actual.below()).is(Blocks.FARMLAND)
				&& level.getBlockState(actual).is(Blocks.WATER)
				&& level.getBlockState(actual.offset(1, 0, 0)).is(Blocks.WHEAT),
				"farmer commits irrigated crop and fence blocks at a selected farm-zone position");
			check(JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE_GATE)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.WATER_BUCKET)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.BUCKET)) == 1,
				"field construction consumes exact farm stock and returns the empty bucket");
			return 3;
		} finally {
			for (Villager villager : fixtures) villager.discard();
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
			box.clearContent();
			for (int i = 0; i < oldItems.size(); i++) box.setItem(i, oldItems.get(i));
			ref.data().farms = oldFarms; ref.data().extra.pen = oldPen; ref.data().extra.nextFarm = oldNextFarm;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES); else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.dirty();
		}
	}

	private static void put(net.minecraft.world.Container box, int slot, Item item, int count) { box.setItem(slot, new ItemStack(item, count)); }
	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: " + message);
		System.out.println("FARM EXPANSION PASS " + message);
	}
}
