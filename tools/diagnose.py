"""
Diagnose: stellt für eine Strecke nach, was die App tut, mit echten Live-Daten.
Läuft auf GitHub Actions (dort sind DBF, Nominatim, Overpass und die DB erreichbar).
"""
import json, math, re, sys, time, uuid, urllib.parse, urllib.request, heapq
from datetime import datetime

FROM, TO = sys.argv[1], sys.argv[2]
UA = "BahnSchmerz-Diagnose (GitHub Actions)"

def get(url, headers=None, data=None, method=None, timeout=60):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})}, method=method)
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, body, time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - t
    except Exception as e:
        return None, repr(e), time.time() - t

def km(a, b):
    r = 6371.0
    dlat = math.radians(b[0] - a[0]); dlon = math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(h), math.sqrt(1 - h))

def norm(s):
    s = s.lower().replace("ß", "ss")
    s = re.sub(r"[()\[\],.\-/]", " ", s)
    s = re.sub(r"\bhauptbahnhof\b", "hbf", s)
    return re.sub(r"\s+", " ", s).strip()

def same(a, b):
    x, y = norm(a), norm(b)
    return x == y or x.replace(" ", "") == y.replace(" ", "") or x.startswith(y + " hbf") or y.startswith(x + " hbf")

print("=" * 70); print(f"Strecke: {FROM} -> {TO}   {datetime.now()}"); print("=" * 70)

# ---------- 1. DBF-Tafel und Bahnhofsfolge ----------
st, body, dt = get(f"https://dbf.finalrewind.org/{urllib.parse.quote(FROM)}?mode=json&version=3&no_related=1&past=1")
print(f"\n[1] DBF-Tafel {FROM}: HTTP {st}, {len(body)} Bytes, {dt:.1f}s")
deps = json.loads(body).get("departures", []) if st == 200 else []
print(f"    {len(deps)} Abfahrten")
candidates = []
for d in deps:
    route = [r["name"] for r in d.get("route", []) if isinstance(r, dict) and "name" in r]
    di = next((i for i, n in enumerate(route) if same(n, TO)), -1)
    oi = next((i for i, n in enumerate(route) if same(n, FROM)), -1)
    if di < 0 or (oi >= 0 and di < oi):
        continue
    stops = route[(oi + 1 if oi >= 0 else 0):di]
    candidates.append((d.get("train"), stops))
print(f"    Züge {FROM} -> {TO}: {len(candidates)}")
for t, s in candidates[:8]:
    print(f"      {t}: {len(s)} Zwischenhalte: {', '.join(s)}")
if deps:
    r0 = [r.get("name") for r in deps[0].get("route", [])]
    print(f"    Beispiel-Laufweg (erster Eintrag, enthält eigene Station? {any(same(n, FROM) for n in r0)}): {r0[:6]} ...")
best = max(candidates, key=lambda c: len(c[1]))[1] if candidates else []
corridor = [FROM] + best + [TO]
print(f"    Bahnhofsfolge (Korridor, {len(corridor)}): {corridor}")

# ---------- 2. Koordinaten wie in der App (Nominatim) ----------
print("\n[2] Koordinaten per Nominatim (wie die App)")
def search_text(name):
    t = name.replace("(", " (").replace(")", ") ")
    t = re.sub(r"\bHbf\b", "Hauptbahnhof", t); t = re.sub(r"\bBf\b", "Bahnhof", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t if "bahnhof" in t.lower() else t + " Bahnhof"
coords = {}
for n in corridor:
    q = urllib.parse.urlencode({"q": search_text(n), "format": "jsonv2", "limit": 8,
                                "countrycodes": "de,at,ch,nl,be,lu,fr,dk,pl,cz,it"})
    st, body, _ = get("https://nominatim.openstreetmap.org/search?" + q, {"Accept-Language": "de"})
    res = json.loads(body) if st == 200 else []
    pick = next((o for o in res if o.get("category") == "railway" and o.get("type") in ("station", "halt", "stop")), None) \
        or next((o for o in res if o.get("category") == "railway"), None) \
        or next((o for o in res if o.get("category") == "public_transport"), None) \
        or (res[0] if res else None)
    if pick:
        coords[n] = (float(pick["lat"]), float(pick["lon"]))
        print(f"    {n:32s} -> {coords[n][0]:.4f},{coords[n][1]:.4f}  [{pick.get('category')}/{pick.get('type')}] {pick.get('display_name','')[:60]}")
    else:
        print(f"    {n:32s} -> NICHT GEFUNDEN (HTTP {st})")
    time.sleep(1.1)
ordered = [(i, coords[n]) for i, n in enumerate(corridor) if n in coords]
for (i, a), (j, b) in zip(ordered, ordered[1:]):
    print(f"      {corridor[i]} -> {corridor[j]}: {km(a, b):.1f} km Luftlinie")

# ---------- 3. Overpass wie in der App ----------
print("\n[3] Overpass-Abfragen (Stücke wie in der App)")
def chunks(stations, max_km=40.0):
    out, start = [], 0
    while start < len(stations) - 1:
        end = start + 1; d = km(stations[start][1], stations[end][1])
        while end < len(stations) - 1:
            nx = km(stations[end][1], stations[end + 1][1])
            if d + nx > max_km: break
            d += nx; end += 1
        out.append(stations[start:end + 1]); start = end
    return out
def query(st):
    mx = max(km(a[1], b[1]) for a, b in zip(st, st[1:]))
    r = int(min(max(mx * 100, 1200), 5000))
    line = ",".join(f"{p[0]:.5f},{p[1]:.5f}" for _, p in st)
    return ('[out:json][timeout:25];way["railway"~"^(rail|light_rail|narrow_gauge)$"]'
            f'["service"!~"^(yard|siding|spur)$"](around:{r},{line});out skel geom;')
SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://overpass.kumi.systems/api/interpreter"]
segments_found = 0; segments_total = 0
for c in chunks(ordered):
    q = query(c)
    print(f"  Stück {corridor[c[0][0]]} .. {corridor[c[-1][0]]} ({len(c)} Bahnhöfe)")
    print(f"    Abfrage: {q[:160]}...")
    data = None
    for s in SERVERS:
        st, body, dt = get(s, {"Content-Type": "application/x-www-form-urlencoded"},
                           urllib.parse.urlencode({"data": q}).encode(), "POST", 60)
        remark = re.search(r'"remark"\s*:\s*"([^"]*)"', body or "")
        print(f"    {s.split('/')[2]}: HTTP {st}, {len(body)} Bytes, {dt:.1f}s" + (f", remark: {remark.group(1)[:120]}" if remark else "")
              + ("" if st == 200 else f", Anfang: {body[:150]!r}"))
        if st == 200 and body.lstrip().startswith("{"):
            data = json.loads(body); break
    if not data:
        print("    -> KEINE DATEN"); segments_total += len(c) - 1; continue
    ways = [e for e in data.get("elements", []) if e.get("type") == "way"]
    print(f"    Wege: {len(ways)}; Schlüssel des ersten: {sorted(ways[0].keys()) if ways else '-'}")
    with_nodes = sum(1 for w in ways if "nodes" in w and "geometry" in w and len(w["nodes"]) == len(w["geometry"]))
    print(f"    Wege mit nodes+geometry gleicher Länge: {with_nodes}")
    # Graph + Dijkstra wie in der App (vereinfacht)
    idx, pts, adj = {}, [], []
    def node(nid, p):
        if nid not in idx: idx[nid] = len(pts); pts.append(p); adj.append([])
        return idx[nid]
    for w in ways:
        ids, geom = w.get("nodes"), w.get("geometry")
        if not ids or not geom or len(ids) != len(geom): continue
        prev = node(ids[0], (geom[0]["lat"], geom[0]["lon"]))
        for k in range(1, len(ids)):
            cur = node(ids[k], (geom[k]["lat"], geom[k]["lon"]))
            if cur != prev:
                d = km(pts[prev], pts[cur]); adj[prev].append((cur, d)); adj[cur].append((prev, d))
            prev = cur
    print(f"    Graph: {len(pts)} Knoten")
    for (i, a), (j, b) in zip(c, c[1:]):
        segments_total += 1
        da = [(km(a, p), n) for n, p in enumerate(pts)]; db = [(km(b, p), n) for n, p in enumerate(pts)]
        if not da: print(f"      {corridor[i]} -> {corridor[j]}: kein Graph"); continue
        ma, mb = min(da)[0], min(db)[0]
        if ma > 1.5 or mb > 1.5:
            print(f"      {corridor[i]} -> {corridor[j]}: Bahnhof zu weit vom Gleis (A {ma:.2f} km, B {mb:.2f} km)"); continue
        src = {n: d for d, n in da if d <= ma + 0.25}; dst = {n: d for d, n in db if d <= mb + 0.25}
        dist = {n: d for n, d in src.items()}; heap = [(d, n) for n, d in src.items()]; heapq.heapify(heap)
        best_t, best_d = None, 1e18; straight = km(a, b); limit = straight * 4 + 5
        while heap:
            d, u = heapq.heappop(heap)
            if d > dist.get(u, 1e18): continue
            if d >= best_d or d > limit: break
            if u in dst and d + dst[u] < best_d: best_d, best_t = d + dst[u], u
            for v, w_ in adj[u]:
                nd = d + w_
                if nd < dist.get(v, 1e18): dist[v] = nd; heapq.heappush(heap, (nd, v))
        if best_t is None:
            print(f"      {corridor[i]} -> {corridor[j]}: KEIN WEG im Gleisgraph (Luftlinie {straight:.1f} km)")
        else:
            segments_found += 1
            print(f"      {corridor[i]} -> {corridor[j]}: Weg {best_d:.1f} km (Luftlinie {straight:.1f} km)")
    time.sleep(2)
print(f"  ERGEBNIS OSM: {segments_found} von {segments_total} Abschnitten mit Gleisweg")

# ---------- 4. DB-Schnittstelle (vendo) ----------
print("\n[4] DB-Navigator-Schnittstelle")
BASE = "https://app.services-bahn.de/mob/"
def vendo(path, typ, body=None):
    h = {"Accept": typ, "Content-Type": typ, "X-Correlation-ID": f"{uuid.uuid4()}_{uuid.uuid4()}",
         "Accept-Language": "de", "User-Agent": "BahnSchmerz-Android"}
    return get(BASE + path, h, json.dumps(body).encode() if body is not None else None, "POST" if body is not None else "GET", 20)
st, body, dt = vendo("location/search", "application/x.db.vendo.mob.location.v3+json",
                     {"locationTypes": ["ST"], "searchTerm": TO, "maxResults": 3})
print(f"  Ortssuche '{TO}': HTTP {st}, {dt:.1f}s, Anfang: {body[:300]!r}")
eva = None
try:
    loc = json.loads(body); first = loc[0] if isinstance(loc, list) else None
    eva = first.get("evaNr") if first else None
    print(f"    erster Treffer: {first.get('name') if first else None}, evaNr {eva}, Schlüssel {sorted(first.keys()) if first else None}")
except Exception as e:
    print(f"    nicht lesbar: {e!r}")
if eva:
    now = datetime.now()
    st, body, dt = vendo("bahnhofstafel/ankunft", "application/x.db.vendo.mob.bahnhofstafeln.v2+json",
                         {"anfragezeit": now.strftime("%H:%M"), "datum": now.strftime("%Y-%m-%d"),
                          "ursprungsBahnhofId": f"A=1@L={eva}@", "verkehrsmittel": ["ALL"]})
    print(f"  Ankunftstafel: HTTP {st}, {len(body)} Bytes, {dt:.1f}s")
    try:
        board = json.loads(body); items = board.get("bahnhofstafelAnkunftPositionen", [])
        print(f"    {len(items)} Einträge; Schlüssel oben: {sorted(board.keys())}")
        if items:
            print(f"    erster: {json.dumps(items[0], ensure_ascii=False)[:400]}")
            tid = items[0]["zuglaufId"]
            for suffix in ["", "?poly=true"]:
                st, body, dt = vendo("zuglauf/" + urllib.parse.quote(tid, safe="") + suffix, "application/x.db.vendo.mob.zuglauf.v2+json")
                print(f"  Zuglauf{suffix}: HTTP {st}, {len(body)} Bytes, {dt:.1f}s")
                try:
                    tr = json.loads(body)
                    pg = tr.get("polylineGroup")
                    n = sum(len(d.get("coordinates", [])) for d in pg.get("polylineDesc", [])) if pg else 0
                    print(f"    Halte: {len(tr.get('halte', []))}, polylineGroup: {'ja' if pg else 'NEIN'} ({n} Punkte), Schlüssel: {sorted(tr.keys())}")
                    h0 = tr.get("halte", [{}])[0]
                    print(f"    erster Halt: {json.dumps(h0, ensure_ascii=False)[:300]}")
                except Exception as e:
                    print(f"    nicht lesbar: {e!r}; Anfang {body[:200]!r}")
    except Exception as e:
        print(f"    nicht lesbar: {e!r}; Anfang {body[:200]!r}")
print("\nFertig.")
