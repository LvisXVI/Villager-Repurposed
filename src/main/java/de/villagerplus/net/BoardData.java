package de.villagerplus.net;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Names;
import de.villagerplus.village.VillageMap;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStats;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Server -> Client: alles, was die Dorftafel anzeigt. Karte als zwei Byte-Gitter (Gelaende, Markierungen), Balken
 * ({@code Label|Text|Prozent|RGB}), Einwohnerliste ({@code Name|Beruf|Taetigkeit|Stimmung|Haus|UUID|Berufs-ID|Kind|Lehrlingsziel}) und Textzeilen.
 */
public record BoardData(BlockPos board, String village, int size, int cellSize, byte[] cells, byte[] marks, List<String> bars, List<String> residents,
	List<String> details, List<String> expeditions, List<String> neighbors, List<String> partners, List<String> journal) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BoardData> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "board_data"));
	private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> JOURNAL_STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(400));
	private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(200));
	public static final StreamCodec<RegistryFriendlyByteBuf, BoardData> CODEC = StreamCodec.of(
		(buf, data) -> {
			BlockPos.STREAM_CODEC.encode(buf, data.board());
			ByteBufCodecs.STRING_UTF8.encode(buf, data.village());
			ByteBufCodecs.VAR_INT.encode(buf, data.size());
			ByteBufCodecs.VAR_INT.encode(buf, data.cellSize());
			ByteBufCodecs.BYTE_ARRAY.encode(buf, data.cells());
			ByteBufCodecs.BYTE_ARRAY.encode(buf, data.marks());
			STRINGS.encode(buf, data.bars());
			STRINGS.encode(buf, data.residents());
			STRINGS.encode(buf, data.details());
			STRINGS.encode(buf, data.expeditions());
			STRINGS.encode(buf, data.neighbors());
			STRINGS.encode(buf, data.partners());
			JOURNAL_STRINGS.encode(buf, data.journal());
		},
		buf -> new BoardData(
			BlockPos.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
			ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
			ByteBufCodecs.BYTE_ARRAY.decode(buf), ByteBufCodecs.BYTE_ARRAY.decode(buf),
			STRINGS.decode(buf), STRINGS.decode(buf), STRINGS.decode(buf), STRINGS.decode(buf),
			STRINGS.decode(buf), STRINGS.decode(buf), JOURNAL_STRINGS.decode(buf)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static BoardData build(ServerLevel level, VillageRef ref, BlockPos board) {
		byte[][] grid = VillageMap.grid(level, ref);
		List<String> residents = new ArrayList<>();
		List<Villager> people = de.villagerplus.village.Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v));
		de.villagerplus.village.Journal.sample(level,ref,people,level.getGameTime());
		people.sort(Comparator.comparing(v -> v.getAttachedOrCreate(VillagerPlus.MEMORY).name));
		for (Villager v : people) {
			if (residents.size() >= 190) {
				break;
			}
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			Wellbeing.Status st = Wellbeing.of(level, v, mem);
			String apprenticeship = de.villagerplus.job.Apprentices.boardLabel(v);
			String job = apprenticeship != null ? apprenticeship : Names.profession(v.getVillagerData().profession()) + " " + v.getVillagerData().level();
			String marketRole = de.villagerplus.village.Market.role(ref,v); if (apprenticeship == null && !marketRole.isEmpty()) job = marketRole;
			String profKey = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:none");
			residents.add((mem.name.isEmpty() ? "?" : mem.name) + "|" + job + "|" + JobManager.activityOf(v, level) + "|" + st.mood() + "|" + (st.home() ? "ja" : "nein")
				+ "|" + v.getUUID() + "|" + profKey + "|" + v.isBaby() + "|" + de.villagerplus.job.Apprentices.get(v).target());
		}
		String name = de.villagerplus.village.Network.villageName(ref);
		return new BoardData(board, name, VillageMap.GRID, VillageMap.gridCellSize(level, ref), grid[0], grid[1], VillageMap.bars(level, ref), residents, VillageStats.lines(level, ref),
			Expedition.lines(level, ref, level.getGameTime()), de.villagerplus.village.Network.neighborRows(level, ref), de.villagerplus.village.Network.partnerRows(level, ref),de.villagerplus.village.Journal.rows(level,ref));
	}
}
