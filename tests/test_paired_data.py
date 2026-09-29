"""Software checks using synthetic fixtures only; no real or final-test data."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from driftlock_ml.paired_data import (
    CONTRACT, CHANNELS, canonicalize_pair, causal_windows, prepare_dataset,
    propose_dataset,
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixture(tmp_path, *, phone_t=None, reference_t=None, speeds=None, valid_rows=None):
    phone_t = np.asarray(phone_t if phone_t is not None else np.arange(30)/10.)
    reference_t = np.asarray(reference_t if reference_t is not None else np.arange(30)/10.)
    n, m = len(phone_t), len(reference_t)
    raw = tmp_path/'data/raw/synthetic'
    raw.mkdir(parents=True)
    phone = pd.DataFrame({'phone_ms': phone_t*1000})
    for i, channel in enumerate(CHANNELS):
        phone[channel] = np.arange(n)/100 + (9.80665 if channel == 'az' else i/10)
    # Strong distractors must never become model features.
    phone['GPS SPEED (Kmh)'] = np.full(n, 99999.)
    phone.to_csv(raw/'S-Vta2.csv', index=False)
    ref = pd.DataFrame({'vehicle_day': 40000.+reference_t,
                        'indicated': np.asarray(speeds if speeds is not None else np.arange(m)+36.)})
    ref.to_csv(raw/'V-vta2.csv', index=False)
    evidence = tmp_path/'reports/synthetic_source_review.txt'
    evidence.parent.mkdir()
    evidence.write_text('SYNTHETIC SOFTWARE TEST FIXTURE ONLY. This is not review of real source data.\n')
    sources = {}
    for key, name in [('s_uncategorized','S-Vta2.csv'), ('v_uncategorized','V-vta2.csv')]:
        path=raw/name
        sources[key]={'local_path':str(path.relative_to(tmp_path)), 'file':name,
                      'lfs_sha256':digest(path), 'expected_bytes':path.stat().st_size,
                      'verification':'PASS_SIZE_AND_LFS_SHA256'}
    inventory={'repository_commit':'1'*40, 'pairs':[{'drive':'vta2','sources':sources}], 'failures':[]}
    mapping={
        'group_id':'review_E_Vta_Vtb', 'original_trip_id':'synthetic_trip_1',
        'original_group_evidence':['fixture'],
        'phone_source_sha256':sources['s_uncategorized']['lfs_sha256'],
        'reference_source_sha256':sources['v_uncategorized']['lfs_sha256'],
        'phone':{'encoding':'utf-8','channels':dict(zip(CHANNELS,CHANNELS)),
                 'frame':'raw_phone_android_xyz','acceleration_unit':'m/s2','gyro_unit':'rad/s',
                 'acceleration_includes_gravity':True,'raw_axes_evidence':['fixture'],
                 'units_gravity_evidence':['fixture'],'sensor_timing_evidence':['fixture']},
        'reference':{'encoding':'utf-8','column':'indicated','unit':'km/h',
                     'quantity':'vehicle_indicated_speed_magnitude','unit_evidence':['fixture'],
                     'label_semantics':'verified_forward_only','direction_evidence':['fixture'],
                     'valid_rows': valid_rows if valid_rows is not None else [[0,m]],
                     'forward_rows':[[0,m]],'validity_evidence':['fixture']},
        'sections':[{'phone_rows':[0,n],'reference_rows':[0,m],
                     'phone_clock':{'column':'phone_ms','unit':'ms','source_origin':'recording_clock',
                                    'offset_to_recording_s':0.,'evidence':['fixture']},
                     'reference_clock':{'column':'vehicle_day','unit':'s','source_origin':'day_clock',
                                        'offset_to_recording_s':-40000.,'evidence':['fixture']},
                     'sync':{'method':'documented_shared_clock_fixture', 'residual_bound_s':0.,
                             'evidence':['fixture']}}],
    }
    review={'schema':'driftlock-paired-source-review-v1','contract':CONTRACT,
            'status':'SOURCE_MAPPING_REVIEWED','reviewer':'synthetic_fixture_author',
            'reviewed_at':'2026-09-20','evidence':{'fixture':{'path':str(evidence.relative_to(tmp_path)),
                                                          'sha256':digest(evidence)}},
            'independence_evidence':['fixture'],
            'preprocessing':{'method':'causal_hold_segment_start_v1','sample_hz':10.,
                             'window_samples':20,'max_imu_gap_s':.15,'max_imu_age_s':.15,
                             'max_reference_gap_s':.15,'max_reference_age_s':.15},
            'drives':{'vta2':mapping}}
    return inventory, review, raw


def save_json(path,value):
    path.write_text(json.dumps(value))
    return path


def prepare(tmp_path, inventory, review, name='prepared'):
    return prepare_dataset(save_json(tmp_path/'inventory.json',inventory),
                           save_json(tmp_path/'review.json',review),tmp_path/name,root=tmp_path)


def test_unreviewed_proposal_is_metadata_only_and_never_approves(tmp_path,monkeypatch):
    inventory, review, raw=fixture(tmp_path)
    for p in raw.iterdir():
        p.unlink()  # Only synthetic fixture files; absence proves proposal does not open payloads.
    manifest=propose_dataset(save_json(tmp_path/'inventory.json',inventory),tmp_path/'proposal')
    assert manifest['review_status']=='REQUIRES_SOURCE_REVIEW'
    assert manifest['training_approved'] is False
    assert manifest['drives']==[]
    assert manifest['proposed_drives'][0]['split']=='train'
    assert manifest['reserved_final_test_payloads_read']==0
    assert not list((tmp_path/'proposal').glob('*.csv'))


@pytest.mark.parametrize('defect,match',[
    ('clock','clock evidence'), ('axes','raw axes evidence'), ('direction','direction evidence'),
    ('gravity','including gravity'), ('unit','reference unit'), ('residual','residual'),
    ('approval','reviewed'), ('independence','independence evidence'),
])
def test_missing_source_meaning_fails_before_raw_payload_read(tmp_path,defect,match):
    inventory,review,raw=fixture(tmp_path)
    m=review['drives']['vta2']
    if defect=='clock':m['sections'][0]['phone_clock'].pop('evidence')
    if defect=='axes':m['phone'].pop('raw_axes_evidence')
    if defect=='direction':m['reference'].pop('direction_evidence')
    if defect=='gravity':m['phone']['acceleration_includes_gravity']=False
    if defect=='unit':m['reference']['unit']='unknown'
    if defect=='residual':m['sections'][0]['sync'].pop('residual_bound_s')
    if defect=='approval':review['status']='REQUIRES_SOURCE_REVIEW'
    if defect=='independence':review.pop('independence_evidence')
    for p in raw.iterdir():p.unlink()
    with pytest.raises(ValueError,match=match):prepare(tmp_path,inventory,review)
    assert not (tmp_path/'prepared').exists()


def test_equal_rows_never_substitute_for_clock_review(tmp_path):
    inventory,review,_=fixture(tmp_path)
    review['drives']['vta2']['sections'][0]['sync']['evidence']=[]
    with pytest.raises(ValueError,match='clock evidence'):prepare(tmp_path,inventory,review)


def test_latest_target_six_features_source_hashes_and_source_clock_retained(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=10+np.arange(30)/10.,reference_t=10+np.arange(30)/10.)
    manifest=prepare(tmp_path,inventory,review)
    assert manifest['contract']==CONTRACT and manifest['prediction_timestamp']=='latest_sample'
    assert manifest['review_status']=='PREPARED_REQUIRES_TRAINING_REVIEW'
    assert manifest['training_approved'] is False and manifest['runtime_packets_emitted'] is False
    assert manifest['data_time_base']=='seconds_since_recording_start'
    frame=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    windows=list(causal_windows(frame,20))
    assert windows
    w=windows[0]
    assert w['imu'].shape==(20,6) and w['imu'].dtype==np.float32
    assert w['target_t']==w['timestamps_s'][-1]
    assert w['target_t']>=11.9-1e-8 and w['timestamps_s'][0]==10.
    assert w['available_t']>=w['target_t']
    assert np.all(frame.feature_source_time_s<=frame.timestamp_s)
    assert np.all(frame.reference_time_s.dropna()<=frame.timestamp_s[frame.reference_time_s.notna()])
    assert 'GPS SPEED (Kmh)' not in frame


def test_future_reference_and_invalid_latest_reference_cannot_supply_label(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=[0,.1,.2,.3,.4],reference_t=[0,.15,.2,.3,.4],
                               speeds=[36,999,72,108,144],valid_rows=[[0,2],[3,5]])
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    at01=f.iloc[np.argmin(abs(f.timestamp_s-.1))]
    assert at01.speed_mps==10. and at01.reference_source_row==0
    at02=f.iloc[np.argmin(abs(f.timestamp_s-.2))]
    assert at02.label_valid==0 and at02.label_reason=='reference_invalid'
    assert np.isnan(at02.speed_mps)  # Do not recover an older valid row.


def test_gaps_and_nonfinite_imu_segment_without_sorting_or_bridging(tmp_path):
    inventory,review,raw=fixture(tmp_path,phone_t=[0,.1,.2,1.,1.1,1.2],reference_t=[0,.1,.2,1.,1.1,1.2])
    manifest=prepare(tmp_path,inventory,review)
    frames=[pd.read_csv(tmp_path/'prepared'/d['path']) for d in manifest['drives']]
    assert len(frames)==2
    assert all(len(list(causal_windows(f,4)))==0 for f in frames)
    assert any(e['reason']=='phone_gap' for e in manifest['audits']['vta2']['events'])


@pytest.mark.parametrize('stream',['phone','reference'])
def test_clock_reset_needs_separately_reviewed_sections_and_is_not_sorted(tmp_path,stream):
    args={f'{stream}_t':[0,.1,.2,.1,.3]}
    other='reference' if stream=='phone' else 'phone';args[f'{other}_t']=[0,.1,.2,.3,.4]
    inventory,review,_=fixture(tmp_path,**args)
    with pytest.raises(ValueError,match='nonmonotonic.*reviewed section'):prepare(tmp_path,inventory,review)


def test_reference_gap_is_not_held_through_or_joined_before_future_sample(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=np.arange(8)/10.,reference_t=[0,.1,.6,.7])
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    middle=f[(f.timestamp_s>.25)&(f.timestamp_s<.59)]
    assert (middle.label_valid==0).all()
    assert set(middle.label_reason)=={'reference_stale'}


def test_forward_mask_and_reference_hash_tampering_fail_closed(tmp_path):
    inventory,review,raw=fixture(tmp_path)
    review['drives']['vta2']['reference']['forward_rows']=[[2,30]]
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    assert f.iloc[0].label_reason=='direction_unverified' and f.iloc[0].label_valid==0
    with (raw/'V-vta2.csv').open('a') as h:h.write('40003,9\n')
    with pytest.raises(ValueError,match='source.*SHA256|source.*size'):prepare(tmp_path,inventory,review,'tampered')


def test_final_group_and_trip_leakage_are_rejected_before_payloads(tmp_path):
    inventory,review,_=fixture(tmp_path)
    review['drives']['s1']=deepcopy(review['drives']['vta2'])
    with pytest.raises(ValueError,match='reserved|Driver E'):prepare(tmp_path,inventory,review)
    review['drives'].pop('s1')
    second=deepcopy(inventory['pairs'][0]);second['drive']='vfa01'
    for key, prefix in [('s_uncategorized','S'),('v_uncategorized','V')]:
        second['sources'][key]['file']=f'{prefix}-Vfa01.csv'
        second['sources'][key]['local_path']=f'data/raw/synthetic/{prefix}-Vfa01.csv'
    inventory['pairs'].append(second)
    review['drives']['vfa01']=deepcopy(review['drives']['vta2'])
    review['drives']['vfa01']['group_id']='review_E_Vfa'
    with pytest.raises(ValueError,match='crosses split|original trip'):prepare(tmp_path,inventory,review)


def rebind(inventory,review,path,role):
    key='s_uncategorized' if role=='phone' else 'v_uncategorized'
    source=inventory['pairs'][0]['sources'][key]
    source['lfs_sha256']=digest(path);source['expected_bytes']=path.stat().st_size
    review['drives']['vta2'][f'{role}_source_sha256']=source['lfs_sha256']


def test_changing_future_values_cannot_change_past_windows_or_labels(tmp_path):
    inventory,review,raw=fixture(tmp_path)
    first=prepare(tmp_path,inventory,review,'before')
    a=pd.read_csv(tmp_path/'before'/first['drives'][0]['path'])
    phone=pd.read_csv(raw/'S-Vta2.csv');phone.loc[25:,CHANNELS]=1234.
    phone.to_csv(raw/'S-Vta2.csv',index=False)
    reference=pd.read_csv(raw/'V-vta2.csv');reference.loc[25:,'indicated']=9876.
    reference.to_csv(raw/'V-vta2.csv',index=False)
    rebind(inventory,review,raw/'S-Vta2.csv','phone');rebind(inventory,review,raw/'V-vta2.csv','reference')
    second=prepare(tmp_path,inventory,review,'after')
    b=pd.read_csv(tmp_path/'after'/second['drives'][0]['path'])
    aw=[w for w in causal_windows(a) if w['target_t']<2.4]
    bw=[w for w in causal_windows(b) if w['target_t']<2.4]
    assert aw and len(aw)==len(bw)
    for left,right in zip(aw,bw):
        np.testing.assert_array_equal(left['imu'],right['imu'])
        assert left['speed_mps']==right['speed_mps']


def test_sync_residual_is_part_of_past_only_reference_and_age_checks(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=[0,.1,.2,.3],reference_t=[0,.1,.2,.3],speeds=[36,72,108,144])
    review['drives']['vta2']['sections'][0]['sync']['residual_bound_s']=.02
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    assert f.iloc[0].label_reason=='no_past_reference'
    assert f.iloc[1].reference_source_row==0 and f.iloc[1].speed_mps==10.


def test_nonfinite_phone_rows_are_logged_and_break_windows(tmp_path):
    inventory,review,raw=fixture(tmp_path)
    phone=pd.read_csv(raw/'S-Vta2.csv');phone.loc[15,'ax']=np.nan
    phone.to_csv(raw/'S-Vta2.csv',index=False);rebind(inventory,review,raw/'S-Vta2.csv','phone')
    manifest=prepare(tmp_path,inventory,review)
    assert len(manifest['drives'])==2
    assert any(e['reason']=='nonfinite_phone_imu' and e['source_row']==15 for e in manifest['audits']['vta2']['events'])
    assert sum(d['eligible_software_windows'] for d in manifest['drives'])==0


def test_pointer_content_is_rejected_even_if_manifest_hash_matches(tmp_path):
    inventory,review,raw=fixture(tmp_path)
    (raw/'S-Vta2.csv').write_text('version https://git-lfs.github.com/spec/v1\noid sha256:'+'0'*64+'\nsize 1\n')
    rebind(inventory,review,raw/'S-Vta2.csv','phone')
    with pytest.raises(ValueError,match='Git LFS pointer'):prepare(tmp_path,inventory,review)


def test_direct_pair_api_refuses_reserved_payload_path_before_open(tmp_path):
    inventory,review,raw=fixture(tmp_path)
    review['inventory_pair']=inventory['pairs'][0]
    # Does not exist: attempting to open it would raise FileNotFoundError instead.
    with pytest.raises(ValueError,match='reserved|identity'):
        canonicalize_pair(raw/'S-S1.csv',raw/'V-vta2.csv',review,'vta2',root=tmp_path)


def test_reference_gap_limit_caps_hold_even_if_age_limit_is_longer(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=np.arange(8)/10.,reference_t=[0,.1,.6,.7])
    review['preprocessing']['max_reference_age_s']=.9
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    assert (f[(f.timestamp_s>.25)&(f.timestamp_s<.59)].label_valid==0).all()


def test_gps_column_cannot_be_declared_a_phone_feature(tmp_path):
    inventory,review,_=fixture(tmp_path)
    review['drives']['vta2']['phone']['channels']['ax']='GPS SPEED (Kmh)'
    with pytest.raises(ValueError,match='reference.*feature|GPS.*feature'):prepare(tmp_path,inventory,review)


def test_windows_reject_tampered_future_reference_time(tmp_path):
    inventory,review,_=fixture(tmp_path)
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    f.loc[20,'reference_time_s']=f.timestamp_s.iloc[20]+1.
    with pytest.raises(ValueError,match='future reference'):list(causal_windows(f))


def test_separately_reviewed_clock_sections_preserve_row_identity(tmp_path):
    inventory,review,_=fixture(tmp_path,phone_t=[0,.1,.2,.1,.2],reference_t=[0,.1,.2,.3,.4])
    first=review['drives']['vta2']['sections'][0]
    second=deepcopy(first)
    first['phone_rows']=first['reference_rows']=[0,3]
    second['phone_rows']=second['reference_rows']=[3,5]
    second['phone_clock']['offset_to_recording_s']=.2
    review['drives']['vta2']['sections']=[first,second]
    manifest=prepare(tmp_path,inventory,review)
    assert len(manifest['drives'])==2
    second_frame=pd.read_csv(tmp_path/'prepared'/manifest['drives'][1]['path'])
    assert second_frame.timestamp_s.iloc[0]==pytest.approx(.3)
    assert second_frame.phone_source_row.iloc[0]==3
    assert second_frame.reviewed_section.iloc[0]==1


def test_documented_signed_reference_keeps_negative_values(tmp_path):
    inventory,review,_=fixture(tmp_path,speeds=np.full(30,-36.))
    reference=review['drives']['vta2']['reference']
    reference['label_semantics']='vehicle_forward_signed'
    reference['quantity']='signed_vehicle_forward_speed'
    reference.pop('forward_rows')
    manifest=prepare(tmp_path,inventory,review)
    f=pd.read_csv(tmp_path/'prepared'/manifest['drives'][0]['path'])
    assert (f.loc[f.label_valid==1,'speed_mps']==-10.).all()


def test_review_document_hash_is_verified_before_preparation(tmp_path):
    inventory,review,_=fixture(tmp_path)
    (tmp_path/'reports/synthetic_source_review.txt').write_text('Changed review evidence')
    with pytest.raises(ValueError,match='evidence hash mismatch'):prepare(tmp_path,inventory,review)
