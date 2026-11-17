import gg.grounds.minestom.lobby.LoadedLobbyMap;
import gg.grounds.minestom.lobby.LobbyMapSource;
import gg.grounds.minestom.lobby.scene.LobbySceneLoader;
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog;
import gg.grounds.minestom.lobby.scene.GroundsNpcModelSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Verify the exact deployable Host against an existing Stage scene and its client addon. */
public final class NpcStageBundleAcceptance {
    public static void main(String[] args) {
        try {
            verify(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        // The real loader initializes Minestom helpers. A one-shot acceptance
        // process must exit after either result instead of waiting for them.
        System.exit(0);
    }

    private static void verify(String[] args) throws Exception {
        if (args.length != 5 || !args[2].matches("[0-9a-f]{64}") || !args[3].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Expected scene directory, addon ZIP, pinned SHA-256, pinned SHA-1 and server-content ZIP");
        var sha256 = MessageDigest.getInstance("SHA-256");
        var sha1 = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            var buffer = new byte[8192];
            for (int size; (size = input.read(buffer)) != -1;) {
                sha256.update(buffer, 0, size);
                sha1.update(buffer, 0, size);
            }
        }
        if (!HexFormat.of().formatHex(sha256.digest()).equals(args[2]) ||
            !HexFormat.of().formatHex(sha1.digest()).equals(args[3]))
            throw new AssertionError("Addon ZIP differs from the pinned deployment hashes");
        gg.grounds.minestom.lobby.NpcAddonBinding.Companion.load().requireMatches(args[3]);
        net.minestom.server.MinecraftServer.init();
        // The accepted Stage disables the legacy bars in favour of the proxy owner.
        var ownership = Class.forName("gg.grounds.lobby.AnnouncementOwnership");
        var instance = ownership.getField("INSTANCE").get(null);
        var enabled = ownership.getMethod("legacyEnabled", String.class);
        if (!Boolean.FALSE.equals(enabled.invoke(instance, "disabled")) ||
            !Boolean.TRUE.equals(enabled.invoke(instance, "legacy")))
            throw new AssertionError("Accepted announcement ownership contract missing");
        System.setProperty("grounds.npc.content", args[4]);
        gg.grounds.npc.core.io.content.NpcContentLoader.load(Path.of(args[4])).verifyResourcePack(Path.of(args[1]));
        gg.grounds.minestom.lobby.NpcDemoPack.Companion.configured(java.util.Map.of(
            "GROUNDS_NPC_ADDON_URL", "https://example.invalid/npc-addon.zip",
            "GROUNDS_NPC_ADDON_SHA1", args[3]));
        var catalog = GroundsAssetCatalog.INSTANCE.getCatalog();
        var models = GroundsNpcModelSource.INSTANCE.load(catalog);
        if (models.size() != 15) throw new AssertionError("Expected fifteen complete NPC models");
        var loaded = new LoadedLobbyMap(Path.of(args[0]), new LobbyMapSource.Local(Path.of(args[0])));
        var prepared = new LobbySceneLoader().load(loaded);
        if (prepared == null || prepared.getScene().getElements().size() != 22)
            throw new AssertionError("Expected the existing twenty-two-element Stage scene");
        var content = gg.grounds.npc.core.io.content.NpcContentLoader.load(Path.of(args[4]));
        content.verifyResourcePack(Path.of(args[1]));
        int parts = models.values().stream().mapToInt(m -> m.getParts().size()).sum();
        System.out.println("PASS: existing Stage scene accepted by production loader; catalog=" + catalog.getVersion() + "; models=" + models.size() + "; displays=" + parts + "; content_id=" + content.getContentId() + "; zip_sha256=" + args[2] + "; zip_sha1=" + args[3]);
    }
}
