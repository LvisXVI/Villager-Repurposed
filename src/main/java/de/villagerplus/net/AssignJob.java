package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: An der Dorftafel einem Dorfbewohner einen Beruf zuteilen ({@code profession} = Registrierungsname). */
public record AssignJob(BlockPos board, UUID villager, String profession) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AssignJob> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "assign_job"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AssignJob> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, AssignJob::board, UUIDUtil.STREAM_CODEC, AssignJob::villager, ByteBufCodecs.STRING_UTF8, AssignJob::profession, AssignJob::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
