package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.FarmerJob;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Workstation;
import de.villagerplus.village.VillageRef;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** B01, B04, B05 and J01 against the same generated village, without moving the worker by hand. */
public final class CtovLifeChecks {
	private CtovLifeChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		var residents = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(32), Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV life: no resident");
		Villager worker = residents.get(0);
		worker.setInvulnerable(true);
		worker.setNoAi(false);
		int checks = 0;
		checks += workstation(level, ref, worker);
		checks += sign(level, ref, worker);
		checks += privateChest(level, ref, worker);
		checks += field(level, ref, worker);
		return checks;
	}

	private static int workstation(ServerLevel level, VillageRef ref, Villager worker) {
		Container chest = ref.chest(level);
		if (chest == null) throw new IllegalStateException("CTOV life: chest missing");
		if (Workstation.find(level, ref.bell(), Blocks.FURNACE, 8) == null) {
			add(chest, new ItemStack(Items.COBBLESTONE, 8));
			var state = JobManager.state(worker);
			for (int tick = 0; tick < 2000 && Workstation.find(level, ref.bell(), Blocks.FURNACE, 8) == null; tick++) {
				Workstation.ensure(new Ctx(level, worker, state, worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + tick), Blocks.FURNACE);
				level.tick(() -> false);
			}
		}
		check(Workstation.find(level, ref.bell(), Blocks.FURNACE, 8) != null,
			"B01 smith crafts and places a furnace beside the village chest (table="
				+ Workstation.find(level, ref.bell(), Blocks.CRAFTING_TABLE, 8) + " chest=" + ref.chestPos(level) + ")");
		System.out.println("CTOV LIFE PASS furnace");
		return 1;
	}

	private static int sign(ServerLevel level, VillageRef ref, Villager worker) {
		var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
		if (memory.name == null || memory.name.isBlank()) memory.name = "CTOV-Bewohner";
		BlockPos sign = ref.bell().north(2);
		level.setBlock(sign.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(sign, de.villagerplus.block.ModBlocks.DORFSCHILD.defaultBlockState(), 3);
		memory.homeDoor = java.util.Optional.of(sign.south());
		String text = String.join("\n", de.villagerplus.block.DorfschildBlock.describe(level, sign));
		check(text.contains("Hier wohnt:") && text.contains(memory.name), "B04 house sign names the resident (" + text.replace('\n', ' ') + ")");
		System.out.println("CTOV LIFE PASS sign");
		return 1;
	}

	private static int privateChest(ServerLevel level, VillageRef ref, Villager worker) {
		BlockPos owned = worker.blockPosition().above();
		level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
		var entity = level.getBlockEntity(owned);
		entity.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
		Container box = (Container) entity;
		box.setItem(0, new ItemStack(Items.WHEAT, 5));
		var ctx = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
		BlockPos communal = JobUtil.communalDeliveryChest(ctx);
		check(communal != null && !communal.equals(owned), "B05 porter delivery target is the communal chest, not the private one");
		check(JobUtil.count(box, stack -> stack.is(Items.WHEAT)) == 5, "B05 private wheat stays in the owned chest");
		System.out.println("CTOV LIFE PASS private chest");
		return 2;
	}

	private static int field(ServerLevel level, VillageRef ref, Villager worker) {
		Container chest = ref.chest(level);
		add(chest, new ItemStack(Items.WHEAT_SEEDS, 16));
		add(chest, new ItemStack(Items.OAK_FENCE, 16));
		add(chest, new ItemStack(Items.OAK_FENCE_GATE, 2));
		add(chest, new ItemStack(Items.WATER_BUCKET, 1));
		ref.data().extra.nextFarm = 0;
		ref.data().clearPlan();
		int before = ref.data().farms;
		var state = JobManager.state(worker);
		state.materialWaitUntil = 0;
		var job = new FarmerJob();
		boolean grew = false;
		for (int tick = 0; tick < 2500 && ref.data().farms <= before; tick++) {
			job.secondary(new Ctx(level, worker, state, worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + tick));
			level.tick(() -> false);
			if (ref.data().farms > before) grew = true;
		}
		check(grew, "J01 farmer expands a field in the CTOV village when food is short (farms " + before + " -> " + ref.data().farms + ")");
		System.out.println("CTOV LIFE PASS field " + ref.data().farms);
		return 1;
	}

	private static void add(Container chest, ItemStack stack) {
		for (int slot = 0; slot < chest.getContainerSize() && !stack.isEmpty(); slot++) {
			ItemStack current = chest.getItem(slot);
			if (current.isEmpty()) {
				chest.setItem(slot, stack.copy());
				return;
			}
			if (ItemStack.isSameItemSameComponents(current, stack) && current.getCount() < current.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
				current.grow(move);
				stack.shrink(move);
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV life: " + message);
	}
}
