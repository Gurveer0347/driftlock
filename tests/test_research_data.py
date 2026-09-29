"""Synthetic source fixtures only; no original reserved-test payload is opened."""
import hashlib
import importlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from driftlock_ml.data import check_manifest

CFG = {'window_samples': 20, 'sample_hz': 10.}
HEADERS = ['TIME SINCE START (ms)', 'ACCELEROMETER X (m/s²)',
           'ACCELEROMETER Y (m/s²)', 'ACCELEROMETER Z (m/s²)',
           'GYROSCOPE X (rad/s)', 'GYROSCOPE Y (rad/s)',
           'GYROSCOPE Z (rad/s)', 'GPS SPEED (Kmh)']
GROUPS = {'review_A_S1_S2':'test','review_A_S3abc':'test','review_A_S4':'test',
          'review_B_M':'test','review_D_Y1':'test','review_E_Vta_Vtb':'train',
          'review_E_Vfa':'val','review_E_Vw':'calibration'}
NAMES = ['S-S1.csv','S-S3a.csv','S-S4.csv','S-M.csv','S-Y1.csv',
         'S-Vta1a.csv','S-Vfa01.csv','S-Vw1.csv']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2))


def source_fixture(tmp_path, monkeypatch, *, raw_values=None, timestamps=None, rename=None, bad_audit=False):
    rd = importlib.import_module('driftlock_ml.research_data')
    monkeypatch.setattr(rd, 'ROOT', tmp_path)
    raw = tmp_path/'data/raw/iovnbd_official/synchronized'
    raw.mkdir(parents=True)
    t = 4200. + np.arange(65)*100 if timestamps is None else np.asarray(timestamps)
    n = len(t)
    values = np.column_stack([np.arange(n)+i*.25 for i in range(6)])
    target = np.arange(n, dtype=float)-12 if raw_values is None else np.asarray(raw_values)
    records=[]; groups=[]; audit=[]
    for idx, ((group, split), name) in enumerate(zip(GROUPS.items(), NAMES)):
        frame = pd.DataFrame(np.column_stack([t, values+idx*10, target]), columns=[' '+h+' ' for h in HEADERS])
        if rename and split=='train':
            frame=frame.rename(columns={' '+HEADERS[rename[0]]+' ':rename[1]})
        path=raw/name
        frame.to_csv(path,index=False,encoding='latin1')
        row={'name':name,'path':str(path.relative_to(tmp_path)), 'sha256':digest(path),
             'bytes':path.stat().st_size,'rows':n,'nonfinite_timestamps':0,
             'duplicate_timestamp_pairs':int(bad_audit and split=='train'), 'backward_timestamp_pairs':0}
        audit.append(row)
        records.append({'filename':name,'proposed_group_id':group,'csv_audit_evidence':row})
        groups.append({'proposed_group_id':group,'filenames':[name],'independence_verified':False})
    write_json(tmp_path/'reports/synchronized_group_review.json',
               {'scope':'SYNTHETIC UNIT TEST FIXTURES ONLY','groups':groups,'records':records})
    write_json(tmp_path/'reports/synchronized_review/full_file_audit.json',{'files':audit,'errors':[]})
    write_json(tmp_path/'logs/exploratory_20260910_01/authorization.json',{'test_fixture':True})
    return rd, raw, values+50, target, t


def prepare(tmp_path, monkeypatch, **kwargs):
    rd, raw, x, y, t=source_fixture(tmp_path,monkeypatch,**kwargs)
    out=tmp_path/'processed'
    manifest=rd.prepare_recorded_dataset(out)
    return rd,out,manifest,raw,x,y,t


def save_manifest(path, manifest):
    write_json(path,manifest)
    path.with_suffix('.sha256').write_text(digest(path)+'\n')


def test_prepare_keeps_exact_channels_numbers_and_source_clock(tmp_path,monkeypatch):
    rd,out,m,raw,x,y,t=prepare(tmp_path,monkeypatch)
    row=next(e for e in m['drives'] if e['split']=='train')
    frame=pd.read_csv(out/row['path'])
    assert list(frame.columns)==['timestamp_s','ax','ay','az','gx','gy','gz','reference_value','reference_present']
    np.testing.assert_array_equal(frame[['ax','ay','az','gx','gy','gz']],x)
    np.testing.assert_array_equal(frame.reference_value,y)
    np.testing.assert_allclose(frame.timestamp_s,t/1000,rtol=0,atol=1e-15)
    assert frame.reference_present.eq(1).all()
    assert row['source_sha256']==digest(raw/row['source_file'])
    assert row['processed_sha256']==digest(out/row['path'])
    assert m['research_contract']==rd.RESEARCH_CONTRACT
    assert m['valid_for_navigation'] is False
    assert m['group_assignments']==GROUPS
    assert 'speed_mps' not in frame and 'label_valid' not in frame
    with pytest.raises(ValueError):check_manifest(out/'manifest.json')


@pytest.mark.parametrize('rename',[(4,'GYROSCOPE W (rad/s)'),(1,'ACCELEROMETER X (g)'),(7,'GPS SPEED (m/s)')])
def test_prepare_rejects_guessed_or_mismatched_headers(tmp_path,monkeypatch,rename):
    rd,*_=source_fixture(tmp_path,monkeypatch,rename=rename)
    with pytest.raises(ValueError,match='header|column'):rd.prepare_recorded_dataset(tmp_path/'processed')


def test_reserved_test_sources_never_opened_and_test_split_refused(tmp_path,monkeypatch):
    rd,raw,*_=source_fixture(tmp_path,monkeypatch)
    original=Path.open
    reserved={raw/name for name in NAMES[:5]}
    def guarded(path,*args,**kwargs):
        assert path not in reserved,'reserved test payload opened'
        return original(path,*args,**kwargs)
    monkeypatch.setattr(Path,'open',guarded)
    out=tmp_path/'processed';m=rd.prepare_recorded_dataset(out)
    assert len(m['reserved_test_files'])==5
    assert len(m['drives'])==3
    assert not any((out/name).exists() for name in NAMES[:5])
    with pytest.raises(ValueError,match='test|reserved'):rd.RecordedWindows(out/'manifest.json',CFG,'test')
    for split in ['train','val','calibration']:
        assert len(rd.RecordedWindows(out/'manifest.json',CFG,split))>0


def test_missing_and_negative_reference_retained_without_fake_validity(tmp_path,monkeypatch):
    target=np.arange(65,dtype=float)-20;target[9]=np.nan
    rd,out,m,*_=prepare(tmp_path,monkeypatch,raw_values=target)
    entry=next(e for e in m['drives'] if e['split']=='train')
    frame=pd.read_csv(out/entry['path'])
    assert len(frame)==65 and np.isnan(frame.reference_value.iloc[9]) and frame.reference_present.iloc[9]==0
    assert frame.reference_value.iloc[0]==-20
    data=rd.RecordedWindows(out/'manifest.json',CFG,'train')
    assert 19 not in data.index[:,1]
    assert data[0][1].item()<0


def test_grid_and_centre_reference_are_latest_past_original_samples(tmp_path,monkeypatch):
    t=4200.+np.arange(65)*100;t[9]=5140.
    rd,out,m,raw,x,y,t=prepare(tmp_path,monkeypatch,timestamps=t)
    data=rd.RecordedWindows(out/'manifest.json',CFG,'train')
    drive=data.drives[0];xx,yy,d,e=data[0]
    assert e==19 and drive['target_t'][e]==pytest.approx(5.15)
    expected=np.searchsorted(t/1000,drive['t'][:20],side='right')-1
    np.testing.assert_array_equal(xx.numpy(),x[expected].astype(np.float32))
    assert yy.item()==y[9]  # original reference at5.14, after the5.1 grid tick
    assert drive['t'][e]==pytest.approx(6.1)
    assert data.manifest==m and data.manifest_path==out/'manifest.json'


def test_windows_do_not_span_large_gap(tmp_path,monkeypatch):
    t=4200.+np.arange(65)*100;t[30:]+=2000
    rd,out,*_=prepare(tmp_path,monkeypatch,timestamps=t)
    data=rd.RecordedWindows(out/'manifest.json',CFG,'train')
    drive=data.drives[0]
    for _,end in data.index:
        ticks=drive['t'][end-19:end+1]
        assert not (ticks[0]<7.2 and ticks[-1]>=9.2)


def test_short_source_gap_cannot_hide_between_grid_ticks(tmp_path,monkeypatch):
    t=4200.+np.arange(65)*100;t[30:]+=100
    rd,out,*_=prepare(tmp_path,monkeypatch,timestamps=t)
    data=rd.RecordedWindows(out/'manifest.json',CFG,'train')
    drive=data.drives[0]
    for _,end in data.index:
        ticks=drive['t'][end-19:end+1]
        assert not (ticks[0]<7.3 and ticks[-1]>=7.3)


@pytest.mark.parametrize('defect',['duplicate','backward','nonfinite'])
def test_source_clock_rechecked_even_when_old_audit_declares_no_failure(tmp_path,monkeypatch,defect):
    t=4200.+np.arange(65)*100
    t[30]=t[29] if defect=='duplicate' else t[29]-1 if defect=='backward' else np.nan
    rd,*_=source_fixture(tmp_path,monkeypatch,timestamps=t)
    with pytest.raises(ValueError,match='clock|finite|increasing'):
        rd.prepare_recorded_dataset(tmp_path/'processed')


def test_excludes_whole_file_on_prior_clock_failure_without_opening_it(tmp_path,monkeypatch):
    rd,raw,*_=source_fixture(tmp_path,monkeypatch,bad_audit=True)
    original=Path.open
    def guarded(path,*args,**kwargs):
        assert path!=raw/'S-Vta1a.csv','known bad-clock file should not be converted'
        return original(path,*args,**kwargs)
    monkeypatch.setattr(Path,'open',guarded)
    m=rd.prepare_recorded_dataset(tmp_path/'processed')
    assert not any(e['split']=='train' for e in m['drives'])
    assert any(e['source_file']=='S-Vta1a.csv' for e in m['excluded_files'])


@pytest.mark.parametrize('mutation',['source_hash','processed_hash','manifest_hash','group','duplicate_hash'])
def test_integrity_and_split_guards(tmp_path,monkeypatch,mutation):
    if mutation=='source_hash':
        rd,raw,*_=source_fixture(tmp_path,monkeypatch)
        with (raw/'S-Vta1a.csv').open('a') as f:f.write('\n')
        with pytest.raises(ValueError,match='hash|SHA'):rd.prepare_recorded_dataset(tmp_path/'processed')
        return
    rd,out,m,*_=prepare(tmp_path,monkeypatch)
    entry=next(e for e in m['drives'] if e['split']=='train')
    if mutation=='processed_hash':
        with (out/entry['path']).open('a') as f:f.write('\n')
    elif mutation=='manifest_hash':
        with (out/'manifest.json').open('a') as f:f.write(' ')
    else:
        if mutation=='group':entry['group_id']='review_E_Vfa'
        else:next(e for e in m['drives'] if e['split']=='val')['source_sha256']=entry['source_sha256']
        save_manifest(out/'manifest.json',m)
    with pytest.raises(ValueError,match='hash|SHA|group|split'):rd.RecordedWindows(out/'manifest.json',CFG,'train')


def test_probe_selection_requires_exact_complete_training_filename(tmp_path,monkeypatch):
    rd,out,*_=prepare(tmp_path,monkeypatch)
    assert len(rd.RecordedWindows(out/'manifest.json',CFG,'train',include_files={'S-Vta1a.csv'}))>0
    for names in [{'S-Vta1a'},{'S-Vfa01.csv'},{'S-M.csv'}]:
        with pytest.raises(ValueError,match='file|split|reserved'):rd.RecordedWindows(out/'manifest.json',CFG,'train',include_files=names)


def test_normalization_uses_training_imu_only(tmp_path,monkeypatch):
    rd,out,*_=prepare(tmp_path,monkeypatch)
    train=rd.RecordedWindows(out/'manifest.json',CFG,'train')
    mean,std=train.normalization()
    z=train.drives[0]['x'][train.drives[0]['imu_valid']]
    np.testing.assert_allclose(mean,z.mean(0),atol=1e-5)
    np.testing.assert_allclose(std,z.std(0),atol=1e-5)
    with pytest.raises(ValueError,match='training'):rd.RecordedWindows(out/'manifest.json',CFG,'val').normalization()


def test_existing_output_directory_is_never_reused(tmp_path,monkeypatch):
    rd,out,*_=prepare(tmp_path,monkeypatch)
    with pytest.raises(FileExistsError):rd.prepare_recorded_dataset(out)
