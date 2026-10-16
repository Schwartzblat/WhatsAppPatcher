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
