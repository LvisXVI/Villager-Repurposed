package de.villagerplus.build;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exercises the real builder material-wait branch before the school milestone is available. */
public final class MilestoneWaitingChecks {
	private MilestoneWaitingChecks() { }

	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant,
		Optional<BlockPos> source, long progress, long nextBuild, List<String> wanted) {
		static Plan of(VillageData d) {
			return new Plan(d.planType, d.planOrigin, d.planDir, d.planStep, d.planVariant, d.planSource,
				d.planProgress, d.nextBuild, List.copyOf(d.wanted));
		}
		void restore(VillageData d) {
			d.planType = type; d.planOrigin = origin; d.planDir = dir; d.planStep = step; d.planVariant = variant;
			d.planSource = source; d.planProgress = progress; d.nextBuild = nextBuild;
			d.wanted.clear(); d.wanted.addAll(wanted);
		}
	}

	public static int run(ServerLevel level, VillageRef ref) {
		Container box = ref.chest(level);
		if (box == null) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: village chest missing");
		VillageData d = ref.data();
		Plan oldPlan = Plan.of(d);
		var oldSchool = d.extra.school;
		var oldLager = d.extra.lager;
		var oldLagerMore = new ArrayList<>(d.extra.lagerMore);
		var oldLagerChests = new ArrayList<>(d.extra.lagerChests);
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		var oldDemolition = ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var oldConstruction = ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		List<ItemStack> stock = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) stock.add(box.getItem(slot).copy());
		List<Villager> workers = new ArrayList<>();
		BlockPos origin = ref.bell().offset(-71, 0, 11);
		level.getChunkAt(origin);
		long now = Math.max(level.getGameTime(), 1_000_000L);
		int checks = 0;
		try {
			d.extra.school = Optional.empty(); d.extra.lager = Optional.empty();
			d.extra.lagerMore.clear(); d.extra.lagerChests.clear();
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, java.util.Map.of());
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);
			ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			box.clearContent();
			for (int i = 0; i < Config.SCHOOL_POP; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, ref.bell().offset(4 + i, 0, 7), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: resident fixture did not spawn");
				workers.add(villager); villager.addTag("vp090_fixture"); villager.setNoAi(true); villager.setInvulnerable(true); villager.setAge(0);
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), ref.bell().north(12)));
			}
			Villager worker = workers.getFirst();
			Ctx c = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
			check(Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> true).size() >= Config.SCHOOL_POP,
				"world fixture reaches the population that used to trigger premature school preemption"); checks++;

			waitingPlan(d, Blueprints.GRANARY, origin, now);
			Builder.tick(c, true);
			check(Blueprints.GRANARY.equals(d.planType) && d.planOrigin.equals(Optional.of(origin)) && d.planStep == 0
				&& actionable(d) && box.isEmpty(),
				"missing granary materials preserve the active prerequisite plan and actionable supplier wishlist"); checks++;
			Builder.cancel(ref, now);

			Specials.set(ref, Blueprints.GRANARY, ref.bell().north(20));
			waitingPlan(d, Blueprints.TOOLSHED, origin, now + 200);
			c.st().materialWaitUntil = 0;
			Ctx toolshed = new Ctx(level, worker, c.st(), c.mem(), ref, now + 200);
			Builder.tick(toolshed, true);
			check(Blueprints.TOOLSHED.equals(d.planType) && d.planOrigin.equals(Optional.of(origin)) && d.planStep == 0
				&& actionable(d) && box.isEmpty(),
				"missing toolshed materials also retain the prerequisite plan instead of repeatedly restarting it"); checks++;
			Builder.cancel(ref, now + 200);
			Specials.set(ref, Blueprints.TOOLSHED, ref.bell().north(28));
			check(Blueprints.SCHOOL.equals(Builder.nextSpecial(ref, Config.SCHOOL_POP)),
				"school becomes the next milestone after the granary and toolshed registrations are complete"); checks++;
			long previousNextBuild = d.nextBuild;
			d.nextBuild = now + Config.BUILD_COOLDOWN;
			check(!Builder.cooldownBlocksBuild(ref, Config.SCHOOL_POP, 0, now),
				"mandatory school milestone bypasses the normal post-building cooldown"); checks++;
			d.nextBuild = previousNextBuild;

			for (int slot = 0; slot < box.getContainerSize(); slot++) box.setItem(slot, new ItemStack(Items.BEDROCK, 64));
			waitingPlan(d, Blueprints.GRANARY, origin, now + 400);
			c.st().materialWaitUntil = 0;
			Ctx full = new Ctx(level, worker, c.st(), c.mem(), ref, now + 400);
			Builder.tick(full, true);
			check(!d.hasPlan() && d.wanted.isEmpty() && d.nextBuild == full.now(),
				"a full communal chest still preempts the blocked plan so storage capacity can be built"); checks++;
			return checks;
		} finally {
			if (d.hasPlan() && d.planOrigin.equals(Optional.of(origin))) Builder.cancel(ref, now + 600);
			workers.forEach(v -> { v.discard(); Villages.forget(v.getUUID()); });
			box.clearContent();
			for (int slot = 0; slot < stock.size(); slot++) box.setItem(slot, stock.get(slot));
			oldPlan.restore(d); d.extra.school = oldSchool; d.extra.lager = oldLager;
			d.extra.lagerMore.clear(); d.extra.lagerMore.addAll(oldLagerMore);
			d.extra.lagerChests.clear(); d.extra.lagerChests.addAll(oldLagerChests);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS); else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldDemolition == null) ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION); else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION, oldDemolition);
			if (oldConstruction == null) ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION); else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, oldConstruction);
			ref.dirty();
		}
	}

	private static void waitingPlan(VillageData d, String type, BlockPos origin, long now) {
		d.planType = type; d.planOrigin = Optional.of(origin); d.planDir = 0; d.planStep = 0; d.planVariant = 64 << 2;
		d.planSource = Optional.empty(); d.planProgress = now; d.wanted.clear();
	}
	private static boolean actionable(VillageData d) {
		boolean needed = false;
		if (d.wanted.isEmpty()) return false;
		for (String entry : d.wanted) {
			String[] parts = entry.split("#", 2);
			if (parts.length != 2) return false;
			try {
				var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0])).orElse(Items.AIR);
				if (item == Items.AIR || Integer.parseInt(parts[1]) <= 0) return false;
				needed |= Builder.planNeeds(d, new ItemStack(item));
			} catch (RuntimeException malformed) { return false; }
		}
		return needed;
	}
	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: " + message);
		System.out.println("MILESTONE WAIT PASS " + message);
	}
}
