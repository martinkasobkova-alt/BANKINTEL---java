import re
from pathlib import Path

fe_root = Path(r"c:\Bankoapp-main\BankIntel-v2\frontend\src")
java_root = Path(r"c:\Bankoapp-main\BankIntel-v2\backend-java\src\main\java\cz\bankintel\controller")

fe_paths = set()
pat = re.compile(r"""api\.(get|post|put|patch|delete)\(\s*[`'"](/[^`'"]+)[`'"]""")
for p in fe_root.rglob("*.jsx"):
    txt = p.read_text(encoding="utf-8", errors="ignore")
    for m in pat.finditer(txt):
        path = m.group(2).split("?")[0]
        if "${" not in path:
            fe_paths.add(path)
        else:
            fe_paths.add(re.sub(r"\$\{[^}]+\}", "{id}", path))

for p in fe_root.rglob("*.js"):
    txt = p.read_text(encoding="utf-8", errors="ignore")
    for m in pat.finditer(txt):
        path = m.group(2).split("?")[0]
        if "${" not in path:
            fe_paths.add(path)
        else:
            fe_paths.add(re.sub(r"\$\{[^}]+\}", "{id}", path))

java_paths = set()
map_pat = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\(([^)]*)\)")
req_pat = re.compile(r'@RequestMapping\("([^"]+)"\)')
for p in java_root.rglob("*.java"):
    txt = p.read_text(encoding="utf-8", errors="ignore")
    base = ""
    rm = req_pat.search(txt)
    if rm:
        base = rm.group(1)
    for m in map_pat.finditer(txt):
        raw = m.group(2)
        paths = re.findall(r'"([^"]*)"', raw)
        if not paths:
            paths = [""]
        for sub in paths:
            if sub in ("", "/"):
                full = base.rstrip("/") or "/"
            else:
                full = (base.rstrip("/") + "/" + sub.lstrip("/")).replace("//", "/")
            full = re.sub(r"\{(\w+)\}", "{id}", full)
            if full.startswith("/api"):
                full = full[4:] if full.startswith("/api/") else full.replace("/api", "", 1)
            java_paths.add(full)

fe_norm = set()
invalid = set()
for p in fe_paths:
    path = p if p.startswith("/") else "/" + p
    if path.startswith("/api/"):
        path = path[4:]
    if "${" in path or "}" in path or "{id}{id}" in path or not path.startswith("/"):
        invalid.add(path)
        continue
    if re.search(r"\{id\}[a-zA-Z_$]", path):
        invalid.add(path)
        continue
    fe_norm.add(path)
missing = sorted(fe_norm - java_paths)
matched = sorted(fe_norm & java_paths)

print("Frontend static paths:", len(fe_norm))
if invalid:
    print("Ignored invalid FE template paths:", len(invalid))
print("Java controller paths (approx):", len(java_paths))
print("Matched:", len(matched), f"({100 * len(matched) // max(1, len(fe_norm))}%)")
print("Still missing:", len(missing))
for x in missing:
    print(" -", x)
