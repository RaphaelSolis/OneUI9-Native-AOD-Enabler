#!/usr/bin/env python3
"""Build the standalone legacy LSPosed module with the installed Android tools."""
import argparse
import hashlib
import json
import re
import secrets
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STUBS = {
    'de/robv/android/xposed/IXposedHookLoadPackage.java': '''package de.robv.android.xposed;
public interface IXposedHookLoadPackage {
 void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam param) throws Throwable;
}''',
    'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public abstract class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
 public static final class MethodHookParam {
  public Object thisObject; public Object[] args;
  public Object getResult() { throw new UnsupportedOperationException(); }
  public void setResult(Object result) { throw new UnsupportedOperationException(); }
 }
 public final class Unhook { public void unhook() {} }
}''',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.Set;
public final class XposedBridge {
 public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) { throw new UnsupportedOperationException(); }
 public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> owner, XC_MethodHook callback) { throw new UnsupportedOperationException(); }
 public static void log(String message) {}
 public static void log(Throwable error) {}
}''',
    'de/robv/android/xposed/callbacks/XC_LoadPackage.java': '''package de.robv.android.xposed.callbacks;
public final class XC_LoadPackage {
 public static final class LoadPackageParam {
  public String packageName; public String processName; public ClassLoader classLoader;
 }
}''',
}


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(result.stdout.strip())
    return result.stdout.strip()


def dex_classes(data):
    count, offset = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(count):
        pos = struct.unpack_from('<I', data, offset + i * 4)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        strings.append(data[pos:data.index(b'\0', pos)].decode('utf-8', 'replace'))
    count, offset = struct.unpack_from('<II', data, 64)
    types = [strings[struct.unpack_from('<I', data, offset + i * 4)[0]]
             for i in range(count)]
    count, offset = struct.unpack_from('<II', data, 96)
    return [types[struct.unpack_from('<I', data, offset + i * 32)[0]]
            for i in range(count)]


def jar_classes(target, classes, predicate):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob('*.class')):
            relative = path.relative_to(classes).as_posix()
            if predicate(relative):
                archive.write(path, relative)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--android-jar', type=Path,
                        default=Path('/data/data/com.termux/files/home/v2/v3/build-tools/android.jar'))
    parser.add_argument('--d8-jar', type=Path,
                        default=Path('/data/data/com.termux/files/home/v2/v3/build-tools/r8.jar'))
    options = parser.parse_args()
    for path in (options.android_jar, options.d8_jar):
        if not path.is_file():
            raise SystemExit(f'Missing build dependency: {path}')

    build = ROOT / 'build'
    if build.exists():
        shutil.rmtree(build)
    build.mkdir()
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    signing = ROOT / '.signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    signing.chmod(0o700)
    password = signing / 'keystore.password'
    key = signing / 'module.keystore'
    if not password.exists():
        password.write_text(secrets.token_urlsafe(32) + '\n')
        password.chmod(0o600)
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storetype', 'JKS',
            '-alias', 'aod-battery', '-keyalg', 'RSA', '-keysize', '3072',
            '-validity', '3650', '-storepass:file', password, '-keypass:file', password,
            '-dname', 'CN=Raphael Solis, OU=OneUI A16 Modules')
        key.chmod(0o600)

    stub_sources = []
    for relative, source in STUBS.items():
        path = build / 'compile-only' / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('// Compile-only API signatures; never bundled in the APK.\n' + source + '\n')
        stub_sources.append(path)
    classes = build / 'classes'
    classes.mkdir()
    sources = sorted((ROOT / 'src').rglob('*.java'))
    run('javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', options.android_jar,
        '-d', classes, *stub_sources, *sources)
    program = build / 'program.jar'
    api = build / 'xposed-compile-only.jar'
    jar_classes(program, classes, lambda path: path.startswith('org/raphaelsolis/aodbattery/'))
    jar_classes(api, classes, lambda path: path.startswith('de/robv/android/xposed/'))
    dex = build / 'dex'
    dex.mkdir()
    run('java', '-cp', options.d8_jar, 'com.android.tools.r8.D8', '--release', '--min-api', '35',
        '--lib', options.android_jar, '--classpath', api, '--output', dex, program)

    resources = build / 'resources.zip'
    run('aapt2', 'compile', '--dir', ROOT / 'res', '-o', resources)
    unsigned = build / 'unsigned.apk'
    run('aapt2', 'link', '-I', options.android_jar, '--manifest', ROOT / 'AndroidManifest.xml',
        '--min-sdk-version', '35', '--target-sdk-version', '36', '-A', ROOT / 'assets',
        '-o', unsigned, resources)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(dex.glob('classes*.dex')):
            archive.write(path, path.name)
    apk = dist / 'OneUI9-AOD-Theme-Fix.apk'
    run('apksigner', 'sign', '--ks', key, '--ks-key-alias', 'aod-battery',
        '--ks-pass', f'file:{password}',
        '--out', apk, unsigned)
    verification = run('apksigner', 'verify', '--verbose', '--print-certs', apk)
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('APK archive integrity check failed')
        entry = archive.read('assets/xposed_init').decode().strip()
        if entry != 'org.raphaelsolis.aodbattery.BatteryHook':
            raise RuntimeError('Unexpected LSPosed entry point')
        definitions = [name for path in archive.namelist() if re.fullmatch(r'classes\d*\.dex', path)
                       for name in dex_classes(archive.read(path))]
        if not definitions or any(not name.startswith('Lorg/raphaelsolis/aodbattery/')
                                  for name in definitions):
            raise RuntimeError('Foreign classes or compile-only API stubs were bundled')
        if 'Lorg/raphaelsolis/aodbattery/BatteryHook;' not in definitions:
            raise RuntimeError('Entry point class missing from DEX')
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist / (apk.name + '.sha256')).write_text(checksum + '  ' + apk.name + '\n')
    report = {
        'artifact': apk.name, 'sha256': checksum, 'size_bytes': apk.stat().st_size,
        'package': 'org.raphaelsolis.aodbattery', 'author': 'Raphael Solis',
        'version': '0.3', 'entry_point': entry,
        'dex_defined_classes': definitions, 'signature_verification': verification,
        'compile_only_api_bundled': False, 'installed': False, 'activated': False,
        'visual_behavior_tested': False,
    }
    (dist / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Built and verified: {apk}')
    print(f'SHA-256: {checksum}')


if __name__ == '__main__':
    main()
