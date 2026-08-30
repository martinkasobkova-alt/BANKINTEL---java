"""Inventura oprávnění: kde u každého endpointu sedí kontrola přístupu.

Spouštěj z kořene repozitáře:

    python scripts/authz_audit.py

Klasifikuje každý @*Mapping handler podle toho, kde se vynucuje oprávnění — v controlleru,
v servisní vrstvě, jen přes rate limit, nebo nikde. Výsledky a rozhodnutí jsou v
docs/AUTHORIZATION.md.

Je to hrubý statický odhad, ne důkaz. Detekce v servisní vrstvě se ptá jen "obsahuje ta třída
někde kontrolu", ne "kontroluje právě tahle metoda" — takže kategorie `service` může být
optimistická. Kategorie `zadna` je naopak spolehlivější a je to ta, kterou má smysl procházet.
Než něco označíš za díru, ověř to v kódu; při psaní tohohle skriptu vyšly tři "díry" jako
falešný poplach, protože kontrolu držela služba.
"""

import re, glob, os, collections

GUARD = re.compile(r'adminAccess\.|currentUser\.|requireAdmin|requireUser|requireSubscriber'
                   r'|requireAdminOrBuildToken|featureAccess\.|@PreAuthorize')
MAP = re.compile(r'@((?:Get|Post|Put|Delete|Patch)Mapping)')
SEP = os.sep

rl_src = open('backend-java/src/main/java/cz/bankintel/security/AuthRateLimitFilter.java', encoding='utf-8').read()
# the switch lives in the limitForPath *definition*, not at its call site
chunk = rl_src[rl_src.index('switch (path)'):]
chunk = chunk[:chunk.index('default ->')]
limited = set(re.findall(r'"(/api/[^"]+)"', chunk))
assert limited, 'nepodarilo se vycist rate-limitovane cesty'

# does any non-controller class contain a guard at all?
svc_guard = {}
for p in glob.glob('backend-java/src/main/java/**/*.java', recursive=True):
    norm = p.replace(SEP, '/')
    if '/controller/' in norm:
        continue
    svc_guard[os.path.basename(p)[:-5]] = bool(GUARD.search(open(p, encoding='utf-8').read()))

cat = collections.Counter()
open_eps = []
for p in glob.glob('backend-java/src/main/java/cz/bankintel/controller/**/*.java', recursive=True):
    s = open(p, encoding='utf-8').read()
    m = re.search(r'@RequestMapping\("([^"]*)"\)', s)
    base = m.group(1) if m else ''
    parts = MAP.split(s)
    for i in range(1, len(parts), 2):
        verb = parts[i].replace('Mapping', '')
        seg = parts[i + 1]
        body = seg[:900]
        sub = re.match(r'\(?\s*\{?\s*"?([^",)}\n]*)', seg)
        path = (base + (sub.group(1) if sub else '')).replace('//', '/')
        if GUARD.search(body):
            cat['controller'] += 1
            continue
        names = set(re.findall(r'\b(\w+Service|\w+Index|\w+Builder|\w+Store|\w+Orchestrator)\.', body))
        names = {n[0].upper() + n[1:] for n in names}
        if any(svc_guard.get(n) for n in names):
            cat['service'] += 1
            continue
        if path in limited:
            cat['rate-limit'] += 1
            continue
        cat['zadna'] += 1
        open_eps.append((verb, path, os.path.basename(p)))

print('kde sedi kontrola:')
for k, v in cat.most_common():
    print('  %-12s %4d' % (k, v))
total = sum(cat.values())
print('  %-12s %4d' % ('CELKEM', total))
print()
mut = [e for e in open_eps if e[0] != 'Get']
print('bez jakekoli kontroly: %d | z toho mutujici: %d' % (len(open_eps), len(mut)))
print()
print('--- mutujici bez kontroly ---')
for v, pa, f in sorted(mut):
    print('  %-6s %-44s %s' % (v, pa[:44], f))
