from pathlib import Path
r=Path('src/main/java/de/villagerplus');(r/'dev').mkdir(exist_ok=True)
(r/'dev/Checks090.java').write_text('''package de.villagerplus.dev;

import de.villagerplus.*;
import de.villagerplus.village.*;
import de.villagerplus.job.*;
import de.villagerplus.build.*;
import java.util.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;

/** Explicit development-only integration checks. Fixtures are confined to the named CTOV test world's remote test area. */
public final class Checks090 {
 private static int passed;
 private static final BlockPos A=new BlockPos(12000,201,12000), B=new BlockPos(12200,201,12000);
 private static void check(boolean ok,String label) {if(!ok)throw new IllegalStateException("CHECK FAILED: "+label);passed++;System.out.println("VP090 PASS "+label);}
 private static VillageRef fixture(ServerLevel l,BlockPos at) {
  l.getChunk(at);l.setBlock(at.below(),Blocks.STONE.defaultBlockState(),3);l.setBlock(at,Blocks.BELL.defaultBlockState(),3);
  var be=l.getBlockEntity(at); var data=new VillageData();be.setAttached(VillagerPlus.VILLAGE,data);
  BlockPos chest=at.east(3);l.setBlock(chest.below(),Blocks.STONE.defaultBlockState(),3);l.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
  data.chest=Optional.of(chest);Villages.noteChunk(l.getChunkAt(at));return new VillageRef(at,data,be);
 }
 private static Object call(Class<?> cls,String name,Class<?>[] signature,Object... args)throws Exception {var m=cls.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(null,args);}
 private static ItemStack card(ServerLevel l,ServerPlayer p,VillageRef ref){Network.giveCard(l,p,ref);for(int i=p.getInventory().getContainerSize()-1;i>=0;i--){var s=p.getInventory().getItem(i);if(s.is(de.villagerplus.block.ModItems.DORFKARTE))return s;}throw new IllegalStateException("No card");}
 private static void redeem(ServerLevel l,ServerPlayer p,BlockPos board,ItemStack card)throws Exception{call(Network.class,"redeem",new Class[]{ServerLevel.class,ServerPlayer.class,BlockPos.class,ItemStack.class},l,p,board,card);}
 private static int total(Container c,Item item){return JobUtil.count(c,s->s.is(item));}
 private static void lot(Container c,Item item,int total){c.clearContent();int i=0;while(total>0){int n=Math.min(64,total);c.setItem(i++,new ItemStack(item,n));total-=n;}}
 public static int run(CommandSourceStack source,String mode)throws Exception {
  if(!source.getLevel().getServer().getWorldData().getLevelName().equals("ctov"))throw new IllegalStateException("Checks only allowed in development world ctov");
  ServerLevel level=source.getLevel();ServerPlayer player=level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
  if(player==null){source.sendFailure(Component.literal("Connect the development client first."));return 0;}
  if(mode.equals("verify")){
   var be=level.getBlockEntity(A);check(be!=null,"persisted test bell");var ref=new VillageRef(A,be.getAttachedOrCreate(VillagerPlus.VILLAGE),be);
   check(Network.of(be).links().stream().anyMatch(l->l.bell().equals(B)),"network links survive restart");
   check(Market.of(ref).plaza().isPresent(),"plaza survives restart");check(!Market.of(ref).shops().isEmpty(),"shop career survives restart");check(Market.of(ref).sales()>0,"shop sales survive restart");
   source.sendSuccess(()->Component.literal("VP090 persistence PASS"),false);return 1;
  }
  passed=0;
  // Remote synthetic plateau. Existing CTOV villages and their buildings are outside this area.
  for(int x=-104;x<=104;x++)for(int z=-104;z<=104;z++) {
   BlockPos floor=A.offset(x,-1,z);level.getChunk(floor);level.setBlock(floor,Blocks.GRASS_BLOCK.defaultBlockState(),2);
   for(int y=0;y<=5;y++)level.setBlock(A.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
  }
  VillageRef a=fixture(level,A),b=fixture(level,B);Container ca=a.chest(level), cb=b.chest(level);
  Network.of(a.bellEntity());Network.of(b.bellEntity());
  check(Network.of(a.bellEntity()).partners().size()==3,"three deterministic partner towns");
  ItemStack link=card(level,player,a);redeem(level,player,B,link);
  check(Network.of(a.bellEntity()).links().size()==1&&Network.of(b.bellEntity()).links().size()==1,"reciprocal map link");
  ItemStack dup=card(level,player,a);int old=dup.getCount();redeem(level,player,B,dup);check(dup.getCount()==old&&Network.of(a.bellEntity()).links().size()==1,"duplicate map preserved");
  ItemStack self=card(level,player,a);old=self.getCount();redeem(level,player,A,self);check(self.getCount()==old,"self-link rejected");
  ItemStack cross=card(level,player,a);var tag=cross.get(DataComponents.CUSTOM_DATA).copyTag();tag.putString("dim","minecraft:the_nether");cross.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));old=cross.getCount();redeem(level,player,B,cross);check(cross.getCount()==old,"cross-dimension map rejected");
  long now=level.getGameTime();Network.act(level,player,A,"greet",0);
  check(Network.of(a.bellEntity()).links().get(0).relation()==15&&Network.of(b.bellEntity()).links().get(0).relation()==15,"greeting reciprocal relation");
  Network.act(level,player,B,"greet",0);check(Network.of(a.bellEntity()).links().get(0).relation()==15,"reverse direction cannot bypass cooldown");
  check(Network.moodBonus(level,a)==3&&Network.moodBonus(level,b)==3,"greeting mood available");
  Network.act(level,player,A,"route",0);check(Network.of(a.bellEntity()).links().get(0).route()&&Network.of(b.bellEntity()).links().get(0).route(),"route reciprocal");
  lot(ca,Items.IRON_INGOT,128);lot(cb,Items.BREAD,128);Network.act(level,player,A,"swap",0);
  check(total(ca,Items.IRON_INGOT)+total(cb,Items.IRON_INGOT)==128&&total(ca,Items.BREAD)+total(cb,Items.BREAD)==128,"swap conserves all goods");
  check(total(ca,Items.BREAD)>0&&total(cb,Items.IRON_INGOT)>0,"swap actually moves goods");
  check((128-total(ca,Items.IRON_INGOT))*24==total(ca,Items.BREAD)*6,"swap equal value");
  int before=total(ca,Items.BREAD);Network.act(level,player,B,"swap",0);check(total(ca,Items.BREAD)==before,"swap reciprocal cooldown");
  Network.save(a.bellEntity(),Network.of(a.bellEntity()).withCooldown(Network.pairKey(a,b,"swap"),0));Network.save(b.bellEntity(),Network.of(b.bellEntity()).withCooldown(Network.pairKey(a,b,"swap"),0));
  ca.clearContent();cb.clearContent();for(int i=0;i<ca.getContainerSize();i++)ca.setItem(i,new ItemStack(Items.IRON_INGOT,64));for(int i=0;i<cb.getContainerSize();i++)cb.setItem(i,new ItemStack(Items.BREAD,64));
  int iron=total(ca,Items.IRON_INGOT),bread=total(cb,Items.BREAD);Network.act(level,player,A,"swap",0);check(total(ca,Items.IRON_INGOT)==iron&&total(cb,Items.BREAD)==bread,"full inventory rejection is atomic");
  ca.clearContent();cb.clearContent();var named=new ItemStack(Items.IRON_INGOT,64);named.set(DataComponents.CUSTOM_NAME,Component.literal("Named test iron"));ca.setItem(0,named);lot(cb,Items.BREAD,128);
  Network.act(level,player,A,"swap",0);check(total(ca,Items.IRON_INGOT)==64&&total(cb,Items.IRON_INGOT)==0,"renamed goods cannot duplicate");
  var net=Network.of(a.bellEntity());var json=TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE,net).getOrThrow();check(TradeNetwork.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow().equals(net),"network codec round trip");
  ca.clearContent();ca.setItem(0,new ItemStack(Items.EMERALD,20));ca.setItem(1,new ItemStack(Items.BREAD,64));
  Villager seller=EntityTypes.VILLAGER.spawn(level,A.east(18),EntitySpawnReason.COMMAND);seller.addTag("vp090_fixture");seller.setInvulnerable(true);
  var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.name="Marta Markt";
  int beforeEmerald=total(ca,Items.EMERALD);call(Plaza.class,"build",new Class[]{ServerLevel.class,VillageRef.class,BlockPos.class,Villager.class},level,a,A.east(24),seller);
  check(total(a.chest(level),Items.EMERALD)==beforeEmerald&&total(ca,Items.EMERALD)==0,"plaza migration preserves inventory");
  check(Plaza.inside(level,a,A.east(24),0)&&!Plaza.inside(level,a,A.east(24).offset(7,0,7),0),"plaza octagonal footprint");
  check(!Sites.okRect(level,A.east(24),-2,2,-2,2,4),"houses cannot occupy plaza");
  var p=Palette.detect(level,A,seller,1);var shop=new MarketData.Shop(seller.getUUID(),A.east(37),Direction.WEST.get2DDataValue(),0,0,0,now,p.ids());
  var market=Market.of(a);Market.save(a,new MarketData(market.plaza(),List.of(shop),now+100000,0));
  var supplies=a.chest(level);supplies.clearContent();Map<Item,Integer> needed=new HashMap<>();for(Step st:Market.hut(shop,p))if(!st.free())needed.merge(st.item(),st.cost(),Integer::sum);
  int slot=0;for(var entry:needed.entrySet()){int n=entry.getValue();while(n>0){int k=Math.min(64,n);supplies.setItem(slot++,new ItemStack(entry.getKey(),k));n-=k;}}
  for(int i=0;i<Market.hut(shop,p).size()+10;i++) {
   var current=Market.shop(a,seller.getUUID());if(current.opened()>0)break;
   Step st=Market.hut(current,p).get(current.progress());seller.teleportTo(st.pos().getX()+.5,st.pos().getY()+2,st.pos().getZ()+.5);
   check(Market.work(new Ctx(level,seller,JobManager.state(seller),sm,a,now+i*30)),"shop construction step "+i);
  }
  var opened=Market.shop(a,seller.getUUID());check(opened.opened()>0,"shop built with real resources");
  BlockPos stock=(BlockPos)call(Market.class,"stockPos",new Class[]{MarketData.Shop.class},opened);var stockBox=HopperBlockEntity.getContainerAt(level,stock);
  check(stockBox!=null&&level.getBlockEntity(stock).getAttached(VillagerPlus.OWNER).equals(seller.getUUID()),"shop stock private ownership");
  // A real wholesale purchase and customer sale, with all money accounted for.
  lot(a.chest(level),Items.BREAD,64);seller.getInventory().clearContent();seller.getInventory().addItem(new ItemStack(Items.EMERALD,2));
  seller.teleportTo(opened.origin().getX()+.5,opened.origin().getY(),opened.origin().getZ()+.5);
  call(Market.class,"restock",new Class[]{Ctx.class,MarketData.Shop.class},new Ctx(level,seller,JobManager.state(seller),sm,a,now+5000),opened);
  check(total(stockBox,Items.BREAD)==8&&total(a.chest(level),Items.BREAD)==56&&total(a.chest(level),Items.EMERALD)==1&&total(seller.getInventory(),Items.EMERALD)==1,"wholesale conserves goods and emeralds");
  Villager customer=EntityTypes.VILLAGER.spawn(level,opened.origin().west(3),EntitySpawnReason.COMMAND);customer.addTag("vp090_fixture");customer.setInvulnerable(true);customer.getInventory().addItem(new ItemStack(Items.EMERALD,2));
  var cm=customer.getAttachedOrCreate(VillagerPlus.MEMORY);cm.lastMeal=1;
  boolean bought=Market.visit(new Ctx(level,customer,JobManager.state(customer),cm,a,now+10000));
  check(bought&&Market.of(a).sales()==1,"villager customer buys at shop");
  check(total(stockBox,Items.BREAD)==4&&total(customer.getInventory(),Items.BREAD)==4&&total(customer.getInventory(),Items.EMERALD)==1&&total(seller.getInventory(),Items.EMERALD)==2,"retail conserves goods and emeralds");
  check(MarketData.CODEC.parse(JsonOps.INSTANCE,MarketData.CODEC.encodeStart(JsonOps.INSTANCE,Market.of(a)).getOrThrow()).getOrThrow().equals(Market.of(a)),"market codec round trip");
  check(!Market.visit(new Ctx(level,customer,JobManager.state(customer),cm,a,now+10001)),"shopping cooldown persistent");
  // High-terracotta designs: every wall/gable remains at most half terracotta, across seeds.
  Palette terra=new Palette(Blocks.WHITE_TERRACOTTA,Blocks.PINK_TERRACOTTA,null,Blocks.OAK_PLANKS,Blocks.SPRUCE_PLANKS,Blocks.SPRUCE_STAIRS,Blocks.SPRUCE_SLAB,Blocks.GLASS_PANE,Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
  for(int seed=0;seed<64;seed++) {
   var design=Architect.design(Architect.Kind.HOUSE,seed);var steps=Architect.build(design,terra,A,Direction.NORTH);
   long t=steps.stream().filter(st->st.item()==Items.WHITE_TERRACOTTA||st.item()==Items.PINK_TERRACOTTA).count();
   long solid=steps.stream().filter(st->!st.free()&&st.pos().getY()>=A.getY()&&(st.item()==Items.WHITE_TERRACOTTA||st.item()==Items.PINK_TERRACOTTA||st.item()==Items.STONE_BRICKS)).count();
   check(t*2<=solid,"terracotta bound seed "+seed);
  }
  // Force a visible caravan on the remote plateau; it is left running for the navigation check.
  a.data().extra.nextCaravan=0;check(Caravan.spawn(level,a),"caravan spawned");
  var traders=level.getEntitiesOfClass(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class,new AABB(A).inflate(130),v->v.entityTags().contains("vp_caravan"));
  check(traders.size()==1,"one caravan trader");var trader=traders.get(0);
  double distance=Math.hypot(trader.getX()-A.getX(),trader.getZ()-A.getZ());check(distance>=69&&distance<=101,"caravan starts 70-100 blocks away");
  check(!trader.getOffers().isEmpty()&&trader.hasCustomName(),"partner offers and name");
  check(level.getEntitiesOfClass(net.minecraft.world.entity.animal.camel.TraderLlama.class,new AABB(trader.blockPosition()).inflate(8)).size()==2,"two trader llamas");
  var bd=de.villagerplus.net.BoardData.build(level,a,a.data().extra.board.orElse(A));check(bd.neighbors().size()==1&&bd.partners().size()==3,"neighbor menu payload");
  source.sendSuccess(()->Component.literal("VP090 PASS "+passed+" checks. Caravan running near 12000 201 12000; inspect arrival log."),false);
  return passed;
 }
}
''',encoding='utf-8')
p=r/'StatusCommand.java';s=p.read_text(encoding='utf-8');needle='\t\t\tdispatcher.register(Commands.literal("vpkaravane")';s=s.replace(needle,'''
            dispatcher.register(Commands.literal("vptest090").then(Commands.argument("mode",StringArgumentType.word()).executes(ctx -> {
                try { return de.villagerplus.dev.Checks090.run(ctx.getSource(),StringArgumentType.getString(ctx,"mode")); }
                catch(Exception ex) { ex.printStackTrace(); ctx.getSource().sendFailure(Component.literal(ex.toString())); return 0; }
            })));
''' +needle);p.write_text(s,encoding='utf-8')
