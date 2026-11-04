"""Verify model/notice integrity offline, with optional inspection of actual packages."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
NOTICE_DIR = Path('app/src/main/assets/licenses/models')
NOTICE_FILES = ('Beat-This-MIT.txt', 'Open-Unmix-MIT.txt', 'NOTICE.md', 'provenance.json')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def stream_sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def file_sha256(path):
    with path.open('rb') as stream:
        return stream_sha256(stream)


def check_archive_notices(archive, prefix, expected):
    for name, data in expected.items():
        entry = prefix + name
        require(entry in archive.namelist(), 'Missing packaged notice: ' + entry)
        require(archive.read(entry) == data, 'Changed packaged notice: ' + entry)


def check_archive_models(archive, rows, prefix):
    for row in rows:
        entry = prefix + row['entry']
        require(entry in archive.namelist(), 'Missing packaged model: ' + entry)
        info = archive.getinfo(entry)
        require(info.file_size == row['bytes'], 'Changed packaged model size: ' + entry)
        with archive.open(entry) as stream:
            require(stream_sha256(stream) == row['sha256'], 'Changed packaged model: ' + entry)


def check_directory_notices(directory, expected, context):
    for name, data in expected.items():
        path = directory / name
        require(path.is_file(), 'Missing ' + context + ' model notice: ' + str(path))
        require(path.read_bytes() == data, 'Changed ' + context + ' model notice: ' + str(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='Source root; useful for isolated verification fixtures')
    parser.add_argument('--apk', type=Path, action='append', default=[])
    parser.add_argument('--desktop-jar', type=Path, action='append', default=[])
    parser.add_argument('--portable', type=Path, action='append', default=[])
    parser.add_argument('--web-dist', type=Path, action='append', default=[],
                        help='Actual built Web output directory, normally web/app/dist')
    args = parser.parse_args()
    root = args.root.resolve()
    notices = root / NOTICE_DIR
    manifest_path = notices / 'provenance.json'
    require(manifest_path.is_file(), 'Missing model notice inventory: ' + str(manifest_path))
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    require(manifest['schema'] == 1, 'Unsupported model notice inventory schema')
    require(manifest['exact_onnx_export_lineage_verified'] is False,
            'Do not silently promote unverified ONNX export lineage')
    require(bool(manifest['unresolved_release_gates']), 'Missing explicit unresolved model release gates')
    expected = {name: (notices / name).read_bytes() for name in NOTICE_FILES}
    require({row['path'] for row in manifest['license_texts']} ==
            {'Beat-This-MIT.txt', 'Open-Unmix-MIT.txt'}, 'The two original model MIT notices are required')
    for license_row in manifest['license_texts']:
        require(license_row['license'] == 'MIT', 'Unexpected original model license label')
        data = expected[license_row['path']]
        require(len(data) == license_row['bytes'], 'Changed original license length: ' + license_row['path'])
        require(hashlib.sha256(data).hexdigest() == license_row['sha256'],
                'Changed original license SHA-256: ' + license_row['path'])
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        require(blob == license_row['origin']['git_blob_sha1'],
                'License differs from its pinned original Git blob: ' + license_row['path'])
    check_directory_notices(root / 'web/app/public/models/licenses', expected, 'Web public')
    for row in manifest['source_models']:
        relative = Path(row['path'])
        require(not relative.is_absolute() and '..' not in relative.parts, 'Invalid model inventory path')
        path = root / relative
        require(path.is_file(), 'Missing inventoried model: ' + row['path'])
        require(path.stat().st_size == row['bytes'], 'Changed model size: ' + row['path'])
        require(file_sha256(path) == row['sha256'], 'Changed model SHA-256: ' + row['path'])
    layouts = manifest['package_layouts']
    packages = []
    for path in args.apk:
        with zipfile.ZipFile(path) as archive:
            check_archive_notices(archive, 'assets/licenses/models/', expected)
            check_archive_models(archive, layouts['android_tv'], 'assets/')
        packages.append({'kind': 'Android/TV APK', 'path': str(path.resolve()), 'sha256': file_sha256(path)})
    for path in args.desktop_jar:
        with zipfile.ZipFile(path) as archive:
            check_archive_notices(archive, 'licenses/models/', expected)
            check_archive_models(archive, layouts['desktop'], 'models/')
        packages.append({'kind': 'Desktop application JAR', 'path': str(path.resolve()), 'sha256': file_sha256(path)})
    for path in args.portable:
        with zipfile.ZipFile(path) as archive:
            check_archive_notices(archive, 'BitChord/licenses/models/', expected)
            app = 'BitChord/lib/bitchord-app.jar'
            require(app in archive.namelist(), 'Missing portable application JAR')
            with zipfile.ZipFile(io.BytesIO(archive.read(app))) as application:
                check_archive_notices(application, 'licenses/models/', expected)
                check_archive_models(application, layouts['desktop'], 'models/')
        packages.append({'kind': 'Desktop portable ZIP', 'path': str(path.resolve()), 'sha256': file_sha256(path)})
    for path in args.web_dist:
        index = path / 'index.html'
        require(index.is_file(), 'Missing Web output index.html: ' + str(index))
        check_directory_notices(path / 'models/licenses', expected, 'Web output')
        verified_files = [{'path': 'index.html', 'sha256': file_sha256(index)}]
        for name, data in expected.items():
            verified_files.append({'path': 'models/licenses/' + name,
                                   'sha256': hashlib.sha256(data).hexdigest()})
        for row in layouts['web_model_assets']:
            model = path / 'models' / row['entry']
            require(model.is_file(), 'Missing Web output model: ' + str(model))
            require(model.stat().st_size == row['bytes'], 'Changed Web output model size: ' + str(model))
            require(file_sha256(model) == row['sha256'], 'Changed Web output model: ' + str(model))
            verified_files.append({'path': 'models/' + row['entry'], 'sha256': row['sha256']})
        packages.append({'kind': 'Web static output', 'path': str(path.resolve()), 'files': verified_files})
    print(json.dumps({'scope': 'Model/notice integrity only; not export-lineage or release-compliance certification',
                      'source_models_verified': len(manifest['source_models']),
                      'original_mit_notices_verified': len(manifest['license_texts']),
                      'exact_onnx_export_lineage_verified': False, 'packages': packages,
                      'unresolved_release_gates': manifest['unresolved_release_gates']}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print('Model notice verification failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
