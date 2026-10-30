from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/MarketData.java';s=p.read_text(encoding='utf-8').replace('List<String> palette)', 'List<String> palette, boolean deceased, long retryAt)');s=s.replace('forGetter(Shop::palette)).apply','forGetter(Shop::palette), Codec.BOOL.optionalFieldOf("deceased",false).forGetter(Shop::deceased), Codec.LONG.optionalFieldOf("retry_at",0L).forGetter(Shop::retryAt)).apply');s=s.replace('now,palette);','now,palette,deceased,0);');s=s.replace('\n }\n public static final Codec<MarketData>', '\n  public Shop retry(long until) { return new Shop(owner,origin,facing,kind,progress,opened,lastSeen,palette,deceased,until); }\n  public Shop died() { return new Shop(owner,origin,facing,kind,progress,opened,lastSeen,palette,true,retryAt); }\n }\n public static final Codec<MarketData>');p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('now,p.ids());','now,p.ids(),false,0);');s=s.replace('half+3)', 'half+5)').replace('<=half+5 &&','<=half+9 &&').replace('<=half+5);','<=half+9);');s=s.replace('int target=Math.min(3,people.size()/8); if(m.shops().size()>=target) return;', 'int target=Math.min(3,people.size()/8); if(m.shops().size()>=target && m.shops().stream().noneMatch(MarketData.Shop::deceased)) return;');s=s.replace('&& v.getVillagerData().profession().unwrapKey()', '&& !java.util.Set.of("farmer","mason","fletcher","nitwit").contains(de.villagerplus.Compat.jobKey(v.getVillagerData().profession()))\n    && v.getVillagerData().profession().unwrapKey()',1);s=s.replace('BlockPos center=m.plaza().get();','''for(int i=0;i<m.shops().size();i++) if(m.shops().get(i).deceased()) {
   var old=m.shops().get(i);var heir=new MarketData.Shop(owner.getUUID(),old.origin(),old.facing(),old.kind(),old.progress(),old.opened(),now,old.palette(),false,0);
   List<MarketData.Shop> replacement=new ArrayList<>(m.shops());replacement.set(i,heir);
   var be=level.getBlockEntity(stockPos(old));if(be!=null)be.setAttached(VillagerPlus.OWNER,owner.getUUID());
   save(ref,new MarketData(m.plaza(),List.copyOf(replacement),now+2400,m.sales()));return;
  }
  BlockPos center=m.plaza().get();''');s=s.replace('c.now()+200; return true;', 'c.now()+100; updateShop(c.village(),s.retry(c.now()+1200)); return false;',1);s=s.replace('Palette p=palette(c.level(),c.village(),s,c.v()); List<Step> steps=hut(s,p);','''if(s.deceased() || c.now()<s.retryAt()) return false;
  Palette p=palette(c.level(),c.village(),s,c.v()); List<Step> steps=hut(s,p);
  if(s.opened()>0 && stock(c.level(),s)==null) {
   if(!c.level().getBlockState(stockPos(s)).canBeReplaced()) { updateShop(c.village(),s.retry(c.now()+1200));return false; }
   for(int k=0;k<steps.size();k++) if(steps.get(k).pos().equals(stockPos(s))) {
    s=new MarketData.Shop(s.owner(),s.origin(),s.facing(),s.kind(),k,0,c.now(),s.palette(),false,0);updateShop(c.village(),s);break;
   }
  }''');s=s.replace('if(c.village()==null || shop(c.village(),c.v().getUUID())!=null', 'if(!open(c.level()) || c.village()==null || shop(c.village(),c.v().getUUID())!=null');s=s.replace('if(s.opened()==0 ||', 'if(s.deceased() || s.opened()==0 ||');pos=s.index('\n public static MarketData of');s=s[:pos]+'''
 public static boolean open(ServerLevel level) { long time=Math.floorMod(level.getOverworldClockTime(),24000L); return time>=de.villagerplus.Config.WORK_A_END && time<6000; }
 public static void register() {
  net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity,damage)->{
   if(entity instanceof Villager v && v.level() instanceof ServerLevel level) {
    var ref=Villages.find(level,v);if(ref==null)return;var s=shop(ref,v.getUUID());if(s!=null)updateShop(ref,s.died());
   }
  });
 }
''' +s[pos:];p.write_text(s,encoding='utf-8')
p=r/'village/Staffing.java';s=p.read_text(encoding='utf-8').replace('v -> v.isAlive() && !Guests.isGuest(v)', 'v -> v.isAlive() && !Guests.isGuest(v) && Market.shop(ref,v.getUUID()) == null');p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8').replace('phase == Phase.LUNCH && Math.floorMod(level.getOverworldClockTime(), 24000L) < 6000', 'phase == Phase.LUNCH && de.villagerplus.village.Market.open(level)');p.write_text(s,encoding='utf-8')
p=r/'VillagerPlus.java';s=p.read_text(encoding='utf-8').replace('JobManager.register();','de.villagerplus.village.Market.register();\n\t\tJobManager.register();');p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('build(level,ref,c,hint); return;', 'if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(c).inflate(8,5,8)).isEmpty()) continue;\n   build(level,ref,c,hint); return;');s=s.replace('(x==0 && z==0 ? Blocks.WATER : p.base()).defaultBlockState()', 'p.base().defaultBlockState()');s=s.replace('  for(int x:new int[]{-5,5})', '''  level.setBlock(c.above(),p.base().defaultBlockState(),3);
  level.setBlock(c.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
  level.setBlock(c.above(3),Blocks.LANTERN.defaultBlockState(),3);
  // A small roofed village shrine, built in the local timber and stone palette.
  for(int x=-2;x<=2;x++) for(int z=-1;z<=1;z++) {
   int height=4+(2-Math.abs(x));
   var roof=p.roofStair().defaultBlockState();
   if(p.roofStair() instanceof StairBlock)roof=roof.setValue(StairBlock.FACING,x<0?Direction.EAST:Direction.WEST);
   level.setBlock(c.offset(x,height,z),roof,3);
  }
  for(int x:new int[]{-1,1}) for(int z:new int[]{-1,1}) for(int y=1;y<4;y++)level.setBlock(c.offset(x,y,z),p.fence().defaultBlockState(),3);
  for(int x:new int[]{-5,5})''');p.write_text(s,encoding='utf-8')
p=r/'job/Routine.java';s=p.read_text(encoding='utf-8').replace('case BREAK -> {\n\t\t\t\treturn Social.tick(c);','case BREAK -> {\n\t\t\t\tif (gather(c)) return true;\n\t\t\t\treturn Social.tick(c);');s=s.replace('\n\tprivate static Container homeBox', '''
 private static boolean gather(Ctx c) {
  if(c.village()==null)return false;var plaza=de.villagerplus.village.Market.of(c.village()).plaza();if(plaza.isEmpty())return false;
  int side=Math.floorMod(c.v().getUUID().hashCode(),4);BlockPos place=plaza.get().relative(net.minecraft.core.Direction.from2DDataValue(side),4);
  c.setActivity("trifft Nachbarn am Dorfschrein");return !JobUtil.approach(c,place,2);
 }
\tprivate static Container homeBox''');s=s.replace('\n\t\t\t\treturn Social.tick(c);\n\t\t\t}\n\t\t\tcase EVENING', '\n\t\t\t\tif (gather(c)) return true;\n\t\t\t\treturn Social.tick(c);\n\t\t\t}\n\t\t\tcase EVENING',1);p.write_text(s,encoding='utf-8')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8').replace('now,p.ids());','now,p.ids(),false,0);');p.write_text(s,encoding='utf-8')
