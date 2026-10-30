package de.villagerplus.village;

import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.ModBlocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Dorftafel: Ein Schild in der Dorfmitte. Rechtsklick zeigt die Uebersicht (Karte und Diagramme); die Reiter
 * "Uebersicht", "Details" (alle Zahlen) und "Expeditionen" lassen sich anklicken oder per Befehl
 * {@code /villagerplus tafel <reiter>} aufrufen. Schleichen + Rechtsklick zeigt direkt die Expeditionen.
 */
public final class Board {
	public static final String[][] TABS = {{"uebersicht", "Übersicht"}, {"details", "Details"}, {"expeditionen", "Expeditionen"}};

	private Board() {
	}

	public static void register() {
		// Die Dorftafel ist ein eigener Block (siehe DorftafelBlock); hier gibt es nichts mehr zu registrieren.
	}

	/** Schreibt den gewaehlten Reiter der Dorftafel. */
	public static void show(Consumer<Component> out, ServerLevel level, VillageRef ref, String tab) {
		out.accept(header(tab));
		switch (tab) {
			case "details" -> {
				for (String line : VillageStats.lines(level, ref)) {
					out.accept(Component.literal(line));
				}
			}
			case "expeditionen" -> {
				for (String line : Expedition.lines(level, ref, level.getGameTime())) {
					out.accept(Component.literal(line));
				}
			}
			default -> {
				for (Component line : VillageMap.render(level, ref)) {
					out.accept(line);
				}
			}
		}
	}

	private static Component header(String active) {
		MutableComponent h = Component.literal("══ Dorftafel ══  ").withStyle(ChatFormatting.GOLD);
		for (String[] tab : TABS) {
			boolean on = tab[0].equals(active);
			Style style = Style.EMPTY.withColor(on ? ChatFormatting.YELLOW : ChatFormatting.AQUA).withUnderlined(!on)
				.withClickEvent(new ClickEvent.RunCommand("villagerplus tafel " + tab[0]));
			h.append(Component.literal("[" + tab[1] + "] ").withStyle(style));
		}
		return h;
	}

	/** Stellt sicher, dass neben der Dorfkiste eine Dorftafel (3 Bloecke breit, 2 hoch) steht; alte Tafeln werden ersetzt. */
	public static void ensure(ServerLevel level, VillageRef ref) {
		BlockPos chest = ref.chestPos(level);
		if (chest == null) {
			return;
		}
		Optional<BlockPos> board = ref.data().extra.board;
		if (board.isPresent()) {
			BlockState cur = level.getBlockState(board.get());
			if (cur.getBlock() instanceof DorftafelBlock) {
				BlockPos[] parts = DorftafelBlock.parts(cur, board.get());
				if (DorftafelBlock.complete(level, board.get(), cur)) {
					return;
				}
				// Aeltere, kleinere Tafel: abbauen und neu (in voller Groesse) aufstellen.
				for (BlockPos p : parts) {
					for (BlockPos q : new BlockPos[] {p, p.above()}) {
						if (level.getBlockState(q).getBlock() instanceof DorftafelBlock) {
							level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
			} else if (cur.getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock) {
				level.setBlock(board.get(), Blocks.AIR.defaultBlockState(), 3);
			}
			for (BlockPos p : BlockPos.betweenClosed(board.get().offset(-3, 0, -3), board.get().offset(3, 1, 3))) {
				if (level.getBlockState(p).getBlock() instanceof DorftafelBlock) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
		for (BlockPos p : BlockPos.betweenClosed(chest.offset(-5, 0, -5), chest.offset(5, 0, 5))) {
			double dist = p.distSqr(chest);
			if (dist < 6.0 || dist > 26.0) {
				continue;
			}
			Direction toChest = Sites.facingTowards(p, chest);
			Direction r = toChest.getCounterClockWise();
			boolean ok = true;
			for (int i = 0; i < 3 && ok; i++) {
				BlockPos t = p.relative(r, i);
				BlockPos below = t.below();
				ok = level.getBlockState(t).isAir() && level.getBlockState(t.above()).isAir() && level.getBlockState(t.above(2)).isAir()
					&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
			}
			if (!ok) {
				continue;
			}
			DorftafelBlock.placeAll(level, p.immutable(), ModBlocks.DORFTAFEL.defaultBlockState().setValue(DorftafelBlock.FACING, toChest));
			ref.data().extra.board = Optional.of(p.immutable());
			ref.dirty();
			return;
		}
	}
}
