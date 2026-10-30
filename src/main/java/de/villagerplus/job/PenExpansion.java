package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Sichtbarer Stallanbau, sobald eine Tierart das Gehege-Cap von 8 überschreitet. */
public final class PenExpansion {
	private PenExpansion() { }

	public static boolean overCapacity(ServerLevel level, BlockPos pen) {
		AABB area = new AABB(pen).inflate(5, 3, 5);
		for (var type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN)) {
			int count = level.getEntitiesOfClass(Animal.class, area, animal -> animal.isAlive() && animal.getType() == type).size();
			if (count > Config.CULL_CAP) return true;
		}
		return false;
	}

	public static boolean work(Ctx c) {
		if (c.village() == null || c.village().data().extra.pen.isEmpty()) return false;
		BlockPos pen = c.village().data().extra.pen.get();
		if (!overCapacity(c.level(), pen)) return false;
		Container chest = communal(c);
		if (chest == null || JobUtil.count(chest, stack -> stack.is(Items.OAK_FENCE)) < 3) return false;
		List<BlockPos> cells = plan(c.level(), pen);
		if (cells.isEmpty()) return false;
		c.setActivity("erweitert den Stall");
		Anim.hold(c, Items.OAK_FENCE);
		if (!JobUtil.approach(c, cells.get(0), 4.0)) return true;
		int placed = 0;
		for (BlockPos cell : cells) {
			if (!c.level().getBlockState(cell).isAir()) continue;
			if (JobUtil.take(chest, stack -> stack.is(Items.OAK_FENCE), 1) != 1) break;
			c.level().setBlock(cell, Blocks.OAK_FENCE.defaultBlockState(), 3);
			placed++;
		}
		if (placed == 0) return false;
		c.level().playSound(null, cells.get(0), net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 40;
		return true;
	}

	static List<BlockPos> plan(ServerLevel level, BlockPos pen) {
		List<BlockPos> cells = new ArrayList<>();
		BlockPos start = pen.east(6);
		for (int i = 0; i < 3; i++) {
			BlockPos cell = start.south(i);
			BlockPos floor = cell.below();
			if (!level.hasChunkAt(cell) || !level.getBlockState(cell).isAir()
				|| !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return List.of();
			cells.add(cell);
		}
		return cells;
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity != null && entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}
}
