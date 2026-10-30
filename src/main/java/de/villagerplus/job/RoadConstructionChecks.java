package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Synthetic arrival/transaction checks for paid three-lane road construction. */
public final class RoadConstructionChecks {
 private RoadConstructionChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);var box=ref.chest(level);box.clearContent();
  BlockPos other=at.east(80);String key=Network.dim(level)+other.asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Construction check",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,10L,key+":start",10L));
  for(int x=10;x<=11;x++)for(int side=-1;side<=1;side++) {
   BlockPos p=at.offset(x,-1,side);level.getChunkAt(p);level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  level.setBlock(at.offset(11,-1,1),Blocks.FARMLAND.defaultBlockState(),3);
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   check(Roads.nextRepair(level,ref)==null,"unbuilt village approach before recorded road start is excluded from repair");checks++;
   Roads.tick(level,ref,now);
   check(level.getBlockState(at.offset(10,-1,0)).is(Blocks.GRASS_BLOCK)&&cursor(ref,key)==10,"village tick neither remotely paves nor advances road");checks++;
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.construct(c)&&cursor(ref,key)==10,"missing materials preserve cursor and terrain");checks++;
   box.setItem(0,new ItemStack(Items.COBBLESTONE,2));box.setItem(1,new ItemStack(Items.GRAVEL));
   for(int lane=0;lane<3;lane++) {
    st.roadBuildScanAt=0;
    var depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
    check(RoadBuilderJob.construct(c)&&!v.getInventory().isEmpty(),"lane material must be physically collected");checks++;
    BlockPos target=st.roadRepair.ground();RoadBuilderJob.construct(c);
    check(level.getBlockState(target).is(Blocks.GRASS_BLOCK)&&cursor(ref,key)==10,"partial lane remains unpaid/unbuilt while walking");checks++;
    v.teleportTo(target.getX()+.5,target.getY()+1,target.getZ()+1.5);
    check(RoadBuilderJob.construct(c)&&v.getInventory().isEmpty(),"arrival consumes exactly one carried lane block");checks++;
    // Recreate volatile work state to simulate interruption between individual lane blocks.
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
   }
   check(box.isEmpty()&&level.getBlockState(at.offset(10,-1,-1)).is(Blocks.COBBLESTONE)
    &&level.getBlockState(at.offset(10,-1,0)).is(Blocks.GRAVEL)&&level.getBlockState(at.offset(10,-1,1)).is(Blocks.COBBLESTONE),"three-lane section consumes two cobblestone plus one gravel");checks++;
   check(Roads.nextConstruction(level,ref)==null&&cursor(ref,key)==11,"completed section advances once; blocked next section does not advance");checks++;
   check(level.getBlockState(at.offset(11,-1,-1)).is(Blocks.GRASS_BLOCK),"whole cross-section is validated before any lane task is issued");checks++;
   return checks;
  } finally {v.discard();}
 }
 private static long cursor(VillageRef ref,String key) {return ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key,-1L);}
 private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Road construction: "+label);}
}
