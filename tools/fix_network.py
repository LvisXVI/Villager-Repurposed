from pathlib import Path
r=Path('src/main/java/de/villagerplus')
for name in ['village/Caravan.java','village/Wellbeing.java','build/Palette.java','build/Architect.java','StatusCommand.java']:
 p=r/name; raw=p.read_bytes(); text=raw.decode('utf-8', errors='surrogateescape'); text=''.join(bytes([ord(c)-0xdc00]).decode('cp1252') if 0xdc80 <= ord(c) <= 0xdcff else c for c in text);p.write_text(text,encoding='utf-8')
p=r/'village/Caravan.java';s=p.read_text(encoding='utf-8').replace('Items.WHITE_WOOL','Items.WOOL.white()').replace('player.displayClientMessage(', 'player.sendSystemMessage(').replace('reward), false);','reward));').replace('Beziehung)."), false);','Beziehung)."));');p.write_text(s,encoding='utf-8')
p=r/'village/Network.java';s=p.read_text(encoding='utf-8');s=s.replace('if (pop != l.population())','if (pop != l.population() || l.populationDay() != day)');s=s.replace('n.withMood(Math.min(8, n.moodBonus() + 3)', 'n.withMood(Math.min(8, (now <= n.moodBonusUntil() ? n.moodBonus() : 0) + 3)');s=s.replace('\t\tJobUtil.take(box, s -> s.is(Items.EMERALD), 2);\n\t\tint n = Guests.invite', '\t\tint n = Guests.invite');s=s.replace('\t\tmarkCooldown(source, target, key, now + 12000L);','\t\tif (n == 0) { player.sendSystemMessage(Component.literal("Die Gäste fanden keinen Ankunftsplatz.")); return; }\n\t\tJobUtil.take(box, s -> s.is(Items.EMERALD), n);\n\t\tmarkCooldown(source, target, key, now + 12000L);',1)
a=s.index('\t\tlong cap = Math.min(512,');b=s.index('\n\t\tmarkCooldown(a, b, key',a)
s=s[:a]+'''		// Choose equal-value lots, capped at 64 items per direction. No rounding creates value.
		Item bestA = null, bestB = null; int countA = 0, countB = 0; long best = 0;
		for (var ea : surA.entrySet()) for (var eb : surB.entrySet()) {
			if (ea.getKey() == eb.getKey()) continue;
			int va = Caravan.value(ea.getKey()), vb = Caravan.value(eb.getKey());
			int x = va, y = vb; while (y != 0) { int t = x % y; x = y; y = t; }
			int na = vb / x, nb = va / x;
			int lots = Math.min(Math.min(64, ea.getValue()) / na, Math.min(64, eb.getValue()) / nb);
			long score = (long) lots * na * va;
			if (score > best) { best = score; bestA = ea.getKey(); bestB = eb.getKey(); countA = lots * na; countB = lots * nb; }
		}
		if (best == 0) { player.sendSystemMessage(Component.literal("Kein gleichwertiger Tausch aus den Überschüssen möglich.")); return; }
		List<ItemStack> giveA = List.of(new ItemStack(bestA, countA)), giveB = List.of(new ItemStack(bestB, countB));
		// Simulate the complete transaction on copies, sharing empty slots across all item types.
		List<Container> copyA = copies(sa), copyB = copies(sb);
		removeLots(copyA, giveA); removeLots(copyB, giveB);
		for (ItemStack stack : giveB) if (!insertAll(copyA, stack.copy())) { player.sendSystemMessage(Component.literal("Kein Platz im Zieldorf.")); return; }
		for (ItemStack stack : giveA) if (!insertAll(copyB, stack.copy())) { player.sendSystemMessage(Component.literal("Kein Platz im Zieldorf.")); return; }
		commit(sa, copyA); commit(sb, copyB);
		List<ItemStack> gotA = giveA, gotB = giveB;
''' + s[b:]
pos=s.index('\n\tprivate static String describe');s=s[:pos]+'''
	private static List<Container> copies(List<Container> stores) {
		List<Container> out = new ArrayList<>();
		for (Container c : stores) {
			var copy = new net.minecraft.world.SimpleContainer(c.getContainerSize());
			for (int i = 0; i < c.getContainerSize(); i++) copy.setItem(i, c.getItem(i).copy());
			out.add(copy);
		}
		return out;
	}
	private static void removeLots(List<Container> stores, List<ItemStack> lots) {
		for (ItemStack stack : lots) { int remaining = stack.getCount();
			for (Container c : stores) remaining -= JobUtil.take(c, s -> ItemStack.isSameItemSameComponents(s, stack), remaining);
		}
	}
	private static void commit(List<Container> stores, List<Container> copies) {
		for (int k = 0; k < stores.size(); k++) {
			Container c = stores.get(k), copy = copies.get(k);
			for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, copy.getItem(i).copy());
			c.setChanged();
		}
	}
''' + s[pos:];p.write_text(s,encoding='utf-8')
