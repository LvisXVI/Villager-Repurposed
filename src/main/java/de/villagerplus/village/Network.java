package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Verbindungen zwischen echten Doerfern: Dorfkarte mitnehmen, an der Tafel eines anderen Dorfes einloesen, Aktionen im
 * Nachbarn-Menue. Alle Pruefungen laufen auf dem Server; der Client schickt nur Tafelposition, Aktion und einen Index in die
 * gespeicherte Link-Liste (keine Zielkoordinaten).
 */
public final class Network {
	public static final String ACTION_SWAP = "swap";
	public static final String ACTION_ROUTE = "route";
	public static final String ACTION_INVITE = "invite";
	public static final String ACTION_GREET = "greet";
	public static final String ACTION_APPROVE = "approve";
	public static final String ACTION_CARD = "card";
	private static final double MAX_DISTANCE = 3000.0;
	private static final String TREASURY_EMERALDS = "village/treasury/minecraft:emerald";

	private Network() {
	}

	// --- Zugriff ----------------------------------------------------------------------------------

	public static TradeNetwork of(BlockEntity bell) {
		TradeNetwork n = bell.getAttached(VillagerPlus.NETWORK);
		if (n == null || n.partners().isEmpty()) {
			TradeNetwork generated = TradeNetwork.create(bell.getBlockPos());
			n = n == null ? generated : n.withPartners(generated.partners());
			bell.setAttached(VillagerPlus.NETWORK, n);
		}
		return n;
	}

	public static void save(BlockEntity bell, TradeNetwork n) {
		bell.setAttached(VillagerPlus.NETWORK, n);
		bell.setChanged();
	}

	/** Persistent village cash, separate from the material-only building depot. */
	public static long treasuryEmeralds(VillageRef ref) {
		return Math.max(0L, of(ref.bellEntity()).tradeBalance().getOrDefault(TREASURY_EMERALDS, 0L));
	}

	/** Credits real emeralds received at the village board; returns false rather than overflowing. */
	public static boolean creditTreasuryEmeralds(VillageRef ref, long count) {
		if (count <= 0) return false;
		long current = treasuryEmeralds(ref);
		if (current > Long.MAX_VALUE - count) return false;
		save(ref.bellEntity(), of(ref.bellEntity()).withTradeDelta(TREASURY_EMERALDS, count));
		return true;
	}

	/** Spends only cash already present in the village treasury. */
	public static boolean spendTreasuryEmeralds(VillageRef ref, long count) {
		if (count <= 0 || treasuryEmeralds(ref) < count) return false;
		save(ref.bellEntity(), of(ref.bellEntity()).withTradeDelta(TREASURY_EMERALDS, -count));
		return true;
	}

	public static String dim(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	public static String villageName(VillageRef ref) {
		String custom = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME);
		if (custom != null && !custom.isBlank()) {
			return custom;
		}
		String biome = "plains";
		if (ref.bellEntity().getLevel() != null) {
			biome = ref.bellEntity().getLevel().getBiome(ref.bell()).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
		}
		return Names.village(biome, ref.bell().asLong());
	}

	/** Manuelles Umbenennen (Dorftafel): maximal 24 Zeichen, keine Steuerzeichen; leer = prozeduraler Name. */
	public static String rename(ServerLevel level, VillageRef ref, String wanted) {
		String clean = wanted.replaceAll("[\\p{Cntrl}§]", "").strip();
		if (clean.length() > 24) {
			clean = clean.substring(0, 24).strip();
		}
		String old = villageName(ref);
		if (clean.isEmpty()) {
			ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_NAME);
		} else {
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_NAME, clean);
		}
		ref.dirty();
		String now = villageName(ref);
		if (!now.equals(old)) {
			Journal.add(level, ref, "Handel", now, "Dorf umbenannt: " + old + " → " + now, 0);
		}
		return now;
	}

	public static String pairKey(VillageRef a, VillageRef b, String action) {
		long x = a.bell().asLong();
		long y = b.bell().asLong();
		return Math.min(x, y) + "|" + Math.max(x, y) + "|" + action;
	}

	/** Large projects require a separately stored affirmative vote at both linked villages. */
	public static boolean projectApproved(VillageRef a, VillageRef b) {
		String key = pairKey(a, b, "large_project_approval");
		return of(a.bellEntity()).cooldowns().getOrDefault(key, 0L) == 1L && of(b.bellEntity()).cooldowns().getOrDefault(key, 0L) == 1L;
	}

	private static boolean projectVote(VillageRef a, VillageRef b) {
		return of(a.bellEntity()).cooldowns().getOrDefault(pairKey(a, b, "large_project_approval"), 0L) == 1L;
	}

	private static void voteProject(VillageRef source, VillageRef target, ServerPlayer player) {
		String key = pairKey(source, target, "large_project_approval");
		boolean approve = !projectVote(source, target);
		save(source.bellEntity(), of(source.bellEntity()).withCooldown(key, approve ? 1L : 0L));
		VillageMessages.system(player, Component.literal(approve ? "Deine Zustimmung zum Großprojekt ist gespeichert; auch das Nachbardorf muss zustimmen."
			: "Deine Zustimmung zum Großprojekt wurde zurückgenommen."));
	}

	/** Das verlinkte Nachbardorf, falls Dimension und Chunk geladen und die Glocke noch da ist (sonst null). */
	public static VillageRef resolve(ServerLevel level, TradeNetwork.Link link) {
		if (!link.dimension().equals(dim(level)) || !level.hasChunkAt(link.bell())) {
			return null;
		}
		BlockEntity be = level.getBlockEntity(link.bell());
		return be instanceof BellBlockEntity ? new VillageRef(link.bell(), be.getAttachedOrCreate(VillagerPlus.VILLAGE), be) : null;
	}

	// --- Registrierung: Dorfkarte an einer Dorftafel einloesen -------------------------------------

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (player.isSpectator() || hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			ItemStack held = sp.getItemInHand(hand);
			if (!held.is(de.villagerplus.block.ModItems.DORFKARTE) || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof DorftafelBlock)) {
				return InteractionResult.PASS;
			}
			redeem(level, sp, hit.getBlockPos(), held);
			return InteractionResult.SUCCESS_SERVER;
		});
	}

	/** Gibt dem Spieler eine Dorfkarte seines Dorfes (Dimension, Glocke, Name). */
	public static void giveCard(ServerLevel level, ServerPlayer player, VillageRef ref) {
		ItemStack card = new ItemStack(de.villagerplus.block.ModItems.DORFKARTE);
		CompoundTag tag = new CompoundTag();
		tag.putString("dim", dim(level));
		tag.putInt("x", ref.bell().getX());
		tag.putInt("y", ref.bell().getY());
		tag.putInt("z", ref.bell().getZ());
		tag.putString("name", villageName(ref));
		card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		card.set(DataComponents.CUSTOM_NAME, Component.literal("Dorfkarte: " + villageName(ref)));
		if (!player.getInventory().add(card)) {
			player.drop(card, false);
		}
		VillageMessages.system(player, Component.literal("Du hast die Dorfkarte von " + villageName(ref) + " eingesteckt. Rechtsklick auf die Dorftafel eines anderen Dorfes verbindet beide."));
	}

	private static void redeem(ServerLevel level, ServerPlayer player, BlockPos board, ItemStack card) {
		CustomData data = card.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
		if (!tag.contains("dim")) {
			VillageMessages.system(player, Component.literal("Diese Dorfkarte ist leer."));
			return;
		}
		String cardDim = tag.getStringOr("dim", "");
		BlockPos srcPos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
		VillageRef target = Villages.villageNear(level, board, 96);
		if (target == null) {
			VillageMessages.system(player, Component.literal("Zu dieser Tafel gehört (noch) kein Dorf."));
			return;
		}
		if (!cardDim.equals(dim(level))) {
			VillageMessages.system(player, Component.literal("Die Dorfkarte stammt aus einer anderen Dimension – Verbindung nicht möglich."));
			return;
		}
		if (srcPos.equals(target.bell())) {
			VillageMessages.system(player, Component.literal("Das ist dieselbe Dorfglocke – such ein anderes Dorf."));
			return;
		}
		if (!level.hasChunkAt(srcPos) || !(level.getBlockEntity(srcPos) instanceof BellBlockEntity srcBell)) {
			VillageMessages.system(player, Component.literal("Das Heimatdorf der Karte ist gerade nicht geladen oder existiert nicht mehr."));
			return;
		}
		if (Math.sqrt(srcPos.distSqr(target.bell())) > MAX_DISTANCE) {
			VillageMessages.system(player, Component.literal("Die Dörfer sind zu weit voneinander entfernt (max. 3000 Blöcke)."));
			return;
		}
		VillageRef source = new VillageRef(srcPos, srcBell.getAttachedOrCreate(VillagerPlus.VILLAGE), srcBell);
		TradeNetwork a = of(source.bellEntity());
		TradeNetwork b = of(target.bellEntity());
		String dim = dim(level);
		if (a.linkIndex(dim, target.bell()) >= 0 || b.linkIndex(dim, source.bell()) >= 0) {
			VillageMessages.system(player, Component.literal("Diese beiden Dörfer sind bereits verbunden – die Karte bleibt dir erhalten."));
			return;
		}
		if (a.links().size() >= TradeNetwork.MAX_LINKS || b.links().size() >= TradeNetwork.MAX_LINKS) {
			VillageMessages.system(player, Component.literal("Eines der Dörfer hat schon die maximale Zahl von " + TradeNetwork.MAX_LINKS + " Verbindungen."));
			return;
		}
		List<TradeNetwork.Link> la = new ArrayList<>(a.links());
		la.add(new TradeNetwork.Link(dim, target.bell(), villageName(target), 10, false, -1, -1L));
		List<TradeNetwork.Link> lb = new ArrayList<>(b.links());
		lb.add(new TradeNetwork.Link(dim, source.bell(), villageName(source), 10, false, -1, -1L));
		save(source.bellEntity(), a.withLinks(la));
		save(target.bellEntity(), b.withLinks(lb));
		card.shrink(1);
		Journal.add(level,source,"Handel",villageName(target),"Dorfverbindung eröffnet",0);
		Journal.add(level,target,"Handel",villageName(source),"Dorfverbindung eröffnet",0);
		VillageMessages.system(player, Component.literal("Die Dörfer " + villageName(source) + " und " + villageName(target) + " sind jetzt verbunden! Öffne die Dorftafel → Reiter „Nachbarn“."));
	}

	// --- Aktionen ---------------------------------------------------------------------------------

	/** Fuehrt eine Aktion des Nachbarn-Menues aus; {@code index} verweist auf die gespeicherte Link-Liste des Quelldorfes. */
	public static void act(ServerLevel level, ServerPlayer player, BlockPos board, String action, int index) {
		VillageRef source = Villages.villageNear(level, board, 96);
		if (player.isSpectator() || source == null) {
			return;
		}
		if (action.equals(ACTION_CARD)) {
			giveCard(level, player, source);
			return;
		}
		if (action.equals(ACTION_APPROVE)) {
			TradeNetwork approvals = of(source.bellEntity());
			if (index < 0 || index >= approvals.links().size()) return;
			VillageRef approvedTarget = resolve(level, approvals.links().get(index));
			if (approvedTarget != null) voteProject(source, approvedTarget, player);
			return;
		}
		TradeNetwork net = of(source.bellEntity());
		if (index < 0 || index >= net.links().size()) {
			VillageMessages.system(player, Component.literal("Dieses Dorf ist nicht (mehr) verbunden."));
			return;
		}
		TradeNetwork.Link link = net.links().get(index);
		VillageRef target = resolve(level, link);
		if (target == null) {
			VillageMessages.system(player, Component.literal("Das Nachbardorf ist nicht erreichbar (Chunks nicht geladen)."));
			return;
		}
		long now = level.getGameTime();
		String key = pairKey(source, target, action);
		TradeNetwork tnet = of(target.bellEntity());
		long until = Math.max(net.cooldowns().getOrDefault(key, 0L), tnet.cooldowns().getOrDefault(key, 0L));
		if (!action.equals(ACTION_ROUTE) && now < until) {
			VillageMessages.system(player, Component.literal("Das geht erst wieder in " + ((until - now) / 1200 + 1) + " Minuten (Spielzeit)."));
			return;
		}
		switch (action) {
			case ACTION_SWAP -> swap(level, player, source, target, key, now);
			case ACTION_ROUTE -> route(player, source, target, link);
			case ACTION_INVITE -> invite(level, player, source, target, key, now);
			case ACTION_GREET -> greet(player, source, target, key, now);
			default -> {
			}
		}
	}

	private static void bumpRelation(VillageRef a, VillageRef b, int delta) {
		for (VillageRef[] pair : new VillageRef[][] {{a, b}, {b, a}}) {
			TradeNetwork n = of(pair[0].bellEntity());
			List<TradeNetwork.Link> l = new ArrayList<>(n.links());
			for (int k = 0; k < l.size(); k++) {
				if (l.get(k).bell().equals(pair[1].bell())) {
					l.set(k, l.get(k).withRelation(l.get(k).relation() + delta));
				}
			}
			save(pair[0].bellEntity(), n.withLinks(l));
		}
	}

	private static void markCooldown(VillageRef a, VillageRef b, String key, long until) {
		save(a.bellEntity(), of(a.bellEntity()).withCooldown(key, until));
		save(b.bellEntity(), of(b.bellEntity()).withCooldown(key, until));
	}

	private static void greet(ServerPlayer player, VillageRef source, VillageRef target, String key, long now) {
		bumpRelation(source, target, 5);
		Journal.add((ServerLevel)player.level(),source,"Befinden",villageName(target),"Gruß erhalten: Stimmung +3",0);
		markCooldown(source, target, key, now + 24000L);
		for (VillageRef v : new VillageRef[] {source, target}) {
			TradeNetwork n = of(v.bellEntity());
			save(v.bellEntity(), n.withMood(Math.min(8, (now <= n.moodBonusUntil() ? n.moodBonus() : 0) + 3), now + 24000L));
		}
		VillageMessages.system(player, Component.literal("Ein Bote bringt Grüße zwischen den Dörfern – die Beziehung wächst, die Stimmung steigt etwas."));
	}

	private static void route(ServerPlayer player, VillageRef source, VillageRef target, TradeNetwork.Link link) {
		boolean on = !link.route();
		for (VillageRef[] pair : new VillageRef[][] {{source, target}, {target, source}}) {
			TradeNetwork n = of(pair[0].bellEntity());
			List<TradeNetwork.Link> l = new ArrayList<>(n.links());
			for (int k = 0; k < l.size(); k++) {
				if (l.get(k).bell().equals(pair[1].bell())) {
					l.set(k, l.get(k).withRoute(on));
				}
			}
			save(pair[0].bellEntity(), n.withLinks(l));
		}
		VillageMessages.system(player, Component.literal(on ? "Handelsroute eingerichtet: Karawanen besuchen beide Dörfer nun häufiger."
			: "Handelsroute aufgegeben."));
	}

	private static void invite(ServerLevel level, ServerPlayer player, VillageRef source, VillageRef target, String key, long now) {
		Container box = source.chest(level);
		if (box == null || JobUtil.count(box, s -> s.is(Items.EMERALD)) < 2) {
			VillageMessages.system(player, Component.literal("Für die Einladung braucht die Dorfkiste von " + villageName(source) + " mindestens 2 Smaragde."));
			return;
		}
		List<Villager> hosts = level.getEntitiesOfClass(Villager.class, new AABB(target.bell()).inflate(48), v -> v.isAlive() && !Guests.isGuest(v));
		if (hosts.isEmpty()) {
			VillageMessages.system(player, Component.literal("Im Nachbardorf lebt niemand, der Gäste empfangen könnte."));
			return;
		}
		int n = Guests.invite(level, target, hosts.get(0), villageName(source), 2);
		if (n == 0) { VillageMessages.system(player, Component.literal("Die Gäste fanden keinen Ankunftsplatz.")); return; }
		JobUtil.take(box, s -> s.is(Items.EMERALD), n);
		markCooldown(source, target, key, now + 12000L);
		bumpRelation(source, target, 2);
		VillageMessages.system(player, Component.literal(n > 0 ? n + " Gäste aus " + villageName(source) + " reisen nach " + villageName(target) + "."
			: "Die Gäste fanden im Nachbardorf keinen Platz zum Ankommen."));
	}

	// --- Warentausch ------------------------------------------------------------------------------

	private static boolean loadedStores(ServerLevel level,VillageRef ref) {
		List<BlockPos> positions = new ArrayList<>(); ref.data().chest.ifPresent(positions::add);
		positions.addAll(ref.data().extra.lagerChests);
		for (BlockPos p:positions) {
			// A double chest may cross a chunk boundary in any horizontal direction.
			if (!level.hasChunkAt(p)) return false;
			var state=level.getBlockState(p);
			if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				var facing=state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
				var dir=state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)==net.minecraft.world.level.block.state.properties.ChestType.LEFT?facing.getClockWise():facing.getCounterClockWise();
				if(!level.hasChunkAt(p.relative(dir))) return false;
			}
		}
		return true;
	}

	private static List<Container> stores(ServerLevel level, VillageRef v) {
		List<Container> out = new ArrayList<>();
		Container box = v.chest(level);
		if (box != null) {
			out.add(box);
		}
		out.addAll(Lager.containers(level, v.data()));
		return out;
	}

	/** Wieviele Stueck dieser Sorte nehmen die Behaelter noch sicher auf? */
	private static int capacity(List<Container> stores, ItemStack proto) {
		int cap = 0;
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (s.isEmpty()) {
					cap += proto.getMaxStackSize();
				} else if (ItemStack.isSameItemSameComponents(s, proto)) {
					cap += Math.max(0, s.getMaxStackSize() - s.getCount());
				}
			}
		}
		return cap;
	}

	/** Legt direkt in die Behaelter (nie als ItemEntity); true, wenn alles untergebracht wurde. */
	private static boolean insertAll(List<Container> stores, ItemStack stack) {
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
					int move = Math.min(stack.getCount(), s.getMaxStackSize() - s.getCount());
					s.grow(move);
					stack.shrink(move);
					c.setChanged();
				}
			}
		}
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
				if (c.getItem(i).isEmpty()) {
					int move = Math.min(stack.getCount(), stack.getMaxStackSize());
					c.setItem(i, stack.copyWithCount(move));
					stack.shrink(move);
					c.setChanged();
				}
			}
		}
		return stack.isEmpty();
	}

	/** Ueberschuesse eines Dorfes (Sorte -> Menge), absteigend nach Wert. */
	private static Map<Item, Integer> surplus(List<Container> stores) {
		Map<Item, Integer> totals = new HashMap<>();
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty() && Caravan.value(s.getItem()) > 0 && ItemStack.isSameItemSameComponents(s, new ItemStack(s.getItem()))) {
					totals.merge(s.getItem(), s.getCount(), Integer::sum);
				}
			}
		}
		Map<Item, Integer> out = new HashMap<>();
		for (Map.Entry<Item, Integer> e : totals.entrySet()) {
			int extra = e.getValue() - Caravan.keep(e.getKey());
			if (extra >= 8) {
				out.put(e.getKey(), extra);
			}
		}
		return out;
	}

	/** Waehlt aus dem Ueberschuss bis zu {@code unitCap} Wert-Einheiten (max. 3 Sorten). */
	private static List<ItemStack> pick(Map<Item, Integer> surplus, long unitCap) {
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(surplus.entrySet());
		sorted.sort(Comparator.comparingLong((Map.Entry<Item, Integer> e) -> -(long) e.getValue() * Caravan.value(e.getKey())));
		List<ItemStack> out = new ArrayList<>();
		long units = 0;
		for (Map.Entry<Item, Integer> e : sorted) {
			if (out.size() >= 3 || units >= unitCap) {
				break;
			}
			int v = Caravan.value(e.getKey());
			int n = (int) Math.min(Math.min(e.getValue(), 64), Math.max(1, (unitCap - units) / v));
			if (n > 0) {
				out.add(new ItemStack(e.getKey(), n));
				units += (long) n * v;
			}
		}
		return out;
	}

	private static long units(List<ItemStack> list) {
		long u = 0;
		for (ItemStack s : list) {
			u += (long) s.getCount() * Caravan.value(s.getItem());
		}
		return u;
	}

	private static void swap(ServerLevel level, ServerPlayer player, VillageRef a, VillageRef b, String key, long now) {
		if (!loadedStores(level,a) || !loadedStores(level,b)) { VillageMessages.system(player, Component.literal("Das Nachbardorf ist nicht erreichbar (Kisten-Chunks nicht geladen).")); return; }
		List<Container> sa = stores(level, a);
		List<Container> sb = stores(level, b);
		if (sa.isEmpty() || sb.isEmpty()) {
			VillageMessages.system(player, Component.literal("Eines der Dörfer hat keine Dorfkiste."));
			return;
		}
		Map<Item, Integer> surA = surplus(sa);
		Map<Item, Integer> surB = surplus(sb);
		if (surA.isEmpty() || surB.isEmpty()) {
			VillageMessages.system(player, Component.literal("Es gibt nichts zu tauschen: Eines der Dörfer hat keinen Überschuss."));
			return;
		}
		// Choose equal-value lots, capped at 64 items per direction. No rounding creates value.
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
		if (best == 0) { VillageMessages.system(player, Component.literal("Kein gleichwertiger Tausch aus den Überschüssen möglich.")); return; }
		List<ItemStack> giveA = List.of(new ItemStack(bestA, countA)), giveB = List.of(new ItemStack(bestB, countB));
		if (giveB.getFirst().getCount() > StockPolicy.deliveryRoom(level, a, giveB.getFirst().getItem())
			|| giveA.getFirst().getCount() > StockPolicy.deliveryRoom(level, b, giveA.getFirst().getItem())) {
			VillageMessages.system(player, Component.literal("Der Handel würde einen Sollbestand überschreiten; die Ware bleibt in beiden Dörfern.")); return;
		}
		// Simulate the complete transaction on copies, sharing empty slots across all item types.
		List<Container> copyA = copies(sa), copyB = copies(sb);
		if (!removeLots(copyA, giveA) || !removeLots(copyB, giveB)) { VillageMessages.system(player, Component.literal("Vorräte haben sich geändert; kein Tausch ausgeführt.")); return; }
		for (ItemStack stack : giveB) if (!insertAll(copyA, stack.copy())) { VillageMessages.system(player, Component.literal("Kein Platz im Zieldorf.")); return; }
		for (ItemStack stack : giveA) if (!insertAll(copyB, stack.copy())) { VillageMessages.system(player, Component.literal("Kein Platz im Zieldorf.")); return; }
		commit(sa, copyA); commit(sb, copyB);
		TradeNetwork na = of(a.bellEntity()), nb = of(b.bellEntity());
		for (ItemStack stack : giveB) { String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); na = na.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, stack.getCount()); nb = nb.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, -stack.getCount()); }
		for (ItemStack stack : giveA) { String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); na = na.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, -stack.getCount()); nb = nb.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, stack.getCount()); }
		save(a.bellEntity(), na); save(b.bellEntity(), nb);
		List<ItemStack> gotA = giveA, gotB = giveB;

		Journal.add(level,a,"Handel",villageName(b),"Tausch: "+describe(gotA)+" gegen "+describe(gotB),best);
		Journal.add(level,b,"Handel",villageName(a),"Tausch: "+describe(gotB)+" gegen "+describe(gotA),best);
		markCooldown(a, b, key, now + 12000L);
		bumpRelation(a, b, 3);
		VillageMessages.system(player, Component.literal("Warentausch abgeschlossen: " + describe(gotA) + " gingen nach " + villageName(b) + ", dafür kamen " + describe(gotB) + "."));
	}

	private static List<Container> copies(List<Container> stores) {
		List<Container> out = new ArrayList<>();
		for (Container c : stores) {
			var copy = new net.minecraft.world.SimpleContainer(c.getContainerSize());
			for (int i = 0; i < c.getContainerSize(); i++) copy.setItem(i, c.getItem(i).copy());
			out.add(copy);
		}
		return out;
	}
	private static boolean removeLots(List<Container> stores, List<ItemStack> lots) {
		for (ItemStack stack : lots) { int remaining = stack.getCount();
			for (Container c : stores) { if (remaining <= 0) break; remaining -= JobUtil.take(c, s -> ItemStack.isSameItemSameComponents(s, stack), remaining); }
			if (remaining != 0) return false;
		}
		return true;
	}
	private static void commit(List<Container> stores, List<Container> copies) {
		for (int k = 0; k < stores.size(); k++) {
			Container c = stores.get(k), copy = copies.get(k);
			for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, copy.getItem(i).copy());
			c.setChanged();
		}
	}

	private static String describe(List<ItemStack> list) {
		List<String> parts = new ArrayList<>();
		for (ItemStack s : list) {
			parts.add(s.getCount() + "x " + s.getHoverName().getString());
		}
		return String.join(", ", parts);
	}

	// --- Anzeige ----------------------------------------------------------------------------------

	/** Zeilen fuer den Reiter "Nachbarn": {@code name|entfernung|richtung|einwohner|beziehung|route}. Frischt Einwohnerstaende auf. */
	public static List<String> neighborRows(ServerLevel level, VillageRef ref) {
		TradeNetwork n = of(ref.bellEntity());
		List<String> rows = new ArrayList<>();
		List<TradeNetwork.Link> links = new ArrayList<>(n.links());
		boolean changed = false;
		long day = level.getGameTime() / 24000L;
		for (int i = 0; i < links.size(); i++) {
			TradeNetwork.Link l = links.get(i);
			VillageRef other = resolve(level, l);
			if (other != null) {
				int pop = level.getEntitiesOfClass(Villager.class, new AABB(other.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS), v -> v.isAlive() && !Guests.isGuest(v)).size();
				if (pop != l.population() || l.populationDay() != day) {
					l = l.withPopulation(pop, day);
					links.set(i, l);
					changed = true;
				}
			}
			int dx = l.bell().getX() - ref.bell().getX();
			int dz = l.bell().getZ() - ref.bell().getZ();
			String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "Osten" : "Westen") : (dz > 0 ? "Süden" : "Norden");
			String pop = l.population() < 0 ? "?" : l.population() + (day - l.populationDay() > 0 ? " (vor " + (day - l.populationDay()) + " Tagen)" : "");
			String trade = other == null ? "nicht geladen" : bilateralBalance(ref, other);
			String approval = other == null ? "unbekannt" : (projectVote(ref, other) ? "dein Ja" : "dein Nein") + " / " + (projectVote(other, ref) ? "Nachbar Ja" : "Nachbar Nein");
			rows.add(l.name() + "|" + (int) Math.sqrt((double) dx * dx + (double) dz * dz) + " Blöcke " + dir + "|" + dir + "|" + pop + "|" + l.relation() + " (" + stage(l.relation()) + ")|"
				+ routeStatus(ref, l) + "|" + (other != null ? "1" : "0") + "|" + trade + "|" + approval);
		}
		if (changed) {
			save(ref.bellEntity(), n.withLinks(links));
		}
		return rows;
	}

	private static String bilateralBalance(VillageRef a, VillageRef b) {
		String prefix = "pair/" + pairKey(a,b,"trade") + "/";
		Map<String, Long> values = new HashMap<>();
		for (var e : of(a.bellEntity()).tradeBalance().entrySet()) if (e.getKey().startsWith(prefix)) values.put(e.getKey().substring(prefix.length()), e.getValue());
		if (values.isEmpty()) return "keine Tausche";
		return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(3).map(e -> {
			var id = net.minecraft.resources.Identifier.tryParse(e.getKey());
			String name = id == null ? e.getKey() : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).map(i -> new ItemStack(i).getHoverName().getString()).orElse(e.getKey());
			return name + " " + (e.getValue() > 0 ? "+" : "") + e.getValue();
		}).reduce((x,y) -> x + ", " + y).orElse("keine Tausche");
	}

	/** Statusstufe der Beziehung: Unbekannt/Neutral -> Handelspartner (+30) -> Buendnispartner (+60) -> Metropolen-Verbund (+90). */
	public static String stage(int relation) {
		return relation >= 90 ? "Metropolen-Verbund" : relation >= 60 ? "Bündnispartner" : relation >= 30 ? "Handelspartner" : relation < 0 ? "Verfeindet" : "Neutral";
	}

	/** Routenstatus fuer die Tafel: Keine / Strasse im Bau (x %) / Strasse fertig / Bahnstrecke im Bau. */
	static String routeStatus(VillageRef ref, TradeNetwork.Link l) {
		if (!l.route() && l.relation() < Roads.TRADE_PARTNER) {
			return "keine";
		}
		boolean rail = l.relation() >= Roads.METROPOLIS && Specials.has(ref, "toolshed") && Specials.has(ref, "granary");
		int pct = Roads.progress(ref, l, Market.of(ref).plaza().orElse(ref.bell()));
		if (rail) {
			return "Bahnstrecke im Bau";
		}
		return pct >= 100 ? "Straße fertig" : "Straße im Bau " + pct + " %";
	}

	/** Zeilen fuer die Partnerorte: {@code name|spezialitaet|beziehung|besuch}. */
	public static List<String> partnerRows(ServerLevel level, VillageRef ref) {
		TradeNetwork n = of(ref.bellEntity());
		long day = level.getGameTime() / 24000L;
		List<String> rows = new ArrayList<>();
		for (TradeNetwork.Partner p : n.partners()) {
			String last = p.lastVisitDay() < 0 ? "noch nie" : "vor " + Math.max(0, day - p.lastVisitDay()) + " Tagen";
			String shown = p.lastVisitDay() < 0 ? "Unbekannter Ort (noch kein Handelskontakt)" : p.name();
			rows.add(shown + "|" + TradeNetwork.SPECIALTIES[p.specialty() % TradeNetwork.SPECIALTIES.length] + "|" + p.relation() + "|" + last);
		}
		return rows;
	}

	/** Gruss-Stimmungsbonus fuer Einwohner (0, wenn abgelaufen). */
	public static int moodBonus(ServerLevel level, VillageRef ref) {
		TradeNetwork n = ref.bellEntity().getAttached(VillagerPlus.NETWORK);
		return n == null || level.getGameTime() > n.moodBonusUntil() ? 0 : n.moodBonus();
	}
}
