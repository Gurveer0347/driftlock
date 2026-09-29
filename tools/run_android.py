"""Run project-local Gradle serially; parallel compilers share output directories.

Usage: .venv/bin/python tools/run_android.py --log logs/build.log -- :core:test
Only development processes use this utility. No runtime app dependency.
"""
from pathlib import Path
import argparse
import fcntl
import os
import subprocess
import sys
import hashlib
import json
import shutil
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log', required=True)
    parser.add_argument('tasks', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = (root / args.log).resolve()
    if not output.is_relative_to(root):
        parser.error('Build logs must stay inside the project')
    output.parent.mkdir(parents=True, exist_ok=True)
    task_args = args.tasks[1:] if args.tasks[:1] == ['--'] else args.tasks
    if not task_args:
        parser.error('Supply Gradle tasks after --')
    env = os.environ.copy()
    env.update(GRADLE_USER_HOME=str(root / '.tooling/gradle-user-home'),
               ANDROID_USER_HOME=str(root / '.tooling/android-user'),
               ANDROID_SDK_ROOT=str(root / '.tooling/android-sdk'))
    command = [str(root / 'android/gradlew'), '-p', str(root / 'android'),
               *task_args, '--no-daemon', '-Pkotlin.incremental=false']
    with (root / '.tooling/gradle.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        def source_hashes():
            return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in (root / 'android').rglob('*') if p.is_file()
                    and 'build' not in p.parts and '.gradle' not in p.parts
                    and p.suffix in {'.kt', '.kts', '.xml', '.properties', '.json', '.onnx'}}
        started = time.time()
        before = source_hashes()
        with output.open('w') as log:
            log.write('COMMAND ' + repr(command) + '\n'); log.flush()
            code = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
            log.write(f'\nEXIT_STATUS={code}\n')
        # Preserve this exact run before another serial build replaces shared XML.
        evidence = output.with_suffix('.evidence')
        evidence.mkdir(exist_ok=True)
        files = {}
        for module, suite in [('core', 'test'), ('app', 'testDebugUnitTest')]:
            for xml in (root / 'android' / module / 'build/test-results' / suite).glob('TEST-*.xml'):
                target = evidence / module / xml.name
                target.parent.mkdir(exist_ok=True)
                shutil.copy2(xml, target)
                files[str(target.relative_to(root))] = {'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                                                       'modified_during_run': xml.stat().st_mtime >= started}
        after = source_hashes()
        (evidence / 'run.json').write_text(json.dumps({'command': command, 'exit_status': code,
            'source_sha256': before, 'sources_changed_during_run': sorted(k for k in set(before) | set(after)
                if before.get(k) != after.get(k)), 'test_artifacts': files}, indent=2) + '\n')
    print(f'Gradle exit={code}; log={output}')
    return code


if __name__ == '__main__':
    sys.exit(main())
