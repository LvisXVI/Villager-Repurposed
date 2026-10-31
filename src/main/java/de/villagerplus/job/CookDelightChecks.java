package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** J02: cook uses communal pantry + furnace; delight blocks are optional extras when installed. */
public final class CookDelightChecks {
	private CookDelightChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos granary = ref.bell().north(14);
		level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
		var potId = Identifier.parse("farmersdelight:cooking_pot");
		var boardId = Identifier.parse("farmersdelight:cutting_board");
		var potBlock = BuiltInRegistries.BLOCK.getOptional(potId).orElse(null);
		var boardBlock = BuiltInRegistries.BLOCK.getOptional(boardId).orElse(null);
		BlockPos kitchen = granary.east();
		level.setBlock(kitchen.below(), Blocks.STONE.defaultBlockState(), 3);
		if (potBlock != null) level.setBlock(kitchen, potBlock.defaultBlockState(), 3);
		else level.setBlock(kitchen, Blocks.FURNACE.defaultBlockState(), 3);
		if (boardBlock != null) level.setBlock(kitchen.north(), boardBlock.defaultBlockState(), 3);
		Specials.set(ref, Blueprints.GRANARY, granary);
		level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
		Container pantry = (Container) level.getBlockEntity(granary);
		if (pantry == null) throw new IllegalStateException("granary chest did not create a container");
		pantry.clearContent();
		pantry.setItem(0, new ItemStack(Items.WHEAT, 9));
		var cook = EntityTypes.VILLAGER.spawn(level, granary.south(2), EntitySpawnReason.COMMAND);
		cook.addTag("vp090_fixture"); cook.setNoAi(true); cook.setInvulnerable(true);
		int checks = 0;
		try {
			check(potBlock != null, "Farmer's Delight cooking pot is installed in the dev pack"); checks++;
			var mem = cook.getAttachedOrCreate(VillagerPlus.MEMORY);
			var st = JobManager.state(cook);
			Ctx ctx = new Ctx(level, cook, st, mem, ref, level.getGameTime() + 100);
			CookJob job = new CookJob();
			check(job.primary(ctx) || job.secondary(new Ctx(level, cook, st, mem, ref, level.getGameTime() + 200)),
				"cook job progresses using granary stores and a real Delight or furnace workstation"); checks++;
			check(JobUtil.count(pantry, s -> s.is(Items.WHEAT)) < 9 || JobUtil.count(pantry, s -> s.is(Items.BREAD)) > 0
				|| st.target != null,
				"cook consumes pantry wheat, crafts bread, or commits to a workstation target"); checks++;
			return checks;
		} finally {
			cook.discard();
			level.setBlock(granary, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(kitchen, Blocks.AIR.defaultBlockState(), 3);
			if (boardBlock != null) level.setBlock(kitchen.north(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(granary.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Cook delight: " + message);
		System.out.println("COOK DELIGHT PASS " + message);
	}
}
