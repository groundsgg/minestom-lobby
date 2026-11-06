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

    def pair(self, changed=False, duplicate=False, extra=False, empty=False):
        addon = self.root / 'addon.zip'
        payload = b'compiled geometry'
        path = 'assets/grounds/models/npc_parts/example.json'
        with zipfile.ZipFile(addon, 'w') as archive:
            archive.writestr('pack.mcmeta', '{}')
            archive.writestr(path, b'wrong' if changed else payload)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr(path, payload)
            if extra:
                archive.writestr('stale.json', '{}')
        sha1 = hashlib.sha1(addon.read_bytes()).hexdigest()
        jar = self.root / 'host.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('gg/grounds/minestom/lobby/npc-addon-sha1.txt', sha1 + '\n')
            archive.writestr('gg/grounds/resourcepacks/npc/pack-digests.json', json.dumps(
                {} if empty else {path: hashlib.sha256(payload).hexdigest()}))
            archive.writestr('gg/grounds/resourcepacks/npc/index.json', json.dumps({
                'schemaVersion': 1, 'models': [] if empty else [
                    {'asset': 'grounds:npc/example', 'resource': '/gg/grounds/resourcepacks/npc/example.json'}]}))
            archive.writestr('gg/grounds/resourcepacks/npc/example.json', '{}')
        return jar, addon, sha1

    def test_qualifies_matching_addon_contract(self):
        jar, addon, sha1 = self.pair()
        self.assertEqual(distribution.verify_pair(jar, addon, sha1), 1)

    def test_rejects_corruption_duplicates_stale_files_and_empty_models(self):
        for options in ({'changed': True}, {'duplicate': True}, {'extra': True}, {'empty': True}):
            with self.subTest(options=options):
                jar, addon, sha1 = self.pair(**options)
                with self.assertRaises(ValueError):
                    distribution.verify_pair(jar, addon, sha1)

    def test_rejects_binding_from_another_build(self):
        jar, addon, _ = self.pair()
        with self.assertRaisesRegex(ValueError, 'binding'):
            distribution.verify_pair(jar, addon, '0' * 40)

    def test_rejects_stale_unindexed_licensed_payload(self):
        jar, addon, sha1 = self.pair()
        with zipfile.ZipFile(jar, 'a') as archive:
            archive.writestr('gg/grounds/resourcepacks/npc/stale.json', '{}')
        with self.assertRaisesRegex(ValueError, 'Unexpected NPC'):
            distribution.verify_pair(jar, addon, sha1)


if __name__ == '__main__':
    unittest.main()
