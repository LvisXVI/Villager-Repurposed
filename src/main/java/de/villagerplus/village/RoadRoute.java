package de.villagerplus.village;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Persistent, bounded, endpoint-order-independent horizontal geometry for overland routes. */
final class RoadRoute {
 private static final int MAX_MANHATTAN=640;
 private static final int MAX_EXTRA=48;
 private static final int MARGIN=16;
 private static final int MAX_NODES=20_000;
 private static final String GEOMETRY="routeGeometry";
 private static final String COUNT="routeCount";
 private static final String POINT="routePoint:";
 private RoadRoute() { }

 static boolean ensure(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                       Map<String,Long> otherState,String otherKey) {
  return ensure(level,a,b,state,key,otherState,otherKey,false);
 }

 static boolean ensure(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                       Map<String,Long> otherState,String otherKey,boolean preserveLegacy) {
  if(validStored(state,key,a,b))return true;
  if(validLegacy(state,key,a,b))return true;
  boolean localLegacy=preserveLegacy||state.getOrDefault(key,0L)>state.getOrDefault(key+":start",0L);
  if(localLegacy) {
   List<BlockPos> old=legacy(a,b);if(old==null){storeLegacy(state,key,a,b);return true;}
   store(state,key,a,b,old,2L);return true;
  }
  // If the peer has already picked its path, reuse the same canonical sequence.
  if(otherState!=null&&otherKey!=null&&validStored(otherState,otherKey,a,b)) {
   copy(otherState,otherKey,state,key);
   return true;
  }
  if(otherState!=null&&otherKey!=null&&validLegacy(otherState,otherKey,a,b)) {
   storeLegacy(state,key,a,b);return true;
  }
  // Routes with paid progress from before geometry persistence keep their original X-then-Z line.
  boolean legacy=otherState!=null&&otherKey!=null
   &&otherState.getOrDefault(otherKey,0L)>otherState.getOrDefault(otherKey+":start",0L);
  List<BlockPos> points=legacy?legacy(a,b):plan(level,a,b);
  if(points==null) {
   // A* can fail on graded platforms or unloaded margins; Manhattan geometry is still valid for bilateral links.
   if(legacy(a,b)!=null) { storeLegacy(state,key,a,b); return true; }
   return false;
  }
  store(state,key,a,b,points,legacy?2L:3L);
  return true;
 }

 static boolean ensureWithRetry(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                                Map<String,Long> otherState,String otherKey,long now,boolean preserveLegacy) {
  String retryKey=key+":routeRetryAt";
  if(state.getOrDefault(retryKey,0L)>now) {
   if(validStored(state,key,a,b)||validLegacy(state,key,a,b)||ensure(level,a,b,state,key,otherState,otherKey,true)) {
    state.remove(retryKey);
    return true;
   }
   return false;
  }
  if(ensure(level,a,b,state,key,otherState,otherKey,preserveLegacy)) {
   state.remove(retryKey);return true;
  }
  state.put(retryKey,now+200);return false;
 }

 static boolean replanShared(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                             Map<String,Long> otherState,String otherKey,long now) {
  clearGeometry(state,key);if(otherState!=null&&otherKey!=null)clearGeometry(otherState,otherKey);
  List<BlockPos> path=plan(level,a,b);
  if(path==null) {
   state.put(key+":routeRetryAt",now+200);
   if(otherState!=null&&otherKey!=null)otherState.put(otherKey+":routeRetryAt",now+200);
   return false;
  }
  store(state,key,a,b,path,3L);
  if(otherState!=null&&otherKey!=null)store(otherState,otherKey,a,b,path,3L);
  return true;
 }

 static void clearGeometry(Map<String,Long> state,String key) {
  String prefix=key+":";
  state.keySet().removeIf(name->name.equals(prefix+GEOMETRY)||name.equals(prefix+COUNT)
   ||name.equals(prefix+"routeFrom")||name.equals(prefix+"routeTo")||name.equals(prefix+"routeRetryAt")
   ||name.startsWith(prefix+POINT));
 }

 private static void store(Map<String,Long> state,String key,BlockPos a,BlockPos b,List<BlockPos> points,long version) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  state.put(key+":routeFrom",low.asLong());state.put(key+":routeTo",high.asLong());state.put(key+":"+GEOMETRY,version);
  state.put(key+":"+COUNT,(long)points.size());
  for(int i=0;i<points.size();i++)state.put(key+":"+POINT+i,points.get(i).asLong());
 }

 private static void storeLegacy(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  state.put(key+":routeFrom",low.asLong());state.put(key+":routeTo",high.asLong());state.put(key+":"+GEOMETRY,2L);
  state.put(key+":"+COUNT,0L);
 }

 static int length(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(validLegacy(state,key,a,b))return Roads.routeLength(a,b);
  if(validStored(state,key,a,b))return Math.max(0,state.get(key+":"+COUNT).intValue()-1);
  return Roads.routeLength(a,b);
 }

 static BlockPos point(Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);int index=Math.max(0,Math.min(length,cursor));
  if(validStored(state,key,a,b)) {
   int canonical=a.asLong()<=b.asLong()?index:length-index;
   BlockPos saved=BlockPos.of(state.get(key+":"+POINT+canonical));
   return new BlockPos(saved.getX(),a.getY(),saved.getZ());
  }
  return Roads.legacyRoutePoint(a,b,index,length);
 }

 static boolean alongX(Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);if(length==0)return true;
  int canonical=a.asLong()<=b.asLong()?cursor:length-cursor;
  BlockPos here=point(state,key,a,b,cursor);
  int adjacent=canonical<length?canonical+1:canonical-1;
  int localAdjacent=a.asLong()<=b.asLong()?adjacent:length-adjacent;
  BlockPos next=point(state,key,a,b,localAdjacent);
  return here.getX()!=next.getX();
 }

 static boolean gradeSafe(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);int from=Math.max(0,cursor-1),to=Math.min(length,cursor+1);
  int previous=Integer.MIN_VALUE;
  for(int i=from;i<=to;i++) {
   BlockPos point=point(state,key,a,b,i);
   int endpointDistance=Math.min(Math.abs(point.getX()-a.getX())+Math.abs(point.getZ()-a.getZ()),
    Math.abs(point.getX()-b.getX())+Math.abs(point.getZ()-b.getZ()));
   if(endpointDistance<=8)continue;
   Column column=column(level,point.getX(),point.getZ(),a);
   if(!column.passable())return false;
   if(!column.known())continue;
   if(previous!=Integer.MIN_VALUE&&Math.abs(column.y()-previous)>1)return false;
   previous=column.y();
  }
  return true;
 }

 private static boolean validStored(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(state==null)return false;
  long low=a.asLong()<=b.asLong()?a.asLong():b.asLong(),high=a.asLong()<=b.asLong()?b.asLong():a.asLong();
  long count=state.getOrDefault(key+":"+COUNT,0L);
  if(!(count>=1&&count<=MAX_MANHATTAN+MAX_EXTRA+1
   &&state.getOrDefault(key+":routeFrom",Long.MIN_VALUE)==low
   &&state.getOrDefault(key+":routeTo",Long.MIN_VALUE)==high
   &&state.containsKey(key+":"+POINT+"0")&&state.containsKey(key+":"+POINT+(count-1))))return false;
  for(int i=0;i<count;i++)if(state.get(key+":"+POINT+i)==null)return false;
  return true;
 }

 private static boolean validLegacy(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(state==null||state.getOrDefault(key+":"+GEOMETRY,0L)!=2L||state.getOrDefault(key+":"+COUNT,-1L)!=0L)return false;
  long low=a.asLong()<=b.asLong()?a.asLong():b.asLong(),high=a.asLong()<=b.asLong()?b.asLong():a.asLong();
  return state.getOrDefault(key+":routeFrom",Long.MIN_VALUE)==low&&state.getOrDefault(key+":routeTo",Long.MIN_VALUE)==high;
 }

 private static void copy(Map<String,Long> from,String fromKey,Map<String,Long> to,String toKey) {
  to.put(toKey+":"+GEOMETRY,from.getOrDefault(fromKey+":"+GEOMETRY,3L));
  to.put(toKey+":routeFrom",from.get(fromKey+":routeFrom"));to.put(toKey+":routeTo",from.get(fromKey+":routeTo"));
  long count=from.get(fromKey+":"+COUNT);to.put(toKey+":"+COUNT,count);
  for(int i=0;i<count;i++)to.put(toKey+":"+POINT+i,from.get(fromKey+":"+POINT+i));
 }

 private static List<BlockPos> legacy(BlockPos a,BlockPos b) {
  int length=Roads.routeLength(a,b);if(length>MAX_MANHATTAN)return null;
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  List<BlockPos> result=new ArrayList<>(length+1);
  for(int i=0;i<=length;i++)result.add(Roads.legacyRoutePoint(low,high,i,length));
  return result;
 }

 private record Node(int x,int z,int g,int h) { int f(){return g+h;} }
 private record Cell(boolean passable,int y,boolean known) { }

 private static List<BlockPos> plan(ServerLevel level,BlockPos a,BlockPos b) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  int distance=Math.abs(high.getX()-low.getX())+Math.abs(high.getZ()-low.getZ());
  if(distance==0||distance>MAX_MANHATTAN)return null;
  int minX=Math.min(low.getX(),high.getX())-MARGIN,maxX=Math.max(low.getX(),high.getX())+MARGIN;
  int minZ=Math.min(low.getZ(),high.getZ())-MARGIN,maxZ=Math.max(low.getZ(),high.getZ())+MARGIN;
  Map<Long,Cell> cells=new HashMap<>();Map<Long,Integer> cost=new HashMap<>();Map<Long,Long> parent=new HashMap<>();
  PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::f).thenComparingInt(Node::h)
   .thenComparingInt(Node::x).thenComparingInt(Node::z));
  long start=key(low.getX(),low.getZ()),goal=key(high.getX(),high.getZ());
  cost.put(start,0);open.add(new Node(low.getX(),low.getZ(),0,heuristic(low.getX(),low.getZ(),high.getX(),high.getZ())));
  int visited=0;int[][] dirs={{1,0},{0,1},{-1,0},{0,-1}};
  while(!open.isEmpty()&&visited++<MAX_NODES) {
   Node node=open.poll();long current=key(node.x(),node.z());
   if(node.g()!=cost.getOrDefault(current,Integer.MAX_VALUE))continue;
   if(current==goal)break;
   Cell here=cell(level,node.x(),node.z(),low,high,cells);
   if(!here.passable())continue;
   for(int[] dir:dirs) {
    int x=node.x()+dir[0],z=node.z()+dir[1];if(x<minX||x>maxX||z<minZ||z>maxZ)continue;
    Cell next=cell(level,x,z,low,high,cells);if(!next.passable())continue;
    if(here.known()&&next.known()&&Math.abs(next.y()-here.y())>1)continue; // no abrupt longitudinal grade
    if(!crossSection(level,node.x(),node.z(),dir[0],dir[1],low,high,cells)
     ||!crossSection(level,x,z,dir[0],dir[1],low,high,cells))continue;
    int g=node.g()+1;
    if(g>distance+MAX_EXTRA)continue;
    long nextKey=key(x,z);if(g>=cost.getOrDefault(nextKey,Integer.MAX_VALUE))continue;
    cost.put(nextKey,g);parent.put(nextKey,current);
    open.add(new Node(x,z,g,heuristic(x,z,high.getX(),high.getZ())));
   }
  }
  if(!cost.containsKey(goal))return null;
  ArrayList<BlockPos> canonical=new ArrayList<>();long cursor=goal;
  for(int safety=0;safety<=MAX_MANHATTAN+MAX_EXTRA;safety++) {
   BlockPos packed=BlockPos.of(cursor);canonical.add(new BlockPos(packed.getX(),0,packed.getZ()));if(cursor==start)break;
   Long previous=parent.get(cursor);if(previous==null)return null;cursor=previous;
  }
  BlockPos packedStart=BlockPos.of(start);
  if(!canonical.get(canonical.size()-1).equals(new BlockPos(packedStart.getX(),0,packedStart.getZ())))return null;
  Collections.reverse(canonical);
  return canonical;
 }

 private static Cell cell(ServerLevel level,int x,int z,BlockPos start,BlockPos end,Map<Long,Cell> memo) {
  long key=key(x,z);Cell cached=memo.get(key);if(cached!=null)return cached;
  int endpointDistance=Math.min(Math.abs(x-start.getX())+Math.abs(z-start.getZ()),Math.abs(x-end.getX())+Math.abs(z-end.getZ()));
  if(!level.hasChunkAt(new BlockPos(x,start.getY(),z))) {Cell c=new Cell(true,0,false);memo.put(key,c);return c;}
  if(endpointDistance<=8) {
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
   Cell c=new Cell(true,y,true);memo.put(key,c);return c;
  }
  Column result=column(level,x,z,start);
  Cell cell=new Cell(result.passable(),result.y(),result.known());memo.put(key,cell);return cell;
 }

 private record Column(boolean passable,int y,boolean known) { }
 private static Column column(ServerLevel level,int x,int z,BlockPos reference) {
  BlockPos probe=new BlockPos(x,reference.getY(),z);if(!level.hasChunkAt(probe))return new Column(true,0,false);
  Map<String,Long> scratch=new HashMap<>();RoadClearing.Column clearing=RoadClearing.column(level,null,x,z,scratch,"route");
  if(clearing==null)return new Column(false,0,true);
  BlockPos ground=clearing.ground();BlockState surface=level.getBlockState(ground);
  if(!surface.getFluidState().isEmpty())return new Column(RoadBridge.plan(level,ground)!=null,ground.getY(),true);
  if(clearing.next()==null) {
   if(surface.is(Blocks.GRAVEL)||surface.is(Blocks.COBBLESTONE)||surface.is(Blocks.SANDSTONE)
    ||surface.is(Blocks.SMOOTH_SANDSTONE)||surface.is(Blocks.SPRUCE_PLANKS))return new Column(true,ground.getY(),true);
   BlockState desired=Blocks.GRAVEL.defaultBlockState();
   if(!surface.is(desired.getBlock())&&!Roads.repairable(level,new Roads.Repair(ground,desired)))return new Column(false,ground.getY(),true);
  }
  return new Column(true,ground.getY(),true);
 }

 private static boolean crossSection(ServerLevel level,int x,int z,int dx,int dz,BlockPos start,BlockPos end,Map<Long,Cell> memo) {
  int px=dz, pz=dx;int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
  for(int side=-1;side<=1;side++) {
   int sx=x+px*side,sz=z+pz*side;
   int endpointDistance=Math.min(Math.abs(sx-start.getX())+Math.abs(sz-start.getZ()),Math.abs(sx-end.getX())+Math.abs(sz-end.getZ()));
   if(endpointDistance<=8)continue;
   Column c=column(level,sx,sz,start);if(!c.passable())return false;if(!c.known())continue;
   min=Math.min(min,c.y());max=Math.max(max,c.y());
  }
  return min==Integer.MAX_VALUE||max-min<=1;
 }

 private static int heuristic(int x,int z,int tx,int tz){return Math.abs(tx-x)+Math.abs(tz-z);}
 private static long key(int x,int z){return new BlockPos(x,0,z).asLong();}
}
