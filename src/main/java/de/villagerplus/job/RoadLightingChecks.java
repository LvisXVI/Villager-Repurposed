package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/** Material-paid road-lighting checks. The fixture snapshots every touched block and block entity. */
public final class RoadLightingChecks {
 private RoadLightingChecks() { }

 public static int run(ServerLevel level,BlockPos at) {
  BlockPos other=at.east(80);
  Map<BlockPos,Cell> before=snapshot(level,touched(at));
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);
  v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  try {
   VillageRef ref=Checks090.fixtureForEquipment(level,at);
   var box=ref.chest(level);box.clearContent();
   if(level.getBlockEntity(ref.chestPos(level))!=null)level.getBlockEntity(ref.chestPos(level)).removeAttached(VillagerPlus.OWNER);
   String key=Network.dim(level)+other.asLong();
   Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Road lighting check",30,true,8,0)),Map.of(),0,0));
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,40L,key+":start",0L));
   // A saved paid legacy route is the canonical straight X-then-Z line for these endpoints.
   for(int x=0;x<40;x++)for(int side=-1;side<=1;side++) {
    BlockPos p=at.offset(x,-1,side);level.getChunkAt(p);
    level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(p,(side==0?Blocks.GRAVEL:Blocks.COBBLESTONE).defaultBlockState(),3);
    if(!p.above().equals(at))level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);
   }
   // The first scheduled fixture site is deliberately blocked; the next eligible interval is 16 blocks away.
   BlockPos blocked=at.offset(0,0,1);level.setBlock(blocked,Blocks.STONE.defaultBlockState(),3);
   var st=new JobState();var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);long now=level.getGameTime()+1000;
   int checks=0;
   {
    Ctx c=new Ctx(level,v,st,mem,ref,now);
    var candidate=Roads.nextRepair(level,ref);
    check(candidate!=null&&candidate.ground().equals(at.offset(16,0,1))&&level.getBlockState(blocked).is(Blocks.STONE),
     "blocked first lamp cell is skipped and next valid interval is selected");checks++;
    // The candidate at route index 16 remains an unpaid worker task, never a Roads/tick placement.
    BlockPos first=at.offset(16,0,1);
    check(level.getBlockState(first).isAir(),"stable completed route has no free remote torch");checks++;
    level.setBlock(ref.chestPos(level).below(),Blocks.STONE.defaultBlockState(),3);
    box.setItem(0,new net.minecraft.world.item.ItemStack(Items.SPRUCE_LOG,4));
    box.setItem(1,new net.minecraft.world.item.ItemStack(Items.LANTERN,2));
    v.teleportTo(ref.chestPos(level).getX()+.5,ref.chestPos(level).getY(),ref.chestPos(level).getZ()+1.5);
    st.roadScanAt=0;
    check(RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==3&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "worker collects exactly one real post log from shared material storage");checks++;
    Roads.tick(level,ref,now+500);
    check(level.getBlockState(first).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "road tick never places a carried lamp post remotely");checks++;
    // Drop volatile task memory between pickup and placement; persisted route work is rediscovered.
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now+1);
    check(RoadBuilderJob.maintain(c)&&level.getBlockState(first).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "interrupted worker keeps cargo and rediscovers the same lamp target");checks++;
    v.teleportTo(first.getX()+.5,first.getY()+1,first.getZ()+.5);
    check(RoadBuilderJob.maintain(c)&&level.getBlockState(first).is(Blocks.SPRUCE_LOG)
      &&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==0&&box.getItem(0).getCount()==3,
     "first paid log post is placed only after arrival at the road edge");checks++;
    String firstDone="light:"+key+":16:1";
    var persisted=ref.bellEntity().getAttached(VillagerPlus.ROADS);
    check(persisted.getOrDefault(firstDone,0L)==1L,"completed lamp cursor is persisted separately from the road cursor");checks++;
    CompoundTag bellNbt=ref.bellEntity().saveWithFullMetadata(level.registryAccess());
    BlockEntity loaded=BlockEntity.loadStatic(at,level.getBlockState(at),bellNbt.copy(),level.registryAccess());
    check(loaded!=null&&loaded.getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(firstDone,0L)==1L,
     "saved bell NBT round-trip retains lamp completion after restart");checks++;
    for(int stage=2;stage<=3;stage++) {
     st=new JobState();c=new Ctx(level,v,st,mem,ref,now+stage);
     BlockPos target=first.above(stage-1);
     v.teleportTo(ref.chestPos(level).getX()+.5,ref.chestPos(level).getY(),ref.chestPos(level).getZ()+1.5);
     check(RoadBuilderJob.maintain(c)&&!v.getInventory().isEmpty(),"each lamp post stage collects its own physical material");checks++;
     v.teleportTo(target.getX()+.5,target.getY()+1,target.getZ()+.5);
     check(RoadBuilderJob.maintain(c)&&level.getBlockState(target).is(stage==3?Blocks.LANTERN:Blocks.SPRUCE_LOG),
      "each lamp post stage is placed only at the worksite");checks++;
    }
    var secondTask=Roads.nextRepair(level,ref);
    check(secondTask!=null&&secondTask.ground().equals(at.offset(32,0,1)),
     "next complete lamp post is scheduled exactly 16 route blocks after the first");checks++;
    check(level.getBlockState(first).is(Blocks.SPRUCE_LOG)&&level.getBlockState(first.above()).is(Blocks.SPRUCE_LOG)
      &&level.getBlockState(first.above(2)).is(Blocks.LANTERN)&&box.getItem(0).getCount()==2&&box.getItem(1).getCount()==1,
     "log/log/lantern structure consumes two logs and one lantern");checks++;
    check(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key,0L)==40L,
     "lighting never changes the saved road construction cursor");checks++;
    return checks;
   }
  } finally {
   v.discard();restore(level,before);
  }
 }

 private static List<BlockPos> touched(BlockPos at) {
  List<BlockPos> out=new ArrayList<>();
  for(int x=0;x<=44;x++)for(int side=-1;side<=1;side++)for(int dy=-2;dy<=2;dy++)out.add(at.offset(x,dy,side).immutable());
  for(int dx=2;dx<=4;dx++)for(int dy=-1;dy<=1;dy++)out.add(at.offset(dx,dy,0).immutable());
  for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)out.add(at.offset(dx,dy,dz).immutable());
  return out;
 }

 private static Map<BlockPos,Cell> snapshot(ServerLevel level,List<BlockPos> positions) {
  Map<BlockPos,Cell> before=new HashMap<>();
  for(BlockPos p:positions) {
   level.getChunkAt(p);BlockEntity be=level.getBlockEntity(p);
   before.put(p, new Cell(level.getBlockState(p),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  return before;
 }

 private static void restore(ServerLevel level,Map<BlockPos,Cell> before) {
  for(BlockPos p:before.keySet())level.removeBlockEntity(p);
  before.forEach((p,c)->level.setBlock(p,c.state(),3));
  before.forEach((p,c)->{
   if(c.nbt()==null)return;
   BlockEntity be=BlockEntity.loadStatic(p,c.state(),c.nbt().copy(),level.registryAccess());
   if(be!=null)level.setBlockEntity(be);
  });
 }

 private record Cell(BlockState state,CompoundTag nbt) { }
 private static void check(boolean ok,String message) {if(!ok)throw new IllegalStateException("Road lighting: "+message);}
}
