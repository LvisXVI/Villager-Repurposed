from pathlib import Path
r=Path('src/main/java/de/villagerplus')
(r/'village/Roads.java').write_text('''package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Straight, three-wide paths. Existing buildings, fields, water and unloaded chunks are left intact. */
public final class Roads {
 private Roads() {}
 public static void tick(ServerLevel level,VillageRef ref,long now) {
  Map<String,Long> state=ref.bellEntity().getAttached(VillagerPlus.ROADS);if(state==null)state=Map.of();
  if(now<state.getOrDefault("next",0L))return;
  Map<String,Long> next=new HashMap<>(state);next.put("next",now+200);
  var plaza=Market.of(ref).plaza();
  if(plaza.isPresent()) advance(level,plaza.get(),ref.bell(),"center",next,8);
  for(var link:Network.of(ref.bellEntity()).links()) if(link.route()&&link.dimension().equals(Network.dim(level)))
   advance(level,plaza.orElse(ref.bell()),link.bell(),link.dimension()+link.bell().asLong(),next,8);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(next));ref.dirty();
 }
 private static void advance(ServerLevel level,BlockPos a,BlockPos b,String key,Map<String,Long> state,int budget) {
  int dx=b.getX()-a.getX(),dz=b.getZ()-a.getZ(),length=Math.max(Math.abs(dx),Math.abs(dz));if(length==0)return;
  int cursor=state.getOrDefault(key,0L).intValue();
  for(int count=0;cursor<=length&&count<budget;cursor++,count++) {
   int x=a.getX()+(int)Math.round(dx*cursor/(double)length),z=a.getZ()+(int)Math.round(dz*cursor/(double)length);
   BlockPos col=new BlockPos(x,a.getY(),z);if(!level.hasChunkAt(col))break;
   for(int side=-1;side<=1;side++) {
    int px=x+(Math.abs(dz)>=Math.abs(dx)?side:0),pz=z+(Math.abs(dx)>Math.abs(dz)?side:0);
    if(!level.hasChunkAt(new BlockPos(px,a.getY(),pz)))continue;
    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,px,pz)-1;BlockPos ground=new BlockPos(px,y,pz);var s=level.getBlockState(ground);
    if(!level.getBlockState(ground.above()).isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.FARMLAND))continue;
    if(s.is(BlockTags.DIRT)||s.is(Blocks.SAND))level.setBlock(ground,Blocks.DIRT_PATH.defaultBlockState(),3);
   }
  }
  state.put(key,(long)cursor);
 }
}
''',encoding='utf-8')
p=r/'VillagerPlus.java';s=p.read_text(encoding='utf-8');pos=s.index('\n\t@Override');s=s[:pos]+'''
 public static final AttachmentType<java.util.Map<String,Long>> ROADS = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"roads"),com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,com.mojang.serialization.Codec.LONG));
''' +s[pos:];p.write_text(s,encoding='utf-8')
p=r/'village/Villages.java';s=p.read_text(encoding='utf-8').replace('Market.plan(level, ref, now);','Market.plan(level, ref, now);\n\t\tRoads.tick(level,ref,now);');p.write_text(s,encoding='utf-8')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8').replace('trader.interact(player,InteractionHand.MAIN_HAND)','trader.interact(player,InteractionHand.MAIN_HAND,net.minecraft.world.phys.Vec3.ZERO)');p.write_text(s,encoding='utf-8')
