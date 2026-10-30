package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import de.villagerplus.job.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

/** Persistent shopkeeper careers, real hut construction, private stock and conserved villager-to-villager payments. */
public final class Market {
 private Market() {}
 public static final String[] KINDS={"Lebensmittelladen","Textilhandel","Materialladen"};
 public static boolean open(ServerLevel level) { long time=Math.floorMod(level.getOverworldClockTime(),24000L); return time>=de.villagerplus.Config.WORK_A_END && time<6000; }
 public static void register() {
  net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity,damage)->{
   if(entity instanceof Villager v && v.level() instanceof ServerLevel level) {
    var ref=Villages.find(level,v);if(ref==null)return;var s=shop(ref,v.getUUID());if(s!=null)updateShop(ref,s.died());
   }
  });
 }

 public static MarketData of(VillageRef ref) { MarketData m=ref.bellEntity().getAttached(VillagerPlus.MARKET); return m==null?MarketData.empty():m; }
 public static void save(VillageRef ref,MarketData m) { ref.bellEntity().setAttached(VillagerPlus.MARKET,m); ref.dirty(); }
 public static MarketData.Shop shop(VillageRef ref,UUID owner) { return of(ref).shops().stream().filter(s->s.owner().equals(owner)).findFirst().orElse(null); }
 public static String role(VillageRef ref,Villager v) { var s=shop(ref,v.getUUID()); return s==null?"":(v.isBaby()?"Ladenlehrling": "Ladeninhaber")+" · "+KINDS[Math.floorMod(s.kind(),3)]; }
 public static boolean reserved(ServerLevel level,BlockPos pos,int half) {
  VillageRef ref=Villages.villageNear(level,pos,96); if(ref==null) return false;
  if(Plaza.inside(level,ref,pos,half+5)) return true;
  return of(ref).shops().stream().anyMatch(s->Math.abs(s.origin().getX()-pos.getX())<=half+9 && Math.abs(s.origin().getZ()-pos.getZ())<=half+9);
 }
 public static void plan(ServerLevel level,VillageRef ref,long now) {
  MarketData m=of(ref); if(m.plaza().isEmpty() || now<m.nextPlan()) return;
  save(ref,new MarketData(m.plaza(),m.shops(),now+2400,m.sales()));
  List<Villager> people=Villages.residents(level,ref,de.villagerplus.Config.VILLAGE_RADIUS,v->!Guests.isGuest(v));
  int target=Math.min(3,people.size()/8); if(m.shops().size()>=target && m.shops().stream().noneMatch(MarketData.Shop::deceased)) return;
  List<Villager> free=people.stream().filter(v->shop(ref,v.getUUID())==null && !Expedition.isAway(v)
    && !java.util.Set.of("farmer","mason","fletcher","nitwit").contains(String.valueOf(de.villagerplus.Compat.jobKey(v.getVillagerData().profession())))
    && v.getVillagerData().profession().unwrapKey().map(k->k.identifier().getNamespace().equals("minecraft")).orElse(false))
    .sorted(Comparator.comparingInt(v->v.isBaby()?(m.shops().isEmpty()?3:0):(v.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath().equals("none")).orElse(false)?1:2))).toList();
  if(free.isEmpty()) return;
  // Prefer a child or an unemployed adult; preserve essential workers in small villages.
  Villager owner=free.get(0);
  if(!owner.isBaby() && !owner.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath().equals("none")).orElse(false)
    && people.size()<16) return;
  for(int i=0;i<m.shops().size();i++) if(m.shops().get(i).deceased()) {
   var old=m.shops().get(i);var heir=new MarketData.Shop(owner.getUUID(),old.origin(),old.facing(),old.kind(),old.progress(),old.opened(),now,old.palette(),false,0);
   List<MarketData.Shop> replacement=new ArrayList<>(m.shops());replacement.set(i,heir);
   var be=level.getBlockEntity(stockPos(old));if(be!=null)be.setAttached(VillagerPlus.OWNER,owner.getUUID());
   save(ref,new MarketData(m.plaza(),List.copyOf(replacement),now+2400,m.sales()));return;
  }
  BlockPos center=m.plaza().get();
  for(Direction face:Direction.Plane.HORIZONTAL) {
   BlockPos candidate=center.relative(face,13); BlockPos site=Plaza.site(level,candidate,4,5);
   if(site==null || m.shops().stream().anyMatch(s->s.origin().distSqr(site)<64)) continue;
   Palette p=Palette.detect(level,ref.bell(),owner,site.asLong(),site);
   var entry=new MarketData.Shop(owner.getUUID(),site,face.getOpposite().get2DDataValue(),m.shops().size()%3,0,0,now,p.ids(),false,0);
   List<MarketData.Shop> shops=new ArrayList<>(m.shops());shops.add(entry);
   save(ref,new MarketData(m.plaza(),List.copyOf(shops),now+2400,m.sales()));
   org.slf4j.LoggerFactory.getLogger("villagerplus").info("Laden geplant: {} bei {}, Inhaber {}",KINDS[entry.kind()],site,owner.getUUID()); return;
  }
 }
 private static Direction facing(MarketData.Shop s) { return Direction.from2DDataValue(s.facing()); }
 private static BlockPos at(MarketData.Shop s,int x,int z,int y) { Direction d=facing(s); return s.origin().relative(d.getClockWise(),x).relative(d,z).above(y); }
 private static BlockPos counter(MarketData.Shop s) { return at(s,0,3,0); }
 private static BlockPos stockPos(MarketData.Shop s) { return at(s,-1,-1,0); }
 public static List<Step> hut(MarketData.Shop s,Palette p) {
  List<Step> out=new ArrayList<>();
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
   out.add(Step.block(at(s,x,z,-1),p.floor()));
   for(int y=-2;y>=-3;y--) out.add(Step.free(at(s,x,z,y),p.base().defaultBlockState()));
   if(z==-2 || Math.abs(x)==2) for(int y=0;y<3;y++) out.add(Step.block(at(s,x,z,y),y==0?p.base():p.wall()));
   out.add(Step.block(at(s,x,z,3),p.roofSlab() instanceof SlabBlock?p.roofSlab():p.roofFull()));
  }
  for(int x=-1;x<=1;x++) out.add(Step.block(at(s,x,2,0),p.fence()));
  // Side entrance remains clear; roof extends over the sales counter.
  out.removeIf(st->st.pos().equals(at(s,2,1,0)) || st.pos().equals(at(s,2,1,1)));
  for(int x=-3;x<=3;x++) for(int z=-3;z<=2;z++) {
   int y=3+3-Math.abs(x);Block roof=Math.abs(x)==0?p.roofFull():p.roofStair();
   final int side=x;
   out.add(Step.block(at(s,x,z,y),roof,b->{var state=b.defaultBlockState();return b instanceof StairBlock?state.setValue(StairBlock.FACING,side<0?facing(s).getClockWise():facing(s).getCounterClockWise()):state;}));
  }
  Block timber=p.frame()==null?p.base():p.frame();
  for(int x:new int[]{-2,2})for(int z:new int[]{-2,2})for(int y=0;y<3;y++) {
   BlockPos post=at(s,x,z,y);out.removeIf(st->st.pos().equals(post));out.add(Step.block(post,timber));
  }
  BlockPos window=at(s,0,-2,1);out.removeIf(st->st.pos().equals(window));out.add(Step.block(window,p.window()));
  String color=new String[]{"green","red","orange"}[Math.floorMod(s.kind(),3)];
  Block cloth=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:"+color+"_wool"));
  for(int x=-2;x<=2;x++)for(int z=3;z<=4;z++)out.add(Step.block(at(s,x,z,3),Math.floorMod(x,2)==0?cloth:net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_wool"))));
  for(int x:new int[]{-2,2})for(int y=0;y<3;y++)out.add(Step.block(at(s,x,4,y),p.fence()));
  out.add(Step.block(stockPos(s),Blocks.CHEST));
  out.add(Step.free(at(s,0,0,2),Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true)));
  java.util.Map<BlockPos,Step> unique=new java.util.LinkedHashMap<>();for(Step st:out)unique.put(st.pos(),st);
  return new ArrayList<>(unique.values());
 }
 private static Palette palette(ServerLevel l,VillageRef r,MarketData.Shop s,Villager v) {
  Palette p=Palette.ofIds(s.palette()); return p==null?Palette.detect(l,r.bell(),v,s.origin().asLong(),s.origin()):p;
 }
 private static void updateShop(VillageRef ref,MarketData.Shop s) {
  MarketData m=of(ref);List<MarketData.Shop> shops=new ArrayList<>(m.shops());
  for(int i=0;i<shops.size();i++) if(shops.get(i).owner().equals(s.owner())) shops.set(i,s);
  save(ref,new MarketData(m.plaza(),List.copyOf(shops),m.nextPlan(),m.sales()));
 }
 public static boolean work(Ctx c) {
  if(c.village()==null) return false; MarketData.Shop s=shop(c.village(),c.v().getUUID()); if(s==null) return false;
  if(s.deceased() || c.now()<s.retryAt()) return false;
  Palette p=palette(c.level(),c.village(),s,c.v()); List<Step> steps=hut(s,p);
  if(s.opened()>0 && stock(c.level(),s)==null) {
   if(!c.level().getBlockState(stockPos(s)).canBeReplaced()) { updateShop(c.village(),s.retry(c.now()+1200));return false; }
   for(int k=0;k<steps.size();k++) if(steps.get(k).pos().equals(stockPos(s))) {
    s=new MarketData.Shop(s.owner(),s.origin(),s.facing(),s.kind(),k,0,c.now(),s.palette(),false,0);updateShop(c.village(),s);break;
   }
  }
  if(s.progress()<steps.size()) {
   c.setActivity("baut den eigenen "+KINDS[s.kind()]);
   Step st=steps.get(s.progress());
   if(!JobUtil.approach(c,counter(s),2.5)) return true;
   var current=c.level().getBlockState(st.pos());
   var desired=st.make().apply(st.item()==null?Blocks.AIR:Block.byItem(st.item()));
   if(!current.equals(desired)) {
    if(!current.isAir() && !current.canBeReplaced() && !VillageStyle.natural(current)) { c.st().cooldownUntil=c.now()+100; updateShop(c.village(),s.retry(c.now()+1200)); return false; }
    if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(st.pos()),e->e.isAlive()).isEmpty()) return true;
    if(!st.free()) {
     List<Container> stores=Balance.storage(c.level(),c.village());
     if(count(stores,st.item())<st.cost()) {
      String wish=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item())+"#32";
      if(c.village().data().wanted.size()<32 && !c.village().data().wanted.contains(wish)) { c.village().data().wanted.add(wish);c.village().dirty(); }
      c.setActivity("wartet auf Ladenmaterial"); c.st().cooldownUntil=c.now()+100; return true;
     }
     take(stores,st.item(),st.cost());
    }
    c.level().setBlock(st.pos(),desired,3); JobUtil.swing(c.v()); Anim.hold(c,Items.STONE_AXE);
   }
   int n=s.progress()+1; updateShop(c.village(),s.progress(n,c.now(),n==steps.size())); c.mem().jobsDone++;
   if(n==steps.size()) {
    Journal.add(c.level(),c.village(),"Bauen",c.mem().name,KINDS[s.kind()]+" eröffnet",0);
    var be=c.level().getBlockEntity(stockPos(s));if(be!=null) be.setAttached(VillagerPlus.OWNER,c.v().getUUID());
    JobUtil.tellNearbyPlayers(c.level(),c.v(),32,Component.literal(c.mem().name+" eröffnet einen "+KINDS[s.kind()]+"."));
   }
   c.st().cooldownUntil=c.now()+20; return true;
  }
  c.setActivity("führt "+KINDS[s.kind()]);
  if(!JobUtil.approach(c,at(s,0,1,0),2)) return true;
  c.v().getLookControl().setLookAt(counter(s).getX()+.5,c.v().getEyeY(),counter(s).getZ()+.5);
  restock(c,s); c.st().cooldownUntil=c.now()+80;return true;
 }
 private static Item[] goods(int kind) { return switch(kind) {
  case 0 -> new Item[]{Items.BREAD,Items.CARROT,Items.POTATO,Items.APPLE};
  case 1 -> new Item[]{Items.WOOL.white(),Items.LEATHER,Items.STRING};
  default -> new Item[]{Items.OAK_PLANKS,Items.SPRUCE_PLANKS,Items.COAL,Items.IRON_INGOT}; }; }
 public static List<Container> wallet(ServerLevel level,Villager v) {
  List<Container> stores=new ArrayList<>();stores.add(v.getInventory());
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  if(mem.homeChest.isPresent() && level.hasChunkAt(mem.homeChest.get())) {
   Container box=HopperBlockEntity.getContainerAt(level,mem.homeChest.get());if(box!=null) stores.add(box);
  } return stores;
 }
 private static Container stock(ServerLevel l,MarketData.Shop s) { return l.hasChunkAt(stockPos(s))?HopperBlockEntity.getContainerAt(l,stockPos(s)):null; }
 private static int count(List<Container> boxes,Item item) { int n=0;for(Container b:boxes)n+=JobUtil.count(b,st->st.is(item)&&st.getComponents().equals(new ItemStack(item).getComponents()));return n; }
 private static void take(List<Container> boxes,Item item,int n) {for(Container b:boxes) { if(n<=0)break;n-=JobUtil.take(b,st->st.is(item)&&st.getComponents().equals(new ItemStack(item).getComponents()),n);}}
 private static boolean fits(Container box,ItemStack s) {int cap=0;for(int i=0;i<box.getContainerSize();i++){var a=box.getItem(i);if(a.isEmpty())cap+=s.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(a,s))cap+=Math.max(0,a.getMaxStackSize()-a.getCount());}return cap>=s.getCount();}
 private static boolean add(List<Container> boxes,ItemStack s) {
  for(Container b:boxes)if(fits(b,s)) {JobUtil.depositTo(b,s.copy());return true;} return false;
 }
 private static void restock(Ctx c,MarketData.Shop s) {
  Container shelf=stock(c.level(),s);if(shelf==null)return;
  List<Container> funds=wallet(c.level(),c.v()); if(count(funds,Items.EMERALD)<1)return;
  Container communal=c.village().chest(c.level());if(communal==null || !fits(communal,new ItemStack(Items.EMERALD)))return;
  for(Item item:goods(s.kind())) {
   int size=item==Items.IRON_INGOT?2:8;
   if(JobUtil.count(shelf,x->x.is(item))>=16 || !fits(shelf,new ItemStack(item,size)))continue;
   List<Container> pool=Balance.storage(c.level(),c.village()); // Dorfkiste, Lager, Kornspeicher, Werkzeugkammer
   if(count(pool,item)<size+16)continue;
   // Owner buys stock with private money, the community receives payment.
   take(funds,Items.EMERALD,1);take(pool,item,size);
   JobUtil.depositTo(communal,new ItemStack(Items.EMERALD));JobUtil.depositTo(shelf,new ItemStack(item,size));
   Anim.hold(c,item);JobUtil.swing(c.v());return;
  }
 }
 // --- Spielerkauf: Rechtsklick auf den Ladeninhaber oeffnet das Handelsfenster mit dem ECHTEN Warenbestand der Ladenkiste. ---
 private record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,BlockPos bell,net.minecraft.world.item.trading.MerchantOffers original,
  net.minecraft.world.item.trading.MerchantOffers offers,int[] uses,int[] quantity,int[] cost,Item[] items) {}
 private static final Map<UUID,Session> SESSIONS=new HashMap<>();
 public static void registerPlayerShop() {
  net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{
   if(!(world instanceof ServerLevel level)||hand!=net.minecraft.world.InteractionHand.MAIN_HAND||player.isSpectator()||player.isShiftKeyDown()||!(entity instanceof Villager seller)||seller.isBaby()||seller.isSleeping())
    return net.minecraft.world.InteractionResult.PASS;
   var ref=Villages.find(level,seller); if(ref==null)return net.minecraft.world.InteractionResult.PASS;
   var shop=shop(ref,seller.getUUID()); if(shop==null||shop.opened()==0||shop.deceased()||seller.getTradingPlayer()!=null||SESSIONS.containsKey(seller.getUUID()))return net.minecraft.world.InteractionResult.PASS;
   Container shelf=stock(level,shop); if(shelf==null)return net.minecraft.world.InteractionResult.PASS;
   String owner=seller.getAttachedOrCreate(VillagerPlus.MEMORY).name; String title=KINDS[Math.floorMod(shop.kind(),3)]+(owner.isEmpty()?"":" von "+owner);
   Map<Item,Integer> counts=new LinkedHashMap<>();
   for(int i=0;i<shelf.getContainerSize();i++){var st=shelf.getItem(i);if(!st.isEmpty()&&st.getComponents().equals(new ItemStack(st.getItem()).getComponents()))counts.merge(st.getItem(),st.getCount(),Integer::sum);}
   var offers=new net.minecraft.world.item.trading.MerchantOffers();List<Item> items=new ArrayList<>();List<Integer> quantities=new ArrayList<>(),costs=new ArrayList<>();
   for(var e:counts.entrySet()) {
    int unit=Caravan.value(e.getKey());if(unit<=0)unit=2;
    int q=e.getKey()==Items.IRON_INGOT?1:Math.min(4,e.getValue());if(e.getValue()<q)continue;
    int cost=Math.max(1,(int)Math.ceil(unit*q*1.3/64.0));
    offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD,cost),new ItemStack(e.getKey(),q),e.getValue()/q,0,0.0F));
    items.add(e.getKey());quantities.add(q);costs.add(cost);
   }
   if(offers.isEmpty()) { VillageMessages.system(player, Component.literal((owner.isEmpty()?"Der Ladeninhaber":owner)+": Heute ist leider alles ausverkauft.")); return net.minecraft.world.InteractionResult.SUCCESS; }
   var original=new net.minecraft.world.item.trading.MerchantOffers();original.addAll(seller.getOffers());
   seller.getOffers().clear();seller.getOffers().addAll(offers);
   SESSIONS.put(seller.getUUID(),new Session(level.dimension(),ref.bell(),original,offers,new int[offers.size()],quantities.stream().mapToInt(Integer::intValue).toArray(),
    costs.stream().mapToInt(Integer::intValue).toArray(),items.toArray(new Item[0])));
   seller.setTradingPlayer(player);
   seller.openTradingScreen(player,Component.literal(title),1);
   return net.minecraft.world.InteractionResult.SUCCESS;
  });
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(Market::settleSessions);
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server->{
   for(var level:server.getAllLevels()) for(var en:new ArrayList<>(SESSIONS.entrySet())) if(en.getValue().dimension().equals(level.dimension())&&level.getEntity(en.getKey()) instanceof Villager v) endSession(level,v,en.getValue());
   SESSIONS.clear();
  });
 }
 /** Verbuchte Spielerkaeufe: Ware aus der Ladenkiste nehmen, Smaragde an den Inhaber, Journal und Umsatz. */
 private static void settleSessions(ServerLevel level) {
  if(SESSIONS.isEmpty()||level.getGameTime()%5!=0)return;
  for(var en:new ArrayList<>(SESSIONS.entrySet())) {
   Session s=en.getValue();if(!s.dimension().equals(level.dimension()))continue;
   if(!(level.getEntity(en.getKey()) instanceof Villager seller)||!seller.isAlive()){SESSIONS.remove(en.getKey());continue;}
   settle(level,seller,s);
   if(seller.getTradingPlayer()==null||seller.getTradingPlayer().isRemoved()) { endSession(level,seller,s);SESSIONS.remove(en.getKey()); }
  }
 }
 private static void settle(ServerLevel level,Villager seller,Session s) {
  var ref=Villages.find(level,seller);if(ref==null)return;var shop=shop(ref,seller.getUUID());if(shop==null)return;
  Container shelf=stock(level,shop);
  for(int i=0;i<s.offers().size();i++) {
   int uses=s.offers().get(i).getUses(),delta=uses-s.uses()[i];if(delta<=0)continue;s.uses()[i]=uses;
   if(shelf!=null) take(List.of(shelf),s.items()[i],delta*s.quantity()[i]);
   int emeralds=delta*s.cost()[i];
   while(emeralds>0){int n=Math.min(64,emeralds);if(!add(wallet(level,seller),new ItemStack(Items.EMERALD,n))){level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,seller.getX(),seller.getY()+.5,seller.getZ(),new ItemStack(Items.EMERALD,n)));}emeralds-=n;}
   var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.jobsDone+=delta;
   MarketData m=of(ref);save(ref,new MarketData(m.plaza(),m.shops(),m.nextPlan(),m.sales()+delta));
   Journal.add(level,ref,"Handel","Laden "+sm.name,"Spieler kauft "+delta*s.quantity()[i]+" "+new ItemStack(s.items()[i]).getHoverName().getString()+" für "+delta*s.cost()[i]+" Smaragd(e)",64L*delta);
   level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,seller.getX(),seller.getY()+1.5,seller.getZ(),5,.2,.2,.2,0);
  }
 }
 private static void endSession(ServerLevel level,Villager seller,Session s) {
  settle(level,seller,s);
  seller.getOffers().clear();seller.getOffers().addAll(s.original());
  if(seller.getTradingPlayer()!=null)seller.setTradingPlayer(null);
 }
 /** Customers visit during breaks, carrying real money and choosing goods they actually lack. */
 public static boolean visit(Ctx c) {
  if(!open(c.level()) || c.village()==null || shop(c.village(),c.v().getUUID())!=null || c.now()<c.v().getAttachedOrElse(VillagerPlus.LAST_SHOP,0L)+2400)return false;
  List<Container> funds=wallet(c.level(),c.v());if(count(funds,Items.EMERALD)==0)return false;
  for(MarketData.Shop s:of(c.village()).shops()) {
   if(s.deceased() || s.opened()==0 || !(c.level().getEntity(s.owner()) instanceof Villager seller) || seller.isBaby() || seller.isSleeping())continue;
   Container shelf=stock(c.level(),s);if(shelf==null)continue;
   for(Item item:goods(s.kind())) {
    if(count(funds,item)>=4 || JobUtil.count(shelf,a->a.is(item))<1)continue;
    if(s.kind()==0 && Wellbeing.of(c.level(),c.v(),c.mem()).satiety()>75)continue;
    int quantity=item==Items.IRON_INGOT?1:4;
    if(count(List.of(shelf),item)<quantity || !fits(c.v().getInventory(),new ItemStack(item,quantity)))continue;
    List<Container> proceeds=wallet(c.level(),seller);
    if(proceeds.stream().noneMatch(b->fits(b,new ItemStack(Items.EMERALD))))continue;
    c.setActivity("kauft bei "+seller.getName().getString());
    if(!JobUtil.approach(c,counter(s),2.5))return true;
    if(seller.position().distanceToSqr(c.v().position())>36)continue;
    take(funds,Items.EMERALD,1);take(List.of(shelf),item,quantity);add(proceeds,new ItemStack(Items.EMERALD));
    JobUtil.depositTo(c.v().getInventory(),new ItemStack(item,quantity));
    c.v().setAttached(VillagerPlus.LAST_SHOP,c.now()); c.mem().lastChat=c.now();c.mem().chats++;
    var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.lastChat=c.now();sm.jobsDone++;
    Journal.add(c.level(),c.village(),"Handel","Laden "+sm.name,c.mem().name+" kauft "+quantity+" "+new ItemStack(item).getHoverName().getString()+" bei "+sm.name+" für 1 Smaragd",64);
    MarketData m=of(c.village());save(c.village(),new MarketData(m.plaza(),m.shops(),m.nextPlan(),m.sales()+1));
    c.v().getLookControl().setLookAt(seller,30,30);seller.getLookControl().setLookAt(c.v(),30,30);
    Anim.hold(c,item);JobUtil.swing(c.v());JobUtil.swing(seller);
    c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.5,c.v().getZ(),5,.2,.2,.2,0);
    c.st().cooldownUntil=c.now()+60;return true;
   }
  }
  return false;
 }
 public static List<String> lines(VillageRef ref) {
  MarketData m=of(ref);List<String> out=new ArrayList<>();out.add("Marktplatz: "+m.plaza().map(BlockPos::toShortString).orElse("sucht freie Fläche mit Abstand zu Häusern"));
  out.add("Läden: "+m.shops().size()+" · Käufe der Einwohner: "+m.sales());
  for(var s:m.shops())out.add(KINDS[s.kind()]+": "+(s.opened()>0?"eröffnet":"Bau / Ausbildung")+" bei "+s.origin().toShortString());return out;
 }
}
