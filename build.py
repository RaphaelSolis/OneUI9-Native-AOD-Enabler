#!/usr/bin/env python3
"""Package the KernelSU module without changing the Samsung APK."""
import hashlib
import stat
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APK = ROOT / 'system/priv-app/SamsungAODCoexist/SamsungAODCoexist.apk'
APK_SHA256 = 'ea8ad221a10ce2e2a47e03cf9a5c5781e4063379f82d3ff1015b4817579b23ae'


def main():
    if hashlib.sha256(APK.read_bytes()).hexdigest() != APK_SHA256:
        raise SystemExit('The Samsung AOD APK differs from the tested donor.')
    metadata = (ROOT / 'module.prop').read_text()
    if 'id=a16_native_aod_coexist\n' not in metadata:
        raise SystemExit('Unexpected module ID.')
    files = [ROOT / name for name in ('module.prop', 'customize.sh', 'service.sh', 'collect-test-logs.sh')]
    for folder in ('system', 'vendor'):
        files.extend(path for path in (ROOT / folder).rglob('*') if path.is_symlink() or path.is_file())
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    output = dist / 'OneUI9-Native-AOD-Enabler.zip'
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):
            relative = path.relative_to(ROOT).as_posix()
            entry = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            if path.is_symlink():
                target = path.readlink().as_posix()
                if relative != 'system/vendor' or target != '../vendor':
                    raise SystemExit('Unexpected module symlink.')
                entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                contents = target.encode()
            else:
                mode = 0o755 if relative.endswith('.sh') else 0o644
                entry.external_attr = (stat.S_IFREG | mode) << 16
                contents = path.read_bytes()
            archive.writestr(entry, contents)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise SystemExit('ZIP integrity check failed.')
        if archive.read('module.prop') != (ROOT / 'module.prop').read_bytes():
            raise SystemExit('Packaged module metadata differs.')
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.zip.sha256').write_text(f'{digest}  {output.name}\n')
    print(f'Built: {output}')
    print(f'SHA-256: {digest}')


if __name__ == '__main__':
    main()
