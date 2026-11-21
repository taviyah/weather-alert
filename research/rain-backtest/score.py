"""Score archived rain forecasts against gauge daily totals.

Usage: python -I score.py <download_dir>
"""
import csv, gzip, io, json, math, os, sys
from datetime import date, datetime, timedelta

D = sys.argv[1]
# Gauge days we trust: Meteostat daily where the station inventory lists genuine daily records.
GAUGES = {
    "40179": ("Bet Dagan (Tel Aviv area)", "2026-02-28"),
    "40153": ("Mount Kenaan (Galilee)", "2026-02-28"),
    "40190": ("Beer Sheva", "2026-02-28"),
    "40180": ("Ben Gurion", "2025-08-24"),
}
MODELS = ["best_match", "icon_eu", "icon_global", "ecmwf_ifs", "ecmwf_ifs025", "ecmwf_aifs025_single",
          "gfs_seamless", "ukmo_global_deterministic_10km", "meteofrance_arpege_world"]
SHORT = {"best_match": "app today (best_match)", "icon_eu": "DWD ICON-EU", "icon_global": "DWD ICON global",
         "ecmwf_ifs": "ECMWF IFS 9 km", "ecmwf_ifs025": "ECMWF IFS 0.25", "ecmwf_aifs025_single": "ECMWF AIFS (AI)",
         "gfs_seamless": "NOAA GFS", "ukmo_global_deterministic_10km": "UK Met Office 10 km",
         "meteofrance_arpege_world": "Meteo-France ARPEGE"}
WET = 1.0
HEAVY = 10.0


def rainy_season(d):  # Oct-Apr: outside it Israel is essentially rainless and scores are trivially good
    return d.month >= 10 or d.month <= 4


def obs(sid, last):
    out = {}
    for r in csv.reader(io.TextIOWrapper(gzip.open(os.path.join(D, f"obs_daily_{sid}.csv.gz")), encoding="utf-8")):
        if "2024-01-20" <= r[0] <= last and r[4] != "":
            out[date.fromisoformat(r[0])] = float(r[4])
    return out


def forecast_daily(sid, model, lead, shift_h):
    """Daily totals over the window [date 00 UTC + shift, +24 h). None if any hour missing."""
    path = os.path.join(D, f"fc_{sid}_{model}.json")
    if not os.path.exists(path):
        return {}
    h = json.load(open(path))["hourly"]
    vals = h.get(f"precipitation_previous_day{lead}")
    if not vals:
        return {}
    # Open-Meteo stamps each value at the end of its hour, so the hour ending at T covers T-1h..T.
    series = {datetime.fromisoformat(t): v for t, v in zip(h["time"], vals)}
    out = {}
    days = sorted({t.date() for t in series})
    for d in days:
        start = datetime(d.year, d.month, d.day) + timedelta(hours=shift_h)
        hrs = [series.get(start + timedelta(hours=i + 1)) for i in range(24)]
        if all(x is not None for x in hrs):
            out[d] = sum(hrs)
    return out


def scores(pairs):
    hit = sum(1 for f, o in pairs if f >= WET and o >= WET)
    miss = sum(1 for f, o in pairs if f < WET and o >= WET)
    fa = sum(1 for f, o in pairs if f >= WET and o < WET)
    hh = sum(1 for f, o in pairs if f >= HEAVY and o >= HEAVY)
    hmiss = sum(1 for f, o in pairs if f < HEAVY and o >= HEAVY)
    hfa = sum(1 for f, o in pairs if f >= HEAVY and o < HEAVY)
    wet = [(f, o) for f, o in pairs if f >= WET or o >= WET]
    fsum, osum = sum(f for f, _ in pairs), sum(o for _, o in pairs)
    return {
        "n": len(pairs), "obs_wet": hit + miss,
        "POD": hit / (hit + miss) if hit + miss else float("nan"),
        "FAR": fa / (hit + fa) if hit + fa else float("nan"),
        "CSI": hit / (hit + miss + fa) if hit + miss + fa else float("nan"),
        "heavyPOD": hh / (hh + hmiss) if hh + hmiss else float("nan"),
        "heavyFAR": hfa / (hh + hfa) if hh + hfa else float("nan"),
        "heavy_obs": hh + hmiss,
        "MAE_wet": sum(abs(f - o) for f, o in wet) / len(wet) if wet else float("nan"),
        "bias": fsum / osum if osum else float("nan"),
    }


GAUGES = {k: v for k, v in GAUGES.items()
          if os.path.exists(os.path.join(D, f"obs_daily_{k}.csv.gz"))
          and all(os.path.exists(os.path.join(D, f"fc_{k}_{m}.json")) for m in MODELS)}
print("gauges scored:", ", ".join(v[0] for v in GAUGES.values()))

# ---- 1. Which daily window do the gauges use? Pick the shift that best matches a model-neutral consensus.
print("Gauge day alignment (correlation of gauge totals with mean of all models, lead 1):")
best_shift = {}
for sid, (name, last) in GAUGES.items():
    o = obs(sid, last)
    res = []
    for shift in (-18, -12, -6, 0, 6):
        fcs = [forecast_daily(sid, m, 1, shift) for m in ("best_match", "gfs_seamless", "ecmwf_ifs025", "meteofrance_arpege_world")]
        days = [d for d in o if rainy_season(d) and all(d in f for f in fcs)]
        xs = [sum(f[d] for f in fcs) / len(fcs) for d in days]
        ys = [o[d] for d in days]
        n = len(xs)
        mx, my = sum(xs) / n, sum(ys) / n
        cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
        r = cov / math.sqrt(sum((x - mx) ** 2 for x in xs) * sum((y - my) ** 2 for y in ys))
        res.append((r, shift))
    best_shift[sid] = max(res)[1]
    print(f"  {name:28s} " + "  ".join(f"{s:+d}h r={r:.2f}" for r, s in sorted(res, key=lambda x: x[1])) +
          f"   -> use {best_shift[sid]:+d}h")


def table(title, models, lead, since=None):
    print(f"\n{title}")
    pooled = {m: [] for m in models}
    ens = []  # (fraction of models wet, obs wet) on fully common days
    for sid, (name, last) in GAUGES.items():
        o = obs(sid, last)
        fcs = {m: forecast_daily(sid, m, lead, best_shift[sid]) for m in models}
        days = [d for d in o if rainy_season(d) and (since is None or d >= since) and all(d in fcs[m] for m in models)]
        for d in days:
            for m in models:
                pooled[m].append((fcs[m][d], o[d]))
            ens.append((sum(fcs[m][d] >= WET for m in models) / len(models), o[d] >= WET,
                        sorted(fcs[m][d] for m in models)[len(models) // 2], o[d]))
    n = len(next(iter(pooled.values())))
    print(f"  {n} station-days, {sum(1 for _, w, _, _ in ens if w)} observed wet (>= {WET} mm), "
          f"{sum(1 for *_, o in ens if o >= HEAVY)} heavy (>= {HEAVY} mm)")
    print(f"  {'model':26s} {'CSI':>5s} {'hit%':>5s} {'false%':>6s} {'heavy hit%':>10s} {'heavy false%':>12s} {'MAE wet mm':>10s} {'amount bias':>11s}")
    rows = [(m, scores(pooled[m])) for m in models]
    med = scores([(m_, o) for _, _, m_, o in ens])
    rows.append(("multi-model median", med))
    for m, s in sorted(rows, key=lambda r: -r[1]["CSI"]):
        print(f"  {SHORT.get(m, m):26s} {s['CSI']:5.2f} {100*s['POD']:5.0f} {100*s['FAR']:6.0f} {100*s['heavyPOD']:10.0f} "
              f"{100*s['heavyFAR']:12.0f} {s['MAE_wet']:10.1f} {s['bias']:11.2f}")
    # Probability from model agreement vs. a yes/no single model, both as Brier scores.
    brier_ens = sum((p - w) ** 2 for p, w, _, _ in ens) / len(ens)
    clim = sum(w for _, w, _, _ in ens) / len(ens)
    brier_clim = sum((clim - w) ** 2 for _, w, _, _ in ens) / len(ens)
    print(f"  Brier (lower is better): multi-model agreement {brier_ens:.3f}, climatology {brier_clim:.3f}, " +
          ", ".join(f"{SHORT[m]} yes/no {sum(((f >= WET) - (o >= WET)) ** 2 for f, o in pooled[m]) / n:.3f}"
                    for m in models if m in ("best_match", "ecmwf_ifs", "ecmwf_ifs025")))
    print("  Reliability of multi-model agreement (share of models saying wet -> how often it rained):")
    buckets = {}
    for p, w, _, _ in ens:
        b = round(p * 4) / 4
        buckets.setdefault(b, []).append(w)
    for b in sorted(buckets):
        v = buckets[b]
        print(f"    ~{100*b:3.0f}% of models wet: rained on {100*sum(v)/len(v):3.0f}% of {len(v)} days")


ALL = MODELS
LONG = ["best_match", "icon_eu", "icon_global", "ecmwf_ifs025", "gfs_seamless", "meteofrance_arpege_world"]
table("A. All 9 models, 2025-26 season (Oct 2025 - Feb 2026), forecast made ~1 day ahead", ALL, 1, date(2025, 10, 1))
table("B. All 9 models, 2025-26 season, ~2 days ahead", ALL, 2, date(2025, 10, 1))
table("C. Six long-archive models, Jan 2024 - Feb 2026 (2.3 seasons), ~1 day ahead", LONG, 1)
table("D. Six long-archive models, Jan 2024 - Feb 2026, ~2 days ahead", LONG, 2)
