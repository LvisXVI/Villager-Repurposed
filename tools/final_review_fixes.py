from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/Journal.java';s=p.read_text(encoding='utf-8').replace('if(entries.size()>256)entries=new ArrayList<>(entries.subList(entries.size()-256,entries.size()));','''int limit=category.equals("Befinden")?96:128;
  int matches=0;for(Entry e:entries)if(e.category().equals(category))matches++;
  for(var it=entries.iterator();matches>limit&&it.hasNext();)if(it.next().category().equals(category)){it.remove();matches--;}''').replace('Map<String,String> states=new HashMap<>();','Map<String,String> states=new HashMap<>(d.states());');a=s.index('   states.put(id,state);String previous=');b=s.index('\n  }\n  Data fresh',a);s=s[:a]+'''   String signature=de.villagerplus.job.Phase.of(level.getOverworldClockTime()).name()+"/"+(w.mood()/20)+"/"+(w.satiety()/25)+"/"+(w.energy()/25)+"/"+(w.health()/25);
   String previous=states.put(id,signature);
   if(!signature.equals(previous))add(level,ref,"Befinden",mem.name.isEmpty()?"Bewohner":mem.name,state,0);
''' +s[b:];s=s.replace('  Data fresh=of(ref);save', '  if(states.size()>512) {var keys=new java.util.HashSet<String>();for(Villager v:people)keys.add(v.getUUID().toString());states.keySet().removeIf(k->!keys.contains(k));}\n  Data fresh=of(ref);save');p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('Plaza.site(level,candidate,2,2)','Plaza.site(level,candidate,4,5)').replace('"Handel",c.mem().name,c.mem().name+', '"Handel","Laden "+sm.name,c.mem().name+').replace('" für 1 Smaragd",0);','" für 1 Smaragd",64);');p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('site(level,at,7,3)','site(level,at,7,5)').replace('  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;','  VillageRef context=Villages.villageNear(level,at,128);\n  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;').replace('BlockPos col=at.offset(x,0,z); if(!level.hasChunkAt(col))', 'BlockPos col=at.offset(x,0,z);\n   if(context!=null && (Math.abs(x)>half || Math.abs(z)>half) && inside(level,context,col,0))continue;\n   if(!level.hasChunkAt(col))');p.write_text(s,encoding='utf-8')
p=r/'village/Roads.java';s=p.read_text(encoding='utf-8').replace('   for(int side=-1;side<=1;side++) {','''   boolean loaded=true;for(int side=-1;side<=1;side++) {
    BlockPos adjacent=new BlockPos(x+(Math.abs(dz)>=Math.abs(dx)?side:0),a.getY(),z+(Math.abs(dx)>Math.abs(dz)?side:0));
    if(!level.hasChunkAt(adjacent)){loaded=false;break;}
   }
   if(!loaded)break;
   for(int side=-1;side<=1;side++) {''');p.write_text(s,encoding='utf-8')
