package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Persisted ownership record for one block placed by a temporary caravan camp. */
public final class CaravanCampData {
 private CaravanCampData() { }

 public record CampBlock(BlockPos pos, BlockState expected) {
  public static final Codec<CampBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
   BlockPos.CODEC.optionalFieldOf("pos", BlockPos.ZERO).forGetter(CampBlock::pos),
   BlockState.CODEC.optionalFieldOf("expected", Blocks.AIR.defaultBlockState()).forGetter(CampBlock::expected)
  ).apply(instance, CampBlock::new));

  public CampBlock {
   pos = pos == null ? BlockPos.ZERO : pos.immutable();
   expected = expected == null ? Blocks.AIR.defaultBlockState() : expected;
  }
 }

 /** Empty list is the safe default for old/missing attachment data. */
 public static final Codec<List<CampBlock>> CODEC = CampBlock.CODEC.listOf().xmap(List::copyOf, List::copyOf);
 public static List<CampBlock> empty() { return List.of(); }
}
