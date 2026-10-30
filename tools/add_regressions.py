from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('.sorted(Comparator.comparingInt(v->v.isBaby()?0:', '.sorted(Comparator.comparingInt(v->v.isBaby()?(m.shops().isEmpty()?3:0):');p.write_text(s,encoding='utf-8')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8');needle='  source.sendSuccess(()->Component.literal("VP090 PASS "+passed';pos=s.index(needle);s=s[:pos]+'''
  Journal.add(level,a,"Handel","Audit partner","retained trade",64);
  for(int k=0;k<180;k++)Journal.add(level,a,"Befinden","Audit resident","status "+k,0);
  check(Journal.of(a).entries().stream().anyMatch(e->e.text().equals("retained trade")),"wellbeing cannot evict trade history");
  check(Journal.of(a).entries().stream().filter(e->e.category().equals("Befinden")).count()<=96,"wellbeing history bounded");
  check(Journal.Data.CODEC.parse(JsonOps.INSTANCE,Journal.Data.CODEC.encodeStart(JsonOps.INSTANCE,Journal.of(a)).getOrThrow()).getOrThrow().equals(Journal.of(a)),"journal codec round trip");
  for(int k=0;k<6;k++){var resident=EntityTypes.VILLAGER.spawn(level,A.east(20+k),EntitySpawnReason.COMMAND);resident.addTag("vp090_fixture");resident.setNoAi(true);}
  var original=Market.of(a);Market.save(a,new MarketData(original.plaza(),List.of(opened.died()),0,original.sales()));
  Market.plan(level,a,now+200000);var heir=Market.of(a).shops().get(0);
  check(!heir.deceased()&&!heir.owner().equals(seller.getUUID()),"shop succession selects new owner");
  check(level.getBlockEntity(stock).getAttached(VillagerPlus.OWNER).equals(heir.owner())&&total(stockBox,Items.BREAD)==4,"succession preserves private stock");
  var preserved=Market.of(a);var operator=(Villager)level.getEntity(heir.owner());
  var blocked=new MarketData.Shop(heir.owner(),A.east(55),Direction.WEST.get2DDataValue(),0,0,0,now,p.ids(),false,0);
  Market.save(a,new MarketData(original.plaza(),List.of(blocked),now+200000,original.sales()));
  Step first=Market.hut(blocked,p).get(0);level.setBlock(first.pos(),Blocks.DIAMOND_BLOCK.defaultBlockState(),3);operator.teleportTo(12052.5,201,12000.5);
  boolean busy=Market.work(new Ctx(level,operator,JobManager.state(operator),operator.getAttachedOrCreate(VillagerPlus.MEMORY),a,now+210000));
  check(!busy&&Market.shop(a,heir.owner()).retryAt()>now+210000,"blocked shop returns to normal work");
  check(level.getBlockState(first.pos()).is(Blocks.DIAMOND_BLOCK),"blocked construction preserves external blocks");
  level.setBlock(first.pos(),Blocks.GRASS_BLOCK.defaultBlockState(),3);Market.save(a,preserved);
''' +s[pos:];p.write_text(s,encoding='utf-8')
