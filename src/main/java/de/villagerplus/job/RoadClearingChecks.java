package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/** Paced real tree felling, exact log drops and structure protection on a remote synthetic road. */
public final class RoadClearingChecks {
 private RoadClearingChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  for(int x=9;x<=35;x++)for(int z=-5;z<=12;z++) {
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);level.setBlock(p.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(p.below(2),Blocks.STONE.defaultBlockState(),3);
   for(int y=0;y<=7;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),3);
  }
  BlockPos root=at.offset(12,0,-1),other=at.east(80);String key=Network.dim(level)+other.asLong();
  tree(level,root,5);
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Clearing check",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,12L,key+":start",12L));
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   check(Woodcutting.isHarvestableTree(level,root),"ordinary woodcutter also recognizes a real tree rooted on grass");checks++;
   var first=Roads.nextConstruction(level,ref);
   check(first!=null&&first.removal()!=null&&first.ground().equals(root.above(4))&&level.getBlockState(root.above(4)).is(Blocks.OAK_LOG),"planning identifies highest natural log without remotely clearing; task="+first
    +" column="+RoadClearing.column(level,ref,root.getX(),root.getZ(),new HashMap<>(),"debug")
    +" top="+level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,root.getX(),root.getZ())
    +" log="+level.getBlockState(root)+" logTag="+level.getBlockState(root).is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)
    +" soil="+level.getBlockState(root.below())+" leaf="+level.getBlockState(root.offset(1,4,0))+" diagnostic="+RoadClearing.diagnostic(level,root,root.above(4)));checks++;
   Ctx c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);
   check(v.getInventory().isEmpty()&&level.getBlockState(root.above(4)).is(Blocks.OAK_LOG)&&Anim.breakingStage(v)==-1,"distant worker must approach before any felling");checks++;
   for(int log=4;log>=0;log--) {
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
    var task=Roads.nextConstruction(level,ref);
    check(task!=null&&task.ground().equals(root.above(log))&&task.removal()!=null,"saved tree evidence accepts remaining short stump after interruption");checks++;
    BlockPos stand=task.worksite();v.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    for(int stage=0;stage<10;stage++) {
     c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);
     check(Anim.breakingStage(v)==stage&&level.getBlockState(task.ground()).is(Blocks.OAK_LOG),"each crack stage 0-9 retains the real log until final stroke");now+=6;
    }
    checks++;
    c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);now+=6;
    check(level.getBlockState(task.ground()).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.OAK_LOG))==5-log,"final stroke produces exactly one real carried log");checks++;
   }
   var paving=Roads.nextConstruction(level,ref);
   check(paving!=null&&paving.removal()==null&&paving.ground().equals(root.below()),"cleared tree transitions to the original soil height for paid paving");checks++;
   BlockPos beam=at.offset(24,0,0);tree(level,beam,1);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"beam")==null&&level.getBlockState(beam).is(Blocks.OAK_LOG),"isolated placed timber is protected even near leaves");checks++;
   tree(level,beam,5);level.setBlock(beam.east(),Blocks.OAK_PLANKS.defaultBlockState(),3);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"house")==null,"structural planks next to rooted logs prevent clearing");checks++;
   level.setBlock(beam.east(),Blocks.AIR.defaultBlockState(),3);level.setBlock(beam.east(),Blocks.BARREL.defaultBlockState(),3);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"private")==null,"nearby container protects tree column");checks++;
   BlockPos hedge=at.offset(24,0,9);
   level.setBlock(hedge,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),3);
   check(RoadClearing.column(level,ref,hedge.getX(),hedge.getZ(),new HashMap<>(),"hedge")==null,"persistent decorative foliage is protected");checks++;
   BlockPos plant=at.offset(33,0,9);level.setBlock(plant,Blocks.SHORT_GRASS.defaultBlockState(),3);
   var soft=RoadClearing.column(level,ref,plant.getX(),plant.getZ(),new HashMap<>(),"plant");
   check(soft!=null&&soft.next()!=null&&soft.next().removal().is(Blocks.SHORT_GRASS),"wild vegetation becomes an animated removal task");checks++;
   level.setBlock(plant,Blocks.OAK_PLANKS.defaultBlockState(),3);
   check(!Roads.repairable(level,soft.next()),"changed target invalidates removal rather than breaking new construction");checks++;
   return checks;
  } finally {Anim.cancelBreaking(v);v.discard();}
 }
 private static void tree(ServerLevel level,BlockPos root,int height){for(int y=0;y<height;y++)level.setBlock(root.above(y),Blocks.OAK_LOG.defaultBlockState(),3);level.setBlock(root.offset(1,4,0),Blocks.OAK_LEAVES.defaultBlockState(),3);}
 private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Road clearing: "+label);}
}
