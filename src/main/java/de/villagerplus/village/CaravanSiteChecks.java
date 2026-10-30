package de.villagerplus.village;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

/** Collision regression on a dedicated remote synthetic platform. */
public final class CaravanSiteChecks {
 private CaravanSiteChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  for(int x=-10;x<=10;x++)for(int z=-10;z<=10;z++) {
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   for(int y=0;y<=5;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),3);
  }
  var golem=EntityTypes.IRON_GOLEM.create(level,EntitySpawnReason.COMMAND);
  var trader=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.COMMAND);
  int checks=0;
  BlockPos camp=at.offset(-25,1,25);
  for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
   BlockPos floor=camp.offset(x,-1,z);level.getChunkAt(floor);level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
   for(int y=0;y<=4;y++)level.setBlock(camp.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
  }
  List<BlockPos> campCells=List.of(camp.offset(-1,0,-1),camp.offset(-1,1,-1),camp.offset(0,3,0),camp.offset(3,0,0));
  check(CaravanSites.canPlaceCampAt(level,camp,campCells),"complete flat air-only campsite footprint is accepted");checks++;
  level.setBlock(camp.offset(0,1,0),Blocks.OAK_PLANKS.defaultBlockState(),3);
  check(!CaravanSites.canPlaceCampAt(level,camp,campCells),"occupied canopy space rejects the whole campsite instead of producing a partial tent");checks++;
  level.setBlock(camp.offset(0,1,0),Blocks.AIR.defaultBlockState(),3);
  level.setBlock(camp.offset(3,0,3),Blocks.STONE.defaultBlockState(),3);
  check(!CaravanSites.canPlaceCampAt(level,camp,campCells),"uneven ground across the full tent-and-fire pad is rejected");checks++;
  level.setBlock(camp.offset(3,0,3),Blocks.AIR.defaultBlockState(),3);
  check(CaravanSites.canStand(level,golem,at,List.of()),"flat supported site fits complete golem box");checks++;
  level.setBlock(at.above(2),Blocks.STONE.defaultBlockState(),3);
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"two-block headroom fitting trader must reject tall golem");checks++;
  level.setBlock(at.above(2),Blocks.AIR.defaultBlockState(),3);level.setBlock(at.east(),Blocks.STONE.defaultBlockState(),3);
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"side wall outside trader box still intersects golem width");checks++;
  level.setBlock(at.east(),Blocks.AIR.defaultBlockState(),3);level.setBlock(at.east().below(),Blocks.AIR.defaultBlockState(),3);
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"golem requires support across full footprint");checks++;
  level.setBlock(at.east().below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(at,Blocks.WATER.defaultBlockState(),3);
  check(!CaravanSites.canStand(level,golem,at,List.of()),"water inside the body is rejected");checks++;
  level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
  check(!CaravanSites.canStand(level,golem,at,List.of(CaravanSites.bounds(trader,at))),"reserved trader slot excludes overlapping guard");checks++;
  for(int y=0;y<3;y++)level.setBlock(at.offset(2,y,-1),Blocks.STONE.defaultBlockState(),3);
  var group=CaravanSites.plan(level,at);
  check(group!=null&&!group.guard().equals(at.offset(2,0,-1)),"blocked legacy offset chooses a different safe guard position");checks++;
  var animal=EntityTypes.DONKEY.create(level,EntitySpawnReason.COMMAND);
  var boxes=new java.util.ArrayList<net.minecraft.world.phys.AABB>();boxes.add(CaravanSites.bounds(trader,group.trader()));boxes.add(CaravanSites.bounds(golem,group.guard()));
  for(BlockPos p:group.donkeys())boxes.add(CaravanSites.bounds(animal,p));
  for(int a=0;a<boxes.size();a++)for(int b=a+1;b<boxes.size();b++)check(!boxes.get(a).intersects(boxes.get(b)),"whole caravan reserves nonoverlapping slots");checks++;
  for(int y=0;y<3;y++)level.setBlock(at.offset(2,y,-1),Blocks.AIR.defaultBlockState(),3);
  for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)level.setBlock(at.offset(x,2,z),Blocks.STONE.defaultBlockState(),3);
  check(CaravanSites.plan(level,at)==null,"no complete group is offered when every guard position is blocked");checks++;
  for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)level.setBlock(at.offset(x,2,z),Blocks.AIR.defaultBlockState(),3);
  var spawned=EntityTypes.IRON_GOLEM.spawn(level,group.guard(),EntitySpawnReason.COMMAND);
  try {
   check(spawned!=null&&CaravanSites.canStand(level,spawned,spawned.blockPosition(),List.of()),"real spawned guard passes post-spawn collision validation");checks++;
   check(!CaravanSites.canPlaceCampAt(level,camp,List.of(spawned.blockPosition())),"living entity in a camp cell blocks the complete placement");checks++;
   spawned.setNoAi(true);spawned.addTag("vp090_fixture");
   check(!CaravanSites.campCellFree(level,spawned.blockPosition().above(2)),"camp canopy may not occupy the guard's upper body");checks++;
   check(!CaravanSites.campCellFree(level,spawned.blockPosition()),"camp posts may not enclose the guard's feet");checks++;
  } finally {if(spawned!=null)spawned.discard();}
  level.setBlock(at.east(8),Blocks.BARREL.defaultBlockState(),3);
  check(!CaravanSites.campCellFree(level,at.east(8)),"existing block entity is never a camp placement cell");checks++;
  BlockPos far=new BlockPos(1100000,280,1100000);
  check(!level.hasChunkAt(far)&&!CaravanSites.canStand(level,golem,far,List.of())&&!level.hasChunkAt(far),"placement rejection does not load remote chunks");checks++;
  check(!level.hasChunkAt(far)&&!CaravanSites.canPlaceCampAt(level,far,List.of(far))&&!level.hasChunkAt(far),"camp footprint rejection does not load remote chunks");checks++;
  var ref=de.villagerplus.dev.Checks090.fixtureForEquipment(level,at);
  // Continuous fallback arrival ring for the public spawn entry point; no RNG seed manipulation.
  for(int x=-49;x<=49;x++)for(int z=-49;z<=49;z++) {
   double radius=Math.hypot(x,z);if(radius<31||radius>49)continue;
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   for(int y=0;y<5;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),3);
  }
  var area=new net.minecraft.world.phys.AABB(at).inflate(110);
  try {
   check(Caravan.spawn(level,ref),"public caravan spawn succeeds with room for the complete group");checks++;
   var members=level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,area,e->e.entityTags().contains("vp_caravan"));
   check(members.stream().filter(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class::isInstance).count()==1
    &&members.stream().filter(net.minecraft.world.entity.animal.equine.Donkey.class::isInstance).count()==2
    &&members.stream().filter(net.minecraft.world.entity.animal.golem.IronGolem.class::isInstance).count()==1,"production spawn creates one trader, two donkeys and one guard");checks++;
   check(members.stream().allMatch(e->CaravanSites.canStand(level,e,e.blockPosition(),List.of())),"every production companion has supported full body clearance");checks++;
  } finally {
   for(var member:level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,area,e->e.entityTags().contains("vp_caravan")))member.discard();
  }
  return checks;
 }
 private static void check(boolean value,String message){if(!value)throw new IllegalStateException("Caravan site: "+message);}
}
