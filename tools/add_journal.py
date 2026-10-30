from pathlib import Path
r=Path('src/main/java/de/villagerplus')
(r/'village/Journal.java').write_text('''package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/** Bounded persistent village history, trade ranking and per-resident wellbeing snapshots. */
public final class Journal {
 private Journal() {}
 public record Entry(long tick,String category,String actor,String text) {
  public static final Codec<Entry> CODEC=RecordCodecBuilder.create(i->i.group(Codec.LONG.fieldOf("tick").forGetter(Entry::tick),
   Codec.STRING.fieldOf("category").forGetter(Entry::category),Codec.STRING.fieldOf("actor").forGetter(Entry::actor),Codec.STRING.fieldOf("text").forGetter(Entry::text)).apply(i,Entry::new));
 }
 public record Data(List<Entry> entries,Map<String,Long> traded,Map<String,String> states,long nextSample) {
  public static final Codec<Data> CODEC=RecordCodecBuilder.create(i->i.group(Entry.CODEC.listOf().optionalFieldOf("entries",List.of()).forGetter(Data::entries),
   Codec.unboundedMap(Codec.STRING,Codec.LONG).optionalFieldOf("traded",Map.of()).forGetter(Data::traded),Codec.unboundedMap(Codec.STRING,Codec.STRING).optionalFieldOf("states",Map.of()).forGetter(Data::states),Codec.LONG.optionalFieldOf("next_sample",0L).forGetter(Data::nextSample)).apply(i,Data::new));
 }
 public static Data of(VillageRef ref) { Data d=ref.bellEntity().getAttached(VillagerPlus.JOURNAL);return d==null?new Data(List.of(),Map.of(),Map.of(),0):d; }
 private static void save(VillageRef ref,Data d){ref.bellEntity().setAttached(VillagerPlus.JOURNAL,d);ref.dirty();}
 public static void add(ServerLevel level,VillageRef ref,String category,String actor,String text,long value) {
  Data d=of(ref);List<Entry> entries=new ArrayList<>(d.entries());entries.add(new Entry(level.getGameTime(),category,actor,text));
  if(entries.size()>256)entries=new ArrayList<>(entries.subList(entries.size()-256,entries.size()));
  Map<String,Long> traded=new HashMap<>(d.traded());if(category.equals("Handel")&&value>0)traded.merge(actor,value,Long::sum);
  save(ref,new Data(List.copyOf(entries),Map.copyOf(traded),d.states(),d.nextSample()));
 }
 public static void sample(ServerLevel level,VillageRef ref,List<Villager> people,long now) {
  Data d=of(ref);if(now<d.nextSample())return;
  Map<String,String> states=new HashMap<>();save(ref,new Data(d.entries(),d.traded(),d.states(),now+1200));
  for(Villager v:people) {
   var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var w=Wellbeing.of(level,v,mem);
   String id=v.getUUID().toString(),state=JobManager.activityOf(v,level)+" · "+w.moodLabel()+" · Hunger "+(100-w.satiety())+"% · Energie "+w.energy()+"%";
   states.put(id,state);String previous=d.states().get(id);
   // Record meaningful changes without writing a log entry for every work tick.
   if(previous==null || !previous.substring(previous.indexOf(" · ")+3).equals(state.substring(state.indexOf(" · ")+3)))
    add(level,ref,"Befinden",mem.name.isEmpty()?"Bewohner":mem.name,state,0);
  }
  Data fresh=of(ref);save(ref,new Data(fresh.entries(),fresh.traded(),Map.copyOf(states),now+1200));
 }
 public static List<String> rows(ServerLevel level,VillageRef ref) {
  Data d=of(ref);List<String> out=new ArrayList<>();
  d.traded().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(12)
   .forEach(e->out.add("Handel|Statistik|"+e.getKey()+"|Handelsvolumen: "+e.getValue()+" Werteinheiten"));
  for(int i=d.entries().size()-1;i>=0 && out.size()<190;i--) {
   Entry e=d.entries().get(i);out.add(e.category()+"|Tag "+(e.tick()/24000)+"|"+e.actor()+"|"+e.text().replace('|','/'));
  }
  return out;
 }
}
''',encoding='utf-8')
p=r/'VillagerPlus.java';s=p.read_text(encoding='utf-8');pos=s.index('\n\t@Override');s=s[:pos]+'''
 public static final AttachmentType<de.villagerplus.village.Journal.Data> JOURNAL = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"journal"),de.villagerplus.village.Journal.Data.CODEC);
''' +s[pos:];p.write_text(s,encoding='utf-8')
p=r/'village/Network.java';s=p.read_text(encoding='utf-8').replace('card.shrink(1);','card.shrink(1);\n\t\tJournal.add(level,source,"Handel",villageName(target),"Dorfverbindung eröffnet",0);\n\t\tJournal.add(level,target,"Handel",villageName(source),"Dorfverbindung eröffnet",0);');s=s.replace('bumpRelation(source, target, 5);','bumpRelation(source, target, 5);\n\t\tJournal.add((ServerLevel)player.level(),source,"Befinden",villageName(target),"Gruß erhalten: Stimmung +3",0);');s=s.replace('markCooldown(a, b, key, now + 12000L);','Journal.add(level,a,"Handel",villageName(b),"Tausch: "+describe(gotA)+" gegen "+describe(gotB),best);\n\t\tJournal.add(level,b,"Handel",villageName(a),"Tausch: "+describe(gotB)+" gegen "+describe(gotA),best);\n\t\tmarkCooldown(a, b, key, now + 12000L);');p.write_text(s,encoding='utf-8')
p=r/'village/Caravan.java';s=p.read_text(encoding='utf-8').replace('Component msg = Component.literal("Die Karawane aus "', 'Journal.add(level,ref,"Handel",town,"Karawane: verkauft ["+String.join(", ",sold)+"], gekauft ["+String.join(", ",bought)+"]",units);\n\t\tComponent msg = Component.literal("Die Karawane aus "');p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('sm.jobsDone++;', 'sm.jobsDone++;\n    Journal.add(c.level(),c.village(),"Handel",c.mem().name,c.mem().name+" kauft "+quantity+" "+new ItemStack(item).getHoverName().getString()+" bei "+sm.name+" für 1 Smaragd",64);');s=s.replace('if(n==steps.size()) {','if(n==steps.size()) {\n    Journal.add(c.level(),c.village(),"Bauen",c.mem().name,KINDS[s.kind()]+" eröffnet",0);',1);p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('  org.slf4j.LoggerFactory', '  Journal.add(level,ref,"Bauen","Dorfgemeinschaft","Dorfschrein und Plaza errichtet bei "+c.toShortString(),0);\n  org.slf4j.LoggerFactory',1);p.write_text(s,encoding='utf-8')
p=r/'net/BoardData.java';s=p.read_text(encoding='utf-8').replace('List<String> neighbors, List<String> partners)', 'List<String> neighbors, List<String> partners, List<String> journal)');s=s.replace('STRINGS, BoardData::partners,','STRINGS, BoardData::partners,\n\t\tSTRINGS, BoardData::journal,');s=s.replace('(b, v, s, c, m, ba, r, d, e, nb, pa) -> new BoardData(b, v, s, c, m, ba, r, d, e, nb, pa)', '(b, v, s, c, m, ba, r, d, e, nb, pa, j) -> new BoardData(b, v, s, c, m, ba, r, d, e, nb, pa, j)');s=s.replace('people.sort(', 'de.villagerplus.village.Journal.sample(level,ref,people,level.getGameTime());\n\t\tpeople.sort(',1);s=s.replace('de.villagerplus.village.Network.partnerRows(level, ref));','de.villagerplus.village.Network.partnerRows(level, ref),de.villagerplus.village.Journal.rows(level,ref));');p.write_text(s,encoding='utf-8')
p=r/'village/Villages.java';s=p.read_text(encoding='utf-8').replace('Market.plan(level, ref, now);','Market.plan(level, ref, now);\n\t\tJournal.sample(level,ref,level.getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(ref.bell()).inflate(64),v->v.isAlive()&&!Guests.isGuest(v)),now);');p.write_text(s,encoding='utf-8')
p=r/'client/BoardScreen.java';s=p.read_text(encoding='utf-8').replace('private static final int W = 420','private static final int W = 460').replace('private static final int H = 252','private static final int H = 280').replace('"Expeditionen", "Nachbarn"','"Reisen", "Nachbarn", "Dorfjournal"');s=s.replace('private int neighbor = -1;','private int neighbor = -1;\n\tprivate int category;\n\tprivate int person = -1;\n\tprivate Button filterCategory, filterPerson;');s=s.replace('private void networkVisibility() {','private void networkVisibility() {\n\t\tif (filterCategory != null) filterCategory.visible = tab == 5;\n\t\tif (filterPerson != null) filterPerson.visible = tab == 5;');s=s.replace('networkButtons.clear();','''filterCategory = addRenderableWidget(Button.builder(Component.literal("Kategorie: Alle"),b->{category=(category+1)%4;scroll=0;b.setMessage(Component.literal("Kategorie: "+new String[]{"Alle","Handel","Befinden","Bauen"}[category]));}).bounds(left+10,top+50,150,18).build());
        filterPerson = addRenderableWidget(Button.builder(Component.literal("Bewohner: Alle"),b->{person++;if(person>=data.residents().size())person=-1;scroll=0;b.setMessage(Component.literal("Bewohner: "+(person<0?"Alle":data.residents().get(person).split("\\\\|")[0])));}).bounds(left+166,top+50,W-176,18).build());
        networkButtons.clear();''');s=s.replace('case 4 -> neighbors(g);','case 4 -> neighbors(g);\n\t\t\tcase 5 -> journal(g);');pos=s.index('\n\tprivate void lines(');s=s[:pos]+'''
 private void journal(GuiGraphicsExtractor g) {
  String wanted = new String[]{"Alle","Handel","Befinden","Bauen"}[category];
  String resident=person>=0 && person<data.residents().size()?data.residents().get(person).split("\\\\|")[0]:"";
  List<String> rows=new ArrayList<>();
  for(String row:data.journal()) {
   String[] p=row.split("\\\\|",4);if(p.length<4 || (category!=0&&!p[0].equals(wanted)) || (!resident.isEmpty()&&!p[2].equals(resident)&&!p[3].contains(resident)))continue;
   rows.add(p[1]+" · "+p[0]+" · "+p[2]);String rest=p[3];
   while(!rest.isEmpty()) {String line=font.plainSubstrByWidth(rest,W-30);if(line.isEmpty())break;rows.add("  "+line);rest=rest.substring(line.length());} rows.add("");
  }
  int visible=(H-110)/11;scroll=Math.min(scroll,Math.max(0,rows.size()-visible));int y=top+74;
  for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++){label(g,rows.get(i),left+12,y,rows.get(i).startsWith("  ")?SOFT:INK);y+=11;}
  if(rows.isEmpty())label(g,"Noch keine passenden Ereignisse aufgezeichnet.",left+12,y,SOFT);
 }
''' +s[pos:];p.write_text(s,encoding='utf-8')
