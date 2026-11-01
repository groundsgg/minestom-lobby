import com.google.gson.JsonParser;
import gg.grounds.minestom.lobby.LoadedLobbyMap;
import gg.grounds.minestom.lobby.LobbyMapSource;
import gg.grounds.minestom.lobby.scene.LobbySceneLoader;
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog;
import gg.grounds.resourcepacks.npc.NpcModels;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;

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
        if (args.length != 4 || !args[2].matches("[0-9a-f]{64}") || !args[3].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Expected scene directory, addon ZIP, pinned SHA-256 and pinned SHA-1");
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
        var catalog = GroundsAssetCatalog.INSTANCE.getCatalog();
        var models = NpcModels.INSTANCE.load(catalog);
        if (models.size() != 15) throw new AssertionError("Expected fifteen complete NPC models");
        var loaded = new LoadedLobbyMap(Path.of(args[0]), new LobbyMapSource.Local(Path.of(args[0])));
        var prepared = new LobbySceneLoader().load(loaded);
        if (prepared == null || prepared.getScene().getElements().size() != 22)
            throw new AssertionError("Expected the existing twenty-two-element Stage scene");
        com.google.gson.JsonObject digests;
        try (var resource = NpcModels.class.getResourceAsStream("/gg/grounds/resourcepacks/npc/pack-digests.json")) {
            if (resource == null) throw new AssertionError("Missing compiled client pack contract");
            digests = JsonParser.parseString(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int checked = 0;
        try (var addon = new ZipFile(args[1])) {
            for (var entry : digests.entrySet()) {
                var file = addon.getEntry(entry.getKey());
                if (file == null) throw new AssertionError("Addon missing " + entry.getKey());
                String actual;
                try (var input = addon.getInputStream(file)) {
                    actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                }
                if (!actual.equals(entry.getValue().getAsString()))
                    throw new AssertionError("Addon differs from Host contract: " + entry.getKey());
                checked++;
            }
            if (addon.size() != checked + 1 || addon.getEntry("pack.mcmeta") == null)
                throw new AssertionError("Unexpected addon entries");
        }
        int parts = models.values().stream().mapToInt(m -> m.getParts().size()).sum();
        System.out.println("PASS: existing Stage scene accepted by production loader; catalog=" + catalog.getVersion() + "; models=" + models.size() + "; displays=" + parts + "; checked_addon_files=" + checked + "; zip_sha256=" + args[2] + "; zip_sha1=" + args[3]);
    }
}
