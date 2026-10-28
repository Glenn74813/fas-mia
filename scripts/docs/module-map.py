"""Module inventory for docs/24-module-map.md: which tables each backend module writes (owns), which modules it imports
and whose tables it reads. Usage: python3 scripts/docs/module-map.py backend/src/main/java/com/miaa/cas \
    backend/src/main/resources/db/migration /tmp/module-map.json"""
import os, re, json, collections, sys
root = sys.argv[1]
tables = set(re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?(\w+)', ' '.join(open(os.path.join(sys.argv[2], f)).read() for f in os.listdir(sys.argv[2]) if f.endswith('.sql'))))
mods = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)))
imports = collections.defaultdict(lambda: collections.Counter())
writes = collections.defaultdict(collections.Counter); reads = collections.defaultdict(collections.Counter)
for m in mods:
    for dp, _, fs in os.walk(os.path.join(root, m)):
        for f in fs:
            if not f.endswith('.java'): continue
            s = open(os.path.join(dp, f)).read()
            for t in re.findall(r'import (?:static )?com\.miaa\.cas\.(\w+)\.', s):
                if t != m: imports[m][t] += 1
            for t in re.findall(r'@Table\(name\s*=\s*"(\w+)"', s): writes[m][t] += 5
            for t in re.findall(r'(?:INSERT INTO|UPDATE|DELETE FROM)\s+(\w+)', s, re.I):
                if t in tables: writes[m][t] += 1
            for t in re.findall(r'(?:FROM|JOIN)\s+(\w+)', s, re.I):
                if t in tables: reads[m][t] += 1
owner = {}
for t in tables:
    cands = [(writes[m][t], m) for m in mods if writes[m][t]]
    owner[t] = max(cands)[1] if cands else None
out = {}
for m in mods:
    own = sorted(t for t, o in owner.items() if o == m)
    uses = collections.defaultdict(set)
    for t in set(reads[m]) | set(writes[m]):
        o = owner.get(t)
        if o and o != m: uses[o].add(t)
    out[m] = {'owns': own, 'imports': dict(imports[m]), 'uses_tables_of': {k: sorted(v) for k, v in uses.items()},
              'writes_other': sorted(t for t in writes[m] if owner.get(t) not in (m, None))}
json.dump(out, open(sys.argv[3], 'w'), indent=1)
print('unowned:', sorted(t for t, o in owner.items() if o is None))
for m in mods:
    print(f"{m}: owns {len(out[m]['owns'])}; imports {sorted(out[m]['imports'])}; reads/writes tables of {sorted(out[m]['uses_tables_of'])}")
