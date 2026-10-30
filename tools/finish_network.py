from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'client/BoardScreen.java'; s=p.read_text(encoding='utf-8'); s=s.replace('import de.villagerplus.net.AssignJob;', 'import de.villagerplus.net.AssignJob;\nimport de.villagerplus.net.NetworkAction;'); s=s.replace('"Details", "Expeditionen"}', '"Details", "Expeditionen", "Nachbarn"}'); s=s.replace('private int selected = -1;', '''private int selected = -1;
	private int neighbor = -1;
	private final List<Button> networkButtons = new ArrayList<>();
	private void networkVisibility() {
		for (int i = 0; i < networkButtons.size(); i++) {
			Button b = networkButtons.get(i);
			b.visible = tab == 4;
			b.active = i == 4 || (neighbor >= 0 && neighbor < data.neighbors().size());
		}
	}'''); s=s.replace('this.data = fresh;', 'this.data = fresh;\n\t\tif (neighbor >= fresh.neighbors().size()) neighbor = -1;\n\t\tnetworkVisibility();'); s=s.replace('(W - 16 - 3 * 4) / 4','(W - 16 - (TABS.length - 1) * 4) / TABS.length'); s=s.replace('selected = -1;\n\t\t\t}', 'selected = -1;\n\t\t\t\tnetworkVisibility();\n\t\t\t}'); s=s.replace('\t\taddRenderableWidget(Button.builder(Component.literal("Aktualisieren")', '''		networkButtons.clear();
		String[] actions = {"swap", "route", "invite", "greet", "card"};
		String[] captions = {"Waren tauschen", "Route an/aus", "Gäste einladen", "Gruß senden", "Dorfkarte mitnehmen"};
		for (int i = 0; i < actions.length; i++) {
			final String action = actions[i];
			Button b = Button.builder(Component.literal(captions[i]), click -> ClientPlayNetworking.send(new NetworkAction(data.board(), action, neighbor)))
				.bounds(i == 4 ? left + 84 : left + 10 + i * 100, i == 4 ? top + H - 24 : top + H - 49, i == 4 ? 144 : 97, 18).build();
			networkButtons.add(addRenderableWidget(b));
		}
		networkVisibility();
		addRenderableWidget(Button.builder(Component.literal("Aktualisieren")'''); s=s.replace('default -> lines(g, data.expeditions());','case 3 -> lines(g, data.expeditions());\n\t\t\tcase 4 -> neighbors(g);'); s=s.replace('\t\tif (tab != 1) {', '''		if (tab == 4) {
			int row = ((int) event.y() - (top + 111)) / 18 + scroll;
			if (event.x() >= left + 10 && event.x() < left + W - 10 && event.y() >= top + 111 && event.y() < top + 183 && row < data.neighbors().size()) {
				neighbor = row; networkVisibility(); return true;
			}
			return false;
		}
		if (tab != 1) {'''); pos=s.index('\n\tprivate void lines('); s=s[:pos]+'''
	private void neighbors(GuiGraphicsExtractor g) {
		label(g, "Partnerorte · Beziehung · letzter Besuch", left + 12, top + 51, INK);
		int y = top + 63;
		for (String row : data.partners()) {
			String[] p = row.split("\\\\|", -1);
			if (p.length >= 4) label(g, font.plainSubstrByWidth(p[0] + " · " + p[1] + " · " + p[2] + " · " + p[3], W - 24), left + 12, y, SOFT);
			y += 11;
		}
		label(g, "Nachbardörfer (anklicken)", left + 12, top + 99, INK);
		scroll = Math.min(scroll, Math.max(0, data.neighbors().size() - 4));
		y = top + 111;
		for (int i = scroll; i < Math.min(data.neighbors().size(), scroll + 4); i++) {
			String[] p = data.neighbors().get(i).split("\\\\|", -1);
			if (p.length < 7) continue;
			if (i == neighbor) g.fill(left + 9, y - 1, left + W - 9, y + 17, 0xFFE2C98A);
			label(g, font.plainSubstrByWidth(p[0] + " · " + p[1] + " · Beziehung " + p[4], W - 24), left + 12, y, INK);
			label(g, "Einwohner: " + p[3] + " · Route " + (p[5].equals("1") ? "aktiv" : "aus") + " · " + (p[6].equals("1") ? "erreichbar" : "nicht geladen"), left + 12, y + 9, SOFT);
			y += 18;
		}
		if (data.neighbors().isEmpty()) label(g, "Dorfkarte mitnehmen und an einer anderen Tafel benutzen.", left + 12, y, SOFT);
	}
''' + s[pos:]; p.write_text(s,encoding='utf-8')
p=r/'village/Wellbeing.java';s=p.read_text();s=s.replace('return new Status(satiety', 'VillageRef village = Villages.find(level, v);\n\t\tif (village != null) mood += Network.moodBonus(level, village);\n\t\treturn new Status(satiety');p.write_text(s)
p=r/'build/Palette.java';s=p.read_text();s=s.replace('if (!VANILLA_WALLS.contains(', 'if (houses(level, site != null ? site : bell, 8).size() >= 3 || !VANILLA_WALLS.contains(',1)
start=s.index('\t\tList<BlockPos> beds = level.getPoiManager()',s.index('public static int profileCode'));end=s.index('\n\t\tif (beds.size()',start);s=s[:start]+'\t\tList<BlockPos> beds = houses(level, bell, 12);'+s[end:]
start=s.index('\t\tList<BlockPos> beds = level.getPoiManager()',s.index('private static Palette layered'));end=s.index('\n\t\tMap<Block',start);s=s[:start]+'\t\tList<BlockPos> beds = houses(level, at, 8);'+s[end:]
start=s.index('\t\tList<BlockPos> beds = new java.util.ArrayList<>();');end=s.index('\n\t\tfor (BlockPos bed : beds)',start);s=s[:start]+'\t\tList<BlockPos> beds = houses(level, site != null ? site : bell, 8);'+s[end:]
pos=s.index('\n\tprivate static boolean terracotta');s=s[:pos]+'''
	/** Connected groups of beds within six blocks count as a single building. */
	public static List<BlockPos> houses(ServerLevel level, BlockPos center, int limit) {
		java.util.List<BlockPos> pending = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), center, 64, PoiManager.Occupancy.ANY)
			.map(p -> p.getPos().immutable()).sorted(java.util.Comparator.comparingDouble(p -> p.distSqr(center))).collect(Collectors.toCollection(java.util.ArrayList::new));
		java.util.List<BlockPos> result = new java.util.ArrayList<>();
		while (!pending.isEmpty() && result.size() < limit) {
			BlockPos first = pending.remove(0);
			result.add(first);
			java.util.List<BlockPos> group = new java.util.ArrayList<>(); group.add(first);
			for (int k = 0; k < group.size(); k++) {
				BlockPos current = group.get(k);
				for (var it = pending.iterator(); it.hasNext();) {
					BlockPos b = it.next();
					if (current.distSqr(b) <= 36) { group.add(b); it.remove(); }
				}
			}
		}
		return result;
	}
''' + s[pos:];p.write_text(s)
