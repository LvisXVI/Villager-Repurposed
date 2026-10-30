from pathlib import Path
p=Path('src/main/java/de/villagerplus/build/Builder.java');s=p.read_text(encoding='utf-8');s=s.replace('''		if (type.equals(Blueprints.HOUSE)) {
			d.popAtLastHouse = pop;
		}
''','');s=s.replace('d.houses++;','d.houses++;\n                d.popAtLastHouse = villagers(c.level(),ref.bell()).size();',1);p.write_text(s,encoding='utf-8')
