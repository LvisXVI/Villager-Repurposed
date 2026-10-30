from pathlib import Path
import json
r=Path('src/main/java/de/villagerplus')
p=r/'Config.java';s=p.read_text(encoding='utf-8').replace('MAX_VILLAGERS = 40','MAX_VILLAGERS = 128');p.write_text(s,encoding='utf-8')
p=r/'village/Journal.java';s=p.read_text(encoding='utf-8').replace('out.size()<190','out.size()<390');p.write_text(s,encoding='utf-8')
p=r/'net/BoardData.java';s=p.read_text(encoding='utf-8').replace('private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRINGS', 'private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> JOURNAL_STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(400));\n\tprivate static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRINGS').replace('STRINGS, BoardData::journal','JOURNAL_STRINGS, BoardData::journal');p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('new net.minecraft.world.phys.AABB(c).inflate(8,5,8)','new net.minecraft.world.phys.AABB(c).inflate(13,8,13)');p.write_text(s,encoding='utf-8')
p=r/'build/Builder.java';s=p.read_text(encoding='utf-8');needle='''		if (d.popAtLastHouse == 0) {
			d.popAtLastHouse = pop;''';s=s.replace(needle,'''		if (d.popAtLastHouse == 0) {
            var net=de.villagerplus.village.Network.of(ref.bellEntity());
            de.villagerplus.village.Network.save(ref.bellEntity(),net.withCooldown("population_settle_until",c.now()+1200));
			d.popAtLastHouse = pop;''',1);s=s.replace('boolean grew = pop > d.popAtLastHouse;', '''long settle=de.villagerplus.village.Network.of(ref.bellEntity()).cooldowns().getOrDefault("population_settle_until",0L);
            if(c.now()<settle && pop>d.popAtLastHouse){d.popAtLastHouse=pop;ref.dirty();}
            boolean grew = c.now()>=settle && pop > d.popAtLastHouse;''');p.write_text(s,encoding='utf-8')
# Fix missing particle references in pre-existing profession badge overlays.
for p in Path('src/main/resources/assets/villagerplus/models/block').glob('dorfschild_stars_*.json'):
 d=json.loads(p.read_text(encoding='utf-8'));d['textures']['particle']=d['textures'].get('stars','minecraft:block/oak_planks');p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
