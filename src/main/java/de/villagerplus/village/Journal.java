package de.villagerplus.village;

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
  int limit=category.equals("Befinden")?96:128;
  int matches=0;for(Entry e:entries)if(e.category().equals(category))matches++;
  for(var it=entries.iterator();matches>limit&&it.hasNext();)if(it.next().category().equals(category)){it.remove();matches--;}
  Map<String,Long> traded=new HashMap<>(d.traded());if(category.equals("Handel")&&value>0)traded.merge(actor,value,Long::sum);
  save(ref,new Data(List.copyOf(entries),Map.copyOf(traded),d.states(),d.nextSample()));
 }
 public static void sample(ServerLevel level,VillageRef ref,List<Villager> people,long now) {
  Data d=of(ref);if(now<d.nextSample())return;
  Map<String,String> states=new HashMap<>(d.states());save(ref,new Data(d.entries(),d.traded(),d.states(),now+1200));
  for(Villager v:people) {
   var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var w=Wellbeing.of(level,v,mem);
   String id=v.getUUID().toString(),state=JobManager.activityOf(v,level)+" · "+w.moodLabel()+" · Hunger "+(100-w.satiety())+"% · Energie "+w.energy()+"%";
   String signature=de.villagerplus.job.Phase.of(level.getOverworldClockTime()).name()+"/"+(w.mood()/20)+"/"+(w.satiety()/25)+"/"+(w.energy()/25)+"/"+(w.health()/25);
   String previous=states.put(id,signature);
   if(!signature.equals(previous))add(level,ref,"Befinden",mem.name.isEmpty()?"Bewohner":mem.name,state,0);

  }
  if(states.size()>512) {var keys=new java.util.HashSet<String>();for(Villager v:people)keys.add(v.getUUID().toString());states.keySet().removeIf(k->!keys.contains(k));}
  Data fresh=of(ref);save(ref,new Data(fresh.entries(),fresh.traded(),Map.copyOf(states),now+1200));
 }
 public static List<String> rows(ServerLevel level,VillageRef ref) {
  Data d=of(ref);List<String> out=new ArrayList<>();
  d.traded().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(12)
   .forEach(e->out.add("Handel|Statistik|"+e.getKey()+"|Handelsvolumen: "+e.getValue()+" Werteinheiten"));
  for(int i=d.entries().size()-1;i>=0 && out.size()<390;i--) {
   Entry e=d.entries().get(i);out.add(e.category()+"|Tag "+(e.tick()/24000)+"|"+e.actor()+"|"+e.text().replace('|','/'));
  }
  return out;
 }
}
