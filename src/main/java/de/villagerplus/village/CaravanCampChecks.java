package de.villagerplus.village;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;

/** Persistence codec checks for crash-safe caravan camp cleanup records. */
public final class CaravanCampChecks {
 private CaravanCampChecks() { }

 public static int run() {
  int checks=0;
  check(CaravanCampData.empty().isEmpty(),"missing camp attachment has an empty safe default");checks++;
  var empty=CaravanCampData.CODEC.parse(JsonOps.INSTANCE,new com.google.gson.JsonArray()).getOrThrow();
  check(empty.isEmpty(),"empty persisted list decodes cleanly");checks++;
  var legacy=CaravanCampData.CampBlock.CODEC.parse(JsonOps.INSTANCE,new JsonObject()).getOrThrow();
  check(legacy.pos().equals(BlockPos.ZERO)&&legacy.expected().is(Blocks.AIR),"older partial records decode with safe position/state defaults");checks++;
  var encoded=CaravanCampData.CampBlock.CODEC.encodeStart(JsonOps.INSTANCE,
   new CaravanCampData.CampBlock(new BlockPos(12,70,-4),Blocks.SPRUCE_FENCE.defaultBlockState()));
  check(encoded.result().isPresent(),"camp position and expected block state encode to persistent data");checks++;
  return checks;
 }

 /** World-backed idempotence and ownership checks; caller supplies a loaded isolated fixture area. */
 public static int runWorld(ServerLevel level,BlockPos at) {
  int checks=0;
  BlockPos exact=at.offset(2,0,0), modified=at.offset(4,0,0), fire=at.offset(6,0,0), barrel=at.offset(8,0,0);
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  for(BlockPos pos:List.of(exact,modified,fire,barrel)) {
   level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
   before.put(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  BlockState fence=Blocks.SPRUCE_FENCE.defaultBlockState();
  try {
   level.setBlock(exact,fence,3);
   var removed=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(exact,fence)));
   check(removed.isEmpty()&&level.getBlockState(exact).isAir(),"matching camp block is removed");checks++;
   check(Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(exact,fence))).isEmpty(),"repeated cleanup is idempotent");checks++;

   level.setBlock(modified,Blocks.STONE.defaultBlockState(),3);
   var modifiedLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(modified,fence)));
   check(modifiedLeft.isEmpty()&&level.getBlockState(modified).is(Blocks.STONE),"a player-modified block is retained and relinquished");checks++;

   BlockState expectedFire=Blocks.CAMPFIRE.defaultBlockState();
   level.setBlock(fire,expectedFire.setValue(CampfireBlock.LIT,false),3);
   check(Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(fire,expectedFire))).isEmpty()
    &&level.getBlockState(fire).isAir(),"rain-extinguished campfire is still recognized as camp-owned");checks++;

   level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);
   if(level.getBlockEntity(barrel) instanceof BarrelBlockEntity container) container.setItem(0,new ItemStack(Items.EMERALD));
   var barrelLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(barrel,Blocks.BARREL.defaultBlockState())));
   check(barrelLeft.isEmpty()&&level.getBlockState(barrel).is(Blocks.BARREL)
    &&level.getBlockEntity(barrel) instanceof BarrelBlockEntity container&&!container.getItem(0).isEmpty(),"nonempty camp barrel and its real inventory are preserved");checks++;
  } finally {
   for(BlockPos pos:before.keySet()) level.removeBlockEntity(pos);
   for(BlockPos pos:before.keySet()) level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   before.forEach((pos,cell)->level.setBlock(pos,cell.state(),3));
   before.forEach((pos,cell)->{
    if(cell.data()!=null) {
     BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),cell.data().copy(),level.registryAccess());
     if(be!=null) level.setBlockEntity(be);
    }
   });
  }

  BlockPos far=new BlockPos(1100000,280,1100000);
  var pending=Caravan.clearLoadedCampBlocks(level,java.util.List.of(new CaravanCampData.CampBlock(far,fence)));
  check(!level.hasChunkAt(far)&&pending.size()==1&&pending.getFirst().pos().equals(far),"unloaded camp positions stay queued without chunk loading");checks++;
  return checks;
 }

 private static void check(boolean value,String message) {
  if(!value) throw new IllegalStateException("Caravan camp persistence: "+message);
 }
 private record Cell(BlockState state,CompoundTag data) { }
}
