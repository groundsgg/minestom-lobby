"""Build and qualify private licensed NPC inputs. Never publishes or patches classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
import xml.etree.ElementTree as ET

NPC = 'gg/grounds/resourcepacks/npc/'
BINDING = 'gg/grounds/minestom/lobby/npc-addon-sha1.txt'


def private_catalog_version(commit, number):
    if not re.fullmatch('[0-9a-f]{40}', commit) or not isinstance(number, int) or number < 1:
        raise ValueError('Private catalog requires the full source commit and a positive build number')
    return '0.0.0-edge.' + str(number) + '.g' + commit[:12]


def verify_catalog_metadata(jar, version):
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names) or archive.testzip() is not None:
            raise ValueError('Invalid neutral catalog archive')
        for name in names:
            if name.endswith('/'):
                continue
            neutral = (name.startswith('gg/grounds/resourcepacks/catalog/') and name.endswith('.class'))
            neutral |= name == 'gg/grounds/resourcepacks/catalog/catalog-version.txt'
            neutral |= name in {
                'META-INF/MANIFEST.MF',
                'META-INF/LICENSE',
                'META-INF/resourcepacks-catalog.kotlin_module',
            }
            if not neutral:
                raise ValueError('Catalog export contains non-neutral payload: ' + name)
        if archive.read('gg/grounds/resourcepacks/catalog/catalog-version.txt').decode().strip() != version:
            raise ValueError('Catalog export identity differs from the private build')


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


def verify_shared_engine(jar):
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = {'gg/grounds/npc/core/model/NpcModel.class',
                    'gg/grounds/npc/minestom/NpcRenderer.class',
                    'gg/grounds/scene/npc/minestom/SceneNpcRenderers.class'}
        if not required <= names:
            raise ValueError('Missing shared NPC engine or Scene adapter')
        legacy = {'AnimatedNpcHandle', 'NpcItemDisplay', 'NpcPendingMetadata',
                  'NpcRenderPolicy', 'NpcViewerRate', 'LobbyNpcModelRenderers'}
        if 'gg/grounds/npc/core/NpcModel.class' in names or any(name.startswith(NPC) and name.endswith('.class') for name in names) or any(
            'gg/grounds/minestom/lobby/scene/' + owner + '.class' in names for owner in legacy
        ):
            raise ValueError('Legacy NPC engine must not remain in the distribution')


def verify_pair(jar, addon, sha1, content):
    """Cheap archive/pinning checks; the Java acceptance tool runs the actual content loader."""
    with zipfile.ZipFile(jar) as host, zipfile.ZipFile(addon) as pack, zipfile.ZipFile(content) as server:
        if len(set(host.namelist())) != len(host.namelist()):
            raise ValueError('Duplicate executable JAR entries')
        if any(name.startswith(NPC) for name in host.namelist()):
            raise ValueError('Unexpected NPC payload embedded in executable JAR')
        if host.read(BINDING).decode('ascii').strip() != sha1 or digest(addon, 'sha1') != sha1:
            raise ValueError('Addon binding differs from pinned SHA-1')
        if len(set(server.namelist())) != len(server.namelist()):
            raise ValueError('Duplicate server content entries')
        contract = json.loads(server.read('bundle.json'))
        if contract.get('bundleSchema') != 1 or not contract.get('models') or not contract.get('clientFiles'):
            raise ValueError('Private models and client pack contract must not be empty')
        if host.read('gg/grounds/minestom/lobby/npc-content-id.txt').decode('ascii').strip() != contract.get('contentId'):
            raise ValueError('Content differs from pinned addon binding')
        expected_resources = {'bundle.json'}
        for model in contract['models']:
            path = model['resource']
            if path.startswith('/') or '..' in path or '\\' in path:
                raise ValueError('Unsafe compiled model resource')
            if path in expected_resources or hashlib.sha256(server.read(path)).hexdigest() != model['sha256']:
                raise ValueError('Compiled model differs from content contract')
            expected_resources.add(path)
        if set(server.namelist()) != expected_resources:
            raise ValueError('Unexpected NPC model payload outside the compiled contract')
        if len(set(pack.namelist())) != len(pack.namelist()):
            raise ValueError('Duplicate addon ZIP entries')
        sound_files = {'assets/grounds/sounds.json'}
        for key in contract['soundEvents']: sound_files.add('assets/' + key.split(':', 1)[0] + '/sounds.json')
        if set(pack.namelist()) != set(contract['clientFiles']) | sound_files | {'pack.mcmeta'}:
            raise ValueError('Missing or unexpected addon files')
        metadata = json.loads(pack.read('pack.mcmeta'))
        if not isinstance(metadata, dict) or not isinstance(metadata.get('pack'), dict):
            raise ValueError('Invalid addon pack.mcmeta')
        for name, expected in contract['clientFiles'].items():
            if hashlib.sha256(pack.read(name)).hexdigest() != expected:
                raise ValueError('Addon differs from compiled model contract: ' + name)
        for name in sound_files:
            namespace = name.split('/')[1]
            actual = json.loads(pack.read(name))
            expected = {key.split(':',1)[1]:value for key,value in contract['soundEvents'].items() if key.split(':',1)[0] == namespace}
            if actual != expected: raise ValueError('Addon sound definitions differ from content contract')
        return len(contract['models'])


def verify_host_tests(directory):
    suites = {}
    for path in Path(directory).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        name = suite.attrib['name']
        if name in suites: raise ValueError('Duplicate host test suite: ' + name)
        suites[name] = {key: int(suite.attrib.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
    required = {'LicensedNpcBundleTest': 3, 'StageNpcDemoSceneTest': 2, 'StageNpcDemoMapGuardTest': 2}
    qualified = 0
    for name, minimum in required.items():
        suite = suites.get('gg.grounds.minestom.lobby.scene.' + name)
        if suite is None or suite['tests'] < minimum or suite['skipped'] or suite['failures'] or suite['errors']:
            raise ValueError('Required licensed host suite did not run completely: ' + name)
        qualified += suite['tests']
    if any(suite['failures'] or suite['errors'] for suite in suites.values()):
        raise ValueError('Host test results contain failures')
    return {'tests': sum(s['tests'] for s in suites.values()),
            'skipped': sum(s['skipped'] for s in suites.values()),
            'failures': 0, 'requiredSuiteTests': qualified, 'requiredSuites': sorted(required)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resourcepacks', type=Path, required=True)
    parser.add_argument('--resourcepacks-commit', required=True)
    parser.add_argument('--catalog-build-number', type=int, required=True)
    parser.add_argument('--lobby-source', type=Path)
    parser.add_argument('--lobby-source-commit')
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
    parser.add_argument('--npc-importer-classpath', required=True)
    parser.add_argument('--npc-version', default='1.1.0')
    parser.add_argument('--npc-repository', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    for name in ('resourcepacks', 'sources', 'composite_policy', 'addon', 'map', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    if args.output.exists():
        raise ValueError('Distribution output must not exist')
    verify_checkout(args.resourcepacks, args.resourcepacks_commit)
    catalog_version = private_catalog_version(args.resourcepacks_commit, args.catalog_build_number)
    host_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    verify_checkout(repo, host_commit)
    if bool(args.lobby_source) != bool(args.lobby_source_commit):
        raise ValueError('Pinned Lobby sources require both directory and exact commit')
    if args.lobby_source:
        args.lobby_source = args.lobby_source.resolve()
        verify_checkout(args.lobby_source, args.lobby_source_commit)
    for expected, algorithm in ((args.addon_sha256, 'sha256'), (args.addon_sha1, 'sha1')):
        if not re.fullmatch('[0-9a-f]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', expected):
            raise ValueError('An exact addon digest is required')
        if digest(args.addon, algorithm) != expected:
            raise ValueError('Addon differs from pinned ' + algorithm)
    if not (args.map / 'scene.json').is_file():
        raise ValueError('Map scene.json missing')
    scene = json.loads((args.map / 'scene.json').read_text())
    if scene['catalogs']['assets']['version'] != catalog_version:
        raise ValueError('Map must select the exact private catalog build identity: ' + catalog_version)
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
        lobby_source = inputs / 'plugin-lobby'
        if args.lobby_source:
            snapshot_checkout(args.lobby_source, args.lobby_source_commit, lobby_source)
        importer_files = []
        importer_hashes = []
        for number, entry in enumerate(args.npc_importer_classpath.split(os.pathsep)):
            original = Path(entry).resolve()
            expected = digest(original)
            copied = inputs / 'importer' / str(number) / original.name
            copied.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, copied)
            if digest(copied) != expected: raise ValueError('Importer changed during snapshot')
            importer_files.append(str(copied))
            importer_hashes.append({'file': original.name, 'sha256': expected})
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
        if args.lobby_source:
            command.append('-PnpcLobbySourceDirectory=' + str(lobby_source))
        if args.npc_repository:
            command.append('-PnpcRepository=' + str(args.npc_repository.resolve()))
        command += [
            '-PnpcVersion=' + args.npc_version,
            '-PnpcImporterClasspath=' + os.pathsep.join(importer_files),
            '-PnpcResourcepacksDirectory=' + str(pack_source),
            '-PnpcSourceDirectory=' + str(sources),
            '-PnpcGeometryMode=composite',
            '-PnpcCompositePolicy=' + str(policy),
            '-PnpcAddonZip=' + str(addon),
            '-PpackSetVersion=' + catalog_version,
            '-PpublicationType=build',
            '-PpublicationId=' + args.resourcepacks_commit,
            '-PprovenanceCommit=' + args.resourcepacks_commit,
            'clean', 'build', 'shadowJar',
        ]
        with (staging / 'build.log').open('w') as log:
            subprocess.run(command, cwd=host_source, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        results = host_source / 'build/private-npc/test-results/test'
        host_tests = verify_host_tests(results)
        shutil.copytree(results, staging / 'test-results')
        jars = list((host_source / 'build/private-npc/libs').glob('*-all.jar'))
        if len(jars) != 1:
            raise ValueError('Expected one private executable host JAR')
        jar = staging / 'minestom-lobby.jar'
        shutil.copyfile(jars[0], jar)
        content = staging / 'server-content.zip'
        shutil.copyfile(pack_source / 'build/generated/npc-models/server-content.zip', content)
        models = verify_pair(jar, addon, args.addon_sha1, content)
        verify_shared_engine(jar)
        bundle = json.loads((pack_source / 'build/generated/npc-models/bundle.json').read_text())
        if bundle['sourceSha256'] != source_hashes:
            raise ValueError('Compiler source archive identity differs from the snapshot')
        with (staging / 'acceptance.log').open('w') as log:
            subprocess.run([
                args.java, '-cp', str(jar), str(host_source / 'tools/NpcStageBundleAcceptance.java'),
                str(selected_map), str(addon), args.addon_sha256, args.addon_sha1, str(content),
            ], check=True, stdout=log, stderr=subprocess.STDOUT, text=True, timeout=120)
        acceptance = (staging / 'acceptance.log').read_text()
        verify_pair(jar, addon, args.addon_sha1, content)
        catalog_jars = [p for p in (pack_source / 'resourcepacks-catalog/build/libs').glob('*.jar')
                        if not p.name.endswith('-sources.jar')]
        if len(catalog_jars) != 1:
            raise ValueError('Expected one source-built standalone catalog JAR')
        catalog = staging / 'catalog.jar'
        shutil.copyfile(catalog_jars[0], catalog)
        verify_catalog_metadata(catalog, catalog_version)
        receipt = {
            'qualified': True,
            'hostCommit': host_commit,
            'hostDirty': False,
            'resourcepacksCommit': args.resourcepacks_commit,
            'lobbySourceCommit': args.lobby_source_commit,
            'policySha256': policy_sha256,
            'sourceArchivesSha256': source_hashes,
            'sceneSha256': scene_sha256,
            'addonSha256': digest(staging / 'npc-addon.zip'),
            'addonSha1': args.addon_sha1,
            'jarSha256': digest(staging / 'minestom-lobby.jar'),
            'catalog': {
                'channel': 'pinned',
                'id': 'grounds:resourcepacks',
                'version': catalog_version,
                'coordinate': 'gg.grounds:resourcepacks-catalog:' + catalog_version,
                'file': 'catalog.jar',
                'sha256': digest(catalog),
                'size': catalog.stat().st_size,
            },
            'models': models,
            'hostTests': host_tests,
            'serverContentSha256': digest(content),
            'npcVersion': args.npc_version,
            'importerArtifacts': importer_hashes,
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
