"""Build and qualify private licensed NPC inputs. Never publishes or patches classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
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



def existing_catalog_version(jar, expected_sha256, *, generated_version=None):
    """Reuse immutable neutral metadata without generating new bytes under its version."""
    jar = Path(jar)
    if not re.fullmatch('[0-9a-f]{64}', expected_sha256 or ''):
        raise ValueError('Existing catalog requires an explicit SHA-256 digest')
    if not jar.is_file() or jar.stat().st_size > 16 * 1024 * 1024:
        raise ValueError('Existing catalog file is missing or too large')
    if digest(jar) != expected_sha256:
        raise ValueError('Existing catalog hash differs from the pinned digest')
    with zipfile.ZipFile(jar) as archive:
        if len(archive.infolist()) > 1024 or sum(i.file_size for i in archive.infolist()) > 64 * 1024 * 1024:
            raise ValueError('Existing catalog metadata exceeds size limits')
        version = archive.read('gg/grounds/resourcepacks/catalog/catalog-version.txt').decode('utf-8').strip()
    if not version or len(version) > 128:
        raise ValueError('Existing catalog version is missing or too long')
    verify_catalog_metadata(jar, version)
    if version == generated_version:
        raise ValueError("Existing catalog identity must differ from the fresh generated build identity")
    return version


def verify_embedded_catalog(host_jar, catalog_jar):
    """Prove Shadow embedded exactly the selected catalog's classes and identity."""
    def entries(path):
        with zipfile.ZipFile(path) as archive:
            selected = [name for name in archive.namelist()
                        if not name.endswith('/') and (
                            name.startswith('gg/grounds/resourcepacks/catalog/') or
                            name == 'META-INF/resourcepacks-catalog.kotlin_module')]
            if len(set(selected)) != len(selected):
                raise ValueError('Duplicate embedded catalog entries')
            require_contract(len(selected) <= 1024 and
                             sum(archive.getinfo(name).file_size for name in selected) <= 64 * 1024 * 1024,
                             'Selected catalog metadata exceeds size limits')
            return {name: archive.read(name) for name in selected}
    if entries(host_jar) != entries(catalog_jar):
        raise ValueError('Executable catalog differs from the selected immutable catalog')


def digest(path, algorithm='sha256'):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


# This allowlist mirrors LobbyDialogueProfileExport.requireToolingEnvironment.
TOOLING_ENVIRONMENT = frozenset(('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'LC_ALL',
    'TMPDIR', 'TEMP', 'TMP', 'TZ', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'OS'))
DIALOGUE_FILES = {'executable': 'minestom-lobby.jar', 'serverContent': 'server-content.zip',
    'addon': 'npc-addon.zip', 'catalog': 'catalog.jar', 'scene': 'dialogue/scene.json',
    'profile': 'dialogue/profile.json', 'hostCatalog': 'dialogue/context/host-catalog.json'}
JSON_LIMIT = 1024 * 1024


def require_contract(condition, message):
    if not condition:
        raise ValueError(message)


def regular_path(path):
    path = Path(os.path.abspath(path))
    try:
        require_contract(not any(part.is_symlink() for part in (path, *path.parents)),
                         'Dialogue paths must not contain symlinks')
        require_contract(stat.S_ISREG(path.lstat().st_mode), 'Dialogue file must be regular')
    except OSError as error:
        raise ValueError('Dialogue file is missing or inaccessible') from error
    return path


def file_pin(path, *, addon=False):
    path = regular_path(path)
    before = path.stat()
    hashes = {name: hashlib.new(name) for name in (('sha256', 'sha1') if addon else ('sha256',))}
    size = 0
    with path.open('rb') as stream:
        require_contract(os.fstat(stream.fileno()) == before, 'Dialogue file changed before hashing')
        while block := stream.read(65536):
            size += len(block)
            for value in hashes.values(): value.update(block)
    after = path.stat()
    def identity(value):
        return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
    require_contract(identity(before) == identity(after) and size == before.st_size,
                     'Dialogue file changed while hashing')
    return dict({name: value.hexdigest() for name, value in hashes.items()}, size=size)


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require_contract(key not in result, 'Duplicate JSON field: ' + key)
            result[key] = value
        return result
    def invalid(value):
        raise ValueError('Non-finite JSON number')
    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError('Invalid dialogue JSON') from error


def bounded_json(path, limit=JSON_LIMIT):
    path = regular_path(path)
    require_contract(path.stat().st_size <= limit, 'Dialogue JSON exceeds size limit')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require_contract(len(data) <= limit, 'Dialogue JSON exceeds size limit')
    return strict_json(data), data


def fields(value, expected):
    require_contract(type(value) is dict and set(value) == set(expected), 'Unexpected dialogue JSON fields')


def reference(value):
    fields(value, ('id', 'version'))
    for item in value.values():
        require_contract(type(item) is str and 0 < len(item) <= 128 and item.isascii(),
                         'Invalid catalog/provider reference')


def verify_dialogue_profile(root, expected_scene_sha256):
    """Verify private exporter artifacts; this is digest integrity, not authentication."""
    root = Path(root)
    binding_pin = file_pin(root / 'dialogue/binding.json')
    binding, binding_bytes = bounded_json(root / 'dialogue/binding.json')
    require_contract(hashlib.sha256(binding_bytes).hexdigest() == binding_pin['sha256'],
                     'Dialogue binding changed while reading')
    profile, profile_bytes = bounded_json(root / DIALOGUE_FILES['profile'])
    host, host_bytes = bounded_json(root / DIALOGUE_FILES['hostCatalog'])
    fields(binding, ('schemaVersion', 'id', 'version', 'generation', 'manifest', 'files'))
    fields(profile, ('schemaVersion', 'id', 'version', 'hostCatalogJson', 'assetCatalog',
                     'actionCatalogs', 'startActions', 'npcAssets'))
    fields(host, ('schemaVersion', 'generation', 'speakers', 'conditions', 'actions',
                  'gestures', 'capabilities', 'durableOutcomes'))
    manifest = binding['manifest']
    fields(manifest, ('schemaVersion', 'serviceVariant', 'provider', 'dialogueId', 'roles',
                      'actionProviders', 'assetCatalog', 'actionCatalogs', 'contentId', 'files'))
    for value in (binding, profile, host, manifest):
        require_contract(type(value['schemaVersion']) is int and value['schemaVersion'] == 1,
                         'Invalid dialogue schema version')
    fields(binding['files'], DIALOGUE_FILES)
    fields(manifest['files'], list(DIALOGUE_FILES)[:5])
    actual_pins = {}
    for role, name in DIALOGUE_FILES.items():
        pin = binding['files'][role]
        expected_fields = {'file', 'sha256', 'size'} | ({'sha1'} if role == 'addon' else set())
        fields(pin, expected_fields)
        require_contract(pin['file'] == name, 'Dialogue file path substitution')
        require_contract(type(pin['size']) is int and pin['size'] >= 0, 'Invalid dialogue pin size')
        for algorithm in ('sha256', 'sha1') if role == 'addon' else ('sha256',):
            require_contract(type(pin[algorithm]) is str and
                             re.fullmatch('[0-9a-f]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', pin[algorithm]),
                             'Invalid dialogue digest')
        actual = file_pin(root / name, addon=role == 'addon')
        require_contract(pin == dict(actual, file=name), 'Dialogue artifact differs from pin: ' + role)
        actual_pins[role] = actual
        if role in manifest['files']:
            fields(manifest['files'][role], expected_fields - {'file'})
            require_contract(type(manifest['files'][role]['size']) is int and manifest['files'][role] == actual,
                             'Dialogue manifest pin differs: ' + role)
    require_contract(hashlib.sha256(profile_bytes).hexdigest() == actual_pins['profile']['sha256'] and
                     hashlib.sha256(host_bytes).hexdigest() == actual_pins['hostCatalog']['sha256'],
                     'Dialogue output changed while reading')
    require_contract(actual_pins['scene']['size'] <= 16 * JSON_LIMIT and
                     actual_pins['scene']['sha256'] == expected_scene_sha256,
                     'Dialogue selected scene differs from snapshot')
    reference(manifest['provider'])
    require_contract(manifest['serviceVariant'] == 'grounds.lobby.navigator' and
                     manifest['provider']['id'] == manifest['serviceVariant'], 'Invalid dialogue service provider')
    reference(manifest['assetCatalog'])
    catalogs = manifest['actionCatalogs']
    require_contract(type(catalogs) is list and 1 <= len(catalogs) <= 16, 'Invalid action catalogs')
    for catalog in catalogs: reference(catalog)
    require_contract(catalogs == sorted(catalogs, key=lambda ref: (ref['id'], ref['version'])) and
                     len({(ref['id'], ref['version']) for ref in catalogs}) == len(catalogs),
                     'Invalid action catalog ordering')
    roles = manifest['roles']
    require_contract(type(roles) is dict and set(roles) == {'guide'} and
                     all(type(body) is str and body and body.isascii() for body in roles.values()),
                     'Invalid dialogue role mapping')
    providers = manifest['actionProviders']
    require_contract(type(providers) is dict, 'Invalid action providers')
    if manifest['dialogueId'] == 'grounds:lobby-guide':
        profile_id = 'grounds:lobby-guide'
        require_contract(set(providers) == {'grounds:lobby/open_navigator', 'grounds:lobby/open_duel_menu'},
                         'Invalid Guide action providers')
    else:
        require_contract(manifest['dialogueId'] == 'stage:mechanic', 'Unsupported dialogue ID')
        profile_id = 'grounds:stage-dialogue'
        require_contract(providers == {}, 'Stage has no action providers')
    for spec in providers.values():
        fields(spec, ('arguments', 'durable'))
        require_contract(spec['arguments'] == {} and spec['durable'] is False, 'Invalid action provider spec')
    # All defined manifest values are ASCII; no escaping or whitespace participates in identity.
    canonical = json.dumps(manifest, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    require_contract(canonical.isascii(), 'Manifest values must be ASCII')
    generation = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
    require_contract(binding['id'] == profile['id'] == profile_id and
                     binding['version'] == binding['generation'] == profile['version'] == host['generation'] == generation,
                     'Dialogue generation or profile identity differs from canonical manifest')
    require_contract(type(profile['hostCatalogJson']) is str and
                     profile['hostCatalogJson'].encode('utf-8') == host_bytes,
                     'Embedded host catalog differs from written host bytes')
    fields(host['actions'], providers)
    for spec in host['actions'].values():
        fields(spec, ('arguments', 'durable'))
        require_contract(spec['arguments'] == {} and spec['durable'] is False,
                         'Invalid host action provider spec')
    require_contract(host['speakers'] == sorted(roles) and host['actions'] == providers and
                     host['conditions'] == {} and host['gestures'] == [] and
                     host['capabilities'] == [] and host['durableOutcomes'] is False,
                     'Host catalog claims differ from manifest')
    require_contract(profile['assetCatalog'] == manifest['assetCatalog'] and
                     profile['actionCatalogs'] == catalogs and
                     profile['npcAssets'] == {body: {'gestures': {}} for body in roles.values()},
                     'Profile roles/catalogs differ from manifest')
    require_contract(profile['startActions'] == {'grounds:lobby/start_dialogue': {
        'dialogueArgument': 'dialogue', 'speakerArgument': 'speaker'}}, 'Invalid dialogue start action')
    catalog = root / DIALOGUE_FILES['catalog']
    require_contract(manifest['assetCatalog']['id'] == 'grounds:assets', 'Invalid asset catalog')
    existing_catalog_version(catalog, actual_pins['catalog']['sha256'])
    verify_catalog_metadata(catalog, manifest['assetCatalog']['version'])
    verify_embedded_catalog(root / DIALOGUE_FILES['executable'], catalog)
    content = root / DIALOGUE_FILES['serverContent']
    with zipfile.ZipFile(content) as archive:
        info = archive.getinfo('bundle.json')
        require_contract(info.file_size <= 16 * JSON_LIMIT, 'Content bundle metadata exceeds size limit')
        contract = strict_json(archive.read(info))
    require_contract(type(contract) is dict and type(manifest['contentId']) is str and
                     re.fullmatch('[0-9a-f]{64}', manifest['contentId']) and
                     contract.get('contentId') == manifest['contentId'], 'Content ID differs from actual bundle')
    require_contract(set(roles.values()) <= {model['id'] for model in contract['models']},
                     'Dialogue role body is missing from actual bundle')
    verify_pair(root / DIALOGUE_FILES['executable'], root / DIALOGUE_FILES['addon'],
                actual_pins['addon']['sha1'], content)
    # Detect changes during the metadata/archive verification as well as during each streamed hash.
    for role, name in DIALOGUE_FILES.items():
        require_contract(file_pin(root / name, addon=role == 'addon') == actual_pins[role],
                         'Dialogue input changed during verification: ' + role)
    require_contract(file_pin(root / 'dialogue/binding.json') == binding_pin,
                     'Dialogue binding changed during verification')
    return dict(id=profile_id, version=generation, generation=generation,
                **{role: dict(actual_pins[role], file=DIALOGUE_FILES[role]) for role in ('profile', 'hostCatalog')},
                binding=dict(binding_pin, file='dialogue/binding.json'))


def finalize_distribution(staging, output, receipt, dialogue_scene=None, expected_scene_sha256=None, *, java='java'):
    """The production publication boundary: exporter and final verification precede receipt/rename."""
    receipt = dict(receipt)
    if dialogue_scene is not None:
        require_contract(file_pin(dialogue_scene / 'scene.json')['sha256'] == expected_scene_sha256,
                         'Dialogue scene snapshot changed before export')
        environment = {key: value for key, value in os.environ.items() if key.upper() in TOOLING_ENVIRONMENT}
        with (staging / 'dialogue-export.log').open('w') as log:
            subprocess.run([java, '-cp', str(staging / 'minestom-lobby.jar'),
                            'gg.grounds.minestom.lobby.scene.LobbyDialogueProfileExport',
                            '--distribution', str(staging), '--scene', str(dialogue_scene)],
                           check=True, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        receipt['dialogueProfile'] = verify_dialogue_profile(staging, expected_scene_sha256)
    shutil.rmtree(staging / 'inputs')
    if dialogue_scene is not None:
        require_contract(verify_dialogue_profile(staging, expected_scene_sha256) == receipt['dialogueProfile'],
                         'Dialogue profile changed before publication')
    require_contract(not output.exists(), 'Distribution output must not exist')
    try:
        (staging / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        staging.rename(output)
    except BaseException:
        (staging / 'receipt.json').unlink(missing_ok=True)
        raise
    return receipt


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
    parser.add_argument('--catalog-jar', type=Path)
    parser.add_argument('--catalog-sha256')
    parser.add_argument('--lobby-source', type=Path)
    parser.add_argument('--lobby-source-commit')
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--composite-policy', type=Path, required=True)
    parser.add_argument('--addon', type=Path, required=True)
    parser.add_argument('--addon-sha256', required=True)
    parser.add_argument('--addon-sha1', required=True)
    parser.add_argument('--map', type=Path, required=True)
    parser.add_argument('--dialogue-profile-scene', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gradle')
    parser.add_argument('--java', default='java')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--npc-importer-classpath', required=True)
    parser.add_argument('--npc-version', default='2.0.0')
    parser.add_argument('--npc-repository', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    for name in ('resourcepacks', 'sources', 'composite_policy', 'addon', 'map', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    if args.output.exists():
        raise ValueError('Distribution output must not exist')
    verify_checkout(args.resourcepacks, args.resourcepacks_commit)
    generated_catalog_version = private_catalog_version(args.resourcepacks_commit, args.catalog_build_number)
    if bool(args.catalog_jar) != bool(args.catalog_sha256):
        raise ValueError('Existing catalog requires both --catalog-jar and --catalog-sha256')
    catalog_version = generated_catalog_version
    if args.catalog_jar:
        args.catalog_jar = args.catalog_jar.resolve()
        catalog_version = existing_catalog_version(args.catalog_jar, args.catalog_sha256, generated_version=generated_catalog_version)
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
    dialogue_scene_sha256 = None
    if args.dialogue_profile_scene is not None:
        selected = regular_path(args.dialogue_profile_scene / 'scene.json')
        require_contract(selected.stat().st_size <= 16 * JSON_LIMIT, 'Dialogue scene exceeds 16 MiB')
        dialogue_scene_sha256 = file_pin(selected)['sha256']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Failed attempts retain their logs, but never a qualified receipt or final output.
    with tempfile.TemporaryDirectory(prefix='.npc-distribution-', dir=args.output.parent, delete=False) as directory:
        staging = Path(directory)
        print('Build logs: ' + str(staging / 'build.log'), flush=True)
        inputs = staging / 'inputs'
        inputs.mkdir()
        existing_catalog = None
        if args.catalog_jar:
            existing_catalog = inputs / 'existing-catalog.jar'
            shutil.copyfile(args.catalog_jar, existing_catalog)
            if existing_catalog_version(existing_catalog, args.catalog_sha256, generated_version=generated_catalog_version) != catalog_version:
                raise ValueError('Existing catalog identity changed during snapshot')
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
        dialogue_scene = None
        if args.dialogue_profile_scene is not None:
            dialogue_scene = inputs / 'dialogue-scene'
            dialogue_scene.mkdir()
            shutil.copyfile(args.dialogue_profile_scene / 'scene.json', dialogue_scene / 'scene.json')
            require_contract(file_pin(dialogue_scene / 'scene.json')['sha256'] == dialogue_scene_sha256,
                             'Dialogue scene changed during snapshot')
        addon = staging / 'npc-addon.zip'
        shutil.copyfile(args.addon, addon)
        if digest(addon) != args.addon_sha256 or digest(addon, 'sha1') != args.addon_sha1:
            raise ValueError('Addon changed during snapshot')
        command = [args.gradle or str(host_source / 'gradlew'), '--no-daemon', '--no-build-cache']
        if args.offline:
            command.append('--offline')
        if args.lobby_source:
            command.append('-PnpcLobbySourceDirectory=' + str(lobby_source))
        if existing_catalog:
            command.append('-PnpcExistingCatalogJar=' + str(existing_catalog))
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
            '-PpackSetVersion=' + generated_catalog_version,
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
        catalog = staging / 'catalog.jar'
        if existing_catalog:
            shutil.copyfile(existing_catalog, catalog)
            if digest(catalog) != args.catalog_sha256:
                raise ValueError('Existing catalog changed before distribution assembly')
        else:
            catalog_jars = [p for p in (pack_source / 'resourcepacks-catalog/build/libs').glob('*.jar')
                            if not p.name.endswith('-sources.jar')]
            if len(catalog_jars) != 1:
                raise ValueError('Expected one source-built standalone catalog JAR')
            shutil.copyfile(catalog_jars[0], catalog)
        verify_catalog_metadata(catalog, catalog_version)
        verify_embedded_catalog(jar, catalog)
        receipt = {
            'qualified': True,
            'embeddedCatalogVerified': True,
            'hostCommit': host_commit,
            'hostDirty': False,
            'resourcepacksCommit': args.resourcepacks_commit,
            'lobbySourceCommit': args.lobby_source_commit,
            'policySha256': policy_sha256,
            'reusedImmutableCatalog': bool(existing_catalog),
            'generatedCatalogVersion': generated_catalog_version,
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
        receipt = finalize_distribution(staging, args.output, receipt, dialogue_scene, dialogue_scene_sha256, java=args.java)
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
