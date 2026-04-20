package me.mklv.handshaker.neoforge;

import me.mklv.handshaker.common.loader.CommonClientHashPayloadService;
import me.mklv.handshaker.common.loader.CommonClientPayloadRuntime;
import me.mklv.handshaker.common.utils.JarIntegrityProof;
import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class ClientHashPayloadService {
    public record ModListData(String transportPayload, String modListHash) {}
    public record IntegrityData(byte[] signature, String jarHash) {}

    private final CommonClientPayloadRuntime runtime = new CommonClientPayloadRuntime();
    private IntegrityData manualIntegrityCache = null;
    private boolean isBooting = false;

    private final CommonClientPayloadRuntime.Context context = new CommonClientPayloadRuntime.Context() {
        @Override
        public Collection<CommonClientHashPayloadService.ModDescriptor> collectMods() {
            return ClientHashPayloadService.this.collectMods();
        }

        @Override
        public String runtimeModId() {
            return HandShakerClientMod.MOD_ID;
        }

        @Override
        public String wireModId() {
            return "hand-shaker";
        }

        @Override
        public Class<?> integrityAnchorClass() {
            return HandShakerClientMod.class;
        }

        @Override
        public CommonClientHashPayloadService.LogSink logSink() {
            return new CommonClientHashPayloadService.LogSink() {
                @Override
                public void info(String message) {
                    if (!isBooting) {
                        HandShakerClientMod.LOGGER.info(message);
                    }
                }

                @Override
                public void warn(String message) {
                    // Silence warnings during boot (like path resolution failures)
                    // They will be handled/retried during actual handshake
                    if (!isBooting) {
                        HandShakerClientMod.LOGGER.warn(message);
                    }
                }
            };
        }
    };

    public void precomputeAtBoot() {
        this.isBooting = true;
        try {
            runtime.precomputeAtBoot(context);
            this.manualIntegrityCache = buildIntegrityDataManual();
        } finally {
            this.isBooting = false;
        }
    }

    public ModListData getOrBuildModListData() {
        CommonClientHashPayloadService.ModListData data = runtime.getOrBuildModListData(context);
        return new ModListData(data.transportPayload(), data.modListHash());
    }

    public IntegrityData getOrBuildIntegrityData() {
        // First try core data
        CommonClientHashPayloadService.IntegrityData coreData = runtime.getOrBuildIntegrityData(context);
        if (coreData.signature().length > 0) {
            return new IntegrityData(coreData.signature(), coreData.jarHash());
        }

        // If core failed (likely due to early boot), check/retry manual resolution
        if (manualIntegrityCache == null || manualIntegrityCache.signature().length == 0) {
            manualIntegrityCache = buildIntegrityDataManual();
        }
        return manualIntegrityCache;
    }

    public ModListData buildModListDataManual() {
        CommonClientHashPayloadService.ModListData data = runtime.buildModListDataManual(context);
        return new ModListData(data.transportPayload(), data.modListHash());
    }

    public IntegrityData buildIntegrityDataManual() {
        // Fallback for NeoForge where resolveRuntimeJar might fail or return /
        Optional<Path> jarPath = getHandShakerJarPath();
        
        // If ModList failed or returned /, try getProtectionDomain as a last resort
        if (jarPath.isEmpty() || !Files.isRegularFile(jarPath.get())) {
            try {
                var loc = HandShakerClientMod.class.getProtectionDomain().getCodeSource().getLocation();
                if (loc != null) {
                    Path p = Paths.get(loc.toURI());
                    if (Files.isRegularFile(p)) {
                        jarPath = Optional.of(p);
                    }
                }
            } catch (Exception ignored) {}
        }

        if (jarPath.isPresent()) {
            Path path = jarPath.get();
            if (!Files.isRegularFile(path)) {
                return new IntegrityData(new byte[0], "");
            }

            JarIntegrityProof.LogSink jarLogger = new JarIntegrityProof.LogSink() {
                @Override public void info(String msg) { context.logSink().info(msg); }
                @Override public void warn(String msg) { context.logSink().warn(msg); }
            };

            if (JarIntegrityProof.verifyJarSignatureLocally(path, jarLogger)) {
                Optional<String> computedHash = JarIntegrityProof.computeCanonicalJarHash(path);
                Optional<byte[]> sig = readBinaryEntry(path, JarIntegrityProof.SIG_ENTRY);
                Optional<String> hash = readTextEntry(path, JarIntegrityProof.HASH_ENTRY);

                if (computedHash.isPresent() && sig.isPresent() && hash.isPresent()) {
                    String expectedHash = hash.get().trim().toLowerCase(java.util.Locale.ROOT);
                    if (computedHash.get().equals(expectedHash)) {
                        if (!isBooting) {
                            context.logSink().info("Integrity proof built successfully via manual NeoForge resolution");
                        }
                        return new IntegrityData(sig.get(), computedHash.get());
                    }
                }
            }
        }

        return new IntegrityData(new byte[0], "");
    }

    private Collection<CommonClientHashPayloadService.ModDescriptor> collectMods() {
        try {
            return ModList.get().getMods().stream()
                .map(this::toDescriptor)
                .toList();
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private Optional<String> invokeString(Object target, String methodName) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? Optional.empty() : Optional.of(value.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveModFilePath(Object modInfo) {
        try {
            java.lang.reflect.Method getOwningFile = modInfo.getClass().getMethod("getOwningFile");
            Object owningFile = getOwningFile.invoke(modInfo);
            if (owningFile == null) {
                return Optional.empty();
            }
            java.lang.reflect.Method getFile = owningFile.getClass().getMethod("getFile");
            Object modFile = getFile.invoke(owningFile);
            if (modFile == null) {
                return Optional.empty();
            }
            java.lang.reflect.Method getFilePath = modFile.getClass().getMethod("getFilePath");
            Object filePath = getFilePath.invoke(modFile);
            if (filePath instanceof Path path) {
                return Optional.of(path);
            }
            if (filePath != null) {
                return Optional.of(Paths.get(filePath.toString()));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private CommonClientHashPayloadService.ModDescriptor toDescriptor(Object modInfo) {
        return new CommonClientHashPayloadService.ModDescriptor(
            invokeString(modInfo, "getModId").orElse("unknown"),
            invokeString(modInfo, "getDisplayName").orElse("null"),
            invokeString(modInfo, "getVersion").orElse("unknown"),
            resolveModFilePath(modInfo).orElse(null)
        );
    }

    private Optional<byte[]> readBinaryEntry(Path jarPath, String name) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath.toFile())) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry(name);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            try (java.io.InputStream input = jarFile.getInputStream(entry)) {
                return Optional.of(input.readAllBytes());
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> readTextEntry(Path jarPath, String name) {
        return readBinaryEntry(jarPath, name)
            .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private Optional<Path> getHandShakerJarPath() {
        try {
            return ModList.get().getMods().stream()
                .filter(m -> context.runtimeModId().equals(invokeString(m, "getModId").orElse("")))
                .findFirst()
                .flatMap(this::resolveModFilePath);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
