"""Checks every language file against the English key list.

Usage:  python tools/i18n/check_i18n.py

Fails (exit 1) if a language is missing a key, has an empty value, or changes the
{placeholders} of a sentence. Extra keys are reported as warnings.
To add a new UI string: add the English text to keys.json, then to every <lang>.json.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYS = json.loads((Path(__file__).with_name("keys.json")).read_text(encoding="utf-8"))
LANG_DIR = ROOT / "src/main/resources/static/i18n"
PH = re.compile(r"\{(\w+)\}")

ok = True
for f in sorted(LANG_DIR.glob("*.json")):
    d = json.loads(f.read_text(encoding="utf-8"))
    missing = [k for k in KEYS if k not in d]
    empty = [k for k in KEYS if k in d and not str(d[k]).strip()]
    bad_ph = [k for k in KEYS if k in d and sorted(PH.findall(k)) != sorted(PH.findall(d[k]))]
    extra = [k for k in d if k not in KEYS]
    status = "OK" if not (missing or empty or bad_ph) else "FAIL"
    ok &= status == "OK"
    print(f"{f.stem}: {status}  {len(d)} strings, {len(missing)} missing, {len(empty)} empty, "
          f"{len(bad_ph)} placeholder errors, {len(extra)} extra")
    for label, items in (("missing", missing), ("placeholder", bad_ph), ("empty", empty), ("extra (warning)", extra)):
        for k in items[:15]:
            print(f"   {label}: {k}")
sys.exit(0 if ok else 1)
