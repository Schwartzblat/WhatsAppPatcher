import json

import main
from artifactory_generator.dex_copier import DexCopier


def test_artifactory_only_drops_dex_copier():
    args = main.get_args_from(['-p', 'x.apk'])
    assert not any(isinstance(f, DexCopier) for f in main.build_finders(args, True))
    assert any(isinstance(f, DexCopier) for f in main.build_finders(args, False))


def test_run_artifactory_only_writes_record_and_exit_code(tmp_path, monkeypatch):
    class FakeStitch:
        def __init__(self, **kw): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def find_artifacts(self): return {'PACKAGE_SIGNATURE': 'aa'}
    monkeypatch.setattr(main, 'Stitch', FakeStitch)
    monkeypatch.setattr(main, 'build_record', lambda apk, arts, feats, ver: {'supported': False, 'dex_sha256': 'd', 'features_off': [], 'v': ver})
    out = tmp_path / 'r.json'
    assert main.run_artifactory_only(main.get_args_from(['-p', 'x.apk', '--artifactory-only', str(out)])) == 3
    assert json.loads(out.read_text())['v'] == '3.0.2'
