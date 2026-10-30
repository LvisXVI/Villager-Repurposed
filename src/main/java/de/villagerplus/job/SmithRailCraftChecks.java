package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.TradeNetwork;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Smith physically crafts rails for an approved metropolis link with low depot stock. */
public final class SmithRailCraftChecks {
	private SmithRailCraftChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos shed = at.east(24);
		BlockPos station = at.east(36);
		BlockPos bench = shed.east(3);
		BlockPos other = at.east(80);
		level.getChunkAt(bench);
		level.setBlock(bench, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
		level.setBlock(station.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(station, Blocks.RAIL.defaultBlockState(), 3);
		Specials.set(ref, Blueprints.TOOLSHED, shed);
		Specials.set(ref, Blueprints.STATION, station);
		Specials.set(ref, Blueprints.GRANARY, at.east(12));
		var box = ref.chest(level);
		box.clearContent();
		box.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
		box.setItem(1, new ItemStack(Items.STICK, 16));
		box.setItem(2, new ItemStack(Items.GOLD_INGOT, 16));
		box.setItem(3, new ItemStack(Items.REDSTONE, 32));
		box.setItem(4, new ItemStack(Items.RAIL, 2));
		String key = Network.dim(level) + other.asLong();
		Network.save(ref.bellEntity(), new TradeNetwork(List.of(),
			List.of(new TradeNetwork.Link(Network.dim(level), other, "Rail craft check", Roads.METROPOLIS, true, 12, 0)),
			Map.of(), 0, 0));
		ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of(key, 40L));
		var villager = EntityTypes.VILLAGER.spawn(level, bench.south(), EntitySpawnReason.COMMAND);
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		var state = new JobState();
		var memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
		long now = level.getGameTime() + 1000;
		int checks = 0;
		try {
			Ctx ctx = new Ctx(level, villager, state, memory, ref, now);
			check(box.getItem(4).getCount() == 2 && JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 0,
				"distant smith does not craft or deliver rails remotely");
			checks++;
			villager.teleportTo(at.getX() + 2.5, at.getY(), at.getZ() + .5);
			for (int step = 0; step < 12 && JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT)) < 6; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT)) == 6, "smith collects six iron ingots from communal storage");
			checks++;
			for (int step = 0; step < 4 && JobUtil.count(villager.getInventory(), s -> s.is(Items.STICK)) < 1; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + 20 + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.STICK)) == 1, "smith collects one stick for the rail recipe");
			checks++;
			villager.teleportTo(bench.getX() + .5, bench.getY(), bench.getZ() + 2.5);
			state = new JobState();
			ctx = new Ctx(level, villager, state, memory, ref, now + 40);
			SmithRailCraft.work(ctx);
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 16
				&& JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT) || s.is(Items.STICK)) == 0,
				"loaded rail recipe assembles sixteen rails at the workbench");
			checks++;
			int railsBeforeDelivery = JobUtil.count(box, s -> s.is(Items.RAIL));
			villager.teleportTo(at.getX() + 2.5, at.getY(), at.getZ() + .5);
			for (int step = 0; step < 6 && JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) > 0; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + 80 + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 0
				&& JobUtil.count(box, s -> s.is(Items.RAIL)) >= railsBeforeDelivery + 16,
				"finished rails return to village storage only after arrival");
			checks++;
			return checks;
		} finally {
			villager.discard();
			Specials.remove(ref, Blueprints.TOOLSHED);
			Specials.remove(ref, Blueprints.STATION);
			Specials.remove(ref, Blueprints.GRANARY);
			for (BlockPos pos : new BlockPos[] {bench, station, station.below()}) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new IllegalStateException("SMITH RAIL CRAFT: " + message);
		}
	}
}
