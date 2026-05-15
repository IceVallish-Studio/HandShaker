package me.mklv.handshaker.neoforge;

import me.mklv.handshaker.common.loader.CommonClientHandshakeOrchestrator;
import me.mklv.handshaker.common.loader.CommonClientHashPayloadService;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import me.mklv.handshaker.neoforge.server.HandShakerServerMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HandShakerClientMod.MOD_ID)
public class HandShakerClientMod {
    public static final String MOD_ID = "hand_shaker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static HandShakerClientMod instance;
    private final ClientHashPayloadService payloadService = new ClientHashPayloadService();
    private final CommonClientHandshakeOrchestrator handshakeOrchestrator = new CommonClientHandshakeOrchestrator();

    public HandShakerClientMod(IEventBus modEventBus) {
        instance = this;
        LOGGER.info("HandShaker client initializing");
        payloadService.precomputeAtBoot();
        NeoForge.EVENT_BUS.register(this);
    }

    public static HandShakerClientMod getInstance() {
        return instance;
    }

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

    public void handleChallenge(HandShakerServerMod.HandshakeChallengePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        handshakeOrchestrator.onChallenge(
            payload.challenge(),
            this::isConnectionReady,
            payloadProvider,
            new CommonClientHandshakeOrchestrator.Sender() {
                @Override
                public void sendModList(String transportPayload, String modListHash, String nonce, String hardwareFingerprint) {
                    context.reply(new HandShakerServerMod.ModsListPayload(transportPayload, modListHash, nonce, hardwareFingerprint));
                }

                @Override
                public void sendIntegrity(byte[] signature, String jarHash, String nonce) {
                    context.reply(new HandShakerServerMod.IntegrityPayload(signature, jarHash, nonce));
                }
            },
            loggerWrapper
        );
    }

    @SubscribeEvent
    public void onServerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        handshakeOrchestrator.onJoin(
            this::isConnectionReady,
            payloadProvider,
            new CommonClientHandshakeOrchestrator.Sender() {
                @Override
                public void sendModList(String transportPayload, String modListHash, String nonce, String hardwareFingerprint) {
                    sendPacket(event, new HandShakerServerMod.ModsListPayload(transportPayload, modListHash, nonce, hardwareFingerprint));
                }

                @Override
                public void sendIntegrity(byte[] signature, String jarHash, String nonce) {
                    sendPacket(event, new HandShakerServerMod.IntegrityPayload(signature, jarHash, nonce));
                }
            },
            loggerWrapper
        );
    }

    private boolean isConnectionReady() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }


    private void sendPacket(ClientPlayerNetworkEvent.LoggingIn event, CustomPacketPayload payload) {
        if (event.getPlayer() != null && event.getPlayer().connection != null) {
            event.getPlayer().connection.send(new ServerboundCustomPayloadPacket(payload));
        }
    }

}
