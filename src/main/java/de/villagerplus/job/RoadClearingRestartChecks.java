package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Two separate commands, separated by a real server stop/start. No reset occurs in verification. */
public final class RoadClearingRestartChecks {
 private static final String PHASE="restart:roadclear:phase",MOST="restart:roadclear:most",LEAST="restart:roadclear:least",CLOCK="restart:roadclear:clock";
 private RoadClearingRestartChecks() { }

 public static int prepare(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);var old=level.getBlockEntity(at);
  check(old==null||old.getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(PHASE,0L)!=1,"unfinished restart fixture must not be overwritten");
  var ref=Checks090.fixtureForEquipment(level,at);
  BlockPos root=at.offset(12,0,-1),other=at.east(80);
  String key=Network.dim(level)+other.asLong();
  boolean advancing=level.getGameRules().get(GameRules.ADVANCE_TIME);
  for(int x=9;x<=17;x++)for(int z=-4;z<=4;z++) {
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);level.setBlock(p.below(2),Blocks.STONE.defaultBlockState(),3);level.setBlock(p.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
   for(int y=0;y<=6;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),3);
  }
  for(int y=0;y<5;y++)level.setBlock(root.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
  level.setBlock(root.offset(1,4,0),Blocks.OAK_LEAVES.defaultBlockState(),3);
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Reload clearing",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,12L,key+":start",12L,CLOCK,advancing?1L:0L));
  var v=EntityTypes.VILLAGER.spawn(level,root.west(),EntitySpawnReason.COMMAND);
  check(v!=null,"restart worker spawns");
  v.setNoAi(true);v.setInvulnerable(true);v.setPersistenceRequired();v.addTag("vp090_roadclear_restart");
  // Nitwit prevents unrelated staffing from replacing the fixture's role. Inventory keeps two logs, below porter surplus.
  v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(),VillagerProfession.NITWIT));
  JobManager.state(v).cooldownUntil=level.getGameTime()+24000;
  var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   level.getGameRules().set(GameRules.ADVANCE_TIME,false,level.getServer());
   for(int log=4;log>=3;log--) {now=finishLog(level,ref,v,st,now,root.above(log));checks++;}
   var task=Roads.nextConstruction(level,ref);check(task!=null&&task.ground().equals(root.above(2)),"third log selected before restart");checks++;
   v.teleportTo(task.worksite().getX()+.5,task.worksite().getY(),task.worksite().getZ()+.5);
   for(int stage=0;stage<=4;stage++){RoadBuilderJob.construct(ctx(level,ref,v,st,now));now+=6;}
   check(Anim.breakingStage(v)==4&&level.getBlockState(root.above(2)).is(Blocks.OAK_LOG),"partial cracks do not remove third log");checks++;
   check(logs(v)==2,"exactly two harvested logs are carried at shutdown");checks++;
   Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttached(VillagerPlus.ROADS));
   state.put(PHASE,1L);state.put(MOST,v.getUUID().getMostSignificantBits());state.put(LEAST,v.getUUID().getLeastSignificantBits());
   state.put("restart:roadclear:workerPos",v.blockPosition().asLong());
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
   force(level,at,true);force(level,v.blockPosition(),true);
   return checks;
  } catch(RuntimeException e) {
   Anim.cancelBreaking(v);force(level,at,false);force(level,v.blockPosition(),false);v.discard();level.getGameRules().set(GameRules.ADVANCE_TIME,advancing,level.getServer());throw e;
  }
 }

 public static int verify(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);var be=level.getBlockEntity(at);check(be!=null,"saved test bell exists");
  var saved=be.getAttached(VillagerPlus.ROADS);check(saved!=null,"road attachment survives");
  var ref=new VillageRef(at,be.getAttachedOrCreate(VillagerPlus.VILLAGE),be);BlockPos root=at.offset(12,0,-1);
  if(saved.getOrDefault(PHASE,0L)==2) {
   restoreEnvironment(level,at,saved);
   check(JobUtil.count(ref.chest(level),s->s.is(Items.OAK_LOG))==5&&level.getBlockState(root).isAir(),"repeat verification preserves final ledger without replay");return 1;
  }
  check(saved.getOrDefault(PHASE,0L)==1,"prepare must precede a real restart");
  Villager v=null;int checks=0;boolean complete=false;
  try {
   BlockPos workerPos=BlockPos.of(saved.getOrDefault("restart:roadclear:workerPos",at.asLong()));level.getChunkAt(workerPos);
   UUID id=new UUID(saved.getOrDefault(MOST,0L),saved.getOrDefault(LEAST,0L));
   check(level.getEntity(id) instanceof Villager,"exact saved worker UUID reloads");
   v=(Villager)level.getEntity(id);
   String key=Network.dim(level)+at.east(80).asLong(),tree=key+":clear:12:-1";
   check(saved.getOrDefault(tree+":root",Long.MIN_VALUE)==root.asLong()&&saved.getOrDefault(tree+":top",Long.MIN_VALUE)==root.getY()+4,"original root and crown height survive restart");checks++;
   check(saved.getOrDefault(key,-1L)==12&&saved.getOrDefault(key+":start",-1L)==12,"unfinished road cursor and boundary survive restart");checks++;
   check(logs(v)==2&&level.getBlockState(root.above(3)).isAir()&&level.getBlockState(root.above(4)).isAir(),"previous harvest remains exactly two carried logs");checks++;
   check(Anim.breakingStage(v)==-1&&level.getBlockState(root.above(2)).is(Blocks.OAK_LOG),"unsaved cracks reset without losing or duplicating the third log");checks++;
   var task=Roads.nextConstruction(level,ref);
   check(task!=null&&task.removal()!=null&&task.ground().equals(root.above(2)),"three-log stump resumes using saved evidence after reload");checks++;
   var st=new JobState();long now=level.getGameTime()+1000;
   for(int log=2;log>=0;log--){now=finishLog(level,ref,v,st,now,root.above(log));checks++;}
   check(logs(v)==5,"resumed felling ends with exactly five total harvested logs");checks++;
   var paving=Roads.nextConstruction(level,ref);
   check(paving!=null&&paving.removal()==null&&paving.ground().equals(root.below()),"reload-resumed tree transitions to original road ground");checks++;
   BlockPos chest=ref.chestPos(level);v.teleportTo(chest.getX()+.5,chest.getY(),chest.getZ()+1.5);
   for(int slot=0;slot<v.getInventory().getContainerSize();slot++) {
    var stack=v.getInventory().removeItemNoUpdate(slot);if(!stack.isEmpty())JobUtil.depositTo(ref.chest(level),stack);
    if(!stack.isEmpty())v.getInventory().setItem(slot,stack);
    check(stack.isEmpty(),"test harvest fits in the real fixture depot");
   }
   check(JobUtil.count(ref.chest(level),s->s.is(Items.OAK_LOG))==5&&v.getInventory().isEmpty(),"final harvest is kept in a real chest before fixture cleanup");checks++;
   Map<String,Long> finished=new HashMap<>(ref.bellEntity().getAttached(VillagerPlus.ROADS));finished.put(PHASE,2L);
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(finished));ref.dirty();complete=true;return checks;
  } finally {
   if(v!=null){Anim.cancelBreaking(v);if(complete)v.discard();}
   restoreEnvironment(level,at,saved);
  }
 }

 private static long finishLog(ServerLevel level,VillageRef ref,Villager v,JobState st,long now,BlockPos target) {
  var task=Roads.nextConstruction(level,ref);check(task!=null&&task.removal()!=null&&task.ground().equals(target),"expected next real log");
  v.teleportTo(task.worksite().getX()+.5,task.worksite().getY(),task.worksite().getZ()+.5);
  for(int stroke=0;stroke<11;stroke++){RoadBuilderJob.construct(ctx(level,ref,v,st,now));now+=6;}
  check(level.getBlockState(target).isAir(),"eleven paced strokes finish exactly one log");return now;
 }
 private static Ctx ctx(ServerLevel level,VillageRef ref,Villager v,JobState st,long now){return new Ctx(level,v,st,v.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);}
 private static int logs(Villager v){return JobUtil.count(v.getInventory(),s->s.is(Items.OAK_LOG));}
 private static void force(ServerLevel level,BlockPos p,boolean forced){level.setChunkForced(p.getX()>>4,p.getZ()>>4,forced);}
 private static void restoreEnvironment(ServerLevel level,BlockPos at,Map<String,Long> saved) {
  force(level,at,false);
  if(saved.containsKey("restart:roadclear:workerPos"))force(level,BlockPos.of(saved.get("restart:roadclear:workerPos")),false);
  if(saved.containsKey(CLOCK))level.getGameRules().set(GameRules.ADVANCE_TIME,saved.get(CLOCK)==1,level.getServer());
 }
 private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Road clearing restart: "+label);}
}
