package de.villagerplus.build;

import de.villagerplus.village.VillageData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

/** Focused checks for persistent construction material requests. */
public final class BuilderChecks {
	private BuilderChecks() {}

	public static int run() {
		List<Holder<Item>> doors = java.util.stream.StreamSupport.stream(
			BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.WOODEN_DOORS).spliterator(), false).toList();
		if (doors.isEmpty()) throw new IllegalStateException("Loaded item tag minecraft:doors is empty");
		Item firstDoor = doors.getFirst().value();
		VillageData data = new VillageData();
		Step doorStep = new Step(BlockPos.ZERO, null, ItemTags.WOODEN_DOORS, 1, false, null, b -> Blocks.OAK_DOOR.defaultBlockState());
		Builder.addWanted(data, doorStep, new SimpleContainer(9));
		String expected = BuiltInRegistries.ITEM.getKey(firstDoor) + "#16";
		if (!data.wanted.equals(List.of(expected))) throw new IllegalStateException("Tagged door requirement was not requested: " + data.wanted);
		return 1;
	}
}
