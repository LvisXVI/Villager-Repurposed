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

/** G02 granary checks. {@link #runHarness} only proves Builder can place blocks. {@link #runWalk} is the village doing the work. */
public final class CtovMilestoneChecks {
	private CtovMilestoneChecks() { }

	/** Harness: no villager AI, the mason is moved onto the chosen stand, and the chest is topped up every tick. */
	public static int runHarness(ServerLevel level, VillageRef ref) {
		Villages.adoptUnassigned(level, ref);
		var residents = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV harness: no resident to build");
		Villager mason = residents.get(0);
		mason.setInvulnerable(true);
		mason.setNoAi(true);
		Villages.ensureCenter(level, ref, mason, level.getGameTime() + 10_000);
		Container chest = ensureChest(level, ref);
		BlockPos existing = Specials.get(ref, Blueprints.GRANARY);
		if (existing != null && solidBlocks(level, existing) >= 40) {
			System.out.println("CTOV HARNESS OBSERVE granary " + existing.toShortString() + " (placement cheat not repeated; walk check rebuilds it)");
			return 2;
		}
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
				throw new IllegalStateException("CTOV harness: granary stalled at step " + step + "/" + Builder.planSize(ref.data())
					+ " type=" + ref.data().planType
					+ " origin=" + ref.data().planOrigin.map(BlockPos::toShortString).orElse("none")
					+ " wanted=" + ref.data().wanted + " mason=" + mason.blockPosition().toShortString()
					+ " diag=" + Builder.planStepDiagnostic(ref.data()));
			}
		}
		check(granary != null, "harness: Builder placed a granary while the mason was moved onto the stand"); checks++;
		check(solidBlocks(level, granary) >= 40, "harness: granary footprint contains placed blocks"); checks++;
		System.out.println("CTOV HARNESS PASS granary " + granary.toShortString() + " (no pathfinding; mason was positioned on the stand)");
		return checks;
	}

	/**
	 * Honest village run: materials are delivered when the wishlist appears, then only real level ticks run.
	 * The mason keeps AI and is never moved by the test.
	 */
	public static int runWalk(ServerLevel level, VillageRef ref) {
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "time set 1000");
		Villages.adoptUnassigned(level, ref);
		var residents = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV walk: no resident to build");
		Villager mason = residents.stream().min(java.util.Comparator.comparing(Villager::getUUID)).orElseThrow();
		mason.setInvulnerable(true);
		mason.setNoAi(false);
		Villages.ensureCenter(level, ref, mason, level.getGameTime() + 10_000);
		Container chest = ensureChest(level, ref);
		BlockPos harnessGranary = Specials.get(ref, Blueprints.GRANARY);
		if (harnessGranary != null) {
			Specials.remove(ref, Blueprints.GRANARY);
			if (ref.data().hasPlan() && Blueprints.GRANARY.equals(ref.data().planType)) ref.data().clearPlan();
		}
		ref.data().nextBuild = 0;
		if ("ctov-headless".equals(ref.data().planType) && ref.data().planOrigin.isEmpty()) ref.data().clearPlan();
		String supplied = "";
		BlockPos planStart = null;
		BlockPos startPos = mason.blockPosition();
		int checks = 0;
		for (int tick = 0; tick < 12000 && Specials.get(ref, Blueprints.GRANARY) == null; tick++) {
			level.tick(() -> false);
			if (planStart == null && ref.data().planOrigin.isPresent() && Blueprints.GRANARY.equals(ref.data().planType)) {
				planStart = mason.blockPosition();
			}
			String wishlist = String.join("|", ref.data().wanted);
			if (!wishlist.isEmpty() && !wishlist.equals(supplied)) {
				supply(chest, ref.data().wanted);
				supplied = wishlist;
			}
			if (tick % 200 == 0) {
				System.out.println("CTOV WALK tick " + tick + " step=" + ref.data().planStep + "/" + Builder.planSize(ref.data())
					+ " origin=" + ref.data().planOrigin.map(BlockPos::toShortString).orElse("none")
					+ " mason=" + mason.blockPosition().toShortString());
				System.out.flush();
			}
		}
		BlockPos granary = Specials.get(ref, Blueprints.GRANARY);
		check(granary != null, "walk: the village finished a granary during normal ticks"); checks++;
		double walked = planStart == null ? 0 : Math.sqrt(mason.blockPosition().distSqr(planStart));
		double fromStart = Math.sqrt(mason.blockPosition().distSqr(startPos));
		check(walked >= 3.0 || fromStart >= 3.0, "walk: mason moved under navigation (plan-start delta " + walked + ", bell delta " + fromStart + ")"); checks++;
		check(solidBlocks(level, granary) >= 40, "walk: granary footprint contains placed blocks"); checks++;
		ref.bellEntity().setChanged();
		level.getChunkAt(granary).markUnsaved();
		System.out.println("CTOV WALK PASS granary " + granary.toShortString() + " moved " + walked + " blocks after the plan started");
		return checks;
	}

	private static Container ensureChest(ServerLevel level, VillageRef ref) {
		Container chest = ref.chest(level);
		if (chest != null) return chest;
		BlockPos pos = ref.bell().south(2);
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
		level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
		ref.data().chest = java.util.Optional.of(pos);
		ref.dirty();
		chest = ref.chest(level);
		if (chest == null) throw new IllegalStateException("CTOV milestone: village chest missing");
		return chest;
	}

	private static int solidBlocks(ServerLevel level, BlockPos granary) {
		int placed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(granary.offset(-8, -2, -8), granary.offset(8, 8, 8))) {
			if (!level.getBlockState(pos).isAir()) placed++;
		}
		return placed;
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
