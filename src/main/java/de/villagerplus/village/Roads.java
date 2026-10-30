package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Wege. Im Dorf: gerade, drei Blöcke breite Pfade (Gebäude, Felder, Wasser und ungeladene Chunks bleiben unberührt).
 * Zwischen Dörfern ab Beziehung +30 (oder aktiver Route): Überlandstraße. Beide Dörfer bauen sich von ihrem Ende bis zur
 * Mitte entgegen: Korridor roden (Blätter, Gras, Stämme natürlicher Bäume), Kiesmitte mit Pflasterrand, Holzbrücke über
 * Wasser. Beleuchtung wird separat durch den bezahlten RoadBuilderJob-Arbeitszyklus gesetzt.
 */
public final class Roads {
 public static final int TRADE_PARTNER = 30;
 public static final int LANTERN_EVERY = 16;
 private static final long ROUTE_GEOMETRY_VERSION = 2; // rail cursor schema; route path schema lives in RoadRoute
 private Roads() {}
 public record Repair(BlockPos ground, BlockState paving, BlockPos worksite, boolean bridge, BlockState removal, BlockPos treeRoot, String completionKey) {
  public Repair(BlockPos ground,BlockState paving) {this(ground,paving,ground.above(),false,null,null,null);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge) {this(ground,paving,worksite,bridge,null,null,null);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge,BlockState removal,BlockPos treeRoot) {this(ground,paving,worksite,bridge,removal,treeRoot,null);}
 }

 /** One pending lane block. Progress is recovered from real completed lane blocks, including after reload. */
 public static Repair nextConstruction(ServerLevel level,VillageRef ref) {
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(!link.dimension().equals(Network.dim(level))||!(link.route()||link.relation()>=TRADE_PARTNER))continue;
   String key=link.dimension()+link.bell().asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),link.bell(),state,key,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,key,ref.bell(),link.bell());
   boolean existing=state.containsKey(key);
   // Old cursors are retained; metadata is added without replaying already paid/built segments.
   int cursor=existing?(int)Math.max(0,Math.min(Integer.MAX_VALUE,state.get(key))):0;
   if(!existing) {
    var bounds=Development.bounds(level,ref);
    while(cursor<=length/2+1) {
     BlockPos p=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
     if(p.getX()<bounds.minX()-2||p.getX()>bounds.maxX()+2||p.getZ()<bounds.minZ()-2||p.getZ()>bounds.maxZ()+2)break;
     cursor++;
    }
    state.put(key,(long)cursor);
    state.put(key+":start",(long)cursor);
   }
   int startCursor=(int)Math.max(0,Math.min(length,state.getOrDefault(key+":start",0L)));
   state.put(key+":from",ref.bell().asLong());state.put(key+":to",link.bell().asLong());state.put(key+":geometry",ROUTE_GEOMETRY_VERSION);
   for(int inspected=0;cursor<=length/2+1&&inspected<8;inspected++) {
    BlockPos route=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
    boolean alongX=RoadRoute.alongX(state,key,ref.bell(),link.bell(),cursor);
    Repair pending=null;boolean blocked=false,unavailableChunk=false;int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
    for(int side=-1;side<=1;side++) {
     int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
     if(!level.hasChunkAt(new BlockPos(x,ref.bell().getY(),z))){blocked=true;unavailableChunk=true;break;}
     BlockPos p=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
     var clearing=RoadClearing.column(level,ref,x,z,state,key+":clear:"+cursor+":"+side);
     if(clearing==null){blocked=true;unavailableChunk=!columnChunksLoaded(level,x,z);break;}
     p=clearing.ground();
     if(clearing.next()!=null) {
      low=Math.min(low,p.getY());high=Math.max(high,p.getY());
      if(pending==null)pending=clearing.next();continue;
     }
     String bridgeKey=key+":bridge:"+cursor+":"+side;
     if(state.containsKey(bridgeKey)||level.getBlockState(p).is(Blocks.WATER)||level.getBlockState(p).is(Blocks.SPRUCE_PLANKS)) {
      if(state.containsKey(bridgeKey))p=new BlockPos(x,state.get(bridgeKey).intValue(),z);
      var plan=RoadBridge.plan(level,p);
      if(plan==null){blocked=true;break;}
      state.put(bridgeKey,(long)p.getY());low=Math.min(low,p.getY());high=Math.max(high,p.getY());
      if(pending==null&&plan.next()!=null)pending=plan.next();
      continue;
     }
     low=Math.min(low,p.getY());high=Math.max(high,p.getY());
     BlockState current=level.getBlockState(p);boolean sand=current.is(BlockTags.SAND)||current.is(Blocks.SANDSTONE)||current.is(Blocks.SMOOTH_SANDSTONE)||current.is(Blocks.RED_SANDSTONE);
     Block desired=side==0?(sand?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sand?Blocks.SANDSTONE:Blocks.COBBLESTONE);
     if(current.is(desired))continue;
     Repair task=new Repair(p,desired.defaultBlockState());
     if(!repairable(level,task)){blocked=true;break;}
     if(pending==null)pending=task;
    }
    boolean badGrade=high-low>1||!RoadRoute.gradeSafe(level,state,key,ref.bell(),link.bell(),cursor);
    if(pending!=null&&pending.removal()!=null) {
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;
    }
    if(blocked||badGrade) {
     if(!unavailableChunk&&cursor==startCursor
      &&!routeCursorHasWork(level,state,key,ref.bell(),link.bell(),cursor)
      &&(peerState==null||peerState.getOrDefault(peerKey,0L)<=peerState.getOrDefault(peerKey+":start",0L)
      &&!routeCursorHasWork(level,peerState,peerKey,link.bell(),ref.bell(),peerState.getOrDefault(peerKey,0L).intValue()))) {
      Map<String,Long> peerMutable=peerState==null?null:new HashMap<>(peerState);
      if(RoadRoute.replanShared(level,ref.bell(),link.bell(),state,key,peerMutable,peerKey,level.getGameTime())) {
       state.remove(key);state.remove(key+":start");
       if(peerMutable!=null){peerMutable.remove(peerKey);peerMutable.remove(peerKey+":start");}
      }
      if(peer!=null&&peerMutable!=null){peer.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(peerMutable));peer.dirty();}
      ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
     }
     break;
    }
    if(pending!=null) {
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;
    }
    state.put(key,(long)++cursor);
   }
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
 }

 private static boolean columnChunksLoaded(ServerLevel level,int x,int z) {
  for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
   if(!level.hasChunkAt(new BlockPos(x+dx,level.getMinY(),z+dz)))return false;
  return true;
 }

 private static boolean routeCursorHasWork(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  String prefix=key+":clear:"+cursor+":";
  for(String name:state.keySet())if(name.startsWith(prefix)||name.startsWith(key+":bridge:"+cursor+":"))return true;
  int length=RoadRoute.length(state,key,a,b);if(cursor<0||cursor>length)return false;
  BlockPos route=RoadRoute.point(state,key,a,b,cursor);boolean alongX=RoadRoute.alongX(state,key,a,b,cursor);
  for(int side=-1;side<=1;side++) {
   int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
   if(!level.hasChunkAt(new BlockPos(x,a.getY(),z)))continue;
   BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
   BlockState s=level.getBlockState(ground);
   if(s.is(Blocks.GRAVEL)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.SANDSTONE)||s.is(Blocks.SMOOTH_SANDSTONE)||s.is(Blocks.SPRUCE_PLANKS))return true;
  }
  return false;
 }

 /** Bounded rotating inspection of already built road halves. Never extends an unfinished route. */
 public static Repair nextRepair(ServerLevel level,VillageRef ref) {
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(!link.dimension().equals(Network.dim(level))||!(link.route()||link.relation()>=TRADE_PARTNER))continue;
   String key=link.dimension()+link.bell().asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),link.bell(),state,key,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,key,ref.bell(),link.bell());
   int built=(int)Math.min(length/2+2,state.getOrDefault(key,0L));
   int start=(int)Math.max(0,Math.min(built,state.getOrDefault(key+":start",0L)));
   if(built<=start)continue;
   Repair lighting=nextLighting(level,state,key,ref.bell(),link.bell(),start,built);
   if(lighting!=null) {
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return lighting;
   }
   int total=(built-start)*3,scan=(int)Math.floorMod(state.getOrDefault("repair:"+key,0L),total);
   for(int i=0;i<Math.min(total,96);i++) {
    int index=(scan+i)%total,cursor=start+index/3,side=index%3-1;
    BlockPos route=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
    boolean alongX=RoadRoute.alongX(state,key,ref.bell(),link.bell(),cursor);
    int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
    BlockPos col=new BlockPos(x,ref.bell().getY(),z);if(!level.hasChunkAt(col))continue;
    BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
    BlockState s=level.getBlockState(ground);
    boolean sand=s.is(BlockTags.SAND)||s.is(Blocks.SANDSTONE)||s.is(Blocks.RED_SANDSTONE);
    Block desired=side==0?(sand?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sand?Blocks.SANDSTONE:Blocks.COBBLESTONE);
    Repair repair=new Repair(ground,desired.defaultBlockState());
    if(repairable(level,repair)) {
     state.put("repair:"+key,(long)((index+1)%total));
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return repair;
    }
   }
   state.put("repair:"+key,(long)((scan+96)%total));
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
 }

 public static boolean repairable(ServerLevel level,Repair repair) {
  BlockPos p=repair.ground();if(!level.hasChunkAt(p))return false;
  if(repair.completionKey()!=null)return lightingRepairable(level,repair);
  if(repair.removal()!=null)return RoadClearing.validTask(level,repair);
  if(repair.bridge())return RoadBridge.validTask(level,repair);
  BlockState s=level.getBlockState(p);
  if(s.is(repair.paving().getBlock())||s.hasBlockEntity()||!s.getFluidState().isEmpty()||s.is(Blocks.FARMLAND))return false;
  if(!(naturalSoil(s)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.STONE)))return false;
  if(!s.isFaceSturdy(level,p,net.minecraft.core.Direction.UP))return false;
  if(repair.paving().getBlock() instanceof net.minecraft.world.level.block.FallingBlock
   &&!level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP))return false;
  return level.getBlockState(p.above()).isAir()&&level.getBlockState(p.above(2)).isAir();
 }

 /** A paid log/log/lantern post is installed by the same physical worker route as road repairs. */
 private static Repair nextLighting(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,int start,int built) {
  int first=Math.max(start,((start+LANTERN_EVERY-1)/LANTERN_EVERY)*LANTERN_EVERY);
  for(int cursor=first;cursor<built;cursor+=LANTERN_EVERY) {
   String doneKey="light:"+key+":"+cursor;
   BlockPos route=RoadRoute.point(state,key,a,b,cursor);
   BlockPos base=lightTarget(level,route,RoadRoute.alongX(state,key,a,b,cursor));
   if(base==null)continue;
   Block[] post={Blocks.SPRUCE_LOG,Blocks.SPRUCE_LOG,Blocks.LANTERN};
   for(int stage=0;stage<post.length;stage++) {
    BlockPos target=base.above(stage);String completion=doneKey+":"+(stage+1);
    BlockState desired=post[stage].defaultBlockState();
    if(post[stage]==Blocks.LANTERN)desired=desired.setValue(net.minecraft.world.level.block.LanternBlock.HANGING,false);
    boolean done=state.getOrDefault(completion,0L)!=0L;
    if(level.hasChunkAt(target)&&level.getBlockState(target).is(post[stage])) {
     state.put(completion,1L);continue;
    }
    if(done)state.remove(completion); // A damaged lamp can be rebuilt later as a paid repair.
    Repair task=new Repair(target,desired,target.above(),false,null,null,completion);
    if(lightingRepairable(level,task))return task;
    break; // A blocked lower post must be resolved before higher stages can be built.
   }
  }
  return null;
 }

 private static BlockPos lightTarget(ServerLevel level,BlockPos route,boolean alongX) {
  int x=route.getX()+(alongX?0:1),z=route.getZ()+(alongX?1:0);
  if(!level.hasChunkAt(new BlockPos(x,route.getY(),z)))return null;
  int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  BlockPos support=new BlockPos(x,y,z);BlockState floor=level.getBlockState(support);
  if(!isRoadSurface(floor)||!floor.isFaceSturdy(level,support,net.minecraft.core.Direction.UP))return null;
  if(y+3>=level.getMaxY())return null;
  return support.above();
 }

 private static boolean lightingRepairable(ServerLevel level,Repair repair) {
  BlockPos target=repair.ground(),support=target.below();
  if(!level.hasChunkAt(target)||!level.hasChunkAt(support))return false;
  BlockState state=level.getBlockState(target),floor=level.getBlockState(support);
  if(!state.isAir()||!state.getFluidState().isEmpty()||floor.hasBlockEntity()
    ||!floor.isFaceSturdy(level,support,net.minecraft.core.Direction.UP))return false;
  if(repair.paving().is(Blocks.SPRUCE_LOG)) {
   if(!(isRoadSurface(floor)||floor.is(Blocks.SPRUCE_LOG)))return false;
  } else if(repair.paving().is(Blocks.LANTERN)) {
   if(!floor.is(Blocks.SPRUCE_LOG))return false;
  } else return false;
  return level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,new net.minecraft.world.phys.AABB(target),e->e.isAlive()).isEmpty();
 }

 private static boolean isRoadSurface(BlockState state) {
  return state.is(Blocks.GRAVEL)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.SANDSTONE)
   ||state.is(Blocks.COBBLESTONE)||state.is(Blocks.SPRUCE_PLANKS);
 }
 public static void tick(ServerLevel level,VillageRef ref,long now) {
  Map<String,Long> state=ref.bellEntity().getAttached(VillagerPlus.ROADS);if(state==null)state=Map.of();
  if(now<state.getOrDefault("next",0L))return;
  Map<String,Long> next=new HashMap<>(state);next.put("next",now+200);
  var plaza=Market.of(ref).plaza();
  if(plaza.isPresent()) advance(level,plaza.get(),ref.bell(),"center",next,8,false);
  // Overland paving is performed at the worksite by RoadBuilderJob, with real carried materials.
  // Rail construction is bilateral: each side must know the other, meet population/milestone gates,
  // and independently pay from its own stores as its half of the route advances.
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(link.relation()<METROPOLIS||!link.dimension().equals(Network.dim(level)))continue;
   VillageRef other=Network.resolve(level,link);
   if(other==null||!reciprocalMetropolis(level,ref,other)||!Network.projectApproved(ref,other)
    ||!railReady(level,ref)||!railReady(level,other))continue;
   advanceRail(level,ref.bell(),link.bell(),"rail"+link.bell().asLong(),next,4,ref,
    other.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(next));ref.dirty();
 }

 public static final int METROPOLIS=90;
 public static final int RAIL_POP=8;

 static int routeLength(BlockPos a,BlockPos b) { return Math.abs(b.getX()-a.getX())+Math.abs(b.getZ()-a.getZ()); }

 /** Canonical X-then-Z Manhattan route. Reversing endpoints returns exactly the same path backward. */
 static BlockPos routePoint(BlockPos a,BlockPos b,int cursor,int length) {
  return legacyRoutePoint(a,b,cursor,length);
 }

 static BlockPos legacyRoutePoint(BlockPos a,BlockPos b,int cursor,int length) {
  if(length<=0)return a;
  boolean forward=a.asLong()<=b.asLong();BlockPos start=forward?a:b,end=forward?b:a;
  int index=forward?cursor:length-cursor;
  index=Math.max(0,Math.min(length,index));
  int dx=end.getX()-start.getX(),dz=end.getZ()-start.getZ();
  int x=start.getX()+(int)Math.copySign(Math.min(Math.abs(dx),index),dx);
  int z=start.getZ()+(int)Math.copySign(Math.max(0,index-Math.abs(dx)),dz);
  return new BlockPos(x,a.getY(),z);
 }

 static boolean alongXAt(BlockPos a,BlockPos b,int cursor,int length) {
  if(length<=0)return true;
  boolean forward=a.asLong()<=b.asLong();BlockPos start=forward?a:b,end=forward?b:a;
  int index=forward?cursor:length-cursor;
  int xSteps=Math.abs(end.getX()-start.getX());
  if(index<xSteps)return true;
  if(index>xSteps)return false;
  return xSteps>0;
 }

 static int stationStartCursor(BlockPos a,BlockPos b,BlockPos station,int last,int total) {
  int selected=-1;double distance=24*24;
  for(int cursor=0;cursor<=last;cursor++) {
   BlockPos p=routePoint(a,b,cursor,total);double candidate=station.distSqr(p);
   if(candidate<=distance){selected=cursor;distance=candidate;}
  }
  return selected;
 }

 private static int findStationRailStart(ServerLevel level,BlockPos a,BlockPos b,BlockPos station,int last,int total,
                                         Map<String,Long> state,String routeKey) {
  int selected=-1;double distance=24*24;
  for(int cursor=0;cursor<=last;cursor++) {
   BlockPos point=RoadRoute.point(state,routeKey,a,b,cursor);
   if(!level.hasChunkAt(point)||findRailSite(level,point.getX(),point.getZ())==null)continue;
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,point.getX(),point.getZ())-1;
   BlockState center=level.getBlockState(new BlockPos(point.getX(),y,point.getZ()));
   if(!(center.is(Blocks.GRAVEL)||center.is(Blocks.SMOOTH_SANDSTONE)||center.is(Blocks.SPRUCE_PLANKS)
    ||center.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock))continue;
   double candidate=station.distSqr(point);
   if(candidate<distance){selected=cursor;distance=candidate;}
  }
  return selected;
 }

 private static boolean reciprocalMetropolis(ServerLevel level,VillageRef source,VillageRef target) {
	  String dimension=Network.dim(level);
  return Network.of(target.bellEntity()).links().stream().anyMatch(link->link.relation()>=METROPOLIS
   &&link.dimension().equals(dimension)&&link.bell().equals(source.bell()));
 }

 static boolean railReady(ServerLevel level,VillageRef ref) {
  int population=Villages.residents(level,ref,de.villagerplus.Config.VILLAGE_RADIUS,v->v.isAlive()).size();
  if(population<RAIL_POP)return false;
  for(String type:new String[]{de.villagerplus.build.Blueprints.GRANARY,de.villagerplus.build.Blueprints.TOOLSHED,de.villagerplus.build.Blueprints.STATION}) {
   BlockPos origin=Specials.get(ref,type);
   if(origin==null||!level.hasChunkAt(origin)||Specials.containers(level,ref,type).isEmpty())return false;
  }
  // The unlock represents a functioning rail workshop, not merely three buildings. Each
  // endpoint must have locally stocked metal/redstone inputs as well as manufactured rails.
  return RailResources.hasMetallurgyStock(level,ref);
 }

 /** Baut eine verbundene Schienenhälfte ab dem straßennahen Bahnhofszugang bis zur gemeinsamen Streckenmitte. */
 private static void advanceRail(ServerLevel level,BlockPos a,BlockPos b,String key,Map<String,Long> state,int budget,VillageRef ref,
                                 Map<String,Long> otherState){
  String routeKey=Network.dim(level)+b.asLong();
  String otherKey=Network.dim(level)+a.asLong();
  if(!RoadRoute.ensureWithRetry(level,a,b,state,routeKey,otherState,otherKey,level.getGameTime(),state.getOrDefault(key,0L)>0))return;
  int length=RoadRoute.length(state,routeKey,a,b);if(length==0)return;
  int last=length/2+1;
  int cursor=cursorFor(state,key,a,b);
  BlockPos station=Specials.get(ref,de.villagerplus.build.Blueprints.STATION);
  if(station==null)return;
  int stationStart=findStationRailStart(level,a,b,station,last,length,state,routeKey);
  if(stationStart<0)return;
  if(cursor<stationStart)cursor=stationStart;
  for(int count=0;cursor<=last&&count<budget;cursor++,count++){
   BlockPos route=RoadRoute.point(state,routeKey,a,b,cursor);int x=route.getX(),z=route.getZ();
   boolean alongX=RoadRoute.alongX(state,routeKey,a,b,cursor);
   BlockPos col=new BlockPos(x,a.getY(),z);if(!level.hasChunkAt(col))break;
   RailSite site=findRailSite(level,x,z);
   if(site==null){
    if(cursor<=length/2&&state.getOrDefault("road:"+key,0L)<1) {state.put("road:"+key,1L);}
    break;
   }
   boolean power=cursor%6==3;
   BlockState existing=level.getBlockState(site.rail());
   boolean alreadyTrack=existing.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock;
   BlockPos previousPoint=cursor>stationStart?RoadRoute.point(state,routeKey,a,b,cursor-1):null;
   RailSite previous=previousPoint==null?null:findRailSite(level,previousPoint.getX(),previousPoint.getZ());
   boolean previousRail=previous!=null&&level.getBlockState(previous.rail()).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock;
   boolean trackPlaced=alreadyTrack;
   if(alreadyTrack&&(!RailTransport.safeRail(level,site.rail())
    ||cursor>stationStart&&(!previousRail||!RailTransport.connected(level,previous.rail(),site.rail()))))break;
   if(!alreadyTrack&&!existing.isAir())break;
   if(!alreadyTrack) {
    boolean needsSupport=power&&!site.supported();
    if(!RailResources.canPay(level,ref,power,needsSupport))break;
    BlockState oldSupport=level.getBlockState(site.support());
    if(needsSupport&&!level.setBlock(site.support(),Blocks.REDSTONE_BLOCK.defaultBlockState(),3))break;
    BlockState desired=(power?Blocks.POWERED_RAIL:Blocks.RAIL).defaultBlockState();
    if(level.setBlock(site.rail(),desired,3)) {
     boolean joinsPrevious=RailTransport.safeRail(level,site.rail())
      &&(cursor==stationStart||(previousRail&&RailTransport.connected(level,previous.rail(),site.rail())));
     if(joinsPrevious&&RailResources.pay(level,ref,power,needsSupport))trackPlaced=true;
     else {
      level.setBlock(site.rail(),Blocks.AIR.defaultBlockState(),3);
      if(needsSupport)level.setBlock(site.support(),oldSupport,3);
      break;
     }
    } else {
     if(needsSupport)level.setBlock(site.support(),oldSupport,3);
     break;
    }
   }
   // Existing rails (including a differently powered/foreign rail) are respected and never charged.
   if(trackPlaced&&cursor==stationStart)platform(level,x,site.rail().getY()-1,z,alongX,Network.villageName(ref));
  }
  state.put(key,(long)cursor);
 }

 private record RailSite(BlockPos support,BlockPos rail,boolean supported) { }

 private static RailSite findRailSite(ServerLevel level,int x,int z) {
  int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  for(int y=top;y>=top-3;y--) {
   BlockPos pos=new BlockPos(x,y,z);BlockState state=level.getBlockState(pos);
   if(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) {
    BlockPos below=pos.below();return new RailSite(below,pos,level.getBlockState(below).is(Blocks.REDSTONE_BLOCK));
   }
   if(state.is(Blocks.REDSTONE_BLOCK)&&railRoad(level.getBlockState(pos.below())))return new RailSite(pos,pos.above(),true);
   if(railRoad(state))return new RailSite(pos,pos.above(),false);
  }
  return null;
 }

 private static boolean railRoad(BlockState state) {
  return state.is(Blocks.GRAVEL)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.SPRUCE_PLANKS);
 }

 private static int cursorFor(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  String from=key+":from",to=key+":to",geometry=key+":geometry";
  if(state.getOrDefault(from,Long.MIN_VALUE)!=a.asLong()||state.getOrDefault(to,Long.MIN_VALUE)!=b.asLong()
   ||state.getOrDefault(geometry,0L)!=ROUTE_GEOMETRY_VERSION) {
   state.put(key,0L);
   state.put(from,a.asLong());
   state.put(to,b.asLong());
   state.put(geometry,ROUTE_GEOMETRY_VERSION);
   return 0;
  }
  return state.getOrDefault(key,0L).intValue();
 }

 /** Überdachter Bahnsteig neben dem Gleis: Zaunpfosten, Holzdach, Bank, Schild mit Dorfname. */
 private static void platform(ServerLevel level,int x,int y,int z,boolean alongX,String village){
  int sx=alongX?0:1,sz=alongX?1:0; // Seitenrichtung
  for(int along=-2;along<=2;along++) for(int w=2;w<=3;w++){
   BlockPos p=new BlockPos(x+(alongX?along:w*sx),y,z+(alongX?w*sz:along));
   if(!level.hasChunkAt(p))continue;
   var s=level.getBlockState(p);
   if(s.isAir()||s.canBeReplaced()||s.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.SAND)) level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),3);
  }
  for(int along=-2;along<=2;along+=4){
   BlockPos post=new BlockPos(x+(alongX?along:3*sx),y+1,z+(alongX?3*sz:along));
   for(int h=0;h<3;h++) if(level.getBlockState(post.above(h)).isAir()) level.setBlock(post.above(h),Blocks.SPRUCE_FENCE.defaultBlockState(),3);
  }
  for(int along=-2;along<=2;along++) for(int w=2;w<=3;w++){
   BlockPos roof=new BlockPos(x+(alongX?along:w*sx),y+4,z+(alongX?w*sz:along));
   if(level.getBlockState(roof).isAir()) level.setBlock(roof,Blocks.SPRUCE_SLAB.defaultBlockState(),3);
  }
  BlockPos bench=new BlockPos(x+(alongX?0:3*sx),y+1,z+(alongX?3*sz:0));
  if(level.getBlockState(bench).isAir()) level.setBlock(bench,Blocks.SPRUCE_STAIRS.defaultBlockState(),3);
  BlockPos lamp=new BlockPos(x+(alongX?2:3*sx),y+3,z+(alongX?3*sz:2));
  if(level.getBlockState(lamp).isAir()) level.setBlock(lamp,Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true),3);
 }

 /** Fortschritt der Überlandstrecke 0..100 (für die Tafel) – aus dem eigenen Cursor, die andere Hälfte baut das Nachbardorf. */
 public static int progress(VillageRef ref,TradeNetwork.Link link,BlockPos from){
  Map<String,Long> state=ref.bellEntity().getAttached(VillagerPlus.ROADS);if(state==null)return 0;
  BlockPos source=ref.bell();
  String key=link.dimension()+link.bell().asLong();
  int length=RoadRoute.length(state,key,source,link.bell());if(length==0)return 100;
  long cursor=state.getOrDefault(key,0L);
  long start=state.getOrDefault(key+":start",0L);
  if(start>length/2+1)return 100;
  return (int)Math.max(0,Math.min(100,(cursor-start)*100/Math.max(1,length/2+2-start)));
 }

 /** Join a nearby, already paved incoming route and follow it toward this village. */
 static BlockPos caravanWaypoint(ServerLevel level,VillageRef ref,BlockPos current) {
  int bestCursor=-1,bestLength=0;BlockPos bestOther=null;double bestDistance=20*20;
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  for(TradeNetwork.Link link:Network.of(ref.bellEntity()).links()) {
   if(!(link.route()||link.relation()>=TRADE_PARTNER)||!link.dimension().equals(Network.dim(level)))continue;
   BlockPos other=link.bell();
   String routeKey=link.dimension()+other.asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),other,state,routeKey,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,routeKey,ref.bell(),other);if(length==0)continue;
   for(int i=0;i<=length/2;i++) {
   BlockPos route=RoadRoute.point(state,routeKey,ref.bell(),other,i);int x=route.getX(),z=route.getZ();
    if(!level.hasChunkAt(new BlockPos(x,ref.bell().getY(),z)))continue;
    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;BlockState road=level.getBlockState(new BlockPos(x,y,z));
    if(!(road.is(Blocks.GRAVEL)||road.is(Blocks.SMOOTH_SANDSTONE)||road.is(Blocks.SPRUCE_PLANKS)))continue;
    double distance=current.distSqr(new BlockPos(x,y,z));if(distance<bestDistance){bestDistance=distance;bestCursor=i;bestOther=other;bestLength=length;}
   }
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
  if(bestCursor<0)return null;
  int waypoint=Math.max(0,bestCursor-6);
  String routeKey=Network.dim(level)+bestOther.asLong();
  BlockPos point=RoadRoute.point(state,routeKey,ref.bell(),bestOther,waypoint);int x=point.getX(),z=point.getZ();
  return new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
 }

 private static boolean clearable(BlockState s){
  return s.isAir()||s.canBeReplaced()||s.is(BlockTags.LEAVES)||s.is(BlockTags.FLOWERS)||s.is(Blocks.SNOW)||s.is(Blocks.VINE);
 }

 /** Resolves terrain below a naturally rooted tree before paving; buildings and placed timber remain untouched. */
 static BlockPos surface(ServerLevel level,int x,int z){
  int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  BlockPos topPos=new BlockPos(x,top,z);
  if(!level.getBlockState(topPos).is(BlockTags.LOGS))return topPos;
  int lowest=Math.max(level.getMinY(),top-32);
  int cursor=top;
  while(cursor>=lowest){
   if(!level.getBlockState(new BlockPos(x,cursor,z)).is(BlockTags.LOGS)){cursor--;continue;}
   int base=cursor;
   while(base>lowest&&level.getBlockState(new BlockPos(x,base-1,z)).is(BlockTags.LOGS))base--;
   BlockPos root=new BlockPos(x,base,z);
   if(cursor-base>=3&&naturalSoil(level.getBlockState(root.below()))&&hasNearbyLeaves(level,x,z,cursor)){
    for(int y=base;y<=cursor;y++){
     BlockPos trunk=new BlockPos(x,y,z);
     if(level.getBlockState(trunk).is(BlockTags.LOGS))level.setBlock(trunk,Blocks.AIR.defaultBlockState(),3);
    }
    return root.below();
   }
   cursor=base-1;
  }
  return topPos;
 }

 private static boolean naturalSoil(BlockState state){
  return state.is(BlockTags.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.MYCELIUM)
   ||state.is(Blocks.SAND)||state.is(Blocks.RED_SAND)||state.is(Blocks.GRAVEL);
 }

 private static boolean hasNearbyLeaves(ServerLevel level,int x,int z,int top){
  int minY=Math.max(level.getMinY(),top-3),maxY=Math.min(level.getMaxY()-1,top+4);
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int y=minY;y<=maxY;y++){
   BlockPos pos=new BlockPos(x+dx,y,z+dz);
   if(level.hasChunkAt(pos)&&level.getBlockState(pos).is(BlockTags.LEAVES))return true;
  }
  return false;
 }

 private static void clearSoftGrowth(ServerLevel level,BlockPos ground){
  for(int h=1;h<=4;h++){
   BlockPos above=ground.above(h);BlockState state=level.getBlockState(above);
   if(clearable(state)&&!state.isAir())level.setBlock(above,Blocks.AIR.defaultBlockState(),3);
  }
 }

 private static void advance(ServerLevel level,BlockPos a,BlockPos b,String key,Map<String,Long> state,int budget,boolean overland) {
  int length=routeLength(a,b);if(length==0)return;
  int last=overland?length/2+1:length; // Überland: nur die eigene Hälfte
  int cursor=cursorFor(state,key,a,b);
  for(int count=0;cursor<=last&&count<budget;cursor++,count++) {
   boolean alongX=alongXAt(a,b,cursor,length);
   BlockPos route=routePoint(a,b,cursor,length);int x=route.getX(),z=route.getZ();
   BlockPos col=new BlockPos(x,a.getY(),z);if(!level.hasChunkAt(col))break;
   boolean loaded=true;for(int side=-2;side<=2;side++) {
    BlockPos adjacent=new BlockPos(x+(alongX?0:side),a.getY(),z+(alongX?side:0));
    if(!level.hasChunkAt(adjacent)){loaded=false;break;}
   }
   if(!loaded)break;
   for(int side=-1;side<=1;side++) {
   int px=x+(alongX?0:side),pz=z+(alongX?side:0);
    if(overland) paveOverland(level,px,pz,side==0);else pavePath(level,px,pz);
   }
  }
  state.put(key,(long)cursor);
 }

 static void pavePath(ServerLevel level,int px,int pz){
  BlockPos ground=surface(level,px,pz);var s=level.getBlockState(ground);
  if(s.is(Blocks.FARMLAND)||s.hasBlockEntity())return;
  if(!s.getFluidState().isEmpty()){
   buildFootbridge(level,ground);
   return;
  }
  clearSoftGrowth(level,ground);
  BlockState above=level.getBlockState(ground.above());
  if((!above.isAir()&&!above.canBeReplaced()) || above.hasBlockEntity() || s.is(Blocks.FARMLAND)||s.hasBlockEntity())return;
  boolean soil=s.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.PODZOL)||s.is(Blocks.MYCELIUM)
   ||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.SAND)||s.is(Blocks.RED_SAND)||s.is(Blocks.GRAVEL)||s.is(Blocks.SNOW_BLOCK);
  if(!soil||!s.isFaceSturdy(level,ground,net.minecraft.core.Direction.UP))return;
  if(!above.isAir())level.setBlock(ground.above(),Blocks.AIR.defaultBlockState(),3);
  level.setBlock(ground,Blocks.DIRT_PATH.defaultBlockState(),3);
 }

 private static void paveOverland(ServerLevel level,int px,int pz,boolean center){
  BlockPos ground=surface(level,px,pz);int y=ground.getY();var s=level.getBlockState(ground);
  if(s.is(Blocks.FARMLAND)||s.hasBlockEntity())return; // Felder und Kisten/Schilder der Dörfer nie anfassen
  // Vegetation im Korridor entfernen, nachdem natürlich verwurzelte Stämme bis zur Krone geräumt wurden.
  for(int h=1;h<=3;h++){
   BlockPos p=ground.above(h);var up=level.getBlockState(p);
   if(clearable(up)&&!up.isAir()){level.setBlock(p,Blocks.AIR.defaultBlockState(),3);}
  }
  if(!level.getBlockState(ground.above()).isAir())return; // Haus, Mauer o. ä. – nicht durchbauen
  boolean sandy=s.is(BlockTags.SAND)||s.is(Blocks.SANDSTONE)||s.is(Blocks.RED_SANDSTONE);
  Block pave=center?(sandy?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sandy?Blocks.SANDSTONE:Blocks.COBBLESTONE);
  if(!s.getFluidState().isEmpty()){
   buildFootbridge(level,ground);
   return;
  }
  if(s.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.SAND)||s.is(Blocks.RED_SAND)||s.is(Blocks.GRAVEL)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.STONE)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.PODZOL)||s.is(Blocks.COARSE_DIRT))
   level.setBlock(ground,pave.defaultBlockState(),3);
 }

 static void buildFootbridge(ServerLevel level,BlockPos deck){
  int supportDepth=0;
  for(int depth=1;depth<=24;depth++){
   BlockPos pile=deck.below(depth);if(!level.hasChunkAt(pile))return;
   BlockState bed=level.getBlockState(pile);
   if(bed.isFaceSturdy(level,pile,net.minecraft.core.Direction.UP)){supportDepth=depth;break;}
   if(!bed.isAir()&&bed.getFluidState().isEmpty())return;
  }
  if(supportDepth==0)return;
  for(int depth=1;depth<supportDepth;depth++)level.setBlock(deck.below(depth),Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(deck,Blocks.SPRUCE_PLANKS.defaultBlockState(),3);
 }

}
