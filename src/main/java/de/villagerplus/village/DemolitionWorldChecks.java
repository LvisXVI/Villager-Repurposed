package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Furnisher;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Physical demolition of one registered building: chest contents, a swapped block, and restored ground. */
public final class DemolitionWorldChecks {
	private DemolitionWorldChecks() { }

	private record Cell(BlockState state, CompoundTag data) { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos origin = at.offset(30, 0, 0);
		BlockPos min = at.offset(-8, -10, -8);
		BlockPos max = at.offset(80, 30, 50);
		Map<BlockPos, Cell> before = snapshot(level, min, max);
		Villager worker = null;
		int checks = 0;
		try {
			for (BlockPos pos : before.keySet()) {
				level.removeBlockEntity(pos);
				level.setBlock(pos, pos.getY() == origin.getY() - 1 ? Blocks.PODZOL.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
			}
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			worker = EntityTypes.VILLAGER.spawn(level, origin, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Demolition: fixture villager was not created");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
				Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
			Built built = new Built(Blueprints.GRANARY, origin, 0, 0, palette.ids());
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.of(built));
			List<Step> steps = Builder.stepsOfBuilt(level, ref, built, worker);
			if (steps.isEmpty()) throw new IllegalStateException("Demolition: granary design produced no steps");
			for (Step step : steps) {
				if (!before.containsKey(step.pos())) throw new IllegalStateException("Demolition: step outside the restored plateau " + step.pos());
			}
			BoundingBox box = Furnisher.boxOf(steps);
			for (Step step : steps) {
				Block material = step.linkedTo() != null ? level.getBlockState(step.linkedTo()).getBlock()
					: step.item() != null ? Block.byItem(step.item()) : Blocks.AIR;
				level.setBlock(step.pos(), step.make().apply(material), 2);
			}
			BlockPos swapped = null;
			BlockPos interior = null;
			for (Step step : steps) {
				if (!level.getBlockState(step.pos()).is(Blocks.OAK_PLANKS)) continue;
				if (swapped == null) swapped = step.pos().immutable();
				else if (interior == null) { interior = step.pos().immutable(); break; }
			}
			if (swapped == null || interior == null) throw new IllegalStateException("Demolition: granary has no two plank cells");
			level.setBlock(swapped, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
			level.setBlock(interior, Blocks.CHEST.defaultBlockState(), 2);
			Container interiorChest = (Container) level.getBlockEntity(interior);
			interiorChest.setItem(0, new ItemStack(Items.BRICK, 4));
			interiorChest.setItem(1, new ItemStack(Items.BREAD, 3));
			int floorY = origin.getY() - 1;
			for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
				BlockPos floor = new BlockPos(x, floorY, z);
				if (level.getBlockState(floor).is(Blocks.PODZOL)) level.setBlock(floor, Blocks.DIRT_PATH.defaultBlockState(), 2);
			}
			BlockPos outsider = new BlockPos(box.minX() - 2, origin.getY(), box.minZ());
			if (!before.containsKey(outsider)) throw new IllegalStateException("Demolition: outside marker is off the plateau");
			level.setBlock(outsider, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
			Specials.set(ref, Blueprints.GRANARY, origin);

			String accepted = Demolition.request(level, ref, origin);
			check(accepted.startsWith("Abriss angeordnet"), "board request queues physical demolition"); checks++;
			check(Demolition.request(level, ref, origin).contains("läuft schon"), "a second request does not duplicate the job"); checks++;
			check(Demolition.jobs(ref).size() == 1 && Demolition.jobs(ref).getFirst().index() == 0, "demolition cursor starts at the saved origin"); checks++;

			JobState state = new JobState();
			long now = level.getGameTime();
			int guard = 0;
			while (!Demolition.jobs(ref).isEmpty()) {
				if (++guard > steps.size() * 16) throw new IllegalStateException("Demolition: work did not finish in " + guard + " ticks");
				BlockPos stand = highestRemaining(level, steps, origin);
				worker.teleportTo(stand.getX() + 0.5, origin.getY(), stand.getZ() + 0.5);
				Demolition.work(new Ctx(level, worker, state, worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now), ref);
				now += 6;
			}
			check(guard > 11, "demolition spends visible crack stages before removing blocks"); checks++;
			check(Builder.builtOf(ref).stream().noneMatch(b -> b.origin().equals(origin)), "finished building is removed from the village register"); checks++;
			check(!Specials.has(ref, Blueprints.GRANARY), "demolished granary registration is cleared"); checks++;
			check(!level.getBlockState(swapped).is(Blocks.SPRUCE_PLANKS)
				&& (swapped.getY() == floorY ? level.getBlockState(swapped).is(Blocks.PODZOL) : level.getBlockState(swapped).isAir()),
				"player-swapped plank is physically removed, found " + level.getBlockState(swapped)); checks++;
			check(!(level.getBlockEntity(interior) instanceof Container)
				&& (interior.getY() == floorY ? level.getBlockState(interior).is(Blocks.PODZOL) : level.getBlockState(interior).isAir()),
				"interior chest is physically removed, found " + level.getBlockState(interior)); checks++;
			check(count(level, ref, before, Items.SPRUCE_PLANKS) == 1, "swapped spruce planks return exactly once"); checks++;
			check(count(level, ref, before, Items.BRICK) == 4, "chest building materials return to shared storage"); checks++;
			Container material = ref.chest(level);
			check(material != null && JobUtil.count(material, stack -> stack.is(Items.BREAD)) == 0, "food from the demolished chest is not stored in the material depot"); checks++;
			check(count(level, ref, before, Items.BREAD) == 3, "food from the demolished chest is conserved"); checks++;
			BlockPos sample = new BlockPos((box.minX() + box.maxX()) / 2, floorY, (box.minZ() + box.maxZ()) / 2);
			check(level.getBlockState(sample).is(Blocks.PODZOL), "building footprint is restored to the surrounding surface"); checks++;
			check(level.getBlockState(sample.below()).is(Blocks.DIRT), "the opened footprint is filled back to solid ground"); checks++;
			check(level.getBlockState(outsider).is(Blocks.EMERALD_BLOCK), "a block outside the saved plan is left untouched"); checks++;
			return checks;
		} finally {
			if (worker != null) {
				Anim.cancelBreaking(worker);
				worker.discard();
			}
			AABB area = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
			for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) drop.discard();
			restore(level, before);
		}
	}

	private static Map<BlockPos, Cell> snapshot(ServerLevel level, BlockPos min, BlockPos max) {
		Map<BlockPos, Cell> before = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			BlockPos p = pos.immutable();
			level.getChunkAt(p);
			BlockEntity be = level.getBlockEntity(p);
			before.put(p, new Cell(level.getBlockState(p), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
		}
		return before;
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 2));
		before.forEach((pos, cell) -> {
			if (cell.data() == null) return;
			BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (be != null) level.setBlockEntity(be);
		});
	}

	private static BlockPos highestRemaining(ServerLevel level, List<Step> steps, BlockPos origin) {
		BlockPos best = origin;
		for (Step step : steps) {
			BlockState state = level.getBlockState(step.pos());
			if (state.isAir() || !state.getFluidState().isEmpty()) continue;
			if (best.equals(origin) || step.pos().getY() > best.getY()) best = step.pos();
		}
		return best;
	}

	private static int count(ServerLevel level, VillageRef ref, Map<BlockPos, Cell> area, Item item) {
		int total = 0;
		Container depot = ref.chest(level);
		if (depot != null) total += JobUtil.count(depot, stack -> stack.is(item));
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : area.keySet()) {
			minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
		}
		AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
		for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box)) {
			if (drop.getItem().is(item)) total += drop.getItem().getCount();
		}
		return total;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Demolition: " + message);
	}
}
