package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;

import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Ctx;

import java.util.ArrayList;

import java.util.HashMap;

import java.util.Iterator;

import java.util.List;

import java.util.Map;

import java.util.Optional;

import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.network.chat.Component;

import net.minecraft.resources.Identifier;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.Container;

import net.minecraft.world.entity.Entity;

import net.minecraft.world.entity.EntitySpawnReason;

import net.minecraft.world.entity.EntityType;

import net.minecraft.world.entity.EntityTypes;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.Display.ItemDisplay;

import net.minecraft.world.entity.npc.villager.Villager;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;

import net.minecraft.world.item.ItemStack;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**

 * Handelskarawane: Alle paar Tage kommt ein wandernder Haendler ins Dorf und tauscht mit einem "imaginaeren" Dorf

 * Ressourcen. Er kauft Ueberschuss aus der Dorfkiste (Erde, Kies, Bruchstein ueber dem Bedarf, Erze, Nahrung, Wolle ...)

 * gegen Smaragde, verkauft dafuer Fehlendes von der Wunschliste (Baumaterial), Saatgut, Setzlinge und Fackeln und liefert

 * die ersten Tiere fuer das Gehege.

 */

public final class Caravan {

	private static final class State {

		final UUID trader;

		final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

		final BlockPos bell, spawn;

		final int partner;

		final List<UUID> llamas = new ArrayList<>(); // beladene Esel (Feldname historisch)

		final List<BlockPos> camp = new ArrayList<>();

		UUID guard;

		final List<net.minecraft.world.level.block.state.BlockState> campBlocks = new ArrayList<>();

		boolean campBuilt;
		long campRetryAt;

		final long born;

		boolean traded;

		long leaveAt;

		long nextUnload;

		int uses, reward;
		String roadKey;
		int roadCursor = -1;

		State(WanderingTrader trader, ServerLevel level, VillageRef ref, BlockPos spawn, int partner) {

			this.trader = trader.getUUID(); this.dimension = level.dimension(); this.bell = ref.bell();

			this.spawn = spawn; this.partner = partner; this.born = level.getGameTime();

		}

	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	/** Bell-keyed camp blocks awaiting cleanup because their chunks are currently unloaded. */
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Map<Long, List<CaravanCampData.CampBlock>>> PENDING_CAMP_CLEANUP = new HashMap<>();
	/** Disposable render-only cargo markers; donkey inventory remains the sole source of truth. */
	private static final Map<UUID, UUID> CARGO_VISUALS = new HashMap<>();
	private static final String PORTER_VISUAL_TAG = "vp_caravan_porter_visual";
	private static final Map<UUID, PorterCarry> PORTER_CARRIES = new HashMap<>();
	private static final Map<UUID, UUID> PORTER_CLAIMS = new HashMap<>();
	private static final class PorterCarry {
		final UUID donkey, porter;
		UUID visual;
		final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
		final int slot, count;
		final BlockPos bell;
		final ItemStack expected;
		BlockPos target;
		boolean pickedUp;
		PorterCarry(UUID donkey, UUID porter, UUID visual, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			int slot, ItemStack expected, BlockPos bell, BlockPos target) {
			this.donkey=donkey; this.porter=porter; this.visual=visual; this.dimension=dimension; this.slot=slot;
			this.count=expected.getCount(); this.expected=expected.copy(); this.bell=bell.immutable(); this.target=target.immutable();
		}
	}
	private record DeliveryTarget(BlockPos pos, String depot, Container container) {}
	private static final Map<UUID, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> CARGO_RECONCILE = new HashMap<>();
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long> CARGO_STARTUP_SCAN = new HashMap<>();

	private Caravan() {}

	public static void register() {

		ServerTickEvents.END_LEVEL_TICK.register(Caravan::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
			for (var be : chunk.getBlockEntities().values()) {
				if (be instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell) queueOrphanCampCleanup(world, bell);
			}
			processPendingCampCleanups(world);
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (PorterCarry carry : List.copyOf(PORTER_CARRIES.values())) {
				ServerLevel l=server.getLevel(carry.dimension); if(l!=null) releasePorter(l,carry.porter);
			}

			for (State st : ACTIVE.values()) { ServerLevel l = server.getLevel(st.dimension); if (l != null) { removeCamp(l, st); preserveCargo(l, st); } }

		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> { ACTIVE.clear(); CARGO_VISUALS.clear(); CARGO_RECONCILE.clear(); PORTER_CARRIES.clear(); PORTER_CLAIMS.clear(); PENDING_CAMP_CLEANUP.clear(); });
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) CARGO_STARTUP_SCAN.put(level.dimension(), level.getGameTime() + 2400L);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> CARGO_STARTUP_SCAN.clear());

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemDisplay display && display.entityTags().contains(PORTER_VISUAL_TAG)
				&& PORTER_CARRIES.values().stream().noneMatch(carry -> display.getUUID().equals(carry.visual))) display.discard();

			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)
				&& donkey.getCustomName() != null && donkey.getCustomName().getString().equals("Karawanen-Frachtesel")) {
				donkey.setPersistenceRequired();
				syncCargoVisual(level, donkey);
				CARGO_RECONCILE.put(donkey.getUUID(), level.dimension());
			}
			if (entity instanceof ItemDisplay display) reconcileCargoDisplay(level, display);
			// Runtime routes are transient; preserve real cargo donkeys but discard empty route leftovers.

			if (entity.entityTags().contains("vp_caravan") && !ACTIVE.containsKey(entity.getUUID())

				&& ACTIVE.values().stream().noneMatch(st -> st.llamas.contains(entity.getUUID()))) {

				if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)) {
					donkey.removeLeash();
					donkey.removeTag("vp_caravan");
					donkey.setPersistenceRequired();
					donkey.setCustomName(Component.literal("Karawanen-Frachtesel"));
					donkey.setCustomNameVisible(true);
					syncCargoVisual(level, donkey);
					CARGO_RECONCILE.put(donkey.getUUID(), level.dimension());
				} else {
					entity.discard();
				}
			}

		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) releasePorter(level, villager.getUUID());
			if (entity instanceof ItemDisplay display && display.entityTags().contains(PORTER_VISUAL_TAG)) {
				for (PorterCarry carry : List.copyOf(PORTER_CARRIES.values())) {
					if (display.getUUID().equals(carry.visual)) { releasePorter(level, carry.porter); break; }
				}
			}
		});

		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {

			if (!(world instanceof ServerLevel level) || hand != net.minecraft.world.InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.PASS;

			State state = ACTIVE.get(entity.getUUID());

			if (state == null || !player.getItemInHand(hand).is(Items.EMERALD)) return net.minecraft.world.InteractionResult.PASS;

			VillageRef ref = resolve(level, state.bell);

			if (ref != null && state.reward < 10) {

				player.getItemInHand(hand).shrink(1); changeRelation(ref, state.partner, 2, -1);

				state.reward += 2;

				VillageMessages.system(player, Component.literal("Das Partnerdorf bedankt sich für dein Geschenk (+2 Beziehung)."));

			}

			return net.minecraft.world.InteractionResult.SUCCESS_SERVER;

		});

	}

	private static net.minecraft.world.level.block.entity.BellBlockEntity loadedBell(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell ? bell : null;
	}

	private static boolean activeAt(ServerLevel level, BlockPos bell) {
		return ACTIVE.values().stream().anyMatch(state -> state.dimension.equals(level.dimension()) && state.bell.equals(bell));
	}

	private static List<CaravanCampData.CampBlock> attachedCampBlocks(net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		return bell.getAttachedOrElse(de.villagerplus.VillagerPlus.CARAVAN_CAMPS, List.of());
	}

	static void saveCampBlocks(net.minecraft.world.level.block.entity.BellBlockEntity bell, List<CaravanCampData.CampBlock> blocks) {
		bell.setAttached(de.villagerplus.VillagerPlus.CARAVAN_CAMPS, List.copyOf(blocks));
		bell.setChanged();
	}

	private static boolean sameCampBlock(net.minecraft.world.level.block.state.BlockState current, net.minecraft.world.level.block.state.BlockState expected) {
		if (current.equals(expected)) return true;
		return expected.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) && current.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
			&& current.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, expected.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)).equals(expected);
	}

	/** Removes only matching camp-owned states; unloaded cells remain recorded for the next chunk load. */
	static List<CaravanCampData.CampBlock> clearLoadedCampBlocks(ServerLevel level, List<CaravanCampData.CampBlock> records) {
		List<CaravanCampData.CampBlock> pending = new ArrayList<>();
		for (CaravanCampData.CampBlock record : records) {
			BlockPos pos = record.pos();
			if (!level.hasChunkAt(pos)) {
				pending.add(record);
				continue;
			}
			var current = level.getBlockState(pos);
			if (current.isAir() || !sameCampBlock(current, record.expected())) continue;
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity instanceof Container container && !container.isEmpty()) continue;
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		}
		return List.copyOf(pending);
	}

	private static void queuePendingCampCleanup(ServerLevel level, BlockPos bell, List<CaravanCampData.CampBlock> records) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.computeIfAbsent(level.dimension(), key -> new HashMap<>());
		if (records.isEmpty()) byBell.remove(bell.asLong());
		else byBell.put(bell.asLong(), List.copyOf(records));
		if (byBell.isEmpty()) PENDING_CAMP_CLEANUP.remove(level.dimension());
	}

	private static void queueOrphanCampCleanup(ServerLevel level, net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		List<CaravanCampData.CampBlock> records = attachedCampBlocks(bell);
		if (!records.isEmpty() && !activeAt(level, bell.getBlockPos())) {
			queuePendingCampCleanup(level, bell.getBlockPos(), records);
			processPendingCampCleanup(level, bell.getBlockPos(), bell);
		}
	}

	private static void processPendingCampCleanups(ServerLevel level) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.get(level.dimension());
		if (byBell == null || byBell.isEmpty()) return;
		for (var entry : Map.copyOf(byBell).entrySet()) {
			BlockPos bellPos = BlockPos.of(entry.getKey());
			if (activeAt(level, bellPos)) continue;
			var bell = loadedBell(level, bellPos);
			processPendingCampCleanup(level, bellPos, bell);
		}
	}

	private static void processPendingCampCleanup(ServerLevel level, BlockPos bellPos, net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.get(level.dimension());
		if (byBell == null) return;
		List<CaravanCampData.CampBlock> records = byBell.get(bellPos.asLong());
		if (records == null || activeAt(level, bellPos)) return;
		if (bell != null) {
			List<CaravanCampData.CampBlock> saved = attachedCampBlocks(bell);
			if (!saved.isEmpty()) records = saved;
		}
		List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, records);
		if (bell != null) saveCampBlocks(bell, remaining);
		queuePendingCampCleanup(level, bellPos, remaining);
	}

	private static boolean persistCampPlan(ServerLevel level, BlockPos bellPos, List<CaravanCampData.CampBlock> planned) {
		var bell = loadedBell(level, bellPos);
		if (bell == null) return false;
		List<CaravanCampData.CampBlock> previous = attachedCampBlocks(bell);
		if (!previous.isEmpty()) {
			List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, previous);
			saveCampBlocks(bell, remaining);
			if (!remaining.isEmpty()) {
				queuePendingCampCleanup(level, bellPos, remaining);
				return false;
			}
			queuePendingCampCleanup(level, bellPos, List.of());
		}
		saveCampBlocks(bell, planned);
		return true;
	}

	public static void maybeSpawn(ServerLevel level, VillageRef ref, Villager who, long now) {

		if (ref.data().extra.nextCaravan == 0) { ref.data().extra.nextCaravan = now + 6000; ref.dirty(); return; }

		if (now < ref.data().extra.nextCaravan || ref.chestPos(level) == null) return;

		if (Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v)).size() < Config.CARAVAN_MIN_POP) return;

		spawn(level, ref);

	}

	public static boolean spawn(ServerLevel level, VillageRef ref) {

		if (ref.chestPos(level) == null || ACTIVE.values().stream().anyMatch(st -> st.dimension.equals(level.dimension()) && st.bell.equals(ref.bell()))) return false;

		long now = level.getGameTime();

		TradeNetwork net = Network.of(ref.bellEntity());

		long day = now / 24000;

		long decayDay = net.cooldowns().getOrDefault("partner_decay", day);

		if (day > decayDay) {

			List<TradeNetwork.Partner> ps = new ArrayList<>(net.partners());

			for (int i = 0; i < ps.size(); i++) {

				var part = ps.get(i);

				if (part.lastVisitDay() >= 0 && day - part.lastVisitDay() > 3) ps.set(i, part.withRelation(part.relation() - (int) Math.min(100, (day - decayDay) / (net.links().stream().anyMatch(TradeNetwork.Link::route) ? 2 : 1))));

			}

			net = net.withPartners(ps);

		}

		net = net.withCooldown("partner_decay", day); Network.save(ref.bellEntity(), net);

		int partner = level.getRandom().nextInt(net.partners().size());

		var part = net.partners().get(partner);

		boolean route = net.links().stream().anyMatch(TradeNetwork.Link::route);

		long nextVisit = now + (long) (Config.CARAVAN_INTERVAL * (route ? 0.6 : 1.0) * (1.0 - part.relation() / 500.0));

		CaravanSites.Group group = arrival(level, ref.bell(), 70, 100);

		if (group == null) group = arrival(level, ref.bell(), 34, 46);

		if (group == null) { ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }
		BlockPos at=group.trader();

		WanderingTrader trader = de.villagerplus.entity.ModEntities.CARAVAN_TRADER.spawn(level, at, EntitySpawnReason.EVENT);

		if (trader == null) { ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }
		if(!CaravanSites.canStand(level,trader,trader.blockPosition(),List.of())) {trader.discard();ref.data().extra.nextCaravan=now+1200;ref.dirty();return false;}
		ref.data().extra.nextCaravan=nextVisit; ref.dirty();

		State state = new State(trader, level, ref, at, partner); ACTIVE.put(trader.getUUID(), state);

		trader.addTag("vp_caravan"); trader.setDespawnDelay(30000);

		trader.setCustomName(Component.literal(part.lastVisitDay() < 0 ? "Fremder Händler" : "Händler aus " + part.name())); trader.setCustomNameVisible(true);

		trader.setWanderTarget(ref.chestPos(level));

		trader.getOffers().clear(); trader.getOffers().addAll(partnerOffers(part));

		for (int i = 0; i < 2; i++) {

			var llama = EntityTypes.DONKEY.spawn(level, group.donkeys().get(i), EntitySpawnReason.EVENT);
			if(llama==null||!CaravanSites.canStand(level,llama,llama.blockPosition(),List.of())) {
				if(llama!=null)llama.discard();return cancelSpawn(level,ref,state,trader,now);
			}

			if (llama != null) { llama.addTag("vp_caravan"); llama.addTag(villageTag(ref.bell())); { var chestSlot = llama.getSlot(499); if (chestSlot != null) chestSlot.set(new ItemStack(Items.CHEST)); } llama.setLeashedTo(trader, true); state.llamas.add(llama.getUUID()); }

		}

		var guard = EntityTypes.IRON_GOLEM.spawn(level, group.guard(), EntitySpawnReason.EVENT);
		if(guard==null||!CaravanSites.canStand(level,guard,guard.blockPosition(),List.of())) {
			if(guard!=null)guard.discard();return cancelSpawn(level,ref,state,trader,now);
		}

		if (guard != null) { guard.addTag("vp_caravan"); guard.setPlayerCreated(true); guard.setCustomName(Component.literal("Karawanenwache")); guard.setCustomNameVisible(true); state.llamas.add(guard.getUUID()); state.guard = guard.getUUID(); }

		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane {}: Start {}, Glocke {}, Abstand {}", part.name(), at, ref.bell(), Math.sqrt(at.distSqr(ref.bell())));

		return true;

	}

	private static boolean cancelSpawn(ServerLevel level,VillageRef ref,State state,WanderingTrader trader,long now) {
		for(UUID id:state.llamas){Entity e=level.getEntity(id);if(e!=null)e.discard();}
		ACTIVE.remove(trader.getUUID());trader.discard();ref.data().extra.nextCaravan=now+1200;ref.dirty();return false;
	}

	private static CaravanSites.Group arrival(ServerLevel level, BlockPos bell, int min, int max) {

		for (int i = 0; i < 20; i++) {

			// Check chunk BEFORE heightmap access; never load remote chunks for a caravan.

			double angle = level.getRandom().nextDouble() * Math.PI * 2;

			double radius = min + level.getRandom().nextDouble() * (max - min);

			BlockPos column = bell.offset((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));

			if (!level.hasChunkAt(column)) continue;

			BlockPos p = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);

			double horizontal = Math.hypot(p.getX() - bell.getX(), p.getZ() - bell.getZ());

			if (horizontal < min || horizontal > max || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()

				|| !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;

			var group=CaravanSites.plan(level,p);
			if(group!=null)return group;

		}

		return null;

	}

	private static net.minecraft.world.item.trading.MerchantOffers partnerOffers(TradeNetwork.Partner p) {

		var offers = new net.minecraft.world.item.trading.MerchantOffers();

		Item[][] stock = {{Items.IRON_INGOT, Items.COAL, Items.COPPER_INGOT}, {Items.STRING, Items.LEATHER, Items.WOOL.white()},

			{Items.BREAD, Items.CARROT, Items.POTATO}, {Items.SPRUCE_LOG, Items.OAK_LOG, Items.STONE_BRICKS}};

		for (Item item : stock[Math.floorMod(p.specialty(), stock.length)]) {

			int quantity = p.relation() >= 40 ? 16 : 8;

			offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD,

				Math.max(1, (int) Math.round(4 * (1 - p.relation() / 400.0)))), new ItemStack(item, quantity), 12, 2, 0));

		}

		offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.WHEAT, Math.max(16, 32 - p.relation() / 10)), new ItemStack(Items.EMERALD), 6, 2, 0));

		return offers;

	}

	private static VillageRef resolve(ServerLevel level, BlockPos bell) {

		if (!level.hasChunkAt(bell) || !(level.getBlockEntity(bell) instanceof net.minecraft.world.level.block.entity.BellBlockEntity be)) return null;

		return new VillageRef(bell, be.getAttachedOrCreate(de.villagerplus.VillagerPlus.VILLAGE), be);

	}

	private static void changeRelation(VillageRef ref, int index, int delta, long day) {

		Network.save(ref.bellEntity(), updatedRelation(Network.of(ref.bellEntity()), index, delta, day));

	}

	static TradeNetwork updatedRelation(TradeNetwork n, int index, int delta, long day) {

		List<TradeNetwork.Partner> ps = new ArrayList<>(n.partners());

		if (index < 0 || index >= ps.size()) return n;

		if (delta > 0) {

			// Gemeinsame Beziehungsgroesse: erfolgreicher Karawanenhandel verbessert auch das Verhaeltnis zu den echten Nachbardoerfern (Strassenbau ab +30).

			List<TradeNetwork.Link> ls = new ArrayList<>(n.links());

			for (int i = 0; i < ls.size(); i++) ls.set(i, ls.get(i).withRelation(ls.get(i).relation() + Math.max(2, delta * 2)));

			if (!ls.isEmpty()) n = n.withLinks(ls);

		}

		var p = ps.get(index).withRelation(ps.get(index).relation() + delta);

		ps.set(index, day >= 0 ? p.visited(day) : p);

		return n.withPartners(ps);

	}

	/** Packt echte Handelsware in freie Plaetze der beiden Eselstruhen. */
	private static int packCargo(ServerLevel level, State state, ItemStack stack) {

		int before = stack.getCount();

		for (UUID id : state.llamas) {

			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)) continue;

			for (int slot = 2; slot < 17 && !stack.isEmpty(); slot++) {

				var access = donkey.getSlot(500 + slot); if (access == null) continue;

				ItemStack current = access.get();

				if (current.isEmpty()) {

					access.set(stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
					syncCargoVisual(level, donkey);

				} else if (ItemStack.isSameItemSameComponents(current, stack) && current.getCount() < current.getMaxStackSize()) {

					int moved = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());

					current.grow(moved); stack.shrink(moved); access.set(current);
					syncCargoVisual(level, donkey);

				}

			}

		}

		return before - stack.getCount();

	}

	/** Shows a non-pickup render-only cargo item; donkey inventory is always the source of truth. */
	private static void syncCargoVisual(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey) {
		ItemStack cargo = ItemStack.EMPTY;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(500 + slot);
			if (access != null && !access.get().isEmpty()) { cargo = access.get(); break; }
		}
		UUID id = CARGO_VISUALS.get(donkey.getUUID());
		Entity mapped = id == null ? null : level.getEntity(id);
		String ownerTag = "vp_caravan_cargo_owner:" + donkey.getUUID();
		List<ItemDisplay> candidates = level.getEntitiesOfClass(ItemDisplay.class, donkey.getBoundingBox().inflate(4.0), e -> e.entityTags().contains(ownerTag));
		ItemDisplay display = mapped instanceof ItemDisplay itemDisplay ? itemDisplay
			: candidates.stream().filter(candidate -> candidate.getVehicle() == donkey).findFirst().orElseGet(() -> candidates.stream().findFirst().orElse(null));
		if (cargo.isEmpty()) {
			if (mapped != null) mapped.discard();
			for (ItemDisplay candidate : candidates) if (candidate != mapped) candidate.discard();
			CARGO_VISUALS.remove(donkey.getUUID());
			return;
		}
		for (ItemDisplay candidate : candidates) if (candidate != display) candidate.discard();
		if (display == null) {
			display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (display == null) return;
			display.addTag("vp_caravan_cargo_visual");
			display.addTag("vp_caravan_cargo_owner:" + donkey.getUUID());
			display.setNoGravity(true);
			display.setInvulnerable(true);
			display.setSilent(true);
			display.setItemTransform(ItemDisplayContext.GROUND);
			display.setItemStack(cargo.copyWithCount(1));
			// Spawn inside the loaded donkey chunk before adding it to the level.
			// Starting at the default origin can unload/discard the display before it mounts.
			display.setPos(donkey.getX(), donkey.getY() + 1.2, donkey.getZ());
			display.setYRot(donkey.getYRot());
			CARGO_VISUALS.put(donkey.getUUID(), display.getUUID());
			if (!level.addFreshEntity(display)) { CARGO_VISUALS.remove(donkey.getUUID()); return; }
		}
		PorterCarry carry = PORTER_CARRIES.get(PORTER_CLAIMS.get(donkey.getUUID()));
		if (carry != null && carry.pickedUp) {
			if (!display.getItemStack().isEmpty()) display.setItemStack(ItemStack.EMPTY);
			if (display.getVehicle() != donkey) display.startRiding(donkey, true, true);
			return;
		}
		if (!ItemStack.isSameItemSameComponents(display.getItemStack(), cargo)) display.setItemStack(cargo.copyWithCount(1));
		if (display.getVehicle() != donkey && !display.startRiding(donkey, true, true)) {
			display.setPos(donkey.getX(), donkey.getY() + 1.2, donkey.getZ());
			display.setYRot(donkey.getYRot());
		}
	}

	private static String villageTag(BlockPos bell) { return "vp_caravan_bell:" + bell.asLong(); }

	/** A porter carries only a visual reservation; the real stack stays in its donkey until the reached crate accepts it. */
	public static boolean porterDelivery(Ctx c) {
		PorterCarry carry = PORTER_CARRIES.get(c.v().getUUID());
		VillageRef ref=c.village()!=null?c.village():carry==null?null:resolve(c.level(),carry.bell);
		if (ref == null) { if(carry!=null) releasePorter(c.level(),c.v().getUUID()); return false; }
		if (carry == null) {
			var donkey = c.level().getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class,
				new AABB(ref.bell()).inflate(112), d -> d.isAlive() && hasCargo(d)
					&& hasDeliverableCargo(c.level(),ref,d)
					&& d.entityTags().contains(villageTag(ref.bell())) && !PORTER_CLAIMS.containsKey(d.getUUID())
				).stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(c.v()))).orElse(null);
			if (donkey == null) return false;
			int slot = -1; ItemStack cargo = ItemStack.EMPTY; DeliveryTarget target = null;
			// Keep product delivery ahead of the cash receipt even when the trade packed emeralds first.
			for (int cashPass=0;cashPass<2&&slot<0;cashPass++) {
				for (int i=2;i<17;i++) {
					var access=donkey.getSlot(500+i);
					if(access==null||access.get().isEmpty()||access.get().is(Items.EMERALD)!=(cashPass==1)) continue;
					DeliveryTarget candidate=deliveryTarget(c.level(),ref,access.get());
					if(candidate==null) continue;
					slot=i; cargo=access.get().copy(); target=candidate; break;
				}
			}
			if (slot < 0 || target == null) return false;
			carry=new PorterCarry(donkey.getUUID(),c.v().getUUID(),null,c.level().dimension(),slot,cargo,ref.bell(),target.pos());
			PORTER_CARRIES.put(c.v().getUUID(),carry); PORTER_CLAIMS.put(donkey.getUUID(),c.v().getUUID());
			c.st().clearTarget(); c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
		}
		if (!carry.dimension.equals(c.level().dimension())) { releasePorter(c.level(),carry.porter); return false; }
		Entity sourceEntity=c.level().getEntity(carry.donkey);
		if (!(sourceEntity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()
			|| !donkey.entityTags().contains(villageTag(ref.bell()))) { releasePorter(c.level(),carry.porter); return false; }
		var source=donkey.getSlot(500+carry.slot);
		if (source==null || source.get().isEmpty() || !ItemStack.isSameItemSameComponents(source.get(),carry.expected)
			|| source.get().getCount()<carry.count) { releasePorter(c.level(),carry.porter); return false; }

		if (!carry.pickedUp) {
			c.setActivity("holt Karawanenfracht ab");
			if (!isPorterStand(c.level(),donkey.blockPosition(),c.st().target)) {
				BlockPos pickupStand=reachablePorterStand(c,donkey.blockPosition());
				if(pickupStand==null) { releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true; }
				c.st().target=pickupStand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
			}
			if (!JobUtil.approachLong(c,c.st().target,1.5)) {
				if(c.st().cooldownUntil>c.now()) releasePorter(c.level(),carry.porter);
				return true;
			}
			ItemDisplay visual=EntityTypes.ITEM_DISPLAY.create(c.level(),EntitySpawnReason.EVENT);
			if(visual==null) { releasePorter(c.level(),carry.porter); return false; }
			visual.addTag(PORTER_VISUAL_TAG); visual.addTag("vp_caravan_porter_owner:"+c.v().getUUID()); visual.addTag("vp_caravan_porter_donkey:"+donkey.getUUID());
			visual.setNoGravity(true); visual.setInvulnerable(true); visual.setSilent(true); visual.setItemTransform(ItemDisplayContext.GROUND);
			visual.setItemStack(carry.expected.copyWithCount(1)); visual.setPos(c.v().getX(),c.v().getY()+1.0,c.v().getZ()); visual.setYRot(c.v().getYRot());
			carry.visual=visual.getUUID();
			if(!c.level().addFreshEntity(visual)||!visual.startRiding(c.v(),true,true)) { releasePorter(c.level(),carry.porter); return false; }
			carry.pickedUp=true; syncCargoVisual(c.level(),donkey); JobUtil.swing(c.v());
			c.level().playSound(null,donkey.blockPosition(),net.minecraft.sounds.SoundEvents.ITEM_PICKUP,net.minecraft.sounds.SoundSource.NEUTRAL,0.6F,0.9F);
			c.st().walkStart=0; c.st().bestDist=0; c.st().stuckCount=0; c.st().target=null; c.st().targetEntity=null;
		}

		DeliveryTarget target=deliveryTarget(c.level(),ref,carry.expected);
		BlockPos targetPos=target==null?null:target.pos(); Container destination=target==null?null:target.container();
		BlockPos stand=null;
		if(targetPos!=null) {
			if(!isPorterStand(c.level(),targetPos,c.st().target)) {
				stand=reachablePorterStand(c,targetPos);
				c.st().target=stand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
			} else stand=c.st().target;
		}
		boolean treasury=target!=null&&"treasury".equals(target.depot());
		if(targetPos==null||stand==null||!targetPos.equals(carry.target)
			|| (!treasury&&(destination==null||StockPolicy.deliveryRoom(c.level(),ref,carry.expected.getItem())<=0||!canAccept(destination,carry.expected)))) {
			releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true;
		}
		if(!stand.equals(c.st().target)) { c.st().target=stand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0; c.st().stuckCount=0; }
		String destinationName=switch(target.depot()) {
			case "treasury" -> "zur Dorfkasse";
			case de.villagerplus.build.Blueprints.GRANARY -> "zum Kornspeicher";
			case de.villagerplus.build.Blueprints.TOOLSHED -> "zur Werkzeugkammer";
			default -> target.pos().equals(ref.chestPos(c.level())) ? "zur Dorfkiste" : "ins Lager";
		};
		c.setActivity("trägt "+carry.expected.getHoverName().getString()+" "+destinationName);
		if(!JobUtil.approachLong(c,stand,2.0)) {
			if(c.st().cooldownUntil>c.now()) releasePorter(c.level(),carry.porter);
			return true;
		}
		// Re-resolve the category depot, ownership, source and destination at arrival.
		target=deliveryTarget(c.level(),ref,source.get());
		if(target==null||!target.pos().equals(carry.target)||c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand))>4.0) {
			releasePorter(c.level(),carry.porter); return true;
		}
		if("treasury".equals(target.depot())) {
			long room=Long.MAX_VALUE-Network.treasuryEmeralds(ref);
			int allowed=(int)Math.min(Math.min((long)carry.count,source.get().getCount()),room);
			if(!source.get().is(Items.EMERALD)||allowed<=0) { releasePorter(c.level(),carry.porter); return true; }
			ItemStack offered=source.get().copyWithCount(allowed), after=source.get().copy(); after.shrink(allowed);
			if(!source.set(after)) { releasePorter(c.level(),carry.porter); return true; }
			if(!Network.creditTreasuryEmeralds(ref,allowed)) {
				restoreCargoSlot(source,offered); releasePorter(c.level(),carry.porter); return true;
			}
			c.mem().jobsDone++;
			c.level().playSound(null,target.pos(),net.minecraft.sounds.SoundEvents.CHEST_CLOSE,net.minecraft.sounds.SoundSource.NEUTRAL,0.7F,1.1F);
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.2,c.v().getZ(),4,0.25,0.2,0.25,0.0);
			JobUtil.swing(c.v()); releasePorter(c.level(),carry.porter); c.st().clearTarget(); c.st().cooldownUntil=c.now()+20;
			return true;
		}
		int allowed=Math.min(carry.count,Math.min(source.get().getCount(),StockPolicy.deliveryRoom(c.level(),ref,source.get().getItem())));
		if(allowed<=0) { releasePorter(c.level(),carry.porter); return true; }
		ItemStack offered=source.get().copyWithCount(allowed); ItemStack after=source.get().copy(); after.shrink(allowed);
		if(!source.set(after)) { releasePorter(c.level(),carry.porter); return true; }
		ItemStack rest;
		try { rest=StockPolicy.insertInto(c.level(),ref,target.depot(),target.container(),offered.copy()); }
		catch(RuntimeException ex) { restoreCargoSlot(source,offered); releasePorter(c.level(),carry.porter); throw ex; }
		int moved=allowed-rest.getCount();
		if(!rest.isEmpty()&&!restoreCargoSlot(source,rest)) {
			var drop=new net.minecraft.world.entity.item.ItemEntity(c.level(),target.pos().getX()+0.5,target.pos().getY()+1.0,target.pos().getZ()+0.5,rest.copy());
			drop.setDefaultPickUpDelay(); c.level().addFreshEntity(drop);
			org.slf4j.LoggerFactory.getLogger("villagerplus").error("Karawanen-Restfracht konnte nicht in den Esel-Slot zurückgelegt werden: {} bei {}",rest,target.pos());
		}
		if(moved<=0) { releasePorter(c.level(),carry.porter); return true; }
		c.mem().jobsDone++;
		c.level().playSound(null,target.pos(),net.minecraft.sounds.SoundEvents.CHEST_CLOSE,net.minecraft.sounds.SoundSource.NEUTRAL,0.7F,1.1F);
		c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.2,c.v().getZ(),4,0.25,0.2,0.25,0.0);
		JobUtil.swing(c.v()); releasePorter(c.level(),carry.porter); c.st().clearTarget(); c.st().cooldownUntil=c.now()+20;
		return true;
	}

	private static boolean canAccept(Container box,ItemStack stack) {
		for(int i=0;i<box.getContainerSize();i++) { ItemStack current=box.getItem(i); if(current.isEmpty()) return true;
			if(ItemStack.isSameItemSameComponents(current,stack)&&current.getCount()<current.getMaxStackSize()) return true; }
		return false;
	}

	private static boolean hasDeliverableCargo(ServerLevel level,VillageRef ref,net.minecraft.world.entity.animal.equine.Donkey donkey) {
		for(int i=2;i<17;i++) { var access=donkey.getSlot(500+i); if(access!=null&&!access.get().isEmpty()&&deliveryTarget(level,ref,access.get())!=null)return true; }
		return false;
	}

	/** Cargo has to match a communal depot or the persistent cash ledger. */
	private static DeliveryTarget deliveryTarget(ServerLevel level,VillageRef ref,ItemStack stack) {
		if(stack.isEmpty()) return null;
		if(stack.is(Items.EMERALD)) return new DeliveryTarget(ref.bell().immutable(),"treasury",null);
		if(StockPolicy.deliveryRoom(level,ref,stack.getItem())<=0) return null;
		String depot;
		BlockPos pos;
		switch(StockPolicy.category(stack.getItem())) {
			case FOOD -> { depot=de.villagerplus.build.Blueprints.GRANARY; pos=Specials.depotWithRoom(level,ref,depot,stack); }
			case TOOLS -> { depot=de.villagerplus.build.Blueprints.TOOLSHED; pos=Specials.depotWithRoom(level,ref,depot,stack); }
			case MATERIAL -> {
				depot=de.villagerplus.build.Blueprints.STORAGE;
				pos=ref.chestPos(level);
				Container chest=pos==null?null:ref.chest(level);
				if(chest==null||!canAccept(chest,stack)) {
					pos=Lager.chestWithRoom(level,ref.data(),stack);
				}
			}
			case OTHER -> { return null; }
			default -> { return null; }
		}
		Container destination=pos==null?null:HopperBlockEntity.getContainerAt(level,pos);
		return destination!=null&&canAccept(destination,stack)?new DeliveryTarget(pos.immutable(),depot,destination):null;
	}

	private static boolean restoreCargoSlot(net.minecraft.world.entity.SlotAccess access,ItemStack rest) {
		if(rest.isEmpty()) return true;
		ItemStack current=access.get();
		if(current.isEmpty()) return access.set(rest.copy());
		if(!ItemStack.isSameItemSameComponents(current,rest)||current.getCount()+rest.getCount()>current.getMaxStackSize()) return false;
		current.grow(rest.getCount()); return access.set(current);
	}

	static BlockPos porterStand(ServerLevel level,BlockPos chest) {
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=chest.relative(dir); if(!level.hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(level,net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null) return BlockPos.containing(safe);
		}
		return null;
	}

	private static boolean isPorterStand(ServerLevel level,BlockPos target,BlockPos stand) {
		if(stand==null) return false;
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=target.relative(dir); if(!level.hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(level,net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null&&BlockPos.containing(safe).equals(stand)) return true;
		}
		return false;
	}

	/** Selects a free adjacent stand only if the villager's bounded work-path search can reach it. */
	private static BlockPos reachablePorterStand(Ctx c,BlockPos target) {
		List<BlockPos> candidates=new ArrayList<>();
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=target.relative(dir); if(!c.level().hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(c.level(),net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null) candidates.add(BlockPos.containing(safe));
		}
		candidates.sort(java.util.Comparator.comparingDouble(p->p.distSqr(c.v().blockPosition())));
		for(BlockPos candidate:candidates) {
			var route=de.villagerplus.build.Builder.workPath(c,candidate);
			if(route!=null&&route.canReach()) return candidate;
		}
		return null;
	}

	public static boolean carryingFor(Villager villager) { return PORTER_CARRIES.containsKey(villager.getUUID()); }

	/** Same chest-slot layout as caravan spawn; call after {@link net.minecraft.world.entity.animal.equine.Donkey#setChest(boolean)}. */
	public static void prepareDonkeyCargo(net.minecraft.world.entity.animal.equine.Donkey donkey, int cargoSlot, ItemStack stack) {
		if (!donkey.hasChest()) donkey.setChest(true);
		var chestLayout = donkey.getSlot(499);
		if (chestLayout != null && chestLayout.get().isEmpty()) chestLayout.set(new ItemStack(Items.CHEST));
		var access = donkey.getSlot(500 + cargoSlot);
		if (access != null && access.set(stack.copy())) return;
		for (int slot = 2; slot < 17; slot++) {
			var fallback = donkey.getSlot(500 + slot);
			if (fallback == null) continue;
			if (fallback.set(stack.copy())) return;
		}
		throw new IllegalStateException("donkey cargo slot unavailable: " + cargoSlot);
	}

	public static void releasePorter(Villager villager) {
		if(villager.level() instanceof ServerLevel level) releasePorter(level,villager.getUUID());
	}

	private static void releasePorter(ServerLevel level,UUID porter) {
		PorterCarry carry=PORTER_CARRIES.remove(porter); if(carry==null) return;
		PORTER_CLAIMS.remove(carry.donkey,porter);
		if(level.getEntity(porter) instanceof Villager villager) {
			villager.getNavigation().stop();
			villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
		}
		Entity visual=carry.visual==null?null:level.getEntity(carry.visual); if(visual!=null) visual.discard();
		Entity donkey=level.getEntity(carry.donkey); if(donkey instanceof net.minecraft.world.entity.animal.equine.Donkey d) syncCargoVisual(level,d);
	}

	/** Reconciles chunk-persisted render markers after either their owner donkey or the marker itself loads. */
	private static void reconcileCargoDisplay(ServerLevel level, ItemDisplay display) {
		for (String tag : display.entityTags()) {
			if (!tag.startsWith("vp_caravan_cargo_owner:")) continue;
			try {
				UUID owner = UUID.fromString(tag.substring("vp_caravan_cargo_owner:".length()));
				if (level.getEntity(owner) instanceof net.minecraft.world.entity.animal.equine.Donkey) {
					CARGO_RECONCILE.put(owner, level.dimension());
				}
			} catch (IllegalArgumentException ignored) { }
			return;
		}
	}

	private static boolean hasCargo(ServerLevel level, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {

			for (int slot = 2; slot < 17; slot++) { var access = donkey.getSlot(500 + slot); if (access != null && !access.get().isEmpty()) return true; }

		}

		return false;

	}

	private static boolean hasCargo(net.minecraft.world.entity.animal.equine.Donkey donkey) {

		for (int slot = 2; slot < 17; slot++) { var access = donkey.getSlot(500 + slot); if (access != null && !access.get().isEmpty()) return true; }

		return false;

	}

	/** Beim Abbruch oder Serverende bleibt nicht entladene Ware auf einem persistenten Frachtesel erhalten. */
	private static void preserveCargo(ServerLevel level, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)) {

			donkey.removeLeash(); donkey.removeTag("vp_caravan"); donkey.setPersistenceRequired();

			donkey.setCustomName(Component.literal("Karawanen-Frachtesel")); donkey.setCustomNameVisible(true);
			syncCargoVisual(level, donkey);

		}

	}

	/** Entlaedt hoechstens einen Stapel pro Aufruf und behaelt ihn im Esel, wenn Dorfkiste und Lager voll sind. */
	private static boolean unloadOne(ServerLevel level, VillageRef ref, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {

			for (int slot = 2; slot < 17; slot++) {

				var access = donkey.getSlot(500 + slot); if (access == null) continue;

				ItemStack cargo = access.get(); if (cargo.isEmpty()) continue;

				int before = cargo.getCount(); ItemStack rest = StockPolicy.insertVillage(level, ref, cargo);

				int moved = before - rest.getCount();

				if (moved > 0) {

					cargo.shrink(moved); access.set(cargo.isEmpty() ? ItemStack.EMPTY : cargo);
					syncCargoVisual(level, donkey);

					level.playSound(null, donkey.blockPosition(), net.minecraft.sounds.SoundEvents.CHEST_CLOSE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.7F, 1.1F);

					level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, donkey.getX(), donkey.getY() + 1.2, donkey.getZ(), 3, 0.25, 0.2, 0.25, 0.0);

					return true;

				}

			}

		}

		return false;

	}

	/** Flache Stelle ~36 Bloecke von der Glocke in Richtung Startpunkt fuer das Rastlager. */
	private static BlockPos campSite(ServerLevel level, BlockPos bell, BlockPos spawn) {

		double dx = spawn.getX() - bell.getX(), dz = spawn.getZ() - bell.getZ(), len = Math.max(1.0, Math.hypot(dx, dz));

		for (int dist : new int[] {36, 32, 40, 28}) {

			for (int lateral : new int[] {0, 6, -6, 12, -12}) {

				int x = bell.getX() + (int) Math.round(dx / len * dist - dz / len * lateral), z = bell.getZ() + (int) Math.round(dz / len * dist + dx / len * lateral);

				BlockPos col = new BlockPos(x, bell.getY(), z);

				if (!level.hasChunkAt(col.offset(-3, 0, -3)) || !level.hasChunkAt(col.offset(3, 0, 3))) continue;

				BlockPos c = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col);

				if (campSiteUsable(level, c)) return c;

			}

		}

		return null;

	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> campLayout(BlockPos c) {
		var blocks = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
		var fence = net.minecraft.world.level.block.Blocks.SPRUCE_FENCE.defaultBlockState();
		for (int x = -1; x <= 1; x += 2) for (int z = -1; z <= 1; z += 2) for (int y = 0; y < 3; y++) blocks.put(c.offset(x, y, z), fence);
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) blocks.put(c.offset(x, 3, z),
			((x + z) & 1) == 0 ? net.minecraft.world.level.block.Blocks.WOOL.red().defaultBlockState() : net.minecraft.world.level.block.Blocks.WOOL.white().defaultBlockState());
		blocks.put(c.offset(3, 0, 0), net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState());
		blocks.put(c.offset(0, 0, 1), net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
		blocks.put(c.offset(-1, 0, 0), net.minecraft.world.level.block.Blocks.HAY_BLOCK.defaultBlockState());
		blocks.put(c.offset(-3, 0, 2), fence);
		blocks.put(c.offset(-3, 0, -2), fence);
		return blocks;
	}

	private static boolean campSiteUsable(ServerLevel level, BlockPos c) {
		return CaravanSites.canPlaceCampAt(level, c, campLayout(c).keySet());
	}

	/** Temporaeres Lager: Zeltplane auf Zaunpfosten, Feuerstelle, Fass und Heuballen. Alles wird beim Abzug wieder abgebaut. */
	private static boolean buildCamp(ServerLevel level, State s, BlockPos bell) {
		long now = level.getGameTime();
		if (now < s.campRetryAt) return false;
		BlockPos c = campSite(level, bell, s.spawn);
		if (c == null) {
			s.campRetryAt = now + 200;
			return false;
		}
		var layout = campLayout(c);
		if (!campSiteUsable(level, c)) {
			s.campRetryAt = now + 200;
			return false;
		}
		List<CaravanCampData.CampBlock> planned = layout.entrySet().stream()
			.map(entry -> new CaravanCampData.CampBlock(entry.getKey(), entry.getValue())).toList();
		if (!persistCampPlan(level, bell, planned)) {
			s.campRetryAt = now + 200;
			return false;
		}
		for (var entry : layout.entrySet()) {
			if (!level.setBlock(entry.getKey(), entry.getValue(), 3)) {
				removeCamp(level, s);
				s.campRetryAt = now + 200;
				return false;
			}
			s.camp.add(entry.getKey().immutable());
			s.campBlocks.add(entry.getValue());
		}
		s.campBuilt = true;
		level.playSound(null, c, net.minecraft.sounds.SoundEvents.WOOL_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawanen-Lager bei {} ({} Bloecke)", c, s.camp.size());
		return true;
	}

	/** Baut nur die Bloecke wieder ab, die noch genau so dort stehen (nichts Fremdes anfassen). */
	private static void removeCamp(ServerLevel level, State s) {
		List<CaravanCampData.CampBlock> records = new ArrayList<>();
		for (int i = 0; i < Math.min(s.camp.size(), s.campBlocks.size()); i++) records.add(new CaravanCampData.CampBlock(s.camp.get(i), s.campBlocks.get(i)));
		var bell = loadedBell(level, s.bell);
		if (bell != null) {
			List<CaravanCampData.CampBlock> persisted = attachedCampBlocks(bell);
			if (!persisted.isEmpty()) records = persisted;
		}
		List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, records);
		if (bell != null) saveCampBlocks(bell, remaining);
		queuePendingCampCleanup(level, s.bell, remaining);
		s.camp.clear(); s.campBlocks.clear();
		s.campBuilt = false;

	}

	private static void cleanup(ServerLevel level, State s) {

		removeCamp(level, s);

		preserveCargo(level, s);

		for (UUID id : s.llamas) { Entity e = level.getEntity(id); if (e != null && !(e instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey))) e.discard(); }

		Entity e = level.getEntity(s.trader); if (e != null) e.discard();

	}

	private static void tick(ServerLevel level) {
		if (level.getGameTime() % 20L == 0L) processPendingCampCleanups(level);
		long scanUntil = CARGO_STARTUP_SCAN.getOrDefault(level.dimension(), -1L);
		if (scanUntil >= level.getGameTime() && level.getGameTime() % 20L == 0L) {
			for (Entity candidate : level.getAllEntities()) {
				if (candidate instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)
					&& donkey.getCustomName() != null && donkey.getCustomName().getString().equals("Karawanen-Frachtesel")) syncCargoVisual(level, donkey);
			}
		} else if (scanUntil >= 0L && scanUntil < level.getGameTime()) {
			CARGO_STARTUP_SCAN.remove(level.dimension());
		}
		for (var entry : new HashMap<>(CARGO_RECONCILE).entrySet()) {
			if (!entry.getValue().equals(level.dimension())) continue;
			Entity owner = level.getEntity(entry.getKey());
			if (owner instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) syncCargoVisual(level, donkey);
			CARGO_RECONCILE.remove(entry.getKey());
		}

		if (level.getGameTime() % 4 != 0) return;

		for (Iterator<State> it = ACTIVE.values().iterator(); it.hasNext();) {

			State s = it.next(); if (!s.dimension.equals(level.dimension())) continue;

			Entity e = level.getEntity(s.trader);

			if (!(e instanceof WanderingTrader trader) || !trader.isAlive()) {

				if (e == null && !level.hasChunkAt(s.spawn) && level.getGameTime() < s.born + 10000) continue;

				cleanup(level, s); it.remove(); continue;

			}

			VillageRef ref = resolve(level, s.bell);

			if (ref == null || (level.getGameTime() > s.born + 12000 && !hasCargo(level, s))) { cleanup(level, s); it.remove(); continue; }
			for (UUID donkeyId : s.llamas) if (level.getEntity(donkeyId) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) syncCargoVisual(level, donkey);

			int uses = trader.getOffers().stream().mapToInt(net.minecraft.world.item.trading.MerchantOffer::getUses).sum();

			int delta = Math.max(0, uses - s.uses); s.uses = uses;

			if (delta > 0 && s.reward < 10) {

				int reward = Math.min(10 - s.reward, delta * 2); s.reward += reward; changeRelation(ref, s.partner, reward, -1);

				var player = level.getNearestPlayer(trader, 12);

				if (player != null) VillageMessages.system(player, Component.literal("Handel abgeschlossen: Beziehung +" + reward));

			}

			if (!s.campBuilt && trader.position().distanceToSqr(ref.bell().getX() + 0.5, ref.bell().getY(), ref.bell().getZ() + 0.5) <= 60 * 60) buildCamp(level, s, ref.bell());

			BlockPos chest = ref.chestPos(level); if (chest == null) continue;

			// The caravan no longer commits cargo remotely. Porters carry it to this crate and commit only on arrival.
			if (s.traded && !hasCargo(level, s) && s.leaveAt == Long.MAX_VALUE) s.leaveAt = level.getGameTime() + 1200;

			boolean leaving = s.traded && s.leaveAt != Long.MAX_VALUE && level.getGameTime() >= s.leaveAt;

			BlockPos target = leaving ? s.spawn : chest;
			BlockPos roadTarget = roadWaypoint(level, ref, trader.blockPosition(), target, s);
			if (roadTarget != null) target = roadTarget;

			if (s.guard != null && level.getGameTime() % 20 == 0 && level.getEntity(s.guard) instanceof net.minecraft.world.entity.animal.golem.IronGolem g && g.distanceToSqr(trader) > 36) g.getNavigation().moveTo(trader, 1.1);

			if (level.getGameTime() % 20 == 0 && trader.getTradingPlayer() == null) {

				trader.setWanderTarget(target);

				trader.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);

			}

			if (!s.traded && trader.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(chest)) <= 16) {

				s.traded = true; s.leaveAt = Long.MAX_VALUE; s.nextUnload = level.getGameTime() + 20; trader.setCustomName(Component.literal("Händler aus " + Network.of(ref.bellEntity()).partners().get(Math.min(s.partner, Network.of(ref.bellEntity()).partners().size() - 1)).name()));

				exchange(level, ref, trader);

				changeRelation(ref, s.partner, 3, level.getGameTime() / 24000);

				org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane angekommen bei {}, Distanz {}", trader.blockPosition(), Math.sqrt(trader.blockPosition().distSqr(chest)));

			} else if (leaving && trader.getTradingPlayer() == null && (trader.blockPosition().distSqr(s.spawn) <= 16 || level.getGameTime() > s.leaveAt + 6000)) {

				cleanup(level, s); it.remove();

			}

		}

	}

	/**
	 * Follow a finished, physically paved canonical overland route when it connects the
	 * caravan's current position to its current destination. The trader's ordinary
	 * navigator remains the fallback for legacy links, incomplete roads, distant
	 * starts, and unloaded route sections.
	 */
	private static BlockPos roadWaypoint(ServerLevel level, VillageRef ref, BlockPos current, BlockPos destination, State caravan) {
		Map<String, Long> roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
		TradeNetwork network = Network.of(ref.bellEntity());
		String dimension = Network.dim(level);
		String selectedKey = null;
		BlockPos selectedOther = null;
		int selectedCursor = -1;
		int selectedDestination = -1;
		double best = Double.POSITIVE_INFINITY;

		for (TradeNetwork.Link link : network.links()) {
			if (!link.route() || !link.dimension().equals(dimension)) continue;
			BlockPos other = link.bell();
			String key = link.dimension() + other.asLong();
			VillageRef peer = Network.resolve(level, link);
			Map<String, Long> peerRoads = peer == null ? null
				: peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
			String peerKey = dimension + ref.bell().asLong();
			if (!RoadRoute.ensureWithRetry(level, ref.bell(), other, roads, key, peerRoads, peerKey,
				level.getGameTime(), false)) continue;
			int length = RoadRoute.length(roads, key, ref.bell(), other);
			if (length <= 0) continue;

			int hereCursor = -1, destinationCursor = -1;
			double hereDistance = Double.POSITIVE_INFINITY, destinationDistance = Double.POSITIVE_INFINITY;
			for (int i = 0; i <= length; i++) {
				BlockPos routePoint = RoadRoute.point(roads, key, ref.bell(), other, i);
				BlockPos probe = new BlockPos(routePoint.getX(), ref.bell().getY(), routePoint.getZ());
				if (!level.hasChunkAt(probe)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, routePoint.getX(), routePoint.getZ());
				BlockPos surface = new BlockPos(routePoint.getX(), y - 1, routePoint.getZ());
				if (!level.hasChunkAt(surface) || !isPavedRoad(level.getBlockState(surface))) continue;
				BlockPos feet = new BlockPos(surface.getX(), y, surface.getZ());
				double from = current.distSqr(feet);
				double to = destination.distSqr(feet);
				if (from < hereDistance) { hereDistance = from; hereCursor = i; }
				if (to < destinationDistance) { destinationDistance = to; destinationCursor = i; }
			}
			// A short gap at either endpoint is fine; a route across the map is not a
			// useful detour for a caravan spawned or departing somewhere else.
			if (hereCursor < 0 || destinationCursor < 0 || hereDistance > 128 * 128 || destinationDistance > 12 * 12) continue;
			int direction = Integer.compare(destinationCursor, hereCursor);
			if (direction == 0) continue;
			int cursor = hereCursor;
			if (key.equals(caravan.roadKey) && caravan.roadCursor >= 0) {
				// Keep progress monotonic while the trader oscillates slightly around a
				// waypoint. Reset naturally when changing links or travel direction.
				if ((direction > 0 && caravan.roadCursor <= destinationCursor)
					|| (direction < 0 && caravan.roadCursor >= destinationCursor)) cursor = direction > 0
					? Math.max(cursor, Math.min(caravan.roadCursor, destinationCursor))
					: Math.min(cursor, Math.max(caravan.roadCursor, destinationCursor));
			}
			double score = hereDistance + destinationDistance * 0.25;
			if (score < best) {
				best = score; selectedKey = key; selectedCursor = cursor;
				selectedOther = other; selectedDestination = destinationCursor;
			}
		}
		ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
		ref.dirty();
		if (selectedKey == null) { caravan.roadKey = null; caravan.roadCursor = -1; return null; }

		int direction = Integer.compare(selectedDestination, selectedCursor);
		int next = selectedCursor + direction * Math.min(6, Math.abs(selectedDestination - selectedCursor));
		caravan.roadKey = selectedKey;
		caravan.roadCursor = next;
		BlockPos routePoint = RoadRoute.point(roads, selectedKey, ref.bell(), selectedOther, next);
		if (!level.hasChunkAt(new BlockPos(routePoint.getX(), ref.bell().getY(), routePoint.getZ()))) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, routePoint.getX(), routePoint.getZ());
		BlockPos waypoint = new BlockPos(routePoint.getX(), y, routePoint.getZ());
		return level.hasChunkAt(waypoint) ? waypoint : null;
	}

	private static boolean isPavedRoad(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
			|| state.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)
			|| state.is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS);
	}

	// --- Handel mit dem imaginaeren Dorf --------------------------------------------------------

	/** Wert in "Einheiten" (64 Einheiten = 1 Smaragd). 0 = wird nie gehandelt. */

	static int value(Item item) {

		String p = BuiltInRegistries.ITEM.getKey(item).getPath();

		if (item == Items.EMERALD) {

			return 0;

		}

		return switch (p) {

			case "dirt", "gravel", "sand", "cobblestone", "cobbled_deepslate", "andesite", "diorite", "granite", "netherrack",

				"red_sand", "sandstone", "clay_ball" -> 1;

			case "coal", "charcoal", "copper_ingot" -> 8;

			case "raw_iron", "raw_copper" -> 16;

			case "iron_ingot" -> 24;

			case "raw_gold", "gold_ingot", "lapis_lazuli", "redstone" -> 32;

			case "wheat", "carrot", "potato", "beetroot", "sugar_cane", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "egg" -> 3;

			case "bread", "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cod", "salmon", "cooked_cod",

				"cooked_salmon", "beef", "porkchop", "mutton", "chicken", "baked_potato" -> 6;

			case "leather", "feather", "string", "bone", "flint", "stick", "paper" -> 4;

			case "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log" -> 8;

			default -> p.endsWith("_wool") ? 6 : p.endsWith("_planks") ? 3 : 0;

		};

	}

	/** So viel behaelt das Dorf immer selbst. */

	static int keep(Item item) {

		String p = BuiltInRegistries.ITEM.getKey(item).getPath();

		return switch (p) {

			case "dirt", "gravel", "clay_ball", "netherrack", "red_sand", "sandstone" -> 32;

			case "sand" -> 64;

			case "cobblestone", "cobbled_deepslate", "andesite", "diorite", "granite" -> 288;

			case "coal", "charcoal", "raw_iron", "iron_ingot", "raw_copper", "copper_ingot" -> 24;

			case "wheat", "carrot", "potato", "beetroot", "bread", "wheat_seeds" -> 64;

			default -> p.endsWith("_planks") ? 160 : p.endsWith("_wool") ? 24 : 64;

		};

	}

	private static void exchange(ServerLevel level, VillageRef ref, WanderingTrader trader) {

		Container box = ref.chest(level);

		if (box == null) {

			return;

		}

		State caravan = ACTIVE.get(trader.getUUID());

		String town = caravan == null ? "Fernhausen" : Network.of(ref.bellEntity()).partners().get(caravan.partner).name();

		List<String> sold = new ArrayList<>();

		List<String> bought = new ArrayList<>();

		long units = 0;

		// 1) Ueberschuss verkaufen.

		// Dorfkiste UND Lager: Was sich im Lager staut, nimmt der Haendler mit (so laeuft es nicht dauerhaft voll).

		List<Container> stores = new ArrayList<>();

		stores.add(box);

		stores.addAll(Lager.containers(level, ref.data()));

		Map<Item, Integer> totals = new HashMap<>();

		for (Container store : stores) {

			for (int i = 0; i < store.getContainerSize(); i++) {

				ItemStack s = store.getItem(i);

				if (!s.isEmpty() && value(s.getItem()) > 0) {

					totals.merge(s.getItem(), s.getCount(), Integer::sum);

				}

			}

		}

		for (Map.Entry<Item, Integer> e : totals.entrySet()) {

			int surplus = e.getValue() - keep(e.getKey());

			if (surplus >= 8) {

				Item item = e.getKey();

				int taken = 0;

				for (Container store : stores) {

					if (taken < surplus) {

						taken += JobUtil.take(store, s -> s.is(item), surplus - taken);

					}

				}

				units += (long) taken * value(item);

				sold.add(taken + "x " + new ItemStack(item).getHoverName().getString());

			}

		}

		// Der "imaginaere" Handelspartner zahlt wenig: hoechstens 6 Smaragde je Besuch, damit Smaragde knapp bleiben wie in Vanilla.

		int emeraldsIn = (int) Math.min(6, units / 96);

		if (emeraldsIn > 0) {

		insert(level, caravan, ref, box, new ItemStack(Items.EMERALD, emeraldsIn));

		}

		// Tauschhandel: Was verkauft wurde und nicht in Smaragde ging, bleibt als Guthaben und bezahlt Fehlendes direkt in Waren.

		long credit = units - emeraldsIn * 96L;

		// 2) Fehlendes (Wunschliste) und Grundbedarf kaufen.

		List<String> wishes = new ArrayList<>(ref.data().wanted);

		wishes.add("minecraft:wheat_seeds#32");

		wishes.add("minecraft:torch#16");

		wishes.add("minecraft:oak_sapling#4");

		for (String w : wishes) {

			String[] parts = w.split("#");

			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));

			if (item.isEmpty()) {

				continue;

			}

			int want = parts.length > 1 ? parseInt(parts[1]) : 8;

			int have = JobUtil.count(box, s -> s.is(item.get())) + Lager.countAll(level, ref.data(), s -> s.is(item.get()));

			int missing = want - have;

			if (missing <= 0) {

				continue;

			}

			// Erst mit dem Tausch-Guthaben bezahlen: Baumaterial kostet 3 Einheiten je Stueck, alles andere 12.

			long unit = item.get() instanceof net.minecraft.world.item.BlockItem ? 3 : 12;

			int byCredit = (int) Math.min(missing, credit / unit);

			if (byCredit > 0) {

				credit -= byCredit * unit;

				insert(level, caravan, ref, box, new ItemStack(item.get(), byCredit));

				bought.add(byCredit + "x " + new ItemStack(item.get()).getHoverName().getString() + " (Tausch)");

				missing -= byCredit;

				if (missing <= 0) {

					continue;

				}

			}

			int price = Math.max(2, value(item.get()) * 2);

			if (value(item.get()) == 0) {

				// Baumaterial (Bloecke) ist guenstig: der Haendler bringt so Dinge, die das Dorf selbst nicht herstellen kann.

				price = item.get() == Items.TORCH ? 2 : item.get() == Items.OAK_SAPLING ? 16

					: item.get() instanceof net.minecraft.world.item.BlockItem ? 8 : 24;

			}

			int emeralds = JobUtil.count(box, s -> s.is(Items.EMERALD));
			long treasury=Network.treasuryEmeralds(ref);
			long funds=treasury>Long.MAX_VALUE-emeralds?Long.MAX_VALUE:treasury+emeralds;
			long tradeUnits=funds>Long.MAX_VALUE/64L?Long.MAX_VALUE:funds*64L;

			int affordable = (int)Math.min(missing, tradeUnits / price);

			if (affordable <= 0) {

				continue;

			}

			int cost = (affordable * price + 63) / 64;

			int fromBox=Math.min(emeralds,cost);
			long fromTreasury=cost-fromBox;
			if(fromTreasury>0&&!Network.spendTreasuryEmeralds(ref,fromTreasury)) continue;
			JobUtil.take(box, s -> s.is(Items.EMERALD), fromBox);

			insert(level, caravan, ref, box, new ItemStack(item.get(), affordable));

			bought.add(affordable + "x " + new ItemStack(item.get()).getHoverName().getString());

		}

		de.villagerplus.build.Builder.caravanVisited(level, ref, box);

		// 3) Tiere fuer das Gehege.

		int animals = restockPen(level, ref, box);

		if (animals > 0) {

			bought.add(animals + " Tiere");

		}

		Journal.add(level,ref,"Handel",town,"Karawane: verkauft ["+String.join(", ",sold)+"], gekauft ["+String.join(", ",bought)+"]",units);
		Component msg = Component.literal("Die Karawane aus " + town + " tauscht Waren: verkauft [" + String.join(", ", sold)

			+ "], gekauft [" + String.join(", ", bought) + "].");

		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: {} (Wunschliste {})", msg.getString(), wishes);

		JobUtil.tellNearbyPlayers(level, trader, 48, msg);

		ref.dirty();

	}

	private static int parseInt(String s) {

		try {

			return Integer.parseInt(s);

		} catch (NumberFormatException e) {

			return 8;

		}

	}

	private static void insert(ServerLevel level, State caravan, VillageRef ref, Container box, ItemStack stack) {

		// Echte Handelsware wird in die Eseltruhe geladen und nach Ankunft stapelweise entladen.

		ItemStack rest = stack.copy(); packCargo(level, caravan, rest);

		// Ohne Esel oder bei erschoepftem Frachtraum bleibt der bisherige Depot-Fallback erhalten.
		if (!rest.isEmpty()) Lager.deposit(level, ref, rest);

	}

	/** Kauft fehlende Tierarten (je 2 Tiere) fuer das Gehege, solange Smaragde da sind. */

	private static int restockPen(ServerLevel level, VillageRef ref, Container box) {

		if (ref.data().extra.pen.isEmpty()) {

			return 0;

		}

		BlockPos pen = ref.data().extra.pen.get();

		int bought = 0;

		Object[][] stock = {{EntityTypes.COW, 6}, {EntityTypes.SHEEP, 4}, {EntityTypes.PIG, 5}, {EntityTypes.CHICKEN, 2}};

		for (Object[] row : stock) {

			@SuppressWarnings("unchecked")

			EntityType<? extends Animal> type = (EntityType<? extends Animal>) row[0];

			int price = (Integer) row[1] * 2;

			long present = level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(6, 3, 6), a -> a.isAlive() && a.getType() == type).size();

			int physical=JobUtil.count(box, s -> s.is(Items.EMERALD));
			if (present >= 2 || (long)physical+Network.treasuryEmeralds(ref) < price) {

				continue;

			}

			int fromBox=Math.min(physical,price);
			long fromTreasury=price-fromBox;
			if(fromTreasury>0&&!Network.spendTreasuryEmeralds(ref,fromTreasury)) continue;
			JobUtil.take(box, s -> s.is(Items.EMERALD), fromBox);

			for (int i = 0; i < 2; i++) {

				BlockPos at = pen.offset(i == 0 ? -1 : 1, 0, i == 0 ? 1 : -1);

				if (type.spawn(level, at, EntitySpawnReason.EVENT) != null) {

					bought++;

				}

			}

		}

		return bought;

	}

}

