package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.PorterJob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** K04: porter walks from the donkey to the granary and commits food without test teleports. */
public final class CaravanPorterDeliveryChecks {
	private CaravanPorterDeliveryChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
		var ref = Checks090.fixtureForEquipment(level, at);
		Container materialChest = ref.chest(level);
		BlockPos granary = ref.bell().north(20);
		BlockPos donkeyPad = ref.bell().east(14);
		BlockPos porterStart = ref.bell().east(10);
		carvePath(level, donkeyPad, porterStart);
		carvePath(level, porterStart, granary);
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			BlockPos p = granary.offset(dx, 0, dz);
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(p, dx == 0 && dz == 0 ? Blocks.CHEST.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		Specials.set(ref, Blueprints.GRANARY, granary);
		var porter = EntityTypes.VILLAGER.spawn(level, porterStart, EntitySpawnReason.COMMAND);
		porter.addTag("vp090_fixture");
		porter.setInvulnerable(true);
		porter.setNoAi(false);
		Villages.bindResident(level, ref, porter, ref.bell().south(6));
		var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
		donkey.setPos(donkeyPad.getX() + 0.5, donkeyPad.getY(), donkeyPad.getZ() + 0.5);
		donkey.setNoAi(true);
		donkey.addTag("vp090_fixture");
		donkey.addTag("vp_caravan_bell:" + ref.bell().asLong());
		int checks = 0;
		try {
			materialChest.clearContent();
			check(level.addFreshEntity(donkey), "donkey fixture loads on a cleared path"); checks++;
			donkey.setTamed(true);
			var chestLayout = donkey.getSlot(499);
			if (chestLayout != null) chestLayout.set(new ItemStack(Items.CHEST));
			var cargo = donkey.getSlot(502);
			check(cargo != null && cargo.set(new ItemStack(Items.WHEAT, 16)), "donkey cargo slot holds sixteen wheat"); checks++;
			var mem = porter.getAttachedOrCreate(VillagerPlus.MEMORY);
			var st = JobManager.state(porter);
			st.idleUntil[0] = 0;
			long now = level.getGameTime() + 30_000;
			PorterJob job = new PorterJob();
			boolean claimed = false;
			for (int tick = 0; tick < 400 && !claimed; tick++) {
				porter.tick();
				claimed = Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + tick)) || Caravan.carryingFor(porter);
			}
			check(claimed, "porter claims caravan wheat from the donkey without operator teleport"); checks++;
			for (int tick = 0; tick < 2400 && Caravan.carryingFor(porter); tick++) {
				porter.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 400 + tick));
				job.primary(new Ctx(level, porter, st, mem, ref, now + 400 + tick));
			}
			check(!Caravan.carryingFor(porter), "porter finishes delivery by walking to the granary stand"); checks++;
			Container granaryBox = (Container) level.getBlockEntity(granary);
			check(granaryBox != null && JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 16
				&& JobUtil.count(materialChest, s -> s.is(Items.WHEAT)) == 0,
				"sixteen wheat land in the granary, not the material crate"); checks++;
			check(countDonkeyWheat(donkey) == 0, "donkey cargo empties after successful delivery"); checks++;
			return checks;
		} finally {
			porter.discard();
			donkey.discard();
			Villages.forget(porter.getUUID());
		}
	}

	private static int countDonkeyWheat(net.minecraft.world.entity.animal.equine.Donkey donkey) {
		int total = 0;
		for (int slot = 0; slot < donkey.getInventorySize(); slot++) {
			var access = donkey.getSlot(slot);
			if (access != null && access.get().is(Items.WHEAT)) total += access.get().getCount();
		}
		return total;
	}

	private static void carvePath(ServerLevel level, BlockPos from, BlockPos to) {
		int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
		for (int i = 0; i <= steps; i++) {
			int x = from.getX() + (steps == 0 ? 0 : (to.getX() - from.getX()) * i / steps);
			int z = from.getZ() + (steps == 0 ? 0 : (to.getZ() - from.getZ()) * i / steps);
			BlockPos floor = new BlockPos(x, from.getY() - 1, z);
			level.getChunkAt(floor);
			level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Caravan porter delivery: " + message);
		System.out.println("CARAVAN DELIVERY PASS " + message);
	}
}
