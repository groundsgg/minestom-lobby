"""Build and qualify private licensed NPC inputs. Never publishes or patches classes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

NPC = 'gg/grounds/resourcepacks/npc/'
BINDING = 'gg/grounds/minestom/lobby/npc-addon-sha1.txt'


def digest(path, algorithm='sha256'):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def verify_checkout(directory, commit):
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Resourcepacks commit must be a full SHA-1')
    def git(*args):
        return subprocess.check_output(['git', '-C', str(directory), *args], text=True).strip()
    if git('rev-parse', 'HEAD') != commit:
        raise ValueError('Resourcepacks checkout differs from pinned commit')
    if git('status', '--porcelain', '--untracked-files=normal'):
        raise ValueError('Resourcepacks checkout must be clean')


def snapshot_checkout(directory, commit, target):
    """Export only committed files: no shared Gradle outputs, Git config, or untracked inputs."""
    archive = target.with_suffix('.tar')
    subprocess.run(['git', '-C', str(directory), 'archive', '--format=tar',
                    '--output=' + str(archive), commit], check=True)
    target.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(target, filter='data')
    archive.unlink()


def verify_pair(jar, addon, sha1):
    with zipfile.ZipFile(jar) as host, zipfile.ZipFile(addon) as pack:
        if len(set(host.namelist())) != len(host.namelist()):
            raise ValueError('Duplicate executable JAR entries')
        resources = [name for name in host.namelist() if name.startswith(NPC) or name == BINDING]
        if host.read(BINDING).decode('ascii').strip() != sha1 or digest(addon, 'sha1') != sha1:
            raise ValueError('Addon binding differs from pinned SHA-1')
        index = json.loads(host.read(NPC + 'index.json'))
        contract = json.loads(host.read(NPC + 'pack-digests.json'))
        if index.get('schemaVersion') != 1 or not index.get('models') or not contract:
            raise ValueError('Private models and client pack contract must not be empty')
        expected_resources = {NPC + 'index.json', NPC + 'pack-digests.json'}
        for model in index['models']:
            path = model['resource']
            if not path.startswith('/' + NPC) or '..' in path or '\\' in path:
                raise ValueError('Unsafe compiled model resource')
            host.read(path[1:])
            expected_resources.add(path[1:])
        actual_resources = {name for name in resources if name.endswith('.json')}
        if actual_resources != expected_resources:
            raise ValueError('Unexpected NPC model payload outside the compiled index')
        names = pack.namelist()
        if len(set(names)) != len(names):
            raise ValueError('Duplicate addon ZIP entries')
        if set(names) != set(contract) | {'pack.mcmeta'}:
            raise ValueError('Missing or unexpected addon files')
        # Reading also validates the metadata entry's ZIP CRC (it is not a model digest).
        metadata = json.loads(pack.read('pack.mcmeta'))
        if not isinstance(metadata, dict) or not isinstance(metadata.get('pack'), dict):
            raise ValueError('Invalid addon pack.mcmeta')
        for name, expected in contract.items():
            with pack.open(name) as stream:
                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != expected:
                raise ValueError('Addon differs from compiled model contract: ' + name)
        return len(index['models'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resourcepacks', type=Path, required=True)
    parser.add_argument('--resourcepacks-commit', required=True)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--composite-policy', type=Path, required=True)
    parser.add_argument('--addon', type=Path, required=True)
    parser.add_argument('--addon-sha256', required=True)
    parser.add_argument('--addon-sha1', required=True)
    parser.add_argument('--map', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gradle')
    parser.add_argument('--java', default='java')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    for name in ('resourcepacks', 'sources', 'composite_policy', 'addon', 'map', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    if args.output.exists():
        raise ValueError('Distribution output must not exist')
    verify_checkout(args.resourcepacks, args.resourcepacks_commit)
    host_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    verify_checkout(repo, host_commit)
    for expected, algorithm in ((args.addon_sha256, 'sha256'), (args.addon_sha1, 'sha1')):
        if not re.fullmatch('[0-9a-f]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', expected):
            raise ValueError('An exact addon digest is required')
        if digest(args.addon, algorithm) != expected:
            raise ValueError('Addon differs from pinned ' + algorithm)
    if not (args.map / 'scene.json').is_file():
        raise ValueError('Map scene.json missing')
    policy_sha256 = digest(args.composite_policy)
    scene_sha256 = digest(args.map / 'scene.json')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Failed attempts retain their logs, but never a qualified receipt or final output.
    with tempfile.TemporaryDirectory(prefix='.npc-distribution-', dir=args.output.parent, delete=False) as directory:
        staging = Path(directory)
        print('Build logs: ' + str(staging / 'build.log'), flush=True)
        inputs = staging / 'inputs'
        inputs.mkdir()
        host_source = inputs / 'host'
        pack_source = inputs / 'resourcepacks'
        snapshot_checkout(repo, host_commit, host_source)
        snapshot_checkout(args.resourcepacks, args.resourcepacks_commit, pack_source)
        sources = inputs / 'licensed-sources'
        sources.mkdir()
        source_hashes = {}
        for collection in ('craftmasters', 'guildfolk', 'townfolk'):
            name = 'Ginko-fantasy_' + collection + '_NPCs.zip'
            source_hashes[name] = digest(args.sources / name)
            shutil.copyfile(args.sources / name, sources / name)
            if digest(sources / name) != source_hashes[name]:
                raise ValueError('Licensed archive changed during snapshot')
        policy = inputs / 'composite-policy.json'
        shutil.copyfile(args.composite_policy, policy)
        if digest(policy) != policy_sha256:
            raise ValueError('Composite policy changed during snapshot')
        selected_map = inputs / 'selected-map'
        selected_map.mkdir()
        shutil.copyfile(args.map / 'scene.json', selected_map / 'scene.json')
        if digest(selected_map / 'scene.json') != scene_sha256:
            raise ValueError('Scene changed during snapshot')
        addon = staging / 'npc-addon.zip'
        shutil.copyfile(args.addon, addon)
        if digest(addon) != args.addon_sha256 or digest(addon, 'sha1') != args.addon_sha1:
            raise ValueError('Addon changed during snapshot')
        command = [args.gradle or str(host_source / 'gradlew'), '--no-daemon', '--no-build-cache']
        if args.offline:
            command.append('--offline')
        command += [
            '-PnpcResourcepacksDirectory=' + str(pack_source),
            '-PnpcSourceDirectory=' + str(sources),
            '-PnpcGeometryMode=composite',
            '-PnpcCompositePolicy=' + str(policy),
            '-PnpcAddonZip=' + str(addon),
            'clean', 'test', 'shadowJar',
        ]
        with (staging / 'build.log').open('w') as log:
            subprocess.run(command, cwd=host_source, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        jars = list((host_source / 'build/private-npc/libs').glob('*-all.jar'))
        if len(jars) != 1:
            raise ValueError('Expected one private executable host JAR')
        jar = staging / 'minestom-lobby.jar'
        shutil.copyfile(jars[0], jar)
        models = verify_pair(jar, addon, args.addon_sha1)
        bundle = json.loads((pack_source / 'build/generated/npc-models/bundle.json').read_text())
        if bundle['sourceSha256'] != source_hashes:
            raise ValueError('Compiler source archive identity differs from the snapshot')
        with (staging / 'acceptance.log').open('w') as log:
            subprocess.run([
                args.java, '-cp', str(jar), str(host_source / 'tools/NpcStageBundleAcceptance.java'),
                str(selected_map), str(addon), args.addon_sha256, args.addon_sha1,
            ], check=True, stdout=log, stderr=subprocess.STDOUT, text=True, timeout=120)
        acceptance = (staging / 'acceptance.log').read_text()
        verify_pair(jar, addon, args.addon_sha1)
        receipt = {
            'qualified': True,
            'hostCommit': host_commit,
            'hostDirty': False,
            'resourcepacksCommit': args.resourcepacks_commit,
            'policySha256': policy_sha256,
            'sourceArchivesSha256': source_hashes,
            'sceneSha256': scene_sha256,
            'addonSha256': digest(staging / 'npc-addon.zip'),
            'addonSha1': args.addon_sha1,
            'jarSha256': digest(staging / 'minestom-lobby.jar'),
            'models': models,
            'acceptance': acceptance.strip(),
            'scope': 'Source build, full host tests, production scene loader and exact addon contract; native client acceptance and deployment are separate.',
        }
        if receipt['addonSha256'] != args.addon_sha256:
            raise ValueError('Addon changed before distribution assembly')
        shutil.rmtree(inputs)
        (staging / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        staging.rename(args.output)
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
