import hashlib
import copy
import os
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
from unittest import mock
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

    def test_existing_immutable_catalog_is_reused_with_its_original_version(self):
        catalog = self.root / 'existing-catalog.jar'
        with zipfile.ZipFile(catalog, 'w') as archive:
            archive.writestr('gg/grounds/resourcepacks/catalog/catalog-version.txt', '0.0.0-edge.7.g0123456789ab\n')
            archive.writestr('gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class', b'compiled metadata')
        before = catalog.read_bytes()
        self.assertEqual(distribution.existing_catalog_version(catalog, hashlib.sha256(before).hexdigest()),
                         '0.0.0-edge.7.g0123456789ab')
        self.assertEqual(catalog.read_bytes(), before)
        with self.assertRaisesRegex(ValueError, 'hash|digest'):
            distribution.existing_catalog_version(catalog, '0' * 64)
        with zipfile.ZipFile(catalog, 'a') as archive:
            archive.writestr('gg/grounds/resourcepacks/npc/private-model.json', '{}')
        with self.assertRaisesRegex(ValueError, 'payload'):
            distribution.existing_catalog_version(catalog, hashlib.sha256(catalog.read_bytes()).hexdigest())

    def test_immutable_catalog_reuse_rejects_reusing_the_generated_build_identity(self):
        catalog = self.root / 'existing.jar'
        version = '0.0.0-edge.7.g0123456789ab'
        with zipfile.ZipFile(catalog, 'w') as archive:
            archive.writestr('gg/grounds/resourcepacks/catalog/catalog-version.txt', version)
        sha = hashlib.sha256(catalog.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'fresh|identity'):
            distribution.existing_catalog_version(catalog, sha, generated_version=version)

    def test_executable_catalog_must_match_all_selected_immutable_entries(self):
        catalog = self.root / 'catalog.jar'; host = self.root / 'host.jar'
        entries = {'gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class': b'original class',
                   'gg/grounds/resourcepacks/catalog/catalog-version.txt': b'1.2.3',
                   'META-INF/resourcepacks-catalog.kotlin_module': b'original metadata'}
        with zipfile.ZipFile(catalog, 'w') as archive:
            for name, data in entries.items(): archive.writestr(name, data)
        for mode in ('exact', 'changed', 'extra', 'missing'):
            payload = dict(entries)
            if mode == 'changed': payload['gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class'] = b'other class'
            if mode == 'extra': payload['gg/grounds/resourcepacks/catalog/Extra.class'] = b'extra'
            if mode == 'missing': payload.pop('META-INF/resourcepacks-catalog.kotlin_module')
            with zipfile.ZipFile(host, 'w') as archive:
                archive.writestr('app/Main.class', b'unrelated host code')
                for name, data in payload.items(): archive.writestr(name, data)
            if mode == 'exact': distribution.verify_embedded_catalog(host, catalog)
            else:
                with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, 'catalog'):
                    distribution.verify_embedded_catalog(host, catalog)

    def test_existing_catalog_requires_an_explicit_valid_digest_and_bounded_metadata(self):
        catalog = self.root / 'existing-catalog.jar'
        with zipfile.ZipFile(catalog, 'w') as archive:
            archive.writestr('gg/grounds/resourcepacks/catalog/catalog-version.txt', '')
        for digest in ('', 'short', 'f' * 63):
            with self.assertRaises(ValueError):
                distribution.existing_catalog_version(catalog, digest)
        with self.assertRaisesRegex(ValueError, 'version'):
            distribution.existing_catalog_version(catalog, hashlib.sha256(catalog.read_bytes()).hexdigest())

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


class DialogueDistributionTest(unittest.TestCase):
    setUp = DistributionTest.setUp
    pair = DistributionTest.pair
    """Neutral unit ZIP/JSON fixtures; these do not qualify actual private content."""

    def fixture(self):
        jar, addon, _, content = self.pair()
        jar.rename(self.root / 'minestom-lobby.jar')
        addon.rename(self.root / 'npc-addon.zip')
        catalog_entries = {'gg/grounds/resourcepacks/catalog/catalog-version.txt': b'1.2.3',
                           'gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class': b'neutral class fixture'}
        with zipfile.ZipFile(self.root / 'catalog.jar', 'w') as archive:
            for name, data in catalog_entries.items(): archive.writestr(name, data)
        with zipfile.ZipFile(self.root / 'minestom-lobby.jar', 'a') as archive:
            for name, data in catalog_entries.items(): archive.writestr(name, data)
        (self.root / 'dialogue/context').mkdir(parents=True)
        self.scene = b'{"unitFixture":true}'
        (self.root / 'dialogue/scene.json').write_bytes(self.scene)
        self.scene_sha = hashlib.sha256(self.scene).hexdigest()
        self.names = {'executable': 'minestom-lobby.jar', 'serverContent': 'server-content.zip',
                      'addon': 'npc-addon.zip', 'catalog': 'catalog.jar', 'scene': 'dialogue/scene.json',
                      'profile': 'dialogue/profile.json', 'hostCatalog': 'dialogue/context/host-catalog.json'}
        self.manifest = {'schemaVersion': 1, 'serviceVariant': 'grounds.lobby.navigator',
                         'provider': {'id': 'grounds.lobby.navigator', 'version': '2.24.0'},
                         'dialogueId': 'grounds:lobby-guide', 'roles': {'guide': 'grounds:npc/example'},
                         'actionProviders': {name: {'arguments': {}, 'durable': False} for name in
                                             ('grounds:lobby/open_duel_menu', 'grounds:lobby/open_navigator')},
                         'assetCatalog': {'id': 'grounds:assets', 'version': '1.2.3'},
                         'actionCatalogs': [{'id': 'grounds:lobby', 'version': '4'}],
                         'contentId': 'a' * 64, 'files': {}}
        self.refresh()

    def pin(self, role):
        path = self.root / self.names[role]
        pin = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'size': path.stat().st_size}
        if role == 'addon': pin['sha1'] = hashlib.sha1(path.read_bytes()).hexdigest()
        return pin

    def refresh(self):
        self.manifest['files'] = {role: self.pin(role) for role in list(self.names)[:5]}
        generation = hashlib.sha256(json.dumps(self.manifest, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        host = {'schemaVersion': 1, 'generation': generation, 'speakers': ['guide'], 'conditions': {},
                'actions': self.manifest['actionProviders'], 'gestures': [], 'capabilities': [], 'durableOutcomes': False}
        host_bytes = json.dumps(host, separators=(',', ':')).encode()
        (self.root / self.names['hostCatalog']).write_bytes(host_bytes)
        profile = {'schemaVersion': 1, 'id': 'grounds:lobby-guide', 'version': generation,
                   'hostCatalogJson': host_bytes.decode(), 'assetCatalog': self.manifest['assetCatalog'],
                   'actionCatalogs': self.manifest['actionCatalogs'],
                   'startActions': {'grounds:lobby/start_dialogue': {'dialogueArgument': 'dialogue', 'speakerArgument': 'speaker'}},
                   'npcAssets': {'grounds:npc/example': {'gestures': {}}}}
        (self.root / self.names['profile']).write_text(json.dumps(profile))
        self.binding = {'schemaVersion': 1, 'id': 'grounds:lobby-guide', 'version': generation, 'generation': generation,
                        'manifest': self.manifest,
                        'files': {role: dict(self.pin(role), file=name) for role, name in self.names.items()}}
        self.write_binding()

    def write_binding(self):
        (self.root / 'dialogue/binding.json').write_text(json.dumps(self.binding))

    def verify(self):
        return distribution.verify_dialogue_profile(self.root, self.scene_sha)

    def test_valid_binding_returns_exact_receipt_pins(self):
        self.fixture()
        result = self.verify()
        self.assertEqual(set(result), {'id', 'version', 'generation', 'profile', 'hostCatalog', 'binding'})
        self.assertEqual(result['profile'], dict(self.pin('profile'), file='dialogue/profile.json'))
        self.assertEqual(result['generation'], self.binding['generation'])

    def test_accepts_actual_scene_asset_catalog_identity(self):
        self.fixture()
        # GroundsAssetCatalog.catalog.id, distinct from the PackSet/artifact ID.
        self.manifest['assetCatalog']['id'] = 'grounds:assets'
        self.refresh()
        self.assertEqual(self.verify()['id'], 'grounds:lobby-guide')

    def test_rejects_numeric_host_provider_durable_after_coherent_repinning(self):
        self.fixture()
        for durable in (0, 0.0):
            for provider in self.manifest['actionProviders']:
                self.refresh()
                host_path = self.root / self.names['hostCatalog']
                host = json.loads(host_path.read_text())
                host['actions'][provider]['durable'] = durable
                host_path.write_text(json.dumps(host))
                profile_path = self.root / self.names['profile']
                profile = json.loads(profile_path.read_text())
                profile['hostCatalogJson'] = host_path.read_text()
                profile_path.write_text(json.dumps(profile))
                for role in ('profile', 'hostCatalog'):
                    self.binding['files'][role] = dict(self.pin(role), file=self.names[role])
                self.write_binding()
                with self.subTest(durable=durable, type=type(durable).__name__, provider=provider):
                    with self.assertRaisesRegex(ValueError, '[Hh]ost'):
                        self.verify()

    def test_rejects_missing_extra_duplicate_oversized_and_wrong_type_json(self):
        self.fixture()
        paths = ['dialogue/binding.json', self.names['profile'], self.names['hostCatalog']]
        for name in paths:
            path = self.root / name; original = path.read_bytes()
            for value in (b'{}', b'[]', b'{"schemaVersion":1,"schemaVersion":1}', b' ' * (1048576 + 1)):
                with self.subTest(name=name, value=value[:40]):
                    path.write_bytes(value)
                    with self.assertRaises(ValueError): self.verify()
            path.write_bytes(original)
        original = copy.deepcopy(self.binding)
        for target in ('binding', 'manifest', 'provider', 'pin'):
            self.binding = copy.deepcopy(original)
            obj = {'binding': self.binding, 'manifest': self.binding['manifest'],
                   'provider': self.binding['manifest']['provider'], 'pin': self.binding['files']['scene']}[target]
            obj['extra'] = True; self.write_binding()
            with self.subTest(target=target), self.assertRaises(ValueError): self.verify()
        self.binding = original; self.binding['manifest']['files']['scene']['size'] = True; self.write_binding()
        with self.assertRaises(ValueError): self.verify()

    def test_each_changed_artifact_and_missing_binding_is_rejected(self):
        self.fixture()
        for name in self.names.values():
            path = self.root / name; original = path.read_bytes(); path.write_bytes(original + b' ')
            with self.subTest(name=name), self.assertRaises(ValueError): self.verify()
            path.write_bytes(original)
        (self.root / 'dialogue/binding.json').unlink()
        with self.assertRaises(ValueError): self.verify()

    def test_content_id_must_match_actual_bundle_even_after_repinning(self):
        self.fixture()
        self.manifest['contentId'] = 'b' * 64; self.refresh()
        with self.assertRaisesRegex(ValueError, '[Cc]ontent'): self.verify()

    def test_embedded_host_must_match_written_bytes_even_after_repinning(self):
        self.fixture()
        path = self.root / self.names['profile']; profile = json.loads(path.read_text())
        profile['hostCatalogJson'] += ' '; path.write_text(json.dumps(profile))
        self.binding['files']['profile'] = dict(self.pin('profile'), file=self.names['profile']); self.write_binding()
        with self.assertRaisesRegex(ValueError, '[Hh]ost'): self.verify()

    def test_path_substitution_and_file_or_parent_symlinks_are_rejected(self):
        self.fixture()
        self.binding['files']['profile']['file'] = 'other.json'; self.write_binding()
        with self.assertRaises(ValueError): self.verify()
        self.binding['files']['profile']['file'] = self.names['profile']; self.write_binding()
        path = self.root / self.names['profile']; other = self.root / 'other.json'; path.rename(other); path.symlink_to(other)
        with self.assertRaises(ValueError): self.verify()
        path.unlink(); other.rename(path)
        context = self.root / 'dialogue/context'; moved = self.root / 'elsewhere'; context.rename(moved); context.symlink_to(moved)
        with self.assertRaises(ValueError): self.verify()

    def test_canonical_generation_must_be_recomputed_not_trusted(self):
        self.fixture()
        self.binding['manifest']['provider']['version'] = 'changed'; self.write_binding()
        with self.assertRaisesRegex(ValueError, '[Gg]eneration'): self.verify()

    def test_profile_role_catalog_and_host_claims_match_manifest(self):
        self.fixture()
        for field, value in [('npcAssets', {}), ('assetCatalog', {'id': 'wrong', 'version': '1'}),
                             ('actionCatalogs', []), ('startActions', {})]:
            path = self.root / self.names['profile']; original = path.read_bytes(); profile = json.loads(original)
            profile[field] = value; path.write_text(json.dumps(profile))
            self.binding['files']['profile'] = dict(self.pin('profile'), file=self.names['profile']); self.write_binding()
            with self.subTest(field=field), self.assertRaises(ValueError): self.verify()
            path.write_bytes(original)
        self.refresh()
        host_path = self.root / self.names['hostCatalog']; host = json.loads(host_path.read_text())
        host['durableOutcomes'] = True; host_path.write_text(json.dumps(host))
        profile_path = self.root / self.names['profile']; profile = json.loads(profile_path.read_text())
        profile['hostCatalogJson'] = host_path.read_text(); profile_path.write_text(json.dumps(profile))
        for role in ('profile', 'hostCatalog'): self.binding['files'][role] = dict(self.pin(role), file=self.names[role])
        self.write_binding()
        with self.assertRaises(ValueError): self.verify()

    def test_finalizer_preserves_legacy_optional_path(self):
        staging = self.root / 'staging'; staging.mkdir(); (staging / 'inputs').mkdir()
        output = self.root / 'out'
        distribution.finalize_distribution(staging, output, {'qualified': True})
        self.assertTrue(output.is_dir())
        self.assertEqual(json.loads((output / 'receipt.json').read_text()), {'qualified': True})

    def test_exporter_failure_does_not_publish_receipt_or_output(self):
        staging = self.root / 'staging'; staging.mkdir(); (staging / 'inputs').mkdir()
        selected = self.root / 'selected'; selected.mkdir(); (selected / 'scene.json').write_text('{}')
        output = self.root / 'out'
        with mock.patch.object(distribution.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['java'])):
            with self.assertRaises(subprocess.CalledProcessError):
                distribution.finalize_distribution(staging, output, {'qualified': True}, selected, hashlib.sha256(b'{}').hexdigest())
        self.assertFalse(output.exists()); self.assertFalse((staging / 'receipt.json').exists())
        self.assertTrue((staging / 'dialogue-export.log').exists())

    def test_finalizer_runs_exact_jar_with_sanitized_env_then_reverifies(self):
        self.fixture(); (self.root / 'inputs').mkdir()
        selected = self.root / 'inputs/selected'; selected.mkdir(); (selected / 'scene.json').write_bytes(self.scene)
        output = self.root.parent / (self.root.name + '-qualified'); self.addCleanup(lambda: __import__('shutil').rmtree(output, ignore_errors=True))
        def exporter(command, **kwargs):
            self.assertEqual(command, ['chosen-java', '-cp', str(self.root / 'minestom-lobby.jar'),
                                     'gg.grounds.minestom.lobby.scene.LobbyDialogueProfileExport',
                                     '--distribution', str(self.root), '--scene', str(selected)])
            self.assertEqual(kwargs['env'], {'PATH': '/bin', 'HOME': '/tmp'})
        with mock.patch.dict(os.environ, {'PATH': '/bin', 'HOME': '/tmp', 'JAVA_TOOL_OPTIONS': 'bad', 'NATS_URL': 'bad'}, clear=True), \
                mock.patch.object(distribution.subprocess, 'run', side_effect=exporter):
            distribution.finalize_distribution(self.root, output, {'qualified': True}, selected, self.scene_sha, java='chosen-java')
        self.assertEqual(json.loads((output / 'receipt.json').read_text())['dialogueProfile']['id'], 'grounds:lobby-guide')

    def test_final_verification_failure_produces_no_receipt_or_output(self):
        self.fixture(); (self.root / 'inputs').mkdir()
        selected = self.root / 'inputs/selected'; selected.mkdir(); (selected / 'scene.json').write_bytes(self.scene)
        output = self.root / 'out'
        real_rmtree = __import__('shutil').rmtree
        def mutate_after_checks(path):
            real_rmtree(path)
            (self.root / 'minestom-lobby.jar').write_bytes(b'tampered after initial verification')
        with mock.patch.object(distribution.subprocess, 'run'), mock.patch.object(distribution.shutil, 'rmtree', side_effect=mutate_after_checks):
            with self.assertRaises(ValueError):
                distribution.finalize_distribution(self.root, output, {'qualified': True}, selected, self.scene_sha)
        self.assertFalse(output.exists()); self.assertFalse((self.root / 'receipt.json').exists())


    def test_finalizer_rejects_binding_only_mutation_after_initial_verification(self):
        self.fixture(); (self.root / 'inputs').mkdir()
        selected = self.root / 'inputs/selected'; selected.mkdir(); (selected / 'scene.json').write_bytes(self.scene)
        output = self.root / 'out'
        real_rmtree = __import__('shutil').rmtree
        def mutate_binding_after_checks(path):
            real_rmtree(path)
            binding = self.root / 'dialogue/binding.json'
            binding.write_bytes(binding.read_bytes() + b' ')
            # Still semantically valid: only comparison with the initial binding pin rejects it.
            self.assertEqual(self.verify()['id'], 'grounds:lobby-guide')
        with mock.patch.object(distribution.subprocess, 'run'), \
                mock.patch.object(distribution.shutil, 'rmtree', side_effect=mutate_binding_after_checks):
            with self.assertRaisesRegex(ValueError, 'changed before publication'):
                distribution.finalize_distribution(self.root, output, {'qualified': True}, selected, self.scene_sha)
        self.assertFalse(output.exists())
        self.assertFalse((self.root / 'receipt.json').exists())

    def test_finalizer_removes_receipt_when_atomic_rename_fails(self):
        self.fixture(); (self.root / 'inputs').mkdir()
        selected = self.root / 'inputs/selected'; selected.mkdir(); (selected / 'scene.json').write_bytes(self.scene)
        output = self.root / 'out'
        with mock.patch.object(distribution.subprocess, 'run'), \
                mock.patch.object(Path, 'rename', side_effect=OSError('injected rename failure')):
            with self.assertRaisesRegex(OSError, 'injected rename failure'):
                distribution.finalize_distribution(self.root, output, {'qualified': True}, selected, self.scene_sha)
        self.assertFalse(output.exists())
        self.assertFalse((self.root / 'receipt.json').exists())


if __name__ == '__main__':
    unittest.main()
