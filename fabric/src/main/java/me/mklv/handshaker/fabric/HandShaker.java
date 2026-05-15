package me.mklv.handshaker.fabric;

import me.mklv.handlib.network.PayloadTypeCompat;
import me.mklv.handlib.fabric.PayloadTypeRegistry;
import me.mklv.handshaker.common.loader.CommonClientHandshakeOrchestrator;
import me.mklv.handshaker.common.loader.CommonClientHashPayloadService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandShaker implements ClientModInitializer {
	public static final String MOD_ID = "hand-shaker";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private final ClientHashPayloadService payloadService = new ClientHashPayloadService();
	private final CommonClientHandshakeOrchestrator handshakeOrchestrator = new CommonClientHandshakeOrchestrator();

	private final CommonClientHandshakeOrchestrator.PayloadProvider payloadProvider = new CommonClientHandshakeOrchestrator.PayloadProvider() {
		@Override
		public CommonClientHashPayloadService.ModListData getModListData() {
			ClientHashPayloadService.ModListData data = payloadService.getOrBuildModListData();
			return new CommonClientHashPayloadService.ModListData(data.transportPayload(), data.modListHash());
		}

		@Override
		public CommonClientHashPayloadService.IntegrityData getIntegrityData() {
			ClientHashPayloadService.IntegrityData data = payloadService.getOrBuildIntegrityData();
			return new CommonClientHashPayloadService.IntegrityData(data.signature(), data.jarHash());
		}
	};

	private final CommonClientHandshakeOrchestrator.Sender senderWrapper = new CommonClientHandshakeOrchestrator.Sender() {
		@Override
		public void sendModList(String transportPayload, String modListHash, String nonce, String hardwareFingerprint) {
			ClientPlayNetworking.send(new ModsListPayload(transportPayload, modListHash, nonce, hardwareFingerprint));
		}

		@Override
		public void sendIntegrity(byte[] signature, String jarHash, String nonce) {
			ClientPlayNetworking.send(new IntegrityPayload(signature, jarHash, nonce));
		}
	};

	private final CommonClientHandshakeOrchestrator.Logger loggerWrapper = new CommonClientHandshakeOrchestrator.Logger() {
		@Override
		public void info(String format, Object... args) {
			LOGGER.info(format, args);
		}

		@Override
		public void warn(String message) {
			LOGGER.warn(message);
		}
	};

	@Override
	public void onInitializeClient() {
		LOGGER.info("HandShaker client initializing");
		payloadService.precomputeAtBoot();

		// Register payload types for 1.21 custom payload system
		PayloadTypeRegistry.registerServerboundPlay(ModsListPayload.TYPE, ModsListPayload.CODEC);
		PayloadTypeRegistry.registerServerboundPlay(IntegrityPayload.TYPE, IntegrityPayload.CODEC);
		PayloadTypeRegistry.registerClientboundPlay(HandshakeChallengePayload.TYPE, HandshakeChallengePayload.CODEC);

		// Handle challenge from server
		ClientPlayNetworking.registerGlobalReceiver(HandshakeChallengePayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				LOGGER.info("Received challenge packet (Record): {}", payload.challenge());
				handshakeOrchestrator.onChallenge(payload.challenge(), this::isConnectionReady, payloadProvider, senderWrapper, loggerWrapper);
			});
		});

		// Register event handlers to send data on server join
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			client.execute(() -> {
				LOGGER.info("JOIN event triggered, current connection state: ready={}", isConnectionReady());
				handshakeOrchestrator.onJoin(this::isConnectionReady, payloadProvider, senderWrapper, loggerWrapper);
			});
		});
	}

	private boolean isConnectionReady() {
		Minecraft client = Minecraft.getInstance();
		return client != null && client.getConnection() != null;
	}

	public record HandshakeChallengePayload(String challenge) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<HandshakeChallengePayload> TYPE = PayloadTypeCompat.payloadType(MOD_ID, "challenge");
		public static final StreamCodec<ByteBuf, HandshakeChallengePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, HandshakeChallengePayload::challenge,
				HandshakeChallengePayload::new);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record ModsListPayload(String mods, String modListHash, String nonce, String hwid) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ModsListPayload> TYPE = PayloadTypeCompat.payloadType(MOD_ID, "mods");
		public static final StreamCodec<ByteBuf, ModsListPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ModsListPayload::mods,
				ByteBufCodecs.STRING_UTF8, ModsListPayload::modListHash,
				ByteBufCodecs.STRING_UTF8, ModsListPayload::nonce,
				ByteBufCodecs.STRING_UTF8, ModsListPayload::hwid,
				ModsListPayload::new);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record IntegrityPayload(byte[] signature, String jarHash, String nonce) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<IntegrityPayload> TYPE = PayloadTypeCompat.payloadType(MOD_ID, "integrity");
		public static final StreamCodec<ByteBuf, IntegrityPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BYTE_ARRAY, IntegrityPayload::signature,
				ByteBufCodecs.STRING_UTF8, IntegrityPayload::jarHash,
				ByteBufCodecs.STRING_UTF8, IntegrityPayload::nonce,
				IntegrityPayload::new);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}