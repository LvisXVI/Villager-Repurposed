from pathlib import Path
import re
r=Path('src/main/java/de/villagerplus')
# Normalize excess line breaks introduced by the original mixed line endings.
for name in ['village/Caravan.java','village/Network.java','build/Architect.java','build/Palette.java','village/Wellbeing.java','StatusCommand.java']:
 p=r/name;s=p.read_text(encoding='utf-8');s=re.sub(r'\n{3,}','\n\n',s);p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('center.relative(face,11)','center.relative(face,13)');p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8').replace('if (phase.routine && phase != Phase.MORNING', 'if (phase == Phase.LUNCH && Math.floorMod(level.getOverworldClockTime(), 24000L) < 6000 && de.villagerplus.village.Market.work(ctx)) { Anim.apply(v, st, now); return; }\n\t\tif (phase.routine && phase != Phase.MORNING');p.write_text(s,encoding='utf-8')
p=Path('gradle.properties');s=p.read_text().replace('mod_version=0.8.1','mod_version=0.9.0');p.write_text(s)
