"""Verify icon provenance, generated sources, production references and optional APK contents."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, action='append', default=[])
    parser.add_argument('--baseline', type=Path, help='Optional preserved icon migration audit folder')
    args = parser.parse_args()
    result = subprocess.run([sys.executable, str(ROOT / 'scripts/generate_phosphor_icons.py'), '--check'])
    assert result.returncode == 0
    vendor = ROOT / 'sharedUi/src/commonMain/phosphor'
    manifest = json.loads((vendor / 'provenance.json').read_text(encoding='utf-8'))
    forbidden = re.compile(r'\bSfIcons\b|\bBrokenHeart\b|com\.composables\.sfsymbols|\bSfSymbols\b|Jetpack_SF_Symbols')
    for module in ('app', 'tv', 'sharedUi'):
        for file in (ROOT / module / 'src').rglob('*.kt'):
            assert not forbidden.search(file.read_text(encoding='utf-8')), str(file)
        gradle = ROOT / module / 'build.gradle.kts'
        assert not forbidden.search(gradle.read_text(encoding='utf-8')), str(gradle)
    expected_assets = {row['path'] for row in manifest['assets']}
    actual_assets = {str(path.relative_to(vendor)).replace('\\', '/') for path in (vendor / 'assets').rglob('*.svg')}
    assert actual_assets == expected_assets
    assert {row['weight'] for row in manifest['icons'].values()} <= {'regular', 'fill'}
    preserved = 0
    if args.baseline:
        inventory = json.loads((args.baseline / 'inventory-before.json').read_text(encoding='utf-8'))
        with zipfile.ZipFile(args.baseline / 'selected-source-before.zip') as archive:
            for row in inventory['files']:
                path = row['path']
                before = archive.read(path).decode('utf-8').replace('\r\n', '\n')
                if '/ui/icons/' in path:
                    assert not (ROOT / path).exists()
                    continue
                if path.endswith('.kts'):
                    continue
                before = before.replace('BrokenHeart.Outline', 'AppIcons.Dislike').replace('BrokenHeart.Fill', 'AppIcons.DislikeFill')
                before = re.sub(r'\bSfIcons\b', 'AppIcons', before)
                before = before.replace('import com.music.bitchord.ui.icons.BrokenHeart\n', '')
                if 'AppIcons.' in before and 'import com.music.bitchord.ui.icons.AppIcons' not in before:
                    before = before.replace('\n\n', '\n\nimport com.music.bitchord.ui.icons.AppIcons\n', 1)
                assert (ROOT / path).read_text(encoding='utf-8') == before, 'Non-icon change in ' + path
                preserved += 1
    packages = []
    license_bytes = (vendor / 'LICENSE').read_bytes()
    for apk in args.apk:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            dex_files = [name for name in names if re.fullmatch(r'classes\d*\.dex', name)]
            has_phosphor = False
            for name in names:
                if name in dex_files:
                    dex = archive.read(name)
                    assert b'com/composables/sfsymbols' not in dex, f'Obsolete classes in {apk}!/{name}'
                    has_phosphor |= b'Phosphor.' in dex
                assert not re.search(r'sfsymbols|sf_symbols', name, re.I), name
                assert not name.endswith('.svg') or '/phosphor/' not in name, 'Source-only SVG unexpectedly packaged'
            assert has_phosphor, 'No generated Phosphor vectors'
            notices = [name for name in names if name.endswith('/Phosphor-MIT.txt')]
            assert notices and all(archive.read(name) == license_bytes for name in notices), 'Missing or changed Phosphor MIT notice'
            packages.append({'path': str(apk.resolve()), 'bytes': apk.stat().st_size,
                             'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                             'license_entries': notices, 'obsolete_symbol_classes': False})
    print(json.dumps({'semantic_icons': len(manifest['icons']), 'svg_subset': len(manifest['assets']),
                      'svg_bytes_in_source': sum(row['bytes'] for row in manifest['assets']),
                      'caller_files_preserved': preserved, 'packages': packages}, indent=2))


if __name__ == '__main__':
    main()
