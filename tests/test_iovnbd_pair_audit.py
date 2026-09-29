"""Synthetic integrity and selection fixtures; never open reserved recordings."""
import hashlib

import numpy as np
import pandas as pd
import pytest

from tools.iovnbd_pair_audit import (
    compare_phone_variants, discover_pairs, parse_pointer, persist_verified,
    survey_frames,
)


BASE = 'Synchronised V abd S datasets/'


def entry(path):
    return {'path': BASE + path, 'type': 'blob', 'sha': 'a' * 40}


def fixture_tree():
    return {'truncated': False, 'tree': [
        entry('Categorised IOVNB Dataset/Vta (Driver E)/Vta02/S-Vta2.csv'),
        entry('Categorised IOVNB Dataset/Vta (Driver E)/Vta02/V-vta2.csv'),
        entry('Uncategorised IOVNB Dataset/S-Dataset/S-Vta2.csv'),
        entry('Uncategorised IOVNB Dataset/V-Dataset/V-vta2.csv'),
        entry('Categorised IOVNB Dataset/S1 (Driver A)/S-S1.csv'),
        entry('Uncategorised IOVNB Dataset/V-Dataset/V-S1.csv'),
        entry('Uncategorised IOVNB Dataset/S-Dataset/S-S1.csv'),
    ]}


def test_inventory_only_complete_driver_e_pairs_preserves_exact_case():
    pairs = discover_pairs(fixture_tree(), expected_count=1)
    assert len(pairs) == 1
    assert pairs[0]['drive'] == 'vta2'
    assert pairs[0]['v_uncategorized']['path'].endswith('V-vta2.csv')
    assert all('S1' not in e['path'] for e in pairs[0].values() if isinstance(e, dict))


def test_incomplete_or_truncated_tree_cannot_claim_full_inventory():
    tree = fixture_tree()
    tree['truncated'] = True
    with pytest.raises(ValueError, match='truncated'):
        discover_pairs(tree, expected_count=1)
    tree['truncated'] = False
    tree['tree'].pop(3)
    with pytest.raises(ValueError, match='incomplete'):
        discover_pairs(tree, expected_count=1)


def test_fragment_suffixes_are_retained_for_winding_family():
    tree = fixture_tree()
    tree['tree'] = [{**e, 'path': e['path'].replace('Vta (Driver E)/Vta02', 'Vw (Driver E)/Vw14a')
                    .replace('Vta2', 'Vw14a').replace('vta2', 'vw14a')} for e in tree['tree']]
    assert discover_pairs(tree, expected_count=1)[0]['drive'] == 'vw14a'


def test_pointer_blob_and_payload_must_both_match_and_never_overwrite(tmp_path):
    payload = b'time,value\n0,1\n'
    oid = hashlib.sha256(payload).hexdigest()
    pointer = f'version https://git-lfs.github.com/spec/v1\noid sha256:{oid}\nsize {len(payload)}\n'.encode()
    blob = hashlib.sha1(b'blob ' + str(len(pointer)).encode() + b'\0' + pointer).hexdigest()
    spec = parse_pointer(pointer, blob)
    path = tmp_path / 'original.csv'
    persist_verified(path, payload, spec)
    assert path.read_bytes() == payload
    with pytest.raises(ValueError, match='payload'):
        persist_verified(path, b'corrupt', spec)
    assert path.read_bytes() == payload
    with pytest.raises(ValueError, match='blob'):
        parse_pointer(pointer, '0' * 40)
    with pytest.raises(ValueError, match='pointer'):
        parse_pointer(b'<html>error</html>', None)
    path.write_bytes(b'existing different original')
    with pytest.raises(ValueError, match='Existing'):
        persist_verified(path, payload, spec)
    assert path.read_bytes() == b'existing different original'


def test_phone_header_alias_equality_does_not_establish_axis_mapping():
    a = pd.DataFrame({'GYROSCOPE X (rad/s)': [0., .1], 'DATE': ['a', 'b']})
    b = pd.DataFrame({'GYROSCOPE Yaw (rad/s)': [0., .1], 'DATE': ['a', 'b']})
    result = compare_phone_variants(a, b)
    assert result['all_values_equal_at_float32_precision'] is True
    assert result['headers_equal'] is False
    assert result['axis_mapping_established'] is False


def frames():
    n = 3
    s = pd.DataFrame({'TIME SINCE START (ms)': [1000, 1100, 1000],
                      'DATE': ['2020-01-01 01:00:00:000', '2020-01-01 01:00:00:100', '2020-01-01 01:00:00:000']})
    for prefix in ['ACCELEROMETER', 'GYROSCOPE', 'GRAVITY']:
        for axis in 'XYZ':
            s[f'{prefix} {axis} (unit)'] = np.zeros(n)
    v = pd.DataFrame({'Time Since Start of Day (seconds)': [3599., 3599.1],
                      'Sample period (seconds)': [.1, .1],
                      'Indicated Vehicle Speed (km/hr)': [36., 36.],
                      'Velocity (km/hr)': [35., 35.],
                      'Gear (Number fof gear employed 1-5)': [3, 3],
                      'Gear Requested (Number fof gear employed 1-5)': [14, 6],
                      'Yaw Rate (deg/sec)': [0., 0.]})
    return s, v


def test_audit_retains_clock_defects_unequal_rows_and_ambiguous_gear_codes():
    s, v = frames()
    result = survey_frames(s, v)
    assert result['phone_rows'] == 3 and result['vehicle_rows'] == 2
    assert result['clocks']['phone_nonpositive_intervals'] == 1
    assert result['clocks']['rows_equal'] is False
    assert 'same_index_elapsed_disagreement_seconds' not in result['clocks']
    assert result['requested_outside_documented_1_to_5_count'] == 2
    assert result['gear_1_to_5_with_requested_outside_count'] == 2
    assert result['training_approval'] == 'NOT_APPROVED'
    assert result['indicated_speed_documented_kmh_divided_3_6']['median'] == 10.
