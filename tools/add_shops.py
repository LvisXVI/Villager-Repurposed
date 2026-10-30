from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/MarketData.java';s=p.read_text(encoding='utf-8').replace('long opened, long lastSeen)', 'long opened, long lastSeen, List<String> palette)');s=s.replace('Codec.LONG.optionalFieldOf("last_seen",0L).forGetter(Shop::lastSeen)', 'Codec.LONG.optionalFieldOf("last_seen",0L).forGetter(Shop::lastSeen), Codec.STRING.listOf().optionalFieldOf("palette",List.of()).forGetter(Shop::palette)');s=s.replace('now : opened,now);','now : opened,now,palette);');p.write_text(s,encoding='utf-8')
(r/'village/Market.java').write_text('''package de.villagerplus.village;

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
 public static MarketData of(VillageRef ref) { MarketData m=ref.bellEntity().getAttached(VillagerPlus.MARKET); return m==null?MarketData.empty():m; }
 public static void save(VillageRef ref,MarketData m) { ref.bellEntity().setAttached(VillagerPlus.MARKET,m); ref.dirty(); }
 public static MarketData.Shop shop(VillageRef ref,UUID owner) { return of(ref).shops().stream().filter(s->s.owner().equals(owner)).findFirst().orElse(null); }
 public static String role(VillageRef ref,Villager v) { var s=shop(ref,v.getUUID()); return s==null?"":(v.isBaby()?"Ladenlehrling": "Ladeninhaber")+" · "+KINDS[Math.floorMod(s.kind(),3)]; }
 public static boolean reserved(ServerLevel level,BlockPos pos,int half) {
  VillageRef ref=Villages.villageNear(level,pos,96); if(ref==null) return false;
  if(Plaza.inside(level,ref,pos,half+3)) return true;
  return of(ref).shops().stream().anyMatch(s->Math.abs(s.origin().getX()-pos.getX())<=half+5 && Math.abs(s.origin().getZ()-pos.getZ())<=half+5);
 }
 public static void plan(ServerLevel level,VillageRef ref,long now) {
  MarketData m=of(ref); if(m.plaza().isEmpty() || now<m.nextPlan()) return;
  save(ref,new MarketData(m.plaza(),m.shops(),now+2400,m.sales()));
  List<Villager> people=level.getEntitiesOfClass(Villager.class,new AABB(ref.bell()).inflate(64),v->v.isAlive()&&!Guests.isGuest(v));
  int target=Math.min(3,people.size()/8); if(m.shops().size()>=target) return;
  List<Villager> free=people.stream().filter(v->shop(ref,v.getUUID())==null && !Expedition.isAway(v)
    && v.getVillagerData().profession().unwrapKey().map(k->k.identifier().getNamespace().equals("minecraft")).orElse(false))
    .sorted(Comparator.comparingInt(v->v.isBaby()?0:(v.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath().equals("none")).orElse(false)?1:2))).toList();
  if(free.isEmpty()) return;
  // Prefer a child or an unemployed adult; preserve essential workers in small villages.
  Villager owner=free.get(0);
  if(!owner.isBaby() && !owner.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath().equals("none")).orElse(false)
    && people.size()<16) return;
  BlockPos center=m.plaza().get();
  for(Direction face:Direction.Plane.HORIZONTAL) {
   BlockPos candidate=center.relative(face,11); BlockPos site=Plaza.site(level,candidate,2,2);
   if(site==null || m.shops().stream().anyMatch(s->s.origin().distSqr(site)<64)) continue;
   Palette p=Palette.detect(level,ref.bell(),owner,site.asLong(),site);
   var entry=new MarketData.Shop(owner.getUUID(),site,face.getOpposite().get2DDataValue(),m.shops().size()%3,0,0,now,p.ids());
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
  out.add(Step.block(stockPos(s),Blocks.CHEST));
  out.add(Step.free(at(s,0,0,2),Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true)));
  return out;
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
  Palette p=palette(c.level(),c.village(),s,c.v()); List<Step> steps=hut(s,p);
  if(s.progress()<steps.size()) {
   c.setActivity("baut den eigenen "+KINDS[s.kind()]);
   Step st=steps.get(s.progress());
   if(!JobUtil.approach(c,st.pos().above(),4)) return true;
   var current=c.level().getBlockState(st.pos());
   var desired=st.make().apply(st.item()==null?Blocks.AIR:Block.byItem(st.item()));
   if(!current.equals(desired)) {
    if(!current.isAir() && !current.canBeReplaced() && !VillageStyle.natural(current)) { c.st().cooldownUntil=c.now()+200; return true; }
    if(!st.free()) {
     List<Container> stores=Balance.storage(c.level(),c.village());
     if(count(stores,st.item())<st.cost()) {
      String wish=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item())+"#32";
      if(c.village().data().wanted.size()<32 && !c.village().data().wanted.contains(wish)) { c.village().data().wanted.add(wish);c.village().dirty(); }
      c.setActivity("wartet auf Ladenmaterial"); c.st().cooldownUntil=c.now()+100; return true;
     }
     take(stores,st.item(),st.cost());
    }
    if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(st.pos()),e->e.isAlive()).isEmpty()) return true;
    c.level().setBlock(st.pos(),desired,3); JobUtil.swing(c.v()); Anim.hold(c,Items.STONE_AXE);
   }
   int n=s.progress()+1; updateShop(c.village(),s.progress(n,c.now(),n==steps.size())); c.mem().jobsDone++;
   if(n==steps.size()) {
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
   if(count(List.of(communal),item)<size+16)continue;
   // Owner buys stock with private money, the community receives payment.
   take(funds,Items.EMERALD,1);take(List.of(communal),item,size);
   JobUtil.depositTo(communal,new ItemStack(Items.EMERALD));JobUtil.depositTo(shelf,new ItemStack(item,size));
   Anim.hold(c,item);JobUtil.swing(c.v());return;
  }
 }
 /** Customers visit during breaks, carrying real money and choosing goods they actually lack. */
 public static boolean visit(Ctx c) {
  if(c.village()==null || shop(c.village(),c.v().getUUID())!=null || c.now()<c.v().getAttachedOrElse(VillagerPlus.LAST_SHOP,0L)+2400)return false;
  List<Container> funds=wallet(c.level(),c.v());if(count(funds,Items.EMERALD)==0)return false;
  for(MarketData.Shop s:of(c.village()).shops()) {
   if(s.opened()==0 || !(c.level().getEntity(s.owner()) instanceof Villager seller) || seller.isBaby() || seller.isSleeping())continue;
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
''',encoding='utf-8')
p=r/'VillagerPlus.java';s=p.read_text(encoding='utf-8');pos=s.index('\n\t@Override');s=s[:pos]+'''
 public static final AttachmentType<de.villagerplus.village.MarketData> MARKET = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"market"),de.villagerplus.village.MarketData.CODEC);
 public static final AttachmentType<Long> LAST_SHOP = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"last_shop"),com.mojang.serialization.Codec.LONG);
''' +s[pos:];p.write_text(s,encoding='utf-8')
p=r/'village/Villages.java';s=p.read_text(encoding='utf-8').replace('BlockPos existing = ref.chestPos(level);','Plaza.ensure(level, ref, who, now);\n\t\tMarket.plan(level, ref, now);\n\t\tBlockPos existing = ref.chestPos(level);',1);p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8').replace('if (phase.routine) {','if (phase.routine && phase != Phase.MORNING && de.villagerplus.village.Market.visit(ctx)) { Anim.apply(v, st, now); return; }\n\t\tif (phase.routine) {',1).replace('boolean busy = first ?','boolean busy = de.villagerplus.village.Market.work(ctx) || (first ?').replace('job.secondary(ctx) || job.primary(ctx);','job.secondary(ctx) || job.primary(ctx));',1);p.write_text(s,encoding='utf-8')
p=r/'village/Sites.java';s=p.read_text(encoding='utf-8').replace('int headroom) {\n\t\tfor (int dx', 'int headroom) {\n\t\tif (Market.reserved(level, c, Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minZ), Math.abs(maxZ))))) return false;\n\t\tfor (int dx',1);p.write_text(s,encoding='utf-8')
p=r/'village/VillagerInfo.java';s=p.read_text(encoding='utf-8').replace('if (vr != null) {','if (vr != null) {\n\t\t\tString role = Market.role(vr,v); if (!role.isEmpty()) out.add("Marktrolle|"+role+"|-1");');p.write_text(s,encoding='utf-8')
p=r/'net/BoardData.java';s=p.read_text(encoding='utf-8').replace('String profKey =','String marketRole = de.villagerplus.village.Market.role(ref,v); if (!marketRole.isEmpty()) job = marketRole;\n\t\t\tString profKey =');p.write_text(s,encoding='utf-8')
p=r/'village/VillageStats.java';s=p.read_text(encoding='utf-8');idx=s.rfind('return '); print('VillageStats return:',s[idx:idx+100]);
