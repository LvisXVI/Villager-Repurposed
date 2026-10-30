package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: refresh bounded communal stock rows for the selected board. */
public record RequestStockPolicy(BlockPos board) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RequestStockPolicy> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "request_stock_policy"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RequestStockPolicy> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, RequestStockPolicy::board, RequestStockPolicy::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
