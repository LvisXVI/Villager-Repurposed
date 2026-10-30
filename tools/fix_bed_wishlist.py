from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'build/Builder.java';s=p.read_text(encoding='utf-8');old='gaps.add(new Gap(k.tag() != null && (k.item() == null || k.tag().equals(ItemTags.BEDS)) ? null : k.item(), amount, e.getValue(), Component.literal(amount + "x ").append(name)));';new='''Item request=k.tag()!=null && k.tag().equals(ItemTags.BEDS)?Items.BED.white() : k.item()!=null?k.item():Items.OAK_DOOR;
                gaps.add(new Gap(request, amount, e.getValue(), Component.literal(amount + "x ").append(name)));''';assert old in s;s=s.replace(old,new);s=s.replace('de.villagerplus.village.Journal.add(c.level(),ref,"Bauen",c.mem().name,"Wohnhaus fertiggestellt",0);','');needle='\t\tswitch (d.planType) {\n\t\t\tcase Blueprints.HOUSE -> {';s=s.replace(needle,'\t\tde.villagerplus.village.Journal.add(c.level(),ref,"Bauen",c.mem().name,label(d.planType)+" fertiggestellt",0);\n'+needle,1);p.write_text(s,encoding='utf-8')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8');needle='  source.sendSuccess(()->Component.literal("VP090 PASS "+passed';idx=s.index(needle);s=s[:idx]+'''
  var bedStep=new Step(A.east(60),null,net.minecraft.tags.ItemTags.BEDS,1,false,null,block->block.defaultBlockState());
  boolean ready=(Boolean)call(Builder.class,"updateWanted",new Class[]{Ctx.class,VillageRef.class,String.class,List.class,Container.class,boolean.class},
   new Ctx(level,operator,JobManager.state(operator),operator.getAttachedOrCreate(VillagerPlus.MEMORY),a,now),a,"house",List.of(bedStep),a.chest(level),false);
  check(!ready&&a.data().wanted.stream().anyMatch(w->w.contains("bed#")),"missing tagged bed produces actionable material request");a.data().wanted.clear();
''' +s[idx:];p.write_text(s,encoding='utf-8')
