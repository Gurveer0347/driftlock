"""Acquire and survey only synchronized Driver E IO-VNBD originals.

This is evidence collection, never a converter, split generator, training run,
clock repair, or source-meaning approval. Reserved A/B/D payloads are not opened.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from hashlib import sha1, sha256
import json
from pathlib import Path, PurePosixPath
import platform
import re
import subprocess
import sys
import threading
from urllib.parse import quote

import numpy as np
import pandas as pd


PREFIX = 'Synchronised V abd S datasets/'
ALLOWED = re.compile(r'([sv])-(vta\d+[a-z]?|vtb\d+[a-z]?|vfa\d+|vw\d+[a-z]?)\.csv', re.I)
COMMIT = '118939602e3422d47b8ab0807b623751c3ac135b'


def discover_pairs(tree, expected_count=64):
    if tree.get('truncated') is not False:
        raise ValueError('Official tree is truncated or lacks explicit completeness')
    found = {}
    for entry in tree['tree']:
        path = entry['path']
        if not path.startswith(PREFIX) or entry.get('type') != 'blob':
            continue
        parts = PurePosixPath(path).parts
        if '..' in parts:
            raise ValueError('Unsafe repository path')
        match = ALLOWED.fullmatch(parts[-1])
        if not match:
            continue
        if '/Categorised IOVNB Dataset/' in path:
            if '(Driver E)' not in path:
                raise ValueError(f'Allowed family lacks Driver E evidence: {path}')
            variant = 'categorized'
        elif '/Uncategorised IOVNB Dataset/' in path:
            variant = 'uncategorized'
        else:
            continue
        kind, drive = match[1].lower(), match[2].lower()
        pair = found.setdefault(drive, {'drive': drive})
        key = f'{kind}_{variant}'
        if key in pair:
            raise ValueError(f'Duplicate source variant: {drive}/{key}')
        pair[key] = dict(entry)
    required = {'s_categorized', 's_uncategorized', 'v_categorized', 'v_uncategorized'}
    for drive, pair in found.items():
        if not required.issubset(pair):
            raise ValueError(f'incomplete pair {drive}: missing {required - pair.keys()}')
    if len(found) != expected_count:
        raise ValueError(f'Expected {expected_count} complete Driver E pairs, found {len(found)}')
    return [found[key] for key in sorted(found)]


def parse_pointer(pointer, expected_blob_sha1):
    actual = sha1(b'blob ' + str(len(pointer)).encode() + b'\0' + pointer).hexdigest()
    if expected_blob_sha1 is not None and actual != expected_blob_sha1:
        raise ValueError('Git pointer blob SHA1 mismatch')
    parsed = re.fullmatch(rb'version https://git-lfs.github.com/spec/v1\noid sha256:([0-9a-f]{64})\nsize ([0-9]+)\n', pointer)
    if parsed is None:
        raise ValueError('Not an exact Git LFS pointer')
    return {'lfs_sha256': parsed[1].decode(), 'expected_bytes': int(parsed[2]),
            'pointer_git_blob_sha1': actual}


def verify_payload(data, spec):
    if len(data) != spec['expected_bytes'] or sha256(data).hexdigest() != spec['lfs_sha256']:
        raise ValueError('Original payload size/SHA256 mismatch')
    prefix = data[:1024].lower().lstrip()
    if prefix.startswith(b'version https://git-lfs') or b'<html' in prefix or prefix.startswith(b'<!doctype'):
        raise ValueError('Original payload is HTML or a pointer, not CSV')


def persist_verified(path, data, spec):
    verify_payload(data, spec)
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError(f'Existing original differs; refusing overwrite: {path}')
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as out:
        out.write(data)


def stats(values):
    a = np.asarray(values, dtype=float)
    finite = a[np.isfinite(a)]
    return {'count': int(a.size), 'nonfinite': int(a.size - finite.size),
            **{key: float(fn(finite)) if finite.size else None for key, fn in
               [('min', np.min), ('median', np.median), ('p95', lambda x: np.quantile(x, .95)), ('max', np.max)]}}


def counts(values):
    return {str(k): int(v) for k, v in values.value_counts(dropna=False).sort_index().items()}


def compare_phone_variants(a, b):
    result = {'uncategorized_shape': list(a.shape), 'categorized_shape': list(b.shape),
              'headers_equal': list(a) == list(b), 'axis_mapping_established': False,
              'all_values_equal_at_float32_precision': False, 'columns': []}
    if a.shape != b.shape:
        return result
    equal = True
    for i in range(a.shape[1]):
        x, y = a.iloc[:, i], b.iloc[:, i]
        row = {'index': i, 'uncategorized_header': a.columns[i], 'categorized_header': b.columns[i]}
        if pd.api.types.is_numeric_dtype(x) and pd.api.types.is_numeric_dtype(y):
            same = bool(np.array_equal(x.to_numpy(np.float32), y.to_numpy(np.float32), equal_nan=True))
            delta = np.abs(x.to_numpy(float) - y.to_numpy(float))
            row.update(float32_equal=same, absolute_difference=stats(delta))
        else:
            same = bool(np.array_equal(x.fillna('<NA>').to_numpy(), y.fillna('<NA>').to_numpy()))
            row.update(exact_equal=same)
        equal &= same
        result['columns'].append(row)
    result['all_values_equal_at_float32_precision'] = equal
    return result


def plateau(values, mask):
    run = best = 0
    for i, value in enumerate(values):
        run = run + 1 if mask[i] and (i == 0 or (mask[i-1] and value == values[i-1])) else int(mask[i])
        best = max(best, run)
    return int(best)


def survey_frames(s, v):
    sc = s['TIME SINCE START (ms)'].to_numpy(float) / 1000
    vc = v['Time Since Start of Day (seconds)'].to_numpy(float)
    dates = pd.to_datetime(s[next(key for key in s if key.startswith('DATE'))],
                           format='%Y-%m-%d %H:%M:%S:%f', errors='raise')
    elapsed = (dates - dates.iloc[0]).dt.total_seconds().to_numpy()
    sod = (dates - dates.dt.normalize()).dt.total_seconds().to_numpy()
    clocks = {'phone_recording_seconds': stats(sc), 'phone_interval_seconds': stats(np.diff(sc)),
              'phone_nonpositive_intervals': int(np.sum(np.diff(sc) <= 0)),
              'phone_gap_over_0_2_seconds_count': int(np.sum(np.diff(sc) > .2 + 1e-9)),
              'phone_date_elapsed_minus_recording_elapsed_seconds': stats(elapsed - (sc - sc[0])),
              'phone_date_start': str(dates.iloc[0]), 'phone_date_end': str(dates.iloc[-1]),
              'vehicle_day_seconds': stats(vc), 'vehicle_interval_seconds': stats(np.diff(vc)),
              'vehicle_nonpositive_intervals': int(np.sum(np.diff(vc) <= 0)),
              'vehicle_gap_over_0_2_seconds_count': int(np.sum(np.diff(vc) > .2 + 1e-9)),
              'vehicle_sample_period_seconds': stats(v['Sample period (seconds)']),
              'phone_minus_vehicle_day_start_seconds': float(sod[0] - vc[0]),
              'phone_minus_vehicle_day_end_seconds': float(sod[-1] - vc[-1]),
              'rows_equal': len(s) == len(v), 'physical_synchronization_certified': False}
    if len(s) == len(v):
        clocks['same_index_elapsed_disagreement_seconds'] = stats((sc-sc[0]) - (vc-vc[0]))
        clocks['same_index_phone_minus_vehicle_day_seconds'] = stats(sod - vc)
    speed = v['Indicated Vehicle Speed (km/hr)'].to_numpy(float)
    gps = v['Velocity (km/hr)'].to_numpy(float)
    gear = v['Gear (Number fof gear employed 1-5)']
    requested = v['Gear Requested (Number fof gear employed 1-5)']
    accel = [key for key in s if key.startswith('ACCELEROMETER')]
    gyro = [key for key in s if key.startswith('GYROSCOPE')]
    gravity = [key for key in s if key.startswith('GRAVITY')]
    result = {'phone_rows': len(s), 'vehicle_rows': len(v), 'phone_columns': list(s), 'vehicle_columns': list(v),
              'clocks': clocks, 'imu': {key: stats(s[key]) for key in accel+gyro},
              'gravity': {key: stats(s[key]) for key in gravity},
              'gravity_norm_mps2': stats(np.linalg.norm(s[gravity].to_numpy(float), axis=1)),
              'acceleration_norm_mps2': stats(np.linalg.norm(s[accel].to_numpy(float), axis=1)),
              'orientation_recorded_degrees': {key: stats(s[key]) for key in s if key.startswith('ORIENTATION')},
              'indicated_speed_documented_kmh': stats(speed),
              'indicated_speed_documented_kmh_divided_3_6': stats(speed/3.6),
              'vehicle_gps_velocity_documented_kmh': stats(gps),
              'indicated_minus_vehicle_gps_kmh': stats(speed-gps),
              'indicated_vs_vehicle_gps_mae_kmh_SOURCE_CONSISTENCY_ONLY': float(np.mean(np.abs(speed-gps))),
              'gear_actual_recorded_codes': counts(gear), 'gear_requested_recorded_codes': counts(requested),
              'gear_code_cross_tab': {f'{actual}|{asked}': int(n) for (actual, asked), n in v.groupby([gear.name, requested.name], dropna=False).size().items()},
              'requested_outside_documented_1_to_5_count': int((~requested.isin(range(1,6))).sum()),
              'gear_outside_documented_1_to_5_count': int((~gear.isin(range(1,6))).sum()),
              'gear_1_to_5_with_requested_outside_count': int((gear.isin(range(1,6)) & ~requested.isin(range(1,6))).sum()),
              'both_gears_1_to_5_count_NOT_APPROVED': int((gear.isin(range(1,6)) & requested.isin(range(1,6))).sum()),
              'zero_indicated_speed_rows': int(np.sum(speed == 0)),
              'longest_identical_moving_indicated_speed_run_rows': plateau(speed, speed > 1),
              'wheel_fields_author_documented_rad_s_VENDOR_UNIT_CONFLICT': {key: stats(v[key]) for key in v if key.startswith('Wheel Speed')},
              'vehicle_yaw_documented_deg_per_s': stats(v['Yaw Rate (deg/sec)']),
              'training_approval': 'NOT_APPROVED', 'axis_mapping_established': False}
    if len(s) == len(v):
        yaw = v['Yaw Rate (deg/sec)'].to_numpy(float) * np.pi/180
        result['same_index_gyro_vs_vehicle_yaw_correlation_NOT_AXIS_MAPPING'] = {
            key: float(np.corrcoef(s[key], yaw)[0,1])
            if np.isfinite(s[key]).all() and np.isfinite(yaw).all() and np.std(s[key]) > 1e-12 and np.std(yaw) > 1e-12 else None
            for key in gyro}
    return result


def read_csv(path):
    frame = pd.read_csv(path, encoding='latin1')
    frame.columns = frame.columns.str.strip()
    if frame.columns.duplicated().any() or frame.empty:
        raise ValueError(f'Empty CSV or duplicate headers: {path}')
    return frame


def fetch(url):
    command = ['curl', '-4', '--fail', '--location', '--connect-timeout', '10', '--max-time', '90',
               '--retry', '2', '--retry-delay', '1', '--silent', '--show-error', url]
    completed = subprocess.run(command, capture_output=True)
    if completed.returncode:
        raise RuntimeError(f'curl exit {completed.returncode}: {url}: {completed.stderr.decode(errors="replace")}')
    return completed.stdout, command


def run(root, logdir, max_workers, audit_only=False):
    if not 1 <= max_workers <= 4:
        raise ValueError('Download concurrency must be between 1 and 4')
    tree_path = root/'data/raw/iovnbd/metadata/tree_20260920.json'
    tree = json.loads(tree_path.read_text())
    if tree.get('sha') != COMMIT:
        raise ValueError('Official tree commit differs from pinned commit')
    pairs = discover_pairs(tree)
    logdir.mkdir(parents=True, exist_ok=True)
    raw = root/'data/raw/iovnbd/synchronized'
    meta = root/'data/raw/iovnbd/metadata/all_e_pairs_20260920'
    meta.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()

    def event(record):
        record = {'access_utc': datetime.now(timezone.utc).isoformat(), **record}
        with lock:
            with (logdir/'events.jsonl').open('a') as stream:
                stream.write(json.dumps(record)+'\n')
            print(json.dumps(record), flush=True)

    def acquire(entry, key):
        path, leaf = entry['path'], PurePosixPath(entry['path']).name
        version = 'categorised' if key.endswith('_categorized') else 'uncategorised'
        pointer_url = f'https://raw.githubusercontent.com/onyekpeu/IO-VNBD/{COMMIT}/'+quote(path)
        pointer_path = meta/f'{version}_{leaf}.lfs_pointer.txt'
        cache = [pointer_path, root/'data/raw/iovnbd/metadata/paired_reference_20260920'/pointer_path.name,
                 root/'data/raw/iovnbd_official/metadata/lfs_pointers'/path]
        pointer = None
        for candidate in cache:
            if candidate.exists():
                candidate_bytes = candidate.read_bytes()
                try:
                    parse_pointer(candidate_bytes, entry['sha'])
                except ValueError:
                    continue
                pointer = candidate_bytes
                break
        pointer_command = None
        if pointer is None:
            if audit_only:
                raise ValueError(f'No verified cached pointer: {path}')
            pointer, pointer_command = fetch(pointer_url)
        spec = parse_pointer(pointer, entry['sha'])
        if pointer_path.exists() and pointer_path.read_bytes() != pointer:
            raise ValueError(f'Existing pointer differs: {pointer_path}')
        if not pointer_path.exists():
            pointer_path.write_bytes(pointer)
        output = raw/('categorized_phone_variants' if key == 's_categorized' else '')/leaf
        if key == 'v_categorized':
            output = raw/'categorized_vehicle_variants'/leaf
        payload_url = f'https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/{COMMIT}/'+quote(path)
        command = None
        if output.exists():
            data = output.read_bytes()
            method = 'reuse_existing_verified_payload'
        elif key == 's_uncategorized':
            data = (root/'data/raw/iovnbd_official/synchronized'/leaf).read_bytes()
            method = 'verified_existing_phone_copy'
        else:
            if audit_only:
                raise ValueError(f'Missing original: {output}')
            data, command = fetch(payload_url)
            method = 'official_public_media_download'
        persist_verified(output, data, spec)
        record = {**spec, 'source_path': path, 'file': leaf, 'variant': key, 'repository_commit': COMMIT,
                  'pointer_url': pointer_url, 'pointer_command': pointer_command,
                  'payload_url': payload_url, 'payload_command': command, 'method': method,
                  'local_path': str(output.relative_to(root)), 'verification': 'PASS_SIZE_AND_LFS_SHA256'}
        event({'event': 'verified_original', **record})
        return record

    def acquire_pair(pair):
        records = {key: acquire(pair[key], key) for key in ('s_uncategorized', 'v_uncategorized', 's_categorized')}
        identical_v = pair['v_uncategorized']['sha'] == pair['v_categorized']['sha']
        if not identical_v:
            records['v_categorized'] = acquire(pair['v_categorized'], 'v_categorized')
        return {'drive': pair['drive'], 'sources': records, 'vehicle_variants_same_pointer_blob': identical_v,
                'categorized_vehicle_tree_entry': pair['v_categorized']}

    acquired, failures = [], []
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        jobs = {pool.submit(acquire_pair, pair): pair['drive'] for pair in pairs}
        for future in as_completed(jobs):
            try:
                acquired.append(future.result())
            except Exception as exc:
                failures.append({'drive': jobs[future], 'error': f'{type(exc).__name__}: {exc}'})
                event({'event': 'acquisition_failure', **failures[-1]})
    acquisition = {'repository_commit': COMMIT, 'tree_sha256': sha256(tree_path.read_bytes()).hexdigest(),
                   'max_concurrent_downloads': max_workers, 'expected_pair_count': len(pairs),
                   'verified_pair_count': len(acquired), 'pairs': sorted(acquired, key=lambda p: p['drive']),
                   'failures': failures, 'reserved_final_test_payloads_read': 0,
                   'command': sys.argv, 'audit_only': audit_only}
    (meta/'acquisition_manifest.json').write_text(json.dumps(acquisition, indent=2)+'\n')
    (logdir/'acquisition_manifest.json').write_text(json.dumps(acquisition, indent=2)+'\n')
    survey = {'audit_utc': datetime.now(timezone.utc).isoformat(), 'repository_commit': COMMIT,
              'mode': 'READ_ONLY_SOURCE_SURVEY_NOT_MODEL_EVALUATION', 'command': sys.argv,
              'python': platform.python_version(), 'numpy': np.__version__, 'pandas': pd.__version__,
              'final_test_data_read': False, 'training_runs': 0, 'source_payload_encoding': 'latin1',
              'pairs': {}, 'errors': [], 'acquisition_failures': failures}
    for pair in acquisition['pairs']:
        try:
            records = pair['sources']
            frames = {key: read_csv(root/r['local_path']) for key, r in records.items()}
            result = survey_frames(frames['s_uncategorized'], frames['v_uncategorized'])
            result['variant_comparison'] = compare_phone_variants(frames['s_uncategorized'], frames['s_categorized'])
            result['source_files'] = {key: {'path': r['local_path'], 'sha256': r['lfs_sha256'], 'bytes': r['expected_bytes']} for key, r in records.items()}
            result['vehicle_variants_same_pointer_blob'] = pair['vehicle_variants_same_pointer_blob']
            survey['pairs'][pair['drive']] = result
            event({'event': 'pair_surveyed', 'drive': pair['drive'], 'phone_rows': result['phone_rows'],
                   'vehicle_rows': result['vehicle_rows'], 'rows_equal': result['clocks']['rows_equal']})
        except Exception as exc:
            survey['errors'].append({'drive': pair['drive'], 'error': f'{type(exc).__name__}: {exc}'})
            event({'event': 'survey_failure', **survey['errors'][-1]})
    survey['exit_status'] = int(bool(failures or survey['errors'] or len(survey['pairs']) != 64))
    (logdir/'survey.json').write_text(json.dumps(survey, indent=2, allow_nan=False)+'\n')
    event({'event': 'completed', 'exit_status': survey['exit_status'], 'surveyed_pairs': len(survey['pairs']),
           'reserved_final_test_payloads_read': 0, 'training_runs': 0})
    return survey['exit_status']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--logdir', type=Path, default=Path('logs/native_20260920_01/all_e_pairs_20260920_01'))
    parser.add_argument('--max-workers', type=int, default=4)
    parser.add_argument('--audit-only', action='store_true')
    args = parser.parse_args()
    return run(args.root.resolve(), args.logdir.resolve(), args.max_workers, args.audit_only)


if __name__ == '__main__':
    raise SystemExit(main())
