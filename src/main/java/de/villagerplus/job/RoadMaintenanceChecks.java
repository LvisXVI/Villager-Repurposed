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

/** Real inventory/arrival checks in a dedicated remote development fixture. */
public final class RoadMaintenanceChecks {
 private RoadMaintenanceChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);
  int groundY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
  at=new BlockPos(at.getX(),groundY+1,at.getZ());
  Checks090.fixtureForEquipment(level, at.east(80));
  var ref=Checks090.fixtureForEquipment(level,at);
  var box=ref.chest(level);box.clearContent();
  groundY=ref.bell().getY()-1;
  BlockPos other=at.east(80);
  var peerRef=Checks090.fixtureForEquipment(level,other);
  int damagedY=groundY;
  BlockPos damaged=new BlockPos(at.getX()+20,damagedY,at.getZ());
  String key=Network.dim(level)+other.asLong();
  String peerKey=Network.dim(level)+ref.bell().asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Road check",30,true,8,0)),Map.of(),0,0));
  Network.save(peerRef.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),ref.bell(),"Road check peer",30,true,8,0)),Map.of(),0,0));
  Map<String,Long> roads=new java.util.HashMap<>();
  roads.put(key,24L);roads.put(key+":start",1L);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,roads);
  Map<String,Long> peerRoads=new java.util.HashMap<>();
  peerRoads.put(peerKey,24L);peerRoads.put(peerKey+":start",0L);
  peerRef.bellEntity().setAttached(VillagerPlus.ROADS,peerRoads);
  for(int x=0;x<24;x++)for(int side=-1;side<=1;side++) {
   BlockPos p=new BlockPos(at.getX()+x,groundY,at.getZ()+side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,(side==0?Blocks.GRAVEL:Blocks.COBBLESTONE).defaultBlockState(),3);
   if(x>=6) {level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);}
  }
  BlockPos lampBase=new BlockPos(at.getX()+16,groundY+1,at.getZ()+1);
  level.setBlock(lampBase,Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(lampBase.above(),Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(lampBase.above(2),Blocks.LANTERN.defaultBlockState(),3);
  roads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  peerRoads=new java.util.HashMap<>(peerRef.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.nextRepair(level,ref);
  roads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);
  v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var st=new JobState();var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.GRASS_BLOCK),"no stock leaves road unchanged");checks++;
   ItemStack gravel=new ItemStack(Items.GRAVEL,8);
   gravel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Straßenkies"));
   box.setItem(0,gravel);
   st=new JobState();c=new Ctx(level,v,st,mem,ref,now+1);st.roadScanAt=0;
   BlockPos depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
   check(RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7&&JobUtil.count(v.getInventory(),s->s.is(Items.GRAVEL))==1,"arrival at communal depot transfers exactly one paving block");checks++;
   check(ItemStack.isSameItemSameComponents(v.getInventory().getItem(0),box.getItem(0)),"material pickup preserves stack components");checks++;
   RoadBuilderJob.maintain(c);
   check(level.getBlockState(damaged).is(Blocks.GRASS_BLOCK)&&JobUtil.count(v.getInventory(),s->s.is(Items.GRAVEL))==1,"walking does not remotely repair or consume cargo");checks++;
   v.teleportTo(damaged.getX()+.5,damaged.getY()+1,damaged.getZ()+1.5);
   check(RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.GRAVEL)&&v.getInventory().isEmpty()&&box.getItem(0).getCount()==7,"worksite arrival repairs from carried stock exactly once");checks++;
   st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7,"intact road does not consume more stock");checks++;
   level.setBlock(damaged,Blocks.FARMLAND.defaultBlockState(),3);st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.FARMLAND),"farmland is protected even inside old route");checks++;
   level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);st.roadScanAt=0;
   level.getBlockEntity(depot).setAttached(VillagerPlus.OWNER,v.getUUID());
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7,"private depot supplies no road materials");checks++;
   level.getBlockEntity(depot).removeAttached(VillagerPlus.OWNER);
   level.setBlock(damaged.below(),Blocks.AIR.defaultBlockState(),3);st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7&&level.getBlockState(damaged).is(Blocks.GRASS_BLOCK),"unsupported ground cannot receive falling gravel");checks++;
   return checks;
  } finally {v.discard();var be=level.getBlockEntity(ref.chestPos(level));if(be!=null)be.removeAttached(VillagerPlus.OWNER);}
 }
 private static void check(boolean value,String message) {if(!value)throw new IllegalStateException("Road maintenance: "+message);}
}
