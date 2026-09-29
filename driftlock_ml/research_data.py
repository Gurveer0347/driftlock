"""Isolated recorded-reference research data; never production speed metadata.

Inputs retain six exported numeric IMU channels. Targets retain recorded GPS
numbers. Header units, source frame, direction and GPS freshness remain unverified.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path

import numpy as np
import pandas as pd

from .data import CHANNELS, Windows

ROOT = Path(__file__).resolve().parents[1]
RESEARCH_CONTRACT = 'driftlock-iovnbd-recorded-reference-v1'
RESEARCH_STATUS = 'EXPLORATORY_UNVERIFIED_RECORDED_VALUES'
GROUP_ASSIGNMENTS = {
    'review_A_S1_S2': 'test', 'review_A_S3abc': 'test', 'review_A_S4': 'test',
    'review_B_M': 'test', 'review_D_Y1': 'test', 'review_E_Vta_Vtb': 'train',
    'review_E_Vfa': 'val', 'review_E_Vw': 'calibration',
}
SOURCE_HEADERS = ['TIME SINCE START (ms)', 'ACCELEROMETER X (m/s²)',
                  'ACCELEROMETER Y (m/s²)', 'ACCELEROMETER Z (m/s²)',
                  'GYROSCOPE X (rad/s)', 'GYROSCOPE Y (rad/s)',
                  'GYROSCOPE Z (rad/s)', 'GPS SPEED (Kmh)']
PROCESSED_COLUMNS = ['timestamp_s', *CHANNELS, 'reference_value', 'reference_present']
GROUP_DOCUMENT = 'reports/synchronized_group_review.json'
AUDIT_DOCUMENT = 'reports/synchronized_review/full_file_audit.json'
AUTHORIZATION_DOCUMENT = 'logs/exploratory_20260910_01/authorization.json'


def _sha(payload):
    return hashlib.sha256(payload).hexdigest()


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _write_json(path, value):
    with path.open('x') as handle:
        json.dump(value, handle, indent=2, allow_nan=False)
        handle.write('\n')


def _clock(values):
    times = np.asarray(values, dtype=np.float64)
    _require(len(times) >= 2 and np.isfinite(times).all() and np.all(np.diff(times) > 0),
             'Source clock must be finite and strictly increasing; never sort or repair clocks.')
    _require(np.all((times >= 0) & (times < 10_000_000)), 'Source elapsed clock must be nonnegative seconds below ten million.')
    return times


def _read_source(path, expected):
    payload = path.read_bytes()
    _require(_sha(payload) == expected['sha256'] and len(payload) == expected['bytes'],
             f'Source SHA256/size mismatch: {path.name}')
    header = next(csv.reader(io.StringIO(payload.decode('latin1'))))
    stripped = [value.strip() for value in header]
    _require(len(set(stripped)) == len(stripped), f'Duplicate source column after trimming: {path.name}')
    _require(set(SOURCE_HEADERS).issubset(stripped), f'Exact required source header/column mismatch: {path.name}')
    source = pd.read_csv(io.BytesIO(payload), encoding='latin1', float_precision='round_trip')
    source.columns = stripped
    _require(len(source) == expected['rows'], f'Source row count disagrees with previous audit: {path.name}')
    numeric = source[SOURCE_HEADERS].apply(pd.to_numeric, errors='raise')
    times = _clock(numeric.iloc[:, 0].to_numpy(dtype=np.float64) / 1000.)
    frame = pd.DataFrame(numeric.iloc[:, 1:7].to_numpy(), columns=CHANNELS)
    frame.insert(0, 'timestamp_s', times)
    frame['reference_value'] = numeric.iloc[:, 7].to_numpy()
    frame['reference_present'] = np.isfinite(frame.reference_value).astype(np.int8)
    return frame, header


def prepare_recorded_dataset(out: Path) -> dict:
    """Convert fixed non-test groups, preserving complete rows and source hashes.

    Selection uses previously saved audit metadata only. Reserved test CSVs and
    known bad-clock CSVs are not opened. A fresh output directory is mandatory.
    """
    out = Path(out).resolve()
    if out.exists():
        raise FileExistsError('Use a new recorded-reference dataset directory.')
    documents = {}
    decoded = {}
    for path in (GROUP_DOCUMENT, AUDIT_DOCUMENT, AUTHORIZATION_DOCUMENT):
        payload = (ROOT / path).read_bytes()
        documents[path] = {'bytes': len(payload), 'sha256': _sha(payload)}
        decoded[path] = json.loads(payload)
    review, audit = decoded[GROUP_DOCUMENT], decoded[AUDIT_DOCUMENT]
    _require(not audit['errors'], 'Prior source audit contains errors.')
    files = {item['name']: item for item in audit['files']}
    records = {item['filename']: item for item in review['records']}
    _require(len(files) == len(audit['files']) and len(records) == len(review['records'])
             and set(files) == set(records), 'Source group/audit inventory mismatch or duplicate filenames.')
    membership = {}
    _require({g['proposed_group_id'] for g in review['groups']} == set(GROUP_ASSIGNMENTS), 'Unexpected original source groups.')
    for group in review['groups']:
        for name in group['filenames']:
            _require(name not in membership, f'Overlapping original group membership: {name}')
            membership[name] = group['proposed_group_id']
    _require(set(membership) == set(records), 'Group membership inventory mismatch.')
    manifest = {
        'research_contract': RESEARCH_CONTRACT,
        'source_type': 'recorded_reference_research', 'review_status': RESEARCH_STATUS,
        'created_utc': datetime.now(timezone.utc).isoformat(),
        'data_time_base': 'seconds_since_recording_start', 'timestamp_origin_changed': False,
        'input_channels': CHANNELS, 'processed_schema': PROCESSED_COLUMNS,
        'target_semantics': 'unchanged_recorded_GPS_SPEED_numeric_value',
        'target_units': 'recorded_reference_units', 'valid_for_navigation': False,
        'source_header_claims': SOURCE_HEADERS,
        'unresolved': ['physical units', 'raw phone axis/frame convention', 'GPS fix freshness',
                       'vehicle-forward sign', 'independent original-trip boundaries'],
        'reference_present_meaning': 'Finite recorded numeric target only; not verified GPS validity or freshness.',
        'reference_lookup': 'Latest past source-row target at window centre; no interpolation or fresh-fix assertion.',
        'gap_policy': 'Past-sample age at most0.15s; windows also cannot span original source gaps over0.15s (1e-8s comparison tolerance).',
        'source_documents': documents,
        'source_group_review_scope': review.get('scope'),
        'group_assignments': dict(GROUP_ASSIGNMENTS),
        'group_policy': 'Fixed conservative review bins; not verified device, route or physical-trip holdout.',
        'reserved_test_files': [], 'excluded_files': [], 'drives': [],
    }
    out.mkdir(parents=True, exist_ok=False)
    for name in sorted(records):
        record, old = records[name], files[name]
        group = membership[name]
        _require(record['proposed_group_id'] == group, f'Conflicting source group: {name}')
        evidence = record['csv_audit_evidence']
        _require(evidence['sha256'] == old['sha256'] and evidence['path'] == old['path'],
                 f'Group/audit source hash or path mismatch: {name}')
        split = GROUP_ASSIGNMENTS[group]
        details = {'source_file': name, 'source_path': old['path'], 'source_sha256': old['sha256'],
                   'group_id': group, 'split': split, 'source_rows': old['rows']}
        if split == 'test':
            manifest['reserved_test_files'].append({**details, 'payload_accessed': False,
                                                    'reason': 'Reserved final test; previously inspected historically, not opened in this preparation.'})
            continue
        defects = {key: old[key] for key in ('nonfinite_timestamps', 'duplicate_timestamp_pairs', 'backward_timestamp_pairs') if old[key]}
        if defects:
            manifest['excluded_files'].append({**details, 'reason': 'Entire file excluded for prior row-clock failure.',
                                               'clock_defects': defects, 'payload_accessed': False})
            continue
        source = (ROOT / old['path']).resolve()
        _require(source.parent == (ROOT/'data/raw/iovnbd_official/synchronized').resolve()
                 and source.name == name, 'Selected source must be the audited synchronized original path.')
        frame, headers = _read_source(source, old)
        destination = out/name
        with destination.open('x') as handle:
            frame.to_csv(handle, index=False)
        _require(_sha(source.read_bytes()) == old['sha256'], f'Source hash changed during conversion: {name}')
        manifest['drives'].append({**details, 'path': name, 'rows': len(frame),
                                   'processed_sha256': _sha(destination.read_bytes()),
                                   'source_hash_verified_before_and_after': True,
                                   'source_headers_latin1': headers,
                                   'reference_present_rows': int(frame.reference_present.sum())})
    target = out/'manifest.json'
    _write_json(target, manifest)
    with target.with_suffix('.sha256').open('x') as handle:
        handle.write(_sha(target.read_bytes())+'\n')
    return manifest


def _manifest(path):
    payload = path.read_bytes()
    _require(_sha(payload) == path.with_suffix('.sha256').read_text().strip(), 'Research manifest SHA256 mismatch.')
    manifest = json.loads(payload)
    _require(manifest.get('research_contract') == RESEARCH_CONTRACT
             and manifest.get('source_type') == 'recorded_reference_research'
             and manifest.get('review_status') == RESEARCH_STATUS
             and manifest.get('valid_for_navigation') is False,
             'Wrong research manifest type/contract; production approval is never accepted here.')
    _require(manifest.get('data_time_base') == 'seconds_since_recording_start'
             and manifest.get('target_semantics') == 'unchanged_recorded_GPS_SPEED_numeric_value'
             and manifest.get('input_channels') == CHANNELS
             and manifest.get('processed_schema') == PROCESSED_COLUMNS,
             'Research schema or source clock/target contract mismatch.')
    _require(manifest.get('group_assignments') == GROUP_ASSIGNMENTS, 'Frozen group split assignments changed.')
    names, paths, hash_splits = set(), set(), {}
    for entry in [*manifest['drives'], *manifest['excluded_files'], *manifest['reserved_test_files']]:
        group, split, name = entry['group_id'], entry['split'], entry['source_file']
        _require(group in GROUP_ASSIGNMENTS and split == GROUP_ASSIGNMENTS[group], 'Source group/split conflict.')
        _require(name not in names and Path(name).name == name and name.endswith('.csv'), 'Duplicate or invalid source filename.')
        names.add(name)
        for key in ('source_sha256', 'processed_sha256'):
            if key not in entry:
                continue
            digest = entry[key]
            _require(isinstance(digest, str) and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest), 'Invalid source/processed SHA256.')
            _require(digest not in hash_splits or hash_splits[digest] == split, 'Identical source/processed hash crosses group splits.')
            hash_splits[digest] = split
    for entry in manifest['drives']:
        _require(entry['split'] != 'test', 'Reserved test cannot enter processed drives.')
        resolved = (path.parent/entry['path']).resolve()
        _require(resolved.is_relative_to(path.parent) and resolved not in paths, 'Duplicate or escaping processed path.')
        _require('processed_sha256' in entry, 'Missing processed SHA256.')
        paths.add(resolved)
    _require(all(e['split'] == 'test' for e in manifest['reserved_test_files']), 'Reserved test inventory contains another split.')
    return manifest, _sha(payload)


def _load_processed(path, entry, window):
    payload = path.read_bytes()
    _require(_sha(payload) == entry['processed_sha256'], f'Processed SHA256 mismatch: {entry["source_file"]}')
    frame = pd.read_csv(io.BytesIO(payload), float_precision='round_trip')
    _require(list(frame.columns) == PROCESSED_COLUMNS and len(frame) == entry['rows'], 'Processed schema/row count mismatch.')
    frame = frame.apply(pd.to_numeric, errors='raise')
    t = _clock(frame.timestamp_s)
    _require(len(t) >= window, 'Recorded drive is shorter than one window.')
    median_dt = float(np.median(np.diff(t)))
    _require(.075 <= median_dt <= .15, 'Recorded sample rate is incompatible with fixed 10Hz past-sample grid.')
    x = frame[CHANNELS].to_numpy(dtype=np.float32)
    raw_y = frame.reference_value.to_numpy(dtype=np.float32)
    present = frame.reference_present.to_numpy()
    _require(np.array_equal(present, np.isfinite(frame.reference_value).astype(np.int8)),
             'reference_present must mean finite recorded number, not invented validity.')
    grid = t[0]+np.arange(int(np.ceil((t[-1]-t[0])*10))+1, dtype=np.float64)/10
    grid = grid[grid <= t[-1]]
    past = np.searchsorted(t, grid, side='right')-1
    age = grid-t[past]
    usable = np.isfinite(x[past]).all(axis=1) & (age >= 0) & (age <= .15)
    # A missing tick can fall wholly between grid ticks while every selected
    # sample is still young enough. Preserve this original-clock discontinuity.
    source_segments = np.r_[0, np.cumsum(np.diff(t) > .15+1e-8)][past]
    targets = np.full(len(grid), np.nan)
    count = max(0, len(grid)-window+1)
    targets[window-1:] = (grid[:count]+grid[window-1:])/2
    reference_index = np.searchsorted(t, targets[window-1:], side='right')-1
    reference_age = targets[window-1:]-t[reference_index]
    y = np.full(len(grid), np.nan, dtype=np.float32)
    y[window-1:] = raw_y[reference_index]
    # This is source-row recency, not GPS fix freshness or scientific validity.
    reference_present = np.zeros(len(grid), dtype=bool)
    reference_present[window-1:] = (present[reference_index] == 1) & np.isfinite(raw_y[reference_index]) & (reference_age >= 0) & (reference_age <= .15)
    return {'x': x[past], 'y': y, 't': grid, 'target_t': targets, 'imu_valid': usable,
            'source_segment': source_segments,
            'reference_present': reference_present, 'name': Path(entry['source_file']).stem,
            'entry': entry, 'data_time_base': 'seconds_since_recording_start'}


class RecordedWindows(Windows):
    """Research-only windows; inherit tensor extraction and training normalization."""
    def __init__(self, manifest_path, config, split, stride=1, include_files=None):
        _require(split in {'train', 'val', 'calibration'}, 'Reserved test split is unavailable in this research run.')
        _require(float(config.get('sample_hz', 0)) == 10. and config.get('window_samples') in {20, 40},
                 'Research architecture requires 10Hz and an approved 20/40 sample window.')
        _require(isinstance(stride, (int, np.integer)) and not isinstance(stride, bool) and stride > 0, 'Window stride must be a positive integer.')
        self.manifest_path = Path(manifest_path).resolve()
        self.manifest, self.manifest_sha256 = _manifest(self.manifest_path)
        self.config, self.split, self.window = config, split, int(config['window_samples'])
        entries = [entry for entry in self.manifest['drives'] if entry['split'] == split]
        if include_files is not None:
            _require(not isinstance(include_files, str), 'include_files must contain complete filenames.')
            selected = set(include_files)
            _require(selected and selected.issubset({entry['source_file'] for entry in entries}), 'Probe filenames must exactly match complete files in the requested split.')
            entries = [entry for entry in entries if entry['source_file'] in selected]
        self.drives, indices = [], []
        for entry in entries:
            drive = _load_processed(self.manifest_path.parent/entry['path'], entry, self.window)
            d = len(self.drives)
            self.drives.append(drive)
            bad = np.r_[0, np.cumsum(~drive['imu_valid'])]
            for end in range(self.window-1, len(drive['x']), stride):
                start = end-self.window+1
                if (bad[end+1] == bad[start] and drive['reference_present'][end]
                        and drive['source_segment'][start] == drive['source_segment'][end]):
                    indices.append((d, end))
        _require(bool(indices), f'No usable recorded {split} windows; inspect source rows and gaps.')
        self.index = np.asarray(indices, dtype=np.int64)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    manifest = prepare_recorded_dataset(args.out)
    print(json.dumps({'research_contract': RESEARCH_CONTRACT, 'prepared_files': len(manifest['drives']),
                      'excluded_files': len(manifest['excluded_files']),
                      'reserved_test_files': len(manifest['reserved_test_files']), 'valid_for_navigation': False}))


if __name__ == '__main__':
    main()
