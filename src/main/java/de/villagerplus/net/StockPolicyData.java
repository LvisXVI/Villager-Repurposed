package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client communal stock rows: {@code item_id|category|communal_count|target}, -1 target means unlimited. */
public record StockPolicyData(List<String> rows) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<StockPolicyData> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "stock_policy_data"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StockPolicyData> CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), StockPolicyData::rows, StockPolicyData::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
