package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Explicit world checks for SmithJob equipment upkeep. */
public final class EquipmentMaintenanceChecks {
	private static int passed;
	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("EQUIPMENT CHECK FAILED: " + label);
		passed++;
		System.out.println("VP090 PASS " + label);
	}

	private EquipmentMaintenanceChecks() {
	}

	public static int run(ServerLevel level, BlockPos at) {
		passed = 0;
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		BlockPos shed = at.offset(6, 0, 6);
		BlockPos barrel = shed.east(2);
		BlockPos anvil = shed.east(4);
		level.getChunk(barrel);
		level.setBlock(barrel.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
		level.setBlock(anvil.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3);
		Specials.set(ref, de.villagerplus.build.Blueprints.TOOLSHED, shed);
		BarrelBlockEntity depot = (BarrelBlockEntity) level.getBlockEntity(barrel);
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		pickaxe.setDamageValue(600);
		depot.setItem(0, pickaxe);
		depot.setItem(1, new ItemStack(Items.DIAMOND, 2));

		Villager smith = EntityTypes.VILLAGER.spawn(level, anvil.east(1), EntitySpawnReason.COMMAND);
		smith.addTag("vp090_fixture");
		smith.setNoAi(true);
		var memory = smith.getAttachedOrCreate(VillagerPlus.MEMORY);
		JobState state = JobManager.state(smith);
		int before = pickaxe.getDamageValue();
		int count = depot.getItem(1).getCount();
		boolean repaired = new SmithJob().secondary(new Ctx(level, smith, state, memory, ref, level.getGameTime()));
		check(repaired && pickaxe.getDamageValue() == before - pickaxe.getMaxDamage() / 4,
			"smith repairs one quarter durability at the toolshed anvil");
		check(count == depot.getItem(1).getCount() + 1,
			"toolshed repair consumes exactly one compatible repair material");

		level.setBlock(anvil, Blocks.AIR.defaultBlockState(), 3);
		pickaxe.setDamageValue(900);
		depot.setItem(1, new ItemStack(Items.DIAMOND, 1));
		int unchangedDamage = pickaxe.getDamageValue();
		int unchangedDiamonds = depot.getItem(1).getCount();
		state.toolRepairCheckAt = 0;
		boolean noAnvil = EquipmentMaintenance.repairAtToolshed(new Ctx(level, smith, state, memory, ref, level.getGameTime() + 201));
		check(!noAnvil && pickaxe.getDamageValue() == unchangedDamage && depot.getItem(1).getCount() == unchangedDiamonds,
			"missing toolshed anvil leaves both equipment and materials untouched");

		smith.discard();
		Specials.remove(ref, de.villagerplus.build.Blueprints.TOOLSHED);
		for (BlockPos pos : new BlockPos[] {barrel, barrel.below(), anvil, anvil.below(), ref.bell(), ref.bell().east(3), ref.bell().below(), ref.bell().east(3).below()}) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		return passed;
	}
}
