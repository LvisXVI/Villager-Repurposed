package de.villagerplus.dev;

import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** G02: a mason completes the granary milestone inside a generated CTOV village. */
public final class CtovMilestoneChecks {
	private CtovMilestoneChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		Villages.adoptUnassigned(level, ref);
		var residents = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV milestone: no resident to build");
		Villager mason = residents.get(0);
		mason.setInvulnerable(true);
		mason.setNoAi(true);
		Villages.ensureCenter(level, ref, mason, level.getGameTime() + 10_000);
		Container chest = ref.chest(level);
		if (chest == null) {
			BlockPos pos = ref.bell().south(2);
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
			ref.data().chest = java.util.Optional.of(pos);
			ref.dirty();
			chest = ref.chest(level);
		}
		if (chest == null) throw new IllegalStateException("CTOV milestone: village chest missing");
		var state = JobManager.state(mason);
		state.buildCheckAt = 0;
		state.materialWaitUntil = 0;
		ref.data().nextBuild = 0;
		if ("ctov-headless".equals(ref.data().planType) && ref.data().planOrigin.isEmpty()) ref.data().clearPlan();
		int checks = 0;
		int lastStep = -1;
		long lastProgress = -1;
		int idle = 0;
		BlockPos granary = Specials.get(ref, Blueprints.GRANARY);
		for (int tick = 0; tick < 8000 && granary == null; tick++) {
			if (ref.data().planOrigin.isPresent()) {
				Ctx preview = new Ctx(level, mason, state, mason.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY), ref, level.getGameTime() + 20_000L + tick);
				BlockPos stand = Builder.constructionStand(preview);
				if (stand != null) mason.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			}
			supply(chest, ref.data().wanted);
			state.buildCheckAt = 0;
			state.materialWaitUntil = 0;
			Ctx ctx = new Ctx(level, mason, state, mason.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY), ref, level.getGameTime() + 20_000L + tick);
			Builder.tick(ctx, true);
			granary = Specials.get(ref, Blueprints.GRANARY);
			int step = ref.data().planStep;
			long progress = ref.data().planProgress;
			if (step != lastStep || progress != lastProgress || !ref.data().wanted.isEmpty()) {
				lastStep = step;
				lastProgress = progress;
				idle = 0;
			} else if (++idle > 800) {
				throw new IllegalStateException("CTOV milestone: granary stalled at step " + step + "/" + Builder.planSize(ref.data())
					+ " type=" + ref.data().planType
					+ " origin=" + ref.data().planOrigin.map(BlockPos::toShortString).orElse("none")
					+ " wanted=" + ref.data().wanted + " mason=" + mason.blockPosition().toShortString()
					+ " diag=" + Builder.planStepDiagnostic(ref.data()));
			}
		}
		check(granary != null, "mason finished a granary in the generated village"); checks++;
		int placed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(granary.offset(-8, -2, -8), granary.offset(8, 8, 8))) {
			if (!level.getBlockState(pos).isAir()) placed++;
		}
		check(placed >= 40, "granary footprint contains placed blocks (" + placed + ")"); checks++;
		ref.bellEntity().setChanged();
		level.getChunkAt(granary).markUnsaved();
		System.out.println("CTOV MILESTONE PASS granary " + granary.toShortString());
		return checks;
	}

	public static BlockPos granaryOf(VillageRef ref) {
		return Specials.get(ref, Blueprints.GRANARY);
	}

	private static void supply(Container chest, java.util.List<String> wanted) {
		for (String entry : wanted) {
			String[] parts = entry.split("#");
			var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (item.isEmpty()) continue;
			int need = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
			int have = JobUtil.count(chest, stack -> stack.is(item.get()));
			int left = need - have;
			for (int slot = 0; slot < chest.getContainerSize() && left > 0; slot++) {
				ItemStack stack = chest.getItem(slot);
				if (!stack.isEmpty() && !stack.is(item.get())) continue;
				int room = stack.isEmpty() ? item.get().getDefaultMaxStackSize() : stack.getMaxStackSize() - stack.getCount();
				if (room <= 0) continue;
				int add = Math.min(room, left);
				if (stack.isEmpty()) chest.setItem(slot, new ItemStack(item.get(), add));
				else stack.grow(add);
				left -= add;
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV milestone: " + message);
	}
}
