package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: set or clear one item's communal-stock target at a village board. */
public record SetStockPolicy(BlockPos board, String itemId, String category, int target, boolean clear) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SetStockPolicy> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "set_stock_policy"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetStockPolicy> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetStockPolicy::board,
		ByteBufCodecs.STRING_UTF8, SetStockPolicy::itemId,
		ByteBufCodecs.STRING_UTF8, SetStockPolicy::category,
		ByteBufCodecs.VAR_INT, SetStockPolicy::target,
		ByteBufCodecs.BOOL, SetStockPolicy::clear,
		SetStockPolicy::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
