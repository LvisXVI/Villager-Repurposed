from pathlib import Path
r=Path('src/main/java/de/villagerplus')
(r/'village/MarketData.java').write_text('''package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/** Persistent plaza and shop careers, separate from the full village codec. Stock lives in real owned chests. */
public record MarketData(Optional<BlockPos> plaza, List<Shop> shops, long nextPlan, long sales) {
 public record Shop(UUID owner, BlockPos origin, int facing, int kind, int progress, long opened, long lastSeen) {
  public static final Codec<Shop> CODEC = RecordCodecBuilder.create(i -> i.group(
   UUIDUtil.CODEC.fieldOf("owner").forGetter(Shop::owner), BlockPos.CODEC.fieldOf("origin").forGetter(Shop::origin),
   Codec.INT.fieldOf("facing").forGetter(Shop::facing), Codec.INT.fieldOf("kind").forGetter(Shop::kind),
   Codec.INT.optionalFieldOf("progress",0).forGetter(Shop::progress), Codec.LONG.optionalFieldOf("opened",0L).forGetter(Shop::opened),
   Codec.LONG.optionalFieldOf("last_seen",0L).forGetter(Shop::lastSeen)).apply(i,Shop::new));
  public Shop progress(int n, long now, boolean done) { return new Shop(owner,origin,facing,kind,n,done && opened==0 ? now : opened,now); }
 }
 public static final Codec<MarketData> CODEC = RecordCodecBuilder.create(i -> i.group(
  BlockPos.CODEC.optionalFieldOf("plaza").forGetter(MarketData::plaza), Shop.CODEC.listOf().optionalFieldOf("shops",List.of()).forGetter(MarketData::shops),
  Codec.LONG.optionalFieldOf("next_plan",0L).forGetter(MarketData::nextPlan), Codec.LONG.optionalFieldOf("sales",0L).forGetter(MarketData::sales)).apply(i,MarketData::new));
 public static MarketData empty() { return new MarketData(Optional.empty(),List.of(),0,0); }
}
''',encoding='utf-8')
(r/'village/Plaza.java').write_text('''package de.villagerplus.village;

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
 public static void ensure(ServerLevel level,VillageRef ref,Villager hint,long now) {
  MarketData m=Market.of(ref); if(m.plaza().isPresent() || now<m.nextPlan()) return;
  Market.save(ref,new MarketData(m.plaza(),m.shops(),now+2400,m.sales()));
  for(int i=0;i<48;i++) {
   BlockPos at=Sites.candidate(level,ref.bell(),hint.getRandom(),16,36); if(at==null) continue;
   BlockPos c=site(level,at,7,3); if(c==null || Math.abs(c.getY()-ref.bell().getY())>12) continue;
   build(level,ref,c,hint); return;
  }
 }
 /** Checks the full footprint plus three blocks of clearance, before changing any block. */
 public static BlockPos site(ServerLevel level,BlockPos at,int half,int clearance) {
  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;
  for(int x=-half-clearance;x<=half+clearance;x++) for(int z=-half-clearance;z<=half+clearance;z++) {
   BlockPos col=at.offset(x,0,z); if(!level.hasChunkAt(col)) return null;
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,col.getX(),col.getZ());
   BlockPos ground=new BlockPos(col.getX(),y-1,col.getZ()); var state=level.getBlockState(ground);
   if(!VillageStyle.natural(state) || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)
    || state.is(Blocks.FARMLAND) || !state.isFaceSturdy(level,ground,Direction.UP)) return null;
   for(int dy=0;dy<6;dy++) { var s=level.getBlockState(ground.above(dy+1)); if(!s.isAir() && !s.canBeReplaced()) return null; }
   if(Math.abs(x)<=half && Math.abs(z)<=half) { high=Math.max(high,y); low=Math.min(low,y); }
  }
  if(high-low>2) return null;
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
   (x==0 && z==0 ? Blocks.WATER : p.base()).defaultBlockState(),3);
  for(int x:new int[]{-5,5}) for(int z:new int[]{-5,5}) {
   BlockPos post=c.offset(x,0,z); level.setBlock(post,p.fence().defaultBlockState(),3);
   level.setBlock(post.above(),p.fence().defaultBlockState(),3); level.setBlock(post.above(2),Blocks.LANTERN.defaultBlockState(),3);
  }
  if(p.roofStair() instanceof StairBlock) for(int side:new int[]{-1,1}) for(int z=-1;z<=1;z++)
   level.setBlock(c.offset(side*6,0,z),p.roofStair().defaultBlockState().setValue(StairBlock.FACING,side<0?Direction.WEST:Direction.EAST),3);
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
  org.slf4j.LoggerFactory.getLogger("villagerplus").info("Plaza errichtet bei {} (alte Dorfkiste {})",c,old);
 }
}
''',encoding='utf-8')
