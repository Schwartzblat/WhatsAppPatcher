import json
import re
from pathlib import Path

SRC = Path(__file__).parent.parent / 'smali_generator/app/src/main/java/com/smali_generator'
FEATURES = json.loads((Path(__file__).parent.parent / 'smali_generator/app/src/main/assets/patcher/features.json').read_text())
GET_RE = re.compile(r'Artifacts\.get\("([A-Z0-9_]+)"\)')
STATIC_READ_RE = re.compile(r'\bstatic\b[^;(]*=[^;]*Artifacts\.get\(')
# `String id() {`, then whitespace and comments, then `return "..."`.
ID_RE = re.compile(r'String id\(\)\s*\{(?:\s|//[^\n]*\n|/\*.*?\*/)*return "([^"]+)"', re.DOTALL)


def sources():
    return {p: p.read_text() for p in SRC.rglob('*.java')}


def test_no_placeholders_left():
    assert [p.name for p, s in sources().items() if re.search(r'\{\{[A-Z0-9_]+\}\}', s)] == []


def test_every_key_is_mapped():
    mapped = set(FEATURES['required_keys']) | {k for f in FEATURES['features'] for k in f['keys']}
    used = {k for s in sources().values() for k in GET_RE.findall(s)}
    assert used and used - mapped == set()


def test_no_static_field_reads_artifacts():
    assert [p.name for p, s in sources().items() if STATIC_READ_RE.search(s)] == []


def test_feature_ids_are_hook_ids():
    hook_ids = set()
    for p in (SRC / 'patches').glob('*.java'):
        m = ID_RE.search(p.read_text())
        hook_ids.add(m.group(1) if m else p.stem)
    assert {f['id'] for f in FEATURES['features']} - hook_ids == set()


# Keys read by helper classes on behalf of a hook's features (file path under SRC -> feature ids).
HELPER_FEATURES = {
    'ui/GroupInfoRows.java': {'group_stats', 'shared_members'},
    'ui/SharedMembersActivity.java': {'shared_members'},
}


def _feature_keys(feature_id):
    return {k for f in FEATURES['features'] if f['id'] == feature_id for k in f['keys']}


def test_each_file_reads_only_keys_of_its_features():
    problems = []
    for path in sorted((SRC / 'patches').glob('*.java')):
        text = path.read_text()
        match = ID_RE.search(text)
        used = set(GET_RE.findall(text))
        feature_ids = {match.group(1)} if match else set()
        if not feature_ids & {f['id'] for f in FEATURES['features']}:
            continue
        allowed = set(FEATURES['required_keys'])
        for fid in feature_ids:
            allowed |= _feature_keys(fid)
        if used - allowed:
            problems.append((path.name, sorted(used - allowed)))
    assert problems == []


def test_helper_classes_keys_are_in_their_features():
    problems = []
    for rel, feature_ids in HELPER_FEATURES.items():
        used = set(GET_RE.findall((SRC / rel).read_text()))
        assert used, rel
        for fid in feature_ids:
            if used - _feature_keys(fid) - set(FEATURES['required_keys']):
                problems.append((rel, fid, sorted(used - _feature_keys(fid))))
    assert problems == []


def test_ab_props_funnel_keys_are_in_group_history():
    used = set(GET_RE.findall((SRC / 'patches/AbProps.java').read_text()))
    assert used <= _feature_keys('group_history'), sorted(used - _feature_keys('group_history'))
