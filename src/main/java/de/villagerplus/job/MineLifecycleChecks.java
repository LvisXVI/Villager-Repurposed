package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageMap;
import de.villagerplus.village.VillageRef;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated checks for completed-mine archival, map persistence, and retained shaft blocks. */
public final class MineLifecycleChecks {
	private MineLifecycleChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos at) throws ReflectiveOperationException {
		Map<BlockPos, Cell> before = new HashMap<>();
		Villager worker = null;
		int checks = 0;
		BlockPos bellPos = at.immutable();
		BlockPos entrance = at.offset(8, 0, 8);
		int minX = entrance.getX() - 8, maxX = entrance.getX() + 14;
		int minY = entrance.getY() - 8, maxY = entrance.getY() + 5;
		int minZ = entrance.getZ() - 8, maxZ = entrance.getZ() + 8;
		try {
			for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
				level.getChunkAt(new BlockPos(x, entrance.getY(), z));
				for (int y = minY; y <= maxY; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockEntity be = level.getBlockEntity(p);
					before.put(p, new Cell(level.getBlockState(p), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
				}
			}

			level.setBlock(bellPos, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.NORTH), 2);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("Mine lifecycle fixture bell was not created");
			}
			VillageData data = bell.getAttachedOrCreate(VillagerPlus.VILLAGE);
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 3, entrance.getY() - 5, false);
			data.mine = Optional.of(mine);
			VillageRef ref = new VillageRef(bellPos, data, bell);

			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Mine lifecycle fixture villager was not created");
			worker.setPos(entrance.getX() - 5.5, entrance.getY(), entrance.getZ() + 0.5);
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			level.addFreshEntity(worker);

			// Preserve a distinctive block inside the passage; closing the entrance must not erase it.
			BlockPos shaft = entrance.relative(Direction.EAST).below();
			level.setBlock(shaft, Blocks.COBBLESTONE.defaultBlockState(), 2);
			Ctx context = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			invokeFinish(context, ref, mine, "Die Mine ist ausgebeutet.");
			check(mine.done && data.mine.isEmpty() && data.extra.oldMines.size() == 1
				&& data.extra.oldMines.getFirst() == mine, "completed mine is archived once and removed from active slot"); checks++;
			check(level.getBlockState(entrance).is(Blocks.SPRUCE_PLANKS)
				&& level.getBlockState(entrance.above()).is(Blocks.SPRUCE_PLANKS)
				&& level.getBlockState(shaft).is(Blocks.COBBLESTONE), "entrance is barricaded while shaft contents remain"); checks++;
			check(level.getBlockEntity(entrance.above().relative(Direction.WEST)) instanceof SignBlockEntity sign
				&& signText(sign).contains("[VERSCHLOSSEN - ERSCHÖPFT]"), "closure sign visibly marks exhausted mine"); checks++;

			invokeFinish(context, ref, mine, "Die Mine ist ausgebeutet.");
			check(data.mine.isEmpty() && data.extra.oldMines.size() == 1, "repeated completion does not duplicate the archived entrance"); checks++;

			CompoundTag savedBell = bell.saveWithFullMetadata(level.registryAccess());
			BlockEntity restoredEntity = BlockEntity.loadStatic(bellPos, level.getBlockState(bellPos), savedBell.copy(), level.registryAccess());
			if (!(restoredEntity instanceof BellBlockEntity restoredBell)) {
				throw new IllegalStateException("Mine lifecycle bell attachment did not survive serialization");
			}
			VillageData restoredData = restoredBell.getAttachedOrElse(VillagerPlus.VILLAGE, new VillageData());
			VillageRef restoredRef = new VillageRef(bellPos, restoredData, restoredBell);
			byte[][] marks = VillageMap.grid(level, restoredRef);
			int[] origin = VillageMap.gridOrigin(level, restoredRef);
			int cellSize = VillageMap.gridCellSize(level, restoredRef);
			int col = (entrance.getX() - origin[0]) / cellSize;
			int row = (entrance.getZ() - origin[1]) / cellSize;
			check(restoredData.mine.isEmpty() && restoredData.extra.oldMines.size() == 1
				&& col >= 0 && col < VillageMap.GRID && row >= 0 && row < VillageMap.GRID
				&& marks[1][row * VillageMap.GRID + col] == 8,
				"persisted map data retains old-mine marker after bell attachment round-trip"); checks++;
			String rendered = VillageMap.render(level, restoredRef).stream().map(component -> component.getString()).reduce("", String::concat);
			check(rendered.contains("▽"), "rendered village map displays old-mine glyph"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
				BlockPos pos = entry.getKey();
				Cell cell = entry.getValue();
				level.setBlock(pos, cell.state(), 2);
				if (cell.blockEntity() == null) {
					if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
				} else {
					BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
					if (restored != null) level.setBlockEntity(restored);
				}
			}
		}
	}

	private static void invokeFinish(Ctx context, VillageRef ref, VillageData.Mine mine, String message) throws ReflectiveOperationException {
		Method finish = Mining.class.getDeclaredMethod("finishMine", Ctx.class, VillageRef.class, VillageData.Mine.class, String.class);
		finish.setAccessible(true);
		finish.invoke(null, context, ref, mine, message);
	}

	private static String signText(SignBlockEntity sign) {
		StringBuilder text = new StringBuilder();
		for (int line = 0; line < 4; line++) text.append(sign.getFrontText().getMessage(line, false).getString()).append(' ');
		return text.toString();
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("MINE LIFECYCLE CHECK FAILED: " + message);
	}
}
