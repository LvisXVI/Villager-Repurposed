from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('hint.getRandom(),16,64','hint.getRandom(),12,80');s=s.replace('  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;','''  // Keep a real buffer around beds, including buildings whose roofs resemble open ground.
  if(level.getPoiManager().getInRange(h->h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),at,half+clearance+5,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
    .anyMatch(p->Math.abs(p.getPos().getX()-at.getX())<=half+clearance+2 && Math.abs(p.getPos().getZ()-at.getZ())<=half+clearance+2))return null;
  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;''');s=s.replace('if(!VillageStyle.natural(state) ||', 'boolean paving=state.is(Blocks.SANDSTONE)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.CUT_SANDSTONE)||state.is(Blocks.DIRT_PATH);\n   if((!VillageStyle.natural(state)&&!paving) ||');p.write_text(s,encoding='utf-8')
# Build completion events are retained in the village journal.
p=r/'build/Builder.java';s=p.read_text(encoding='utf-8');s=s.replace('d.houses++;','d.houses++;\n\t\t\t\tde.villagerplus.village.Journal.add(level,ref,"Bauen",c.mem().name,"Wohnhaus fertiggestellt",0);',1);p.write_text(s,encoding='utf-8')
# Probe the actual village using production site selection, no forced placements.
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8');s=s.replace('  if(mode.equals("village")) {','''  if(mode.equals("plaza")) {
   var ref=Villages.villageNear(level,player.blockPosition(),128);if(ref==null)return 0;
   var hint=level.getEntitiesOfClass(Villager.class,new AABB(ref.bell()).inflate(96)).stream().findFirst().orElse(null);if(hint==null)return 0;
   var m=Market.of(ref);Market.save(ref,new MarketData(m.plaza(),m.shops(),0,m.sales()));Plaza.ensure(level,ref,hint,level.getGameTime());
   source.sendSuccess(()->Component.literal("Plaza "+Market.of(ref).plaza()),false);return Market.of(ref).plaza().isPresent()?1:0;
  }
  if(mode.equals("village")) {''');p.write_text(s,encoding='utf-8')
