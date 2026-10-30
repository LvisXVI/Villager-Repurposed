package de.villagerplus.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only bridge planning; the road worker places each paid log/plank separately. */
public final class RoadBridge {
 private RoadBridge() { }
 public record Plan(Roads.Repair next) { }

 public static Plan plan(ServerLevel level,BlockPos deck) {
  if(!level.hasChunkAt(deck)||!clear(level,deck.above())||!clear(level,deck.above(2)))return null;
  BlockState surface=level.getBlockState(deck);
  if(!(replaceableWater(surface)||surface.is(Blocks.SPRUCE_PLANKS)))return null;
  int depth=0;
  for(int d=1;d<=24;d++) {
   BlockPos p=deck.below(d);if(!level.hasChunkAt(p))return null;
   BlockState s=level.getBlockState(p);
   if(s.hasBlockEntity())return null;
   if(s.is(Blocks.SPRUCE_LOG))continue;
   if(s.getFluidState().isEmpty()&&s.isFaceSturdy(level,p,Direction.UP)){depth=d;break;}
   if(!replaceableWater(s))return null;
  }
  if(depth==0)return null;
  BlockPos pending=null;
  for(int d=depth-1;d>=1;d--) {
   BlockPos p=deck.below(d);BlockState s=level.getBlockState(p);
   if(s.is(Blocks.SPRUCE_LOG))continue;
   if(!replaceableWater(s))return null;
   if(pending==null)pending=p;
  }
  if(pending==null&&surface.is(Blocks.SPRUCE_PLANKS))return new Plan(null);
  BlockPos stand=stand(level,deck);if(stand==null)return null;
  var task=new Roads.Repair(pending==null?deck:pending,
   (pending==null?Blocks.SPRUCE_PLANKS:Blocks.SPRUCE_LOG).defaultBlockState(),stand,true);
  return validTask(level,task)?new Plan(task):null;
 }

 public static boolean validTask(ServerLevel level,Roads.Repair task) {
  BlockPos p=task.ground(),stand=task.worksite();
  if(!level.hasChunkAt(p)||!level.hasChunkAt(stand))return false;
  if(!(task.paving().is(Blocks.SPRUCE_LOG)||task.paving().is(Blocks.SPRUCE_PLANKS)))return false;
  if(!replaceableWater(level.getBlockState(p))||!supported(level,p.below()))return false;
  if(!clear(level,stand)||!clear(level,stand.above())||!supported(level,stand.below()))return false;
  return Math.abs(p.getX()-stand.getX())<=3&&Math.abs(p.getZ()-stand.getZ())<=3
   &&stand.getY()>p.getY()&&stand.getY()-p.getY()<=25;
 }

 private static BlockPos stand(ServerLevel level,BlockPos deck) {
  for(int radius=0;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   BlockPos p=deck.offset(dx,1,dz);
   if(level.hasChunkAt(p)&&supported(level,p.below())&&clear(level,p)&&clear(level,p.above()))return p;
  }
  return null;
 }
 private static boolean replaceableWater(BlockState s){return s.isAir()||s.is(Blocks.WATER);}
 private static boolean clear(ServerLevel level,BlockPos p){return level.getBlockState(p).isAir();}
 private static boolean supported(ServerLevel level,BlockPos p){var s=level.getBlockState(p);return s.getFluidState().isEmpty()&&s.isFaceSturdy(level,p,Direction.UP);}
}
