from pathlib import Path
p=Path('src/main/java/de/villagerplus/dev/Checks090.java');s=p.read_text(encoding='utf-8');
for color in ['white','pink']:
 s=s.replace('Blocks.TERRACOTTA.'+color+'()', 'net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:'+color+'_terracotta"))')
 s=s.replace('Items.TERRACOTTA.'+color+'()', 'net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:'+color+'_terracotta"))')
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/de/villagerplus/village/Caravan.java');s=p.read_text(encoding='utf-8').replace('if (at == null) return false;', 'if (at == null) { ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }');p.write_text(s,encoding='utf-8')
