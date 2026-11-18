import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
import zipfile

import build_npc_distribution as distribution


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_private_distribution_requires_all_licensed_suites_to_run_without_skips(self):
        results = self.root / 'test-results'; results.mkdir()
        suites = {'LicensedNpcBundleTest': 3, 'StageNpcDemoSceneTest': 2, 'StageNpcDemoMapGuardTest': 2}
        for name, count in suites.items():
            (results / ('TEST-' + name + '.xml')).write_text(
                f'<testsuite name="gg.grounds.minestom.lobby.scene.{name}" tests="{count}" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(distribution.verify_host_tests(results)['requiredSuiteTests'], 7)
        (results / 'TEST-LicensedNpcBundleTest.xml').write_text(
            '<testsuite name="gg.grounds.minestom.lobby.scene.LicensedNpcBundleTest" tests="3" failures="0" errors="0" skipped="1"/>')
        with self.assertRaisesRegex(ValueError, 'licensed|Licensed'):
            distribution.verify_host_tests(results)
        (results / 'TEST-LicensedNpcBundleTest.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'licensed|Licensed'):
            distribution.verify_host_tests(results)

    def test_distribution_requires_shared_engine_and_rejects_legacy_copies(self):
        jar = self.root / 'engine.jar'
        required = ['gg/grounds/npc/core/model/NpcModel.class',
                    'gg/grounds/npc/minestom/NpcRenderer.class',
                    'gg/grounds/scene/npc/minestom/SceneNpcRenderers.class']
        with zipfile.ZipFile(jar, 'w') as archive:
            for entry in required:
                archive.writestr(entry, b'class fixture')
        distribution.verify_shared_engine(jar)
        for obsolete in ['gg/grounds/npc/core/NpcModel.class',
                         'gg/grounds/resourcepacks/npc/NpcModel.class']:
            with zipfile.ZipFile(jar, 'w') as archive:
                for entry in required:
                    archive.writestr(entry, b'class fixture')
                archive.writestr(obsolete, b'legacy')
            with self.assertRaisesRegex(ValueError, 'Legacy NPC'):
                distribution.verify_shared_engine(jar)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr(required[0], b'class fixture')
        with self.assertRaisesRegex(ValueError, 'Missing shared NPC'):
            distribution.verify_shared_engine(jar)

    def test_private_catalog_has_a_distinct_source_build_identity(self):
        commit = '0123456789abcdef0123456789abcdef01234567'
        self.assertEqual(distribution.private_catalog_version(commit, 42),
                         '0.0.0-edge.42.g0123456789ab')
        for number in [0, -1]:
            with self.assertRaises(ValueError):
                distribution.private_catalog_version(commit, number)
        with self.assertRaises(ValueError):
            distribution.private_catalog_version('short', 42)

    def test_catalog_export_rejects_licensed_payload_and_wrong_identity(self):
        catalog = self.root / 'catalog.jar'
        with zipfile.ZipFile(catalog, 'w') as archive:
            archive.writestr('gg/grounds/resourcepacks/catalog/catalog-version.txt', '0.0.0-edge.42.g0123456789ab\n')
            archive.writestr('gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class', b'compiled metadata')
        distribution.verify_catalog_metadata(catalog, '0.0.0-edge.42.g0123456789ab')
        with self.assertRaisesRegex(ValueError, 'identity'):
            distribution.verify_catalog_metadata(catalog, '1.3.0')
        with zipfile.ZipFile(catalog, 'a') as archive:
            archive.writestr('gg/grounds/resourcepacks/npc/example.json', '{"bones":[]}')
        with self.assertRaisesRegex(ValueError, 'payload'):
            distribution.verify_catalog_metadata(catalog, '0.0.0-edge.42.g0123456789ab')

    def test_catalog_export_rejects_unexpected_meta_inf_payload(self):
        for name in ['META-INF/licensed-models.zip', 'META-INF/model.bbmodel', 'META-INF/texture.PNG']:
            catalog = self.root / 'catalog-metadata.jar'
            with zipfile.ZipFile(catalog, 'w') as archive:
                archive.writestr('gg/grounds/resourcepacks/catalog/catalog-version.txt', '1.2.3\n')
                archive.writestr(name, b'private model payload')
            with self.assertRaisesRegex(ValueError, 'payload'):
                distribution.verify_catalog_metadata(catalog, '1.2.3')

    def test_refuses_wrong_commit_and_uncommitted_model_compiler(self):
        repo = self.root / 'source'
        repo.mkdir()
        for args in [('init', '-q'), ('config', 'user.name', 'Test'),
                     ('config', 'user.email', 'test@example.invalid'),
                     ('config', 'commit.gpgsign', 'false')]:
            subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True)
        (repo / 'compiler.py').write_text('original')
        subprocess.run(['git', '-C', str(repo), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(repo), 'commit', '-qm', 'fixture'], check=True)
        commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
        distribution.verify_checkout(repo, commit)
        snapshot = self.root / 'snapshot'
        distribution.snapshot_checkout(repo, commit, snapshot)
        self.assertEqual((snapshot / 'compiler.py').read_text(), 'original')
        self.assertFalse((snapshot / '.git').exists())
        with self.assertRaisesRegex(ValueError, 'commit'):
            distribution.verify_checkout(repo, '0' * 40)
        (repo / 'compiler.py').write_text('modified')
        with self.assertRaisesRegex(ValueError, 'clean'):
            distribution.verify_checkout(repo, commit)

    def pair(self, changed=False, duplicate=False, extra=False, empty=False, corrupt_metadata=False):
        addon = self.root / 'addon.zip'
        payload = b'compiled geometry'
        path = 'assets/grounds/models/npc_parts/example.json'
        with zipfile.ZipFile(addon, 'w') as archive:
            archive.writestr('pack.mcmeta', '{"pack":{"description":"test","pack_format":75}}')
            archive.writestr(path, b'wrong' if changed else payload)
            archive.writestr('assets/grounds/sounds.json', '{}')
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr(path, payload)
            if extra:
                archive.writestr('stale.json', '{}')
        if corrupt_metadata:
            with zipfile.ZipFile(addon) as archive:
                info = archive.getinfo('pack.mcmeta')
                offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
            data = bytearray(addon.read_bytes())
            data[offset] ^= 1
            addon.write_bytes(data)
        sha1 = hashlib.sha1(addon.read_bytes()).hexdigest()
        jar = self.root / 'host.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('gg/grounds/minestom/lobby/npc-addon-sha1.txt', sha1 + '\n')
            archive.writestr('gg/grounds/minestom/lobby/npc-content-id.txt', 'a' * 64)
        content = self.root / 'server-content.zip'
        model = b'{"asset":"grounds:npc/example"}'
        contract = {'bundleSchema': 1, 'contentId': 'a' * 64,
            'models': [] if empty else [{'id': 'grounds:npc/example', 'resource': 'models/example.json', 'sha256': hashlib.sha256(model).hexdigest()}],
            'clientFiles': {} if empty else {path: hashlib.sha256(payload).hexdigest()}, 'soundEvents': {}}
        with zipfile.ZipFile(content, 'w') as archive:
            archive.writestr('bundle.json', json.dumps(contract))
            archive.writestr('models/example.json', model)
        return jar, addon, sha1, content

    def test_qualifies_matching_addon_contract(self):
        jar, addon, sha1, content = self.pair()
        self.assertEqual(distribution.verify_pair(jar, addon, sha1, content), 1)

    def test_rejects_corruption_duplicates_stale_files_and_empty_models(self):
        for options in ({'changed': True}, {'duplicate': True}, {'extra': True}, {'empty': True}):
            with self.subTest(options=options):
                jar, addon, sha1, content = self.pair(**options)
                with self.assertRaises(ValueError):
                    distribution.verify_pair(jar, addon, sha1, content)

    def test_rejects_binding_from_another_build(self):
        jar, addon, _, content = self.pair()
        with self.assertRaisesRegex(ValueError, 'binding'):
            distribution.verify_pair(jar, addon, '0' * 40, content)

    def test_rejects_stale_unindexed_licensed_payload(self):
        jar, addon, sha1, content = self.pair()
        with zipfile.ZipFile(content, 'a') as archive:
            archive.writestr('models/stale.json', '{}')
        with self.assertRaisesRegex(ValueError, 'Unexpected NPC'):
            distribution.verify_pair(jar, addon, sha1, content)

    def test_requires_the_actual_external_content_and_rejects_model_tampering(self):
        jar, addon, sha1, content = self.pair()
        with self.assertRaises(FileNotFoundError):
            distribution.verify_pair(jar, addon, sha1, self.root / 'missing.zip')
        with zipfile.ZipFile(content) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries['models/example.json'] += b' '
        with zipfile.ZipFile(content, 'w') as archive:
            for name, payload in entries.items(): archive.writestr(name, payload)
        with self.assertRaisesRegex(ValueError, 'model.*contract'):
            distribution.verify_pair(jar, addon, sha1, content)

    def test_rejects_corrupted_metadata_even_when_pinned_zip_hash_matches(self):
        jar, addon, sha1, content = self.pair(corrupt_metadata=True)
        with self.assertRaises(zipfile.BadZipFile):
            distribution.verify_pair(jar, addon, sha1, content)

    def test_rejects_ambiguous_duplicate_host_entries(self):
        jar, addon, sha1, content = self.pair()
        with zipfile.ZipFile(jar, 'a') as archive, warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('META-INF/example.kotlin_module', b'first')
            archive.writestr('META-INF/example.kotlin_module', b'second')
        with self.assertRaisesRegex(ValueError, 'Duplicate executable JAR'):
            distribution.verify_pair(jar, addon, sha1, content)


if __name__ == '__main__':
    unittest.main()
