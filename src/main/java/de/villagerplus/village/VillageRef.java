package de.villagerplus.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Handle auf ein Dorf: die Glocke (Dorfzentrum) samt ihrer gespeicherten Daten. */
public record VillageRef(BlockPos bell, VillageData data, BlockEntity bellEntity) {
	public void dirty() {
		bellEntity.setChanged();
	}

	/** Position der Dorfmitten-Doppelkiste, falls sie existiert. */
	public BlockPos chestPos(ServerLevel level) {
		if (data.chest.isPresent() && level.getBlockState(data.chest.get()).getBlock() instanceof ChestBlock) {
			return data.chest.get();
		}
		return null;
	}

	public Container chest(ServerLevel level) {
		BlockPos pos = chestPos(level);
		return pos == null ? null : HopperBlockEntity.getContainerAt(level, pos);
	}
}
