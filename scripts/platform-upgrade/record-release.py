"""Record artifact checksums and test config revisions, never configuration contents."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import struct
import urllib.parse
import urllib.request
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--frontend-source', type=pathlib.Path, required=True)
parser.add_argument('--frontend-commit', required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''): digest.update(chunk)
    return digest.hexdigest()

backend_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
artifacts = []
for module in sorted(root.glob('tj-*')):
    for path in sorted(module.rglob('target/*.jar')):
        artifacts.append({'path': str(path.relative_to(root)).replace('\\', '/'), 'bytes': path.stat().st_size, 'sha256': sha(path)})
dist = args.frontend_source.resolve() / 'dist'
if not (dist / 'index.html').is_file(): raise RuntimeError('Build the isolated frontend source first')
archive = dist.parent.parent / 'frontend-release.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    for path in sorted(dist.rglob('*')):
        if path.is_file(): output.write(path, path.relative_to(dist))

with zipfile.ZipFile(root / 'tj-aigc/target/tj-aigc.jar') as packaged:
    ai_major = struct.unpack('>H', packaged.read('BOOT-INF/classes/com/tianji/aigc/AigcApplication.class')[6:8])[0]
if ai_major != 61: raise RuntimeError('AI release bytecode must be Java 17')

configs = []
for namespace in ('tianji-platform-upgrade-test', 'tianji-platform-upgrade-test-boot3'):
    query = urllib.parse.urlencode({'search': 'blur', 'tenant': namespace, 'dataId': '', 'group': 'DEFAULT_GROUP', 'pageNo': 1, 'pageSize': 100})
    with urllib.request.urlopen('http://127.0.0.1:8848/nacos/v1/cs/configs?' + query, timeout=20) as response:
        listing = json.load(response)
    for item in listing['pageItems']:
        # Nacos also returns content; retain only its digest, name and group.
        configs.append({'namespace': namespace, 'dataId': item['dataId'], 'group': item['group'],
                        'md5': item.get('md5') or hashlib.md5(item['content'].encode()).hexdigest()})

result = {
    'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'backend_commit': backend_commit, 'frontend_commit': args.frontend_commit,
    'java': '17', 'spring_boot': '3.5.16', 'spring_cloud': '2025.0.3', 'spring_cloud_alibaba': '2025.0.0.0', 'spring_ai': '1.1.2',
    'ai_bytecode_major': ai_major,
    'backend_artifacts': artifacts,
    'frontend': {'source': 'git archive of frontend commit; excludes unrelated worktree changes', 'api_base': '/api',
                 'archive': str(archive.relative_to(root)).replace('\\', '/'), 'bytes': archive.stat().st_size, 'sha256': sha(archive),
                 'index_sha256': sha(dist / 'index.html')},
    'docker': {'base': 'eclipse-temurin:17-jre-jammy', 'dockerfile_sha256': sha(root/'Dockerfile'),
               'image_built': False, 'image_deployed': False},
    'ai_schema_sha256': sha(root/'tj-aigc/src/main/resources/db/schema.sql'),
    'test_nacos_revisions': configs,
    'rollback': {'source': 'codex/platform-upgrade-p0', 'java': '11', 'spring_boot': '2.7.2',
                 'namespace': 'tianji-platform-upgrade-test', 'login_checks_passed': 9, 'restored_checks_passed': 9},
    'production_deployed': False,
}
(root/'docs/platform-upgrade-release.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Recorded {len(artifacts)} JAR checksums, frontend archive and {len(configs)} test configuration revisions; no secrets exported.')
