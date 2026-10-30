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

/** Client -> Server: An der Dorftafel eine Aufgabe abbrechen ({@code kind} = "build" fuer den Bauauftrag, "villager" fuer die Aufgabe eines Dorfbewohners). */
public record CancelTask(BlockPos board, String kind, UUID villager) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CancelTask> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "cancel_task"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CancelTask> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, CancelTask::board, ByteBufCodecs.STRING_UTF8, CancelTask::kind, UUIDUtil.STREAM_CODEC, CancelTask::villager, CancelTask::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
