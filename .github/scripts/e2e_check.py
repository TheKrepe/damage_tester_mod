#!/usr/bin/env python3
"""Compares the selftest server and client logs; writes summary.md; exit 1 on any problem."""
import os
import re
import sys

e, mc, loader = sys.argv[1], sys.argv[2], sys.argv[3]


def read(name):
    p = os.path.join(e, name)
    return open(p, errors="replace").read().splitlines() if os.path.exists(p) else []


s1, s2, cl = read("server1.log"), read("server2.log"), read("client.log")
passes, fails, expects = [], [], []
for line in s1 + s2:
    m = re.search(r"\[DT-SELFTEST\] (PASS|FAIL|EXPECT-CLIENT) (.*)$", line)
    if m:
        {"PASS": passes, "FAIL": fails, "EXPECT-CLIENT": expects}[m.group(1)].append(m.group(2).strip())
client = [l.split("[DT-SELFTEST-CLIENT] ", 1)[1] for l in cl if "[DT-SELFTEST-CLIENT] " in l]

missing = []
for pat in expects:
    rx = re.compile(".*".join(re.escape(p) for p in pat.split("*")))
    if not any(rx.search(c) for c in client):
        missing.append(pat)
client_fails = [c for c in client if " FAIL" in c]

noise = re.compile(r"realms|narrator|flite|openal|alsa|SoundSystem|Anisotropic|GLX|authlib|sessionserver|401|Unable to load|"
                   r"No data fixer|Failed to fetch|telemetry|profile key|Yggdrasil|ReportEnvironment|Couldn't load|ChatReport|"
                   r"Unable to play|pulse|pipewire|libXxf86vm", re.I)
errors = []
for name, lines in (("server1", s1), ("server2", s2), ("client", cl)):
    for i, l in enumerate(lines):
        if re.search(r"/ERROR\]|/WARN\].*damage_tester|Exception|Failed to draw|FAILED during APPLY|was not applied|Mixin apply.*failed", l) \
                and not noise.search(l) and "DT-SELFTEST" not in l:
            if "damage_tester" in l.lower() or "Failed to draw" in l or "Mixin" in l or "/ERROR]" in l:
                errors.append(f"{name}:{i + 1}: {l.strip()[:300]}")

phase1 = any("PHASE1 DONE" in l for l in s1)
phase2 = any("PHASE2 DONE" in l for l in s2)
ok = phase1 and phase2 and not fails and not missing and not client_fails
with open(os.path.join(e, "summary.md"), "w") as f:
    f.write(f"# E2E {mc} {loader}: {'OK' if ok else 'PROBLEMS'}\n\n")
    f.write(f"phase1 done: {phase1}, phase2 done: {phase2}\n\npassed: {len(passes)}, failed: {len(fails)}, "
            f"client expectations: {len(expects) - len(missing)}/{len(expects)}\n\n")
    for title, items in (("Failed checks", fails), ("Missing on the client", missing), ("Client check failures", client_fails),
                         ("Errors in logs (review)", errors[:80])):
        if items:
            f.write(f"## {title}\n\n" + "\n".join("- " + i for i in items) + "\n\n")
    f.write("## Passed\n\n" + "\n".join("- " + p for p in passes) + "\n")
print(open(os.path.join(e, "summary.md")).read())
sys.exit(0 if ok else 1)
