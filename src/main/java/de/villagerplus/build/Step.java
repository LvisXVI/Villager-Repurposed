package de.villagerplus.build;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ein einzelner Bauschritt.
 *
 * @param item     exakter Gegenstand, der bezahlt wird (null = nichts)
 * @param tag      alternativ: beliebiger Gegenstand dieses Tags (z.B. Betten, Tueren)
 * @param cost     Anzahl
 * @param optional wenn nicht bezahlbar, wird der Schritt uebersprungen statt den Bau zu stoppen
 * @param linkedTo uebernimmt den Block, der dort bereits steht (z.B. Tuer-Oberteil, Bettkopf)
 * @param make     baut aus dem bezahlten Block den Blockzustand
 */
public record Step(BlockPos pos, Item item, TagKey<Item> tag, int cost, boolean optional, BlockPos linkedTo,
	Function<Block, BlockState> make) {

	public boolean free() {
		return item == null && tag == null;
	}

	public static Step free(BlockPos pos, BlockState state) {
		return new Step(pos, null, null, 0, false, null, b -> state);
	}

	/** Bezahlt mit dem Gegenstand des Blocks selbst (Palette-Material). */
	public static Step block(BlockPos pos, Block block) {
		return new Step(pos, block.asItem(), null, 1, false, null, b -> block.defaultBlockState());
	}

	public static Step block(BlockPos pos, Block block, Function<Block, BlockState> make) {
		return new Step(pos, block.asItem(), null, 1, false, null, make);
	}

	public static Step linked(BlockPos pos, BlockPos linkedTo, Function<Block, BlockState> make) {
		return new Step(pos, null, null, 0, false, linkedTo, make);
	}
}
