"""Prüft die Transitous-API (MOTIS) für eine Strecke: Antwortzeit, Format, Linienverlauf."""
import json, math, sys, time, urllib.parse, urllib.request
UA = "BahnSchmerz/1.5 (Diagnose; https://github.com/FHeiCode/Bahnschmerz)"
BASE = "https://api.transitous.org/api/"
def get(path):
    t = time.time()
    req = urllib.request.Request(BASE + path, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode(), time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - t
    except Exception as e:
        return None, repr(e), time.time() - t
def km(a, b):
    r = 6371.0; dl = math.radians(b[0]-a[0]); dn = math.radians(b[1]-a[1])
    h = math.sin(dl/2)**2 + math.cos(math.radians(a[0]))*math.cos(math.radians(b[0]))*math.sin(dn/2)**2
    return 2*r*math.atan2(math.sqrt(h), math.sqrt(1-h))
def decode(s, prec):
    f = 10 ** prec; idx = lat = lon = 0; pts = []
    while idx < len(s):
        for which in (0, 1):
            shift = res = 0
            while True:
                b = ord(s[idx]) - 63; idx += 1; res |= (b & 0x1f) << shift; shift += 5
                if b < 0x20: break
            d = ~(res >> 1) if res & 1 else res >> 1
            if which == 0: lat += d
            else: lon += d
        pts.append((lat / f, lon / f))
    return pts
A, B = (48.1407, 11.5569), (49.4452, 11.0823)
# Version des Endpunkts finden
for v in ["v5", "v4", "v3", "v2", "v1"]:
    q = urllib.parse.urlencode({"fromPlace": f"{A[0]},{A[1]}", "toPlace": f"{B[0]},{B[1]}", "transitModes": "RAIL", "maxTransfers": 0})
    st, body, dt = get(f"{v}/plan?{q}")
    print(f"plan {v}: HTTP {st}, {len(body)} Bytes, {dt:.1f}s" + ("" if st == 200 else f"  {body[:120]!r}"))
    if st == 200:
        data = json.loads(body); break
else:
    sys.exit(0)
print("Schlüssel:", sorted(data.keys()))
import os; os.makedirs("tools/data", exist_ok=True); open("tools/data/motis_plan.json", "w").write(body)
for n, it in enumerate(data.get("itineraries", [])[:4]):
    for leg in it.get("legs", []):
        if leg.get("mode") in ("WALK",): continue
        g = leg.get("legGeometry", {}); prec = g.get("precision", 6)
        pts = decode(g.get("points", ""), prec)
        length = sum(km(x, y) for x, y in zip(pts, pts[1:]))
        gaps = sorted((km(x, y) for x, y in zip(pts, pts[1:])), reverse=True)[:3]
        print(f"  Verbindung {n}: {leg.get('mode')} {leg.get('routeShortName') or leg.get('displayName')} trip={leg.get('tripShortName')} "
              f"{leg['from']['name']} -> {leg['to']['name']}, Zwischenhalte {len(leg.get('intermediateStops', []))}, "
              f"Linie {len(pts)} Punkte, {length:.0f} km (Luftlinie {km(A, B):.0f} km), größte Lücken {[round(x,1) for x in gaps]} km, precision {prec}")
        print(f"    Leg-Schlüssel: {sorted(leg.keys())}")
        if n == 0:
            print("    Zwischenhalte:", [s.get('name') for s in leg.get('intermediateStops', [])])
            print("    tripId:", leg.get("tripId"))
