package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.ModBlocks;
import de.villagerplus.build.Palette;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;

/** A broad octagonal square with free sightlines; no existing building or chest is removed. */
public final class Plaza {
 private Plaza() {}
 public static boolean inside(ServerLevel level, VillageRef ref, BlockPos p, int margin) {
  MarketData m=Market.of(ref); if(m.plaza().isEmpty()) return false;
  BlockPos c=m.plaza().get(); int x=Math.abs(c.getX()-p.getX()), z=Math.abs(c.getZ()-p.getZ());
  return x<=7+margin && z<=7+margin && x+z<=11+margin*2;
 }
 private static final java.util.Set<String> SHRINE_FIXED=new java.util.HashSet<>();
 private static final java.util.Map<String,Long> DECOR_CHECK=new java.util.HashMap<>();

 private static String cacheKey(ServerLevel level,VillageRef ref) { return level.dimension().identifier()+"/"+ref.bell().asLong(); }

 /** Add small palette-matched seating and planters only in empty plaza cells; existing/player blocks are left intact. */
 private static void decorate(ServerLevel level, VillageRef ref, Villager hint, BlockPos c, long now) {
  String key=cacheKey(level,ref);
  if(now<DECOR_CHECK.getOrDefault(key,0L)||!level.hasChunkAt(c)) return;
  DECOR_CHECK.put(key,now+1200L);
  Palette p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  for(int[] spec:new int[][]{{-4,-2,1},{4,-2,-1},{-4,2,1},{4,2,-1}}) {
   BlockPos at=c.offset(spec[0],0,spec[1]);
   if(!decorationCellFree(level,at)) continue;
   var stair=p.roofStair();
   if(stair instanceof StairBlock) level.setBlock(at,stair.defaultBlockState().setValue(StairBlock.FACING,spec[2]>0?Direction.EAST:Direction.WEST),3);
  }
  for(int[] spec:new int[][]{{-5,-2},{5,-2},{-5,2},{5,2}}) {
   BlockPos at=c.offset(spec[0],0,spec[1]);
   if(!decorationCellFree(level,at)) continue;
   level.setBlock(at,Blocks.COMPOSTER.defaultBlockState(),3);
   BlockPos leaves=at.above();
   if(level.getBlockState(leaves).isAir() && noLivingEntity(level,leaves))
    level.setBlock(leaves,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),3);
  }
  var flowers=new net.minecraft.world.level.block.Block[]{Blocks.POPPY,Blocks.CORNFLOWER,Blocks.ALLIUM,Blocks.AZURE_BLUET};
  int i=0;
  for(int[] spec:new int[][]{{-3,-4},{3,-4},{-3,4},{3,4}}) {
   BlockPos at=c.offset(spec[0],0,spec[1]);
   if(decorationCellFree(level,at)) level.setBlock(at,flowers[i].defaultBlockState(),3);
   i++;
  }
 }

 private static boolean decorationCellFree(ServerLevel level,BlockPos pos) {
  return level.hasChunkAt(pos) && level.getBlockState(pos).isAir()
   && level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)
   && noLivingEntity(level,pos);
 }
 private static boolean noLivingEntity(ServerLevel level,BlockPos pos) {
  return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(pos)).isEmpty();
 }

 /** Symmetrisches Satteldach auf vier durchgehenden Pfosten (Pfostenkopf = Dachunterkante, nichts schwebt). */
 static void buildShrineRoof(ServerLevel level,BlockPos c,Palette p) {
  var full=p.roofFull()!=null?p.roofFull():Blocks.STONE_BRICKS;
  // Pfosten (Zaun) von der Plattform bis zur Dachunterkante
  for(int x:new int[]{-1,1}) for(int z:new int[]{-2,2}) for(int y=1;y<=4;y++) level.setBlock(c.offset(x,y,z),p.fence().defaultBlockState(),3);
  for(int z=-2;z<=2;z++) {
   // untere Dachreihe: Traufe (Treppen) aussen, Vollblock innen
   for(int x=-2;x<=2;x++) {
    var st=full.defaultBlockState();
    if(Math.abs(x)==2 && p.roofStair() instanceof StairBlock) st=p.roofStair().defaultBlockState().setValue(StairBlock.FACING,x<0?Direction.WEST:Direction.EAST);
    level.setBlock(c.offset(x,5,z),st,3);
   }
   // obere Reihe: Treppen zur Mitte hin, First als Vollblock
   for(int x=-1;x<=1;x++) {
    var st=full.defaultBlockState();
    if(x!=0 && p.roofStair() instanceof StairBlock) st=p.roofStair().defaultBlockState().setValue(StairBlock.FACING,x<0?Direction.WEST:Direction.EAST);
    level.setBlock(c.offset(x,6,z),st,3);
   }
  }
  // hängende Laterne unter dem Dach, Mittelsäule bleibt frei zugänglich
  level.setBlock(c.offset(0,4,0),Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true),3);
 }

 /** Einmal je Sitzung: den Schrein eines schon bestehenden Platzes neu decken (alte Dachform war schief und schwebte). */
 public static void refreshShrine(ServerLevel level,VillageRef ref,Villager hint) {
  String key=cacheKey(level,ref);
  MarketData m=Market.of(ref); if(m.plaza().isEmpty()||!SHRINE_FIXED.add(key)) return;
  BlockPos c=m.plaza().get(); if(!level.hasChunkAt(c)) { SHRINE_FIXED.remove(key); return; }
  Palette p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  // Altes Dach (Hoehe 4-6, x -2..2, z -1..1) nur entfernen, wenn es ein Dachblock ist (Treppe/Platte/Vollblock des Dorfes)
  for(int x=-2;x<=2;x++) for(int z=-1;z<=1;z++) for(int y=4;y<=6;y++) {
   BlockPos q=c.offset(x,y,z); var st=level.getBlockState(q);
   if(st.getBlock() instanceof StairBlock || st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock || st.getBlock()==p.roofFull()) level.setBlock(q,Blocks.AIR.defaultBlockState(),3);
  }
  for(int x:new int[]{-1,1}) for(int z:new int[]{-1,1}) for(int y=1;y<=3;y++) { // alte Pfosten (innerhalb) entfernen, neue stehen bei z=+-2
   BlockPos q=c.offset(x,y,z); if(level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.FenceBlock) level.setBlock(q,Blocks.AIR.defaultBlockState(),3);
  }
  buildShrineRoof(level,c,p);
 }
 public static void ensure(ServerLevel level,VillageRef ref,Villager hint,long now) {
  MarketData m=Market.of(ref);
  if(m.plaza().isPresent()) { decorate(level,ref,hint,m.plaza().get(),now); return; }
  if(now<m.nextPlan()) return;
  Market.save(ref,new MarketData(m.plaza(),m.shops(),now+600,m.sales()));
  BlockPos center=Development.center(level,ref);
  BlockPos c=findCentralSite(level,center,ref.bell(),ref.bell().asLong());
  if(c!=null) build(level,ref,c,hint);
 }

 /** Deterministic centre-out search. Existing buildings and homes are skipped by {@link #site}; each retry moves outward. */
 static BlockPos findCentralSite(ServerLevel level,BlockPos center,BlockPos bell,long seed) {
  final int samples=192; final double maxRadius=48.0;
  double phase=(seed&0xffffL)*(Math.PI*2.0/65536.0);
  for(int i=0;i<samples;i++) {
   double fraction=(i+0.5)/samples;
   double radius=2.0+Math.sqrt(fraction)*maxRadius;
   double angle=phase+i*Math.PI*(3.0-Math.sqrt(5.0));
   int x=center.getX()+(int)Math.round(Math.cos(angle)*radius);
   int z=center.getZ()+(int)Math.round(Math.sin(angle)*radius);
   BlockPos column=new BlockPos(x,center.getY(),z);
   if(!level.hasChunkAt(column)) continue;
   BlockPos at=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
   BlockPos plaza=site(level,at,7,5);
   if(plaza==null||Math.abs(plaza.getY()-bell.getY())>12) continue;
   if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(plaza).inflate(13,8,13)).isEmpty()) continue;
   return plaza;
  }
  return null;
 }
 /** Checks the full footprint plus three blocks of clearance, before changing any block. */
 public static BlockPos site(ServerLevel level,BlockPos at,int half,int clearance) {
  // Keep a real buffer around beds, including buildings whose roofs resemble open ground.
  if(level.getPoiManager().getInRange(h->h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),at,half+clearance+5,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
    .anyMatch(p->Math.abs(p.getPos().getX()-at.getX())<=half+clearance+2 && Math.abs(p.getPos().getZ()-at.getZ())<=half+clearance+2))return null;
  VillageRef context=Villages.villageNear(level,at,128);
  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;
  for(int x=-half-clearance;x<=half+clearance;x++) for(int z=-half-clearance;z<=half+clearance;z++) {
   BlockPos col=at.offset(x,0,z);
   if(context!=null && (Math.abs(x)>half || Math.abs(z)>half) && inside(level,context,col,0))continue;
   if(!level.hasChunkAt(col)) return null;
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,col.getX(),col.getZ());
   BlockPos ground=new BlockPos(col.getX(),y-1,col.getZ()); var state=level.getBlockState(ground);
   boolean paving=state.is(Blocks.SANDSTONE)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.CUT_SANDSTONE)||state.is(Blocks.DIRT_PATH);
   if((!VillageStyle.natural(state)&&!paving) || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)
    || state.is(Blocks.FARMLAND) || !state.isFaceSturdy(level,ground,Direction.UP)) return null;
   for(int dy=0;dy<6;dy++) { var s=level.getBlockState(ground.above(dy+1)); if(!s.isAir() && !s.canBeReplaced()) return null; }
   if(Math.abs(x)<=half && Math.abs(z)<=half) { high=Math.max(high,y); low=Math.min(low,y); }
  }
  if(high-low>4) return null;
  return new BlockPos(at.getX(),high,at.getZ());
 }
 private static void build(ServerLevel level,VillageRef ref,BlockPos c,Villager hint) {
  Palette p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  for(int x=-7;x<=7;x++) for(int z=-7;z<=7;z++) {
   if(Math.abs(x)+Math.abs(z)>11) continue;
   BlockPos floor=c.offset(x,-1,z);
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,floor.getX(),floor.getZ());
   for(int k=y;k<c.getY()-1;k++) level.setBlock(new BlockPos(floor.getX(),k,floor.getZ()),p.base().defaultBlockState(),3);
   boolean ring=Math.abs(x)==7 || Math.abs(z)==7 || Math.abs(x)+Math.abs(z)==11;
   level.setBlock(floor,(ring || Math.abs(x)<=1 || Math.abs(z)<=1 ? p.base() : p.floor()).defaultBlockState(),3);
   for(int dy=0;dy<3;dy++) if(level.getBlockState(c.offset(x,dy,z)).canBeReplaced()) level.setBlock(c.offset(x,dy,z),Blocks.AIR.defaultBlockState(),3);
  }
  // Low fountain with four wide approach paths; chest and board remain accessible.
  for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) level.setBlock(c.offset(x,0,z),
   p.base().defaultBlockState(),3);
  level.setBlock(c.above(),p.base().defaultBlockState(),3);
  level.setBlock(c.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
  level.setBlock(c.above(3),Blocks.LANTERN.defaultBlockState(),3);
  buildShrineRoof(level,c,p);
  for(int x:new int[]{-5,5}) for(int z:new int[]{-5,5}) {
   BlockPos post=c.offset(x,0,z); level.setBlock(post,p.fence().defaultBlockState(),3);
   level.setBlock(post.above(),p.fence().defaultBlockState(),3); level.setBlock(post.above(2),Blocks.LANTERN.defaultBlockState(),3);
  }
  if(p.roofStair() instanceof StairBlock) for(int side:new int[]{-1,1}) for(int z=-1;z<=1;z++)
   level.setBlock(c.offset(side*6,0,z),p.roofStair().defaultBlockState().setValue(StairBlock.FACING,side<0?Direction.WEST:Direction.EAST),3);
  for(Direction dir:Direction.Plane.HORIZONTAL) for(int n=8;n<=12;n++) {
   BlockPos road=c.relative(dir,n);int height=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,road.getX(),road.getZ());
   int target=c.getY()-(n-7);if(height>target+1)break;
   for(int side=-1;side<=1;side++) {
    BlockPos stair=new BlockPos(road.getX(),target,road.getZ()).relative(dir.getClockWise(),side);
    var cur=level.getBlockState(stair);if(!cur.isAir()&&!cur.canBeReplaced()&&!VillageStyle.natural(cur))continue;
    var stairState=p.roofStair().defaultBlockState();if(p.roofStair() instanceof StairBlock)stairState=stairState.setValue(StairBlock.FACING,dir.getOpposite());
    level.setBlock(stair,stairState,3);
   }
  }
  BlockPos old=ref.chestPos(level), chest=c.offset(-1,0,4);
  var oldBox=ref.chest(level); java.util.List<net.minecraft.world.item.ItemStack> contents=new java.util.ArrayList<>();
  if(oldBox!=null) for(int i=0;i<oldBox.getContainerSize();i++) contents.add(oldBox.getItem(i).copy());
  var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
  level.setBlock(chest,state.setValue(ChestBlock.TYPE,ChestType.LEFT),3);
  level.setBlock(chest.east(),state.setValue(ChestBlock.TYPE,ChestType.RIGHT),3);
  var box=net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level,chest);
  if(box==null || contents.size()>box.getContainerSize()) throw new IllegalStateException("Plaza chest capacity mismatch");
  for(int i=0;i<contents.size();i++) box.setItem(i,contents.get(i));
  if(oldBox!=null) oldBox.clearContent();
  ref.data().chest=Optional.of(chest);
  // Old center remains as an empty chest; only the mod's own board is relocated.
  if(ref.data().extra.board.isPresent()) {
   BlockPos b=ref.data().extra.board.get(); var bs=level.getBlockState(b);
   if(bs.getBlock() instanceof DorftafelBlock) for(BlockPos q:DorftafelBlock.parts(bs,b)) for(BlockPos t:new BlockPos[]{q,q.above()})
    if(level.getBlockState(t).getBlock() instanceof DorftafelBlock) level.setBlock(t,Blocks.AIR.defaultBlockState(),3);
  }
  BlockPos board=c.offset(-1,0,-5);
  DorftafelBlock.placeAll(level,board,ModBlocks.DORFTAFEL.defaultBlockState().setValue(DorftafelBlock.FACING,Direction.SOUTH));
  ref.data().extra.board=Optional.of(board); ref.dirty();
  MarketData m=Market.of(ref); Market.save(ref,new MarketData(Optional.of(c),m.shops(),m.nextPlan(),m.sales()));
  decorate(level,ref,hint,c,level.getGameTime());
  Journal.add(level,ref,"Bauen","Dorfgemeinschaft","Dorfschrein und Plaza errichtet bei "+c.toShortString(),0);
  org.slf4j.LoggerFactory.getLogger("villagerplus").info("Plaza errichtet bei {} (alte Dorfkiste {})",c,old);
 }
}
