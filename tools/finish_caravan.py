from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'build/Architect.java';s=p.read_text();s=s.replace('dy >= 2 ? p.wall2()', 'dy >= (h + 1) / 2 ? p.wall2()');s=s.replace('\t\troof(m, d, p, c, dir, perp);','''		boolean stoneDetails = terracotta(p.wall()) || terracotta(p.wall2()) || p.wall() != p.wall2();
		Block stone = terracotta(p.base()) ? Blocks.STONE_BRICKS : p.base();
		if (stoneDetails) {
			for (int i = -hw; i <= hw; i++) for (int j = -hd; j <= hd; j++) {
				if (Math.abs(i) != hw && Math.abs(j) != hd) continue;
				boolean corner = Math.abs(i) == hw && Math.abs(j) == hd;
				if (corner && p.frame() == null) for (int y = 0; y < h; y++) put(m, Step.block(at(c, dir, i, j, y), stone));
				BlockPos belt = at(c, dir, i, j, h / 2);
				Step existing = m.get(belt);
				if (h >= 4 && existing != null && existing.item() != p.door().asItem() && existing.linkedTo() == null && existing.item() != p.window().asItem()) put(m, Step.block(belt, stone));
				for (int y = 1; y < h - 1; y += 2) {
					Step win = m.get(at(c, dir, i, j, y));
					if (win != null && win.item() == p.window().asItem()) put(m, Step.block(at(c, dir, i, j, y - 1), stone));
				}
			}
		}
		roof(m, d, p, c, dir, perp);''');s=s.replace('\t\tList<Step> steps = new ArrayList<>(m.values());','''		// Count actual facade/gable material after doors, windows and wings have overwritten cells.
		List<Step> facade = m.values().stream().filter(st -> !st.free() && st.pos().getY() >= c.getY()
			&& (st.item() == p.wall().asItem() || st.item() == p.wall2().asItem() || st.item() == stone.asItem()
				|| (p.frame() != null && st.item() == p.frame().asItem())))
			.sorted(Comparator.comparingInt(st -> st.pos().getY())).toList();
		long terra = facade.stream().filter(st -> terracotta(Block.byItem(st.item()))).count();
		for (Step st : facade) {
			if (terra * 2 <= facade.size()) break;
			if (terracotta(Block.byItem(st.item()))) { put(m, Step.block(st.pos(), stone)); terra--; }
		}
		List<Step> steps = new ArrayList<>(m.values());''');pos=s.index('\n\t/** Zustand');s=s[:pos]+'''
	private static boolean terracotta(Block block) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath().contains("terracotta");
	}
''' + s[pos:];p.write_text(s)
p=r/'village/Caravan.java';s=p.read_text();start=s.index('\tprivate static final class State');end=s.index('\n\t// --- Handel mit dem imaginaeren Dorf');s=s[:start]+'''	private static final class State {
		final UUID trader;
		final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
		final BlockPos bell, spawn;
		final int partner;
		final List<UUID> llamas = new ArrayList<>();
		final long born;
		boolean traded;
		long leaveAt;
		int uses, reward;
		State(WanderingTrader trader, ServerLevel level, VillageRef ref, BlockPos spawn, int partner) {
			this.trader = trader.getUUID(); this.dimension = level.dimension(); this.bell = ref.bell();
			this.spawn = spawn; this.partner = partner; this.born = level.getGameTime();
		}
	}
	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	private Caravan() {}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(Caravan::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.clear());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			// Runtime routes are not persisted; remove their old tagged entities on world reload.
			if (entity.entityTags().contains("vp_caravan") && !ACTIVE.containsKey(entity.getUUID())
				&& ACTIVE.values().stream().noneMatch(st -> st.llamas.contains(entity.getUUID()))) entity.discard();
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!(world instanceof ServerLevel level) || hand != net.minecraft.world.InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.PASS;
			State state = ACTIVE.get(entity.getUUID());
			if (state == null || !player.getItemInHand(hand).is(Items.EMERALD)) return net.minecraft.world.InteractionResult.PASS;
			VillageRef ref = resolve(level, state.bell);
			if (ref != null && state.reward < 10) {
				player.getItemInHand(hand).shrink(1); changeRelation(ref, state.partner, 2, -1);
				state.reward += 2;
				player.displayClientMessage(Component.literal("Das Partnerdorf bedankt sich für dein Geschenk (+2 Beziehung)."), false);
			}
			return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
		});
	}

	public static void maybeSpawn(ServerLevel level, VillageRef ref, Villager who, long now) {
		if (ref.data().extra.nextCaravan == 0) { ref.data().extra.nextCaravan = now + 6000; ref.dirty(); return; }
		if (now < ref.data().extra.nextCaravan || ref.chestPos(level) == null) return;
		if (level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(Config.VILLAGE_RADIUS), Villager::isAlive).size() < Config.CARAVAN_MIN_POP) return;
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
		ref.data().extra.nextCaravan = now + (long) (Config.CARAVAN_INTERVAL * (route ? 0.6 : 1.0) * (1.0 - part.relation() / 500.0)); ref.dirty();
		BlockPos at = arrival(level, ref.bell(), 70, 100);
		if (at == null) at = arrival(level, ref.bell(), 34, 46);
		if (at == null) return false;
		WanderingTrader trader = EntityTypes.WANDERING_TRADER.spawn(level, at, EntitySpawnReason.EVENT);
		if (trader == null) return false;
		State state = new State(trader, level, ref, at, partner); ACTIVE.put(trader.getUUID(), state);
		trader.addTag("vp_caravan"); trader.setDespawnDelay(30000);
		trader.setCustomName(Component.literal("Händler aus " + part.name())); trader.setCustomNameVisible(true);
		trader.setWanderTarget(ref.chestPos(level));
		trader.getOffers().clear(); trader.getOffers().addAll(partnerOffers(part));
		for (int i = 0; i < 2; i++) {
			var llama = EntityTypes.TRADER_LLAMA.spawn(level, at.offset(i == 0 ? -1 : 1, 0, 1), EntitySpawnReason.EVENT);
			if (llama != null) { llama.addTag("vp_caravan"); llama.setLeashedTo(trader, true); state.llamas.add(llama.getUUID()); }
		}
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane {}: Start {}, Glocke {}, Abstand {}", part.name(), at, ref.bell(), Math.sqrt(at.distSqr(ref.bell())));
		return true;
	}

	private static BlockPos arrival(ServerLevel level, BlockPos bell, int min, int max) {
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
			return p;
		}
		return null;
	}

	private static net.minecraft.world.item.trading.MerchantOffers partnerOffers(TradeNetwork.Partner p) {
		var offers = new net.minecraft.world.item.trading.MerchantOffers();
		Item[][] stock = {{Items.IRON_INGOT, Items.COAL, Items.COPPER_INGOT}, {Items.STRING, Items.LEATHER, Items.WHITE_WOOL},
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
		TradeNetwork n = Network.of(ref.bellEntity()); List<TradeNetwork.Partner> ps = new ArrayList<>(n.partners());
		if (index >= ps.size()) return;
		var p = ps.get(index).withRelation(ps.get(index).relation() + delta);
		ps.set(index, day >= 0 ? p.visited(day) : p); Network.save(ref.bellEntity(), n.withPartners(ps));
	}
	private static void cleanup(ServerLevel level, State s) {
		for (UUID id : s.llamas) { Entity e = level.getEntity(id); if (e != null) e.discard(); }
		Entity e = level.getEntity(s.trader); if (e != null) e.discard();
	}
	private static void tick(ServerLevel level) {
		if (level.getGameTime() % 4 != 0) return;
		for (Iterator<State> it = ACTIVE.values().iterator(); it.hasNext();) {
			State s = it.next(); if (!s.dimension.equals(level.dimension())) continue;
			Entity e = level.getEntity(s.trader);
			if (!(e instanceof WanderingTrader trader) || !trader.isAlive()) {
				if (e == null && !level.hasChunkAt(s.spawn) && level.getGameTime() < s.born + 10000) continue;
				cleanup(level, s); it.remove(); continue;
			}
			VillageRef ref = resolve(level, s.bell);
			if (ref == null || level.getGameTime() > s.born + 12000) { cleanup(level, s); it.remove(); continue; }
			int uses = trader.getOffers().stream().mapToInt(net.minecraft.world.item.trading.MerchantOffer::getUses).sum();
			int delta = Math.max(0, uses - s.uses); s.uses = uses;
			if (delta > 0 && s.reward < 10) {
				int reward = Math.min(10 - s.reward, delta * 2); s.reward += reward; changeRelation(ref, s.partner, reward, -1);
				var player = level.getNearestPlayer(trader, 12);
				if (player != null) player.displayClientMessage(Component.literal("Handel abgeschlossen: Beziehung +" + reward), false);
			}
			BlockPos chest = ref.chestPos(level); if (chest == null) continue;
			boolean leaving = s.traded && level.getGameTime() >= s.leaveAt;
			BlockPos target = leaving ? s.spawn : chest;
			if (level.getGameTime() % 20 == 0 && trader.getTradingPlayer() == null) {
				trader.setWanderTarget(target);
				trader.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
			}
			if (!s.traded && trader.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(chest)) <= 16) {
				s.traded = true; s.leaveAt = level.getGameTime() + 1200;
				exchange(level, ref, trader);
				changeRelation(ref, s.partner, 3, level.getGameTime() / 24000);
				org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane angekommen bei {}, Distanz {}", trader.blockPosition(), Math.sqrt(trader.blockPosition().distSqr(chest)));
			} else if (leaving && trader.getTradingPlayer() == null && (trader.blockPosition().distSqr(s.spawn) <= 16 || level.getGameTime() > s.leaveAt + 6000)) {
				cleanup(level, s); it.remove();
			}
		}
	}
''' + s[end:];s=s.replace('String town = TOWNS[trader.getRandom().nextInt(TOWNS.length)];','State caravan = ACTIVE.get(trader.getUUID());\n\t\tString town = caravan == null ? "Fernhausen" : Network.of(ref.bellEntity()).partners().get(caravan.partner).name();');p.write_text(s)
p=r/'StatusCommand.java';s=p.read_text();needle='\t\t\tdispatcher.register(Commands.literal("vpplan")';s=s.replace(needle,'''			dispatcher.register(Commands.literal("vpkaravane").executes(ctx -> {
				var source = ctx.getSource();
				var ref = de.villagerplus.village.Villages.villageNear(source.getLevel(), BlockPos.containing(source.getPosition()), 96);
				boolean spawned = ref != null && de.villagerplus.village.Caravan.spawn(source.getLevel(), ref);
				source.sendSuccess(() -> Component.literal(spawned ? "Karawane gestartet" : "Kein Dorf/Platz oder Karawane bereits unterwegs"), false);
				return spawned ? 1 : 0;
			}));
''' + needle);p.write_text(s)
