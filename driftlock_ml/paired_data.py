"""Evidence-gated, causal paired-source preparation for native contract v2.

Proposal reads metadata only. Preparation needs a separate reviewed mapping;
neither operation approves training, reads final-test payloads, or trains a model.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from hashlib import sha256
import io
import json
from pathlib import Path
import re
import sys

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = 'driftlock-ml-2.0-causal-latest'
CHANNELS = ['ax', 'ay', 'az', 'gx', 'gy', 'gz']
RESERVED_GROUPS = ['review_A_S1_S2', 'review_A_S3abc', 'review_A_S4', 'review_B_M', 'review_D_Y1']
GROUP_SPLITS = {'review_E_Vta_Vtb': 'train', 'review_E_Vfa': 'val', 'review_E_Vw': 'calibration'}
UNRESOLVED = [
    'phone.raw_axes_evidence: exact raw Android XYZ correspondence and exporter settings',
    'phone.units_gravity_evidence: SI acceleration including gravity and gyro rad/s',
    'phone.sensor_timing_evidence: sensor sample times/ages, not only CSV row cadence',
    'sections[].phone_clock/reference_clock: exact origins, units and evidenced offsets',
    'sections[].sync: source-specific alignment procedure and residual bound in seconds',
    'reference.direction_evidence: signed source or explicitly reviewed forward intervals',
    'reference.validity_evidence: accuracy/freshness/outages and reviewed valid intervals',
    'original_group_evidence/independence_evidence: original trips, overlap and split independence',
]


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _hash(data):
    return sha256(data).hexdigest()


def _group(drive):
    if re.fullmatch(r'vt[ab]\d+[a-z]?', drive):
        return 'review_E_Vta_Vtb'
    if re.fullmatch(r'vfa\d+', drive):
        return 'review_E_Vfa'
    if re.fullmatch(r'vw\d+[a-z]?', drive):
        return 'review_E_Vw'
    raise ValueError(f'Only Driver E is allowed; reserved/unknown drive: {drive}')


def _inventory(path):
    payload = Path(path).read_bytes()
    inventory = json.loads(payload)
    _require(not inventory.get('failures'), 'Acquisition metadata contains failures')
    pairs = {}
    for pair in inventory['pairs']:
        name = pair['drive']
        _group(name)
        _require(name not in pairs, 'Duplicate pair in inventory')
        for key in ('s_uncategorized', 'v_uncategorized'):
            source = pair['sources'][key]
            _require(source.get('verification') == 'PASS_SIZE_AND_LFS_SHA256', 'Unverified source metadata')
            _require(re.fullmatch(r'[0-9a-f]{64}', source.get('lfs_sha256', '')), 'Invalid source SHA256')
            leaf = Path(source['local_path']).name
            _require(leaf.lower() == f'{key[0]}-{name}.csv', 'Source filename conflicts with Driver E pair identity')
        pairs[name] = pair
    _require(pairs, 'No paired source inventory')
    return pairs, _hash(payload), inventory.get('repository_commit')


def _base_manifest():
    return {'contract': CONTRACT, 'model_contract_version': CONTRACT,
            'prediction_timestamp': 'latest_sample', 'feature_channels': CHANNELS,
            'sample_hz': 10., 'window_samples': 20, 'time_base': 'seconds_since_boot',
            'data_time_base': 'seconds_since_recording_start', 'frame': 'phone',
            'acceleration_includes_gravity': True, 'acceleration_unit': 'm/s2',
            'gyro_unit': 'rad/s', 'speed_unit': 'm/s',
            'training_approved': False, 'runtime_packets_emitted': False,
            'reserved_final_test_groups': RESERVED_GROUPS,
            'reserved_final_test_payloads_read': 0, 'drives': []}


def _write_manifest(out, manifest):
    out.mkdir(parents=True, exist_ok=False)
    payload = (json.dumps(manifest, indent=2, allow_nan=False)+'\n').encode()
    (out/'manifest.json').write_bytes(payload)
    (out/'manifest.sha256').write_text(_hash(payload)+'\n')


def propose_dataset(inventory_path, out):
    """Write a non-trainable source-review proposal without opening any CSV."""
    pairs, digest, commit = _inventory(inventory_path)
    manifest = {**_base_manifest(), 'review_status': 'REQUIRES_SOURCE_REVIEW',
                'contract_metadata_role': 'desired_output_contract_not_verified_source_meaning',
                'source_inventory_sha256': digest, 'repository_commit': commit,
                'unresolved_fields': UNRESOLVED, 'source_payloads_read': 0,
                'proposed_drives': [
                    {'drive_id': name, 'group_id': _group(name), 'split': GROUP_SPLITS[_group(name)],
                     'independent_original_trip_verified': False,
                     'sources': {key: pair['sources'][key] for key in ('s_uncategorized', 'v_uncategorized')}}
                    for name, pair in sorted(pairs.items())]}
    _write_manifest(Path(out), manifest)
    return manifest


def _evidence_refs(value, evidence, label):
    _require(isinstance(value, list) and bool(value) and all(x in evidence for x in value),
             f'Missing or unknown {label}')


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and np.isfinite(value)


def _intervals(value, label, *, allow_empty=False):
    _require(isinstance(value, list) and (bool(value) or allow_empty), f'Missing reviewed {label}')
    end = 0
    for interval in value:
        _require(isinstance(interval, list) and len(interval) == 2
                 and all(type(x) is int for x in interval)
                 and 0 <= interval[0] < interval[1] and interval[0] >= end,
                 f'Invalid/overlapping/out-of-order reviewed {label}')
        end = interval[1]


def _validate_review(review, pairs, root):
    _require(review.get('schema') == 'driftlock-paired-source-review-v1'
             and review.get('contract') == CONTRACT, 'Wrong paired review schema/contract')
    _require(review.get('status') == 'SOURCE_MAPPING_REVIEWED'
             and bool(review.get('reviewer')) and bool(review.get('reviewed_at')),
             'Source mapping must be separately reviewed')
    evidence = review.get('evidence', {})
    _require(isinstance(evidence, dict) and evidence, 'Missing source review evidence')
    # Evidence must be documents, never a disguised final-test CSV read.
    for item in evidence.values():
        path = (root/item['path']).resolve()
        _require(path.is_relative_to(root/'docs') or path.is_relative_to(root/'reports'),
                 'Source review evidence must be a document under docs/ or reports/')
        _require(path.suffix.lower() in {'.md','.txt','.json','.pdf'}, 'Invalid evidence document type')
        _require(_hash(path.read_bytes()) == item.get('sha256'), 'Source review evidence hash mismatch')
    _evidence_refs(review.get('independence_evidence'), evidence, 'independence evidence')
    config = review.get('preprocessing', {})
    _require(config.get('method') == 'causal_hold_segment_start_v1'
             and config.get('sample_hz') == 10. and config.get('window_samples') == 20,
             'Unsupported v2 causal preprocessing contract')
    for key in ('max_imu_gap_s','max_imu_age_s'):
        _require(config.get(key) == .15, 'IMU gap/age must match the native .15 s causal policy')
    for key in ('max_reference_gap_s','max_reference_age_s'):
        _require(_finite(config.get(key)) and 0 < config[key] <= 1., 'Explicit bounded reference age/gap required')
    drives = review.get('drives', {})
    _require(isinstance(drives, dict) and drives, 'No reviewed source mappings')
    seen_trips, seen_hashes = {}, {}
    for name, mapping in drives.items():
        group = _group(name)
        _require(name in pairs, 'Reviewed pair missing from verified inventory')
        _require(mapping.get('group_id') == group, 'Original group/split assignment conflict')
        split = GROUP_SPLITS[group]
        trip = mapping.get('original_trip_id')
        _require(isinstance(trip, str) and bool(trip.strip()), 'Missing reviewed original trip')
        _require(trip not in seen_trips or seen_trips[trip] == split, 'Same original trip crosses split')
        seen_trips[trip] = split
        _evidence_refs(mapping.get('original_group_evidence'), evidence, 'original group evidence')
        for role, variant in [('phone','s_uncategorized'),('reference','v_uncategorized')]:
            digest = mapping.get(f'{role}_source_sha256')
            _require(digest == pairs[name]['sources'][variant]['lfs_sha256'], 'Reviewed source SHA256 mismatch')
            _require(digest not in seen_hashes or seen_hashes[digest] == split, 'Identical original source crosses split')
            seen_hashes[digest] = split
        phone, reference = mapping.get('phone', {}), mapping.get('reference', {})
        _require(phone.get('frame') == 'raw_phone_android_xyz', 'Raw phone XYZ frame must be reviewed')
        _require(phone.get('acceleration_includes_gravity') is True, 'Raw acceleration including gravity is required')
        _require(phone.get('acceleration_unit') == 'm/s2' and phone.get('gyro_unit') == 'rad/s', 'Phone SI units required')
        channels = phone.get('channels', {})
        _require(set(channels) == set(CHANNELS) and len(set(channels.values())) == 6
                 and all(isinstance(x, str) and x for x in channels.values()), 'Exactly six reviewed phone columns required')
        _require(not any(re.search(r'gps|gnss|speed|latitude|longitude|wheel|gear|heading|reference', x, re.I)
                         for x in channels.values()), 'GPS/reference columns are forbidden model features')
        _evidence_refs(phone.get('raw_axes_evidence'), evidence, 'raw axes evidence')
        _evidence_refs(phone.get('units_gravity_evidence'), evidence, 'units/gravity evidence')
        _evidence_refs(phone.get('sensor_timing_evidence'), evidence, 'sensor timing evidence')
        _require(reference.get('unit') in {'km/h','m/s'}, 'Unsupported or unknown reference unit')
        _evidence_refs(reference.get('unit_evidence'), evidence, 'reference unit evidence')
        _evidence_refs(reference.get('direction_evidence'), evidence, 'direction evidence')
        _evidence_refs(reference.get('validity_evidence'), evidence, 'reference validity evidence')
        semantics = reference.get('label_semantics')
        quantity = reference.get('quantity')
        _require(semantics in {'vehicle_forward_signed','verified_forward_only'}, 'Signed/verified-forward label semantics required')
        _require(quantity in {'signed_vehicle_forward_speed','vehicle_indicated_speed_magnitude'}, 'Unreviewed reference quantity')
        _require(quantity != 'vehicle_indicated_speed_magnitude' or semantics == 'verified_forward_only',
                 'Indicated magnitude cannot be relabelled signed vehicle velocity')
        _require(isinstance(reference.get('column'), str) and reference['column'], 'Exact reference column required')
        _intervals(reference.get('valid_rows'), 'reference valid intervals', allow_empty=True)
        if semantics == 'verified_forward_only':
            _intervals(reference.get('forward_rows'), 'forward intervals', allow_empty=True)
        sections = mapping.get('sections', [])
        _require(isinstance(sections, list) and sections, 'Missing source-specific clock evidence/sections')
        for stream in ('phone','reference'):
            _intervals([s[f'{stream}_rows'] for s in sections], f'{stream} sections')
        for section in sections:
            for stream in ('phone','reference'):
                clock = section.get(f'{stream}_clock', {})
                _evidence_refs(clock.get('evidence'), evidence, 'clock evidence')
                _require(clock.get('unit') in {'ms','s'} and bool(clock.get('column'))
                         and bool(clock.get('source_origin')) and _finite(clock.get('offset_to_recording_s')),
                         'Incomplete source-specific clock evidence/origin')
            sync = section.get('sync', {})
            _evidence_refs(sync.get('evidence'), evidence, 'clock evidence for residual synchronization')
            _require(bool(sync.get('method')) and _finite(sync.get('residual_bound_s'))
                     and 0 <= sync['residual_bound_s'] < config['max_reference_age_s'],
                     'Missing or excessive synchronization residual bound')
        _require(phone.get('encoding') in {'utf-8','latin1'} and reference.get('encoding') in {'utf-8','latin1'},
                 'Explicit supported source encodings required')


def _read_original(path, source, encoding):
    payload = path.read_bytes()
    _require(len(payload) == source['expected_bytes'] and _hash(payload) == source['lfs_sha256'],
             f'Original source size/SHA256 mismatch: {path.name}')
    prefix = payload[:1024].lstrip().lower()
    _require(not prefix.startswith(b'version https://git-lfs') and b'<html' not in prefix
             and not prefix.startswith(b'<!doctype'), 'Source is HTML or a Git LFS pointer')
    headers = next(csv.reader(io.StringIO(payload.decode(encoding))))
    _require(len(headers) == len(set(headers)), 'Duplicate original source headers')
    frame = pd.read_csv(io.BytesIO(payload), encoding=encoding, float_precision='round_trip')
    _require(list(frame) == headers and len(frame) > 0, 'Source headers changed or empty source')
    return frame


def _clock(frame, config, bounds, stream):
    first, end = bounds
    _require(end <= len(frame), f'{stream} reviewed section exceeds original rows')
    values = pd.to_numeric(frame[config['column']].iloc[first:end], errors='raise').to_numpy(float)
    times = values * (.001 if config['unit'] == 'ms' else 1.) + config['offset_to_recording_s']
    _require(np.isfinite(times).all() and np.all((times >= 0) & (times < 10_000_000)),
             f'{stream} clock must have reviewed finite recording-relative seconds')
    _require(np.all(np.diff(times) > 0), f'{stream} nonmonotonic clock inside reviewed section; never sort; review separate sections')
    return times


def _mask(rows, intervals, size):
    _require(all(end <= size for _,end in intervals), 'Reviewed validity/direction interval exceeds source')
    result = np.zeros(len(rows), dtype=bool)
    for first,end in intervals:
        result |= (rows >= first) & (rows < end)
    return result


def _canonical_frames(phone, reference, mapping, config):
    channels = [mapping['phone']['channels'][key] for key in CHANNELS]
    reference_spec = mapping['reference']
    imu = phone[channels].apply(pd.to_numeric, errors='raise').to_numpy(dtype=np.float32)
    speed = pd.to_numeric(reference[reference_spec['column']], errors='raise').to_numpy(float)
    if reference_spec['unit'] == 'km/h':
        speed = speed/3.6
    valid = _mask(np.arange(len(reference)), reference_spec['valid_rows'], len(reference))
    forward = (_mask(np.arange(len(reference)), reference_spec['forward_rows'], len(reference))
               if reference_spec['label_semantics'] == 'verified_forward_only' else np.ones(len(reference), bool))
    _require(not np.any(valid & forward & (speed < 0)) or reference_spec['label_semantics'] == 'vehicle_forward_signed',
             'Reviewed forward-only interval contains negative reference values')
    segments, events, rows = [], [], []
    def flush():
        if rows:
            segments.append(pd.DataFrame(rows)); rows.clear()
    for section_id, section in enumerate(mapping['sections']):
        flush()
        st = _clock(phone, section['phone_clock'], section['phone_rows'], 'phone')
        rt = _clock(reference, section['reference_clock'], section['reference_rows'], 'reference')
        # Faster source streams need a reviewed anti-aliasing design; this path has none.
        if len(st) > 1:
            _require(np.median(np.diff(st)) >= .075, 'Higher-rate phone source requires reviewed anti-alias preprocessing')
        for i in np.flatnonzero(np.diff(rt) > config['max_reference_gap_s'] + 1e-8):
            events.append({'reason':'reference_gap','source_row':int(section['reference_rows'][0]+i+1)})
        residual = section['sync']['residual_bound_s']
        previous = None
        origin = None
        tick_index = 0
        for local, t in enumerate(st):
            source_row = section['phone_rows'][0]+local
            if not np.isfinite(imu[source_row]).all():
                flush(); previous=None
                events.append({'reason':'nonfinite_phone_imu','source_row':source_row})
                continue
            if previous is not None and t-previous[0] > config['max_imu_gap_s'] + 1e-8:
                flush(); previous=None
                events.append({'reason':'phone_gap','source_row':source_row})
            if previous is None:
                origin=float(t); tick_index=0
            tick=origin+tick_index/config['sample_hz']
            while tick <= t:
                feature_row=source_row if tick == t else previous[1] if previous is not None else None
                feature_t=float(t if feature_row==source_row else previous[0]) if feature_row is not None else None
                if feature_row is None or tick-feature_t > config['max_imu_age_s'] + 1e-8:
                    flush(); events.append({'reason':'stale_phone_imu','target_t':float(tick)})
                else:
                    # Use a reference only when its latest possible time is in the past.
                    rlocal=int(np.searchsorted(rt+residual,tick,side='right')-1)
                    rrow=section['reference_rows'][0]+rlocal if rlocal>=0 else -1
                    rtime=float(rt[rlocal]) if rlocal>=0 else None
                    reason='valid'
                    if rlocal<0: reason='no_past_reference'
                    elif tick-(rtime-residual) > min(config['max_reference_age_s'], config['max_reference_gap_s']) + 1e-8: reason='reference_stale'
                    elif not valid[rrow]: reason='reference_invalid'
                    elif not forward[rrow]: reason='direction_unverified'
                    elif not np.isfinite(speed[rrow]): reason='reference_nonfinite'
                    row={'timestamp_s':float(tick), **dict(zip(CHANNELS,imu[feature_row].tolist())),
                         'speed_mps':float(speed[rrow]) if reason=='valid' else np.nan,
                         'label_valid':int(reason=='valid'), 'label_reason':reason,
                         'feature_source_time_s':feature_t,'phone_source_row':int(feature_row),
                         'reference_time_s':rtime,'reference_source_row':int(rrow),
                         'available_t':float(t), 'reviewed_section':section_id}
                    rows.append(row)
                tick_index+=1; tick=origin+tick_index/config['sample_hz']
            previous=(float(t),source_row)
        flush()
    audit={'events':events, 'phone_rows':len(phone),'reference_rows':len(reference),
           'segments':len(segments),'canonical_rows':sum(len(s) for s in segments),
           'label_reasons':dict(Counter(reason for segment in segments for reason in segment.label_reason)),
           'feature_channels':CHANNELS,'future_feature_rows_used':0,'source_order_changed':False,
           'clock_offsets_inferred':False,'training_approved':False}
    return segments,audit


def canonicalize_pair(phone_path, reference_path, review, drive_id, *, root=ROOT):
    """Canonicalize one reviewed source pair; output remains unapproved staging.

    Review carries an ``inventory_pair`` for this direct API. The dataset API
    instead binds all selected pairs to the supplied official acquisition file.
    """
    pair=review.get('inventory_pair')
    _require(isinstance(pair,dict) and pair.get('drive')==drive_id,'Direct pair API requires verified inventory_pair metadata')
    pairs={drive_id:pair};root=Path(root).resolve()
    _validate_review(review,pairs,root)
    paths=_source_paths([phone_path,reference_path],drive_id,root)
    return _read_and_canonicalize(*paths,review['drives'][drive_id],
                                  pair,review['preprocessing'])


def _source_paths(paths,drive_id,root):
    resolved=[Path(p).resolve() for p in paths]
    _require(all(p.is_relative_to(root/'data/raw') and p.name.lower()==f'{prefix}-{drive_id}.csv'
                 for prefix,p in zip(('s','v'),resolved)),
             'Source path identity conflicts with selected Driver E; reserved paths are forbidden')
    return resolved


def _read_and_canonicalize(phone_path,reference_path,mapping,pair,config):
    phone=_read_original(phone_path,pair['sources']['s_uncategorized'],mapping['phone']['encoding'])
    reference=_read_original(reference_path,pair['sources']['v_uncategorized'],mapping['reference']['encoding'])
    return _canonical_frames(phone,reference,mapping,config)


def audit_pair(phone_path,reference_path,review,drive_id,*,root=ROOT):
    return canonicalize_pair(phone_path,reference_path,review,drive_id,root=root)[1]


def causal_windows(frame, window_samples=20):
    """Yield six-feature latest-target windows from a single canonical segment."""
    _require(type(window_samples) is int and window_samples>=2,'Invalid window size')
    times=frame.timestamp_s.to_numpy(float)
    _require(np.isfinite(times).all() and np.all(np.diff(times)>0),'Canonical segment clock is not monotonic')
    _require(np.allclose(np.diff(times),.1,rtol=0,atol=1e-8),'Canonical segment must be contiguous 10 Hz')
    features=frame[CHANNELS].to_numpy(np.float32)
    _require(np.isfinite(features).all(),'Nonfinite canonical IMU')
    _require(np.all(frame.feature_source_time_s.to_numpy(float)<=times),'Future IMU source in canonical frame')
    _require(np.isin(frame.label_valid,[0,1]).all(),'Canonical label validity must be binary')
    reference_times=frame.reference_time_s.to_numpy(float)
    valid=frame.label_valid.to_numpy()==1
    _require(np.all(np.isfinite(reference_times[valid])) and np.all(reference_times[valid]<=times[valid]),
             'Invalid or future reference in canonical frame')
    for end in range(window_samples-1,len(frame)):
        if frame.label_valid.iloc[end] != 1 or not np.isfinite(frame.speed_mps.iloc[end]):
            continue
        start=end-window_samples+1
        available=float(frame.available_t.iloc[start:end+1].max())
        _require(available>=times[end],'Window made available before target')
        yield {'imu':features[start:end+1].copy(),'timestamps_s':times[start:end+1].copy(),
               'target_t':float(times[end]),'available_t':available,
               'speed_mps':float(frame.speed_mps.iloc[end]),
               'phone_source_rows':frame.phone_source_row.iloc[start:end+1].to_numpy(np.int64),
               'reference_source_row':int(frame.reference_source_row.iloc[end])}


def prepare_dataset(inventory_path,review_path,out,*,root=ROOT):
    """Verify all selected mappings before reading any source or writing output."""
    root,out=Path(root).resolve(),Path(out)
    _require(not out.exists(),'Use a fresh preparation output directory')
    pairs,inventory_hash,commit=_inventory(inventory_path)
    review_payload=Path(review_path).read_bytes(); review=json.loads(review_payload)
    _validate_review(review,pairs,root)
    manifest={**_base_manifest(),'review_status':'PREPARED_REQUIRES_TRAINING_REVIEW',
              'source_inventory_sha256':inventory_hash,'repository_commit':commit,
              'source_review_sha256':_hash(review_payload),'source_review_evidence':review['evidence'],
              'source_mappings':review['drives'],'independence_evidence':review['independence_evidence'],
              'evidence_path_base':'project_root',
              'preprocessing':review['preprocessing'],'audits':{},'source_payloads_read':0}
    outputs=[]
    for name,mapping in sorted(review['drives'].items()):
        pair=pairs[name]
        paths=_source_paths([root/pair['sources'][key]['local_path'] for key in ('s_uncategorized','v_uncategorized')],name,root)
        segments,audit=_read_and_canonicalize(*paths,mapping,pair,review['preprocessing'])
        manifest['audits'][name]=audit; manifest['source_payloads_read']+=2
        for i,frame in enumerate(segments):
            filename=f'{name}_segment_{i:04d}.csv'
            payload=frame.to_csv(index=False).encode()
            outputs.append((filename,payload))
            manifest['drives'].append({'drive_id':name,'path':filename,'group_id':mapping['group_id'],
                'original_trip_id':mapping['original_trip_id'],'split':GROUP_SPLITS[mapping['group_id']],
                'label_semantics':mapping['reference']['label_semantics'],
                'label_semantics_evidence':mapping['reference']['direction_evidence'],
                'time_base_evidence':mapping['sections'],
                'label_source':mapping['reference']['quantity'],'data_time_base':'seconds_since_recording_start',
                'phone_source_sha256':mapping['phone_source_sha256'],'reference_source_sha256':mapping['reference_source_sha256'],
                'processed_sha256':_hash(payload),'rows':len(frame),
                'eligible_software_windows':sum(1 for _ in causal_windows(frame,20)),
                'source_review_evidence':mapping['original_group_evidence']})
    _write_manifest(out,manifest)
    for filename,payload in outputs:
        (out/filename).write_bytes(payload)
    return manifest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['propose','prepare'])
    parser.add_argument('--inventory',type=Path,required=True)
    parser.add_argument('--review',type=Path)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--root',type=Path,default=ROOT)
    args=parser.parse_args()
    if args.mode=='propose':
        result=propose_dataset(args.inventory,args.out)
    else:
        if args.review is None:
            parser.error('prepare requires a separately reviewed --review file')
        result=prepare_dataset(args.inventory,args.review,args.out,root=args.root)
    print(json.dumps({'review_status':result['review_status'],'canonical_segments':len(result['drives']),
                      'training_approved':False,'reserved_final_test_payloads_read':0}))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
