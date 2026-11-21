"""Download gauge observations (Meteostat bulk) and archived forecasts (Open-Meteo previous runs).

Usage: python -I fetch.py <download_dir>
Everything lands in <download_dir>; nothing is executed from there.
"""
import json, os, sys, time, urllib.request

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "WeatherAlert-backtest/0.1 (personal research)"}

STATIONS = {  # id: (name, lat, lon, source)
    "40153": ("Mount Kenaan (Safed)", 32.9667, 35.5000, "daily"),
    "40155": ("Haifa", 32.8000, 35.0333, "hourly"),
    "40179": ("Bet Dagan", 32.0000, 34.8167, "daily"),
    "40180": ("Ben Gurion", 32.0000, 34.9000, "both"),
    "40190": ("Beer Sheva", 31.2500, 34.8000, "daily"),
    "40199": ("Eilat", 29.5500, 34.9500, "both"),
}
MODELS = ["best_match", "ecmwf_ifs", "ecmwf_ifs025", "icon_eu", "icon_global", "gfs_seamless",
          "ukmo_global_deterministic_10km", "ecmwf_aifs025_single", "meteofrance_arpege_world"]
START, END = "2024-01-01", "2026-03-10"


def fetch(url, path, timeout=180):
    if os.path.exists(path) and os.path.getsize(path) > 200:
        return True
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = r.read()
            open(path, "wb").write(data)
            return True
        except Exception as e:
            msg = getattr(e, "read", lambda: b"")()[:300]
            print(f"  retry {attempt} {e} {msg}", flush=True)
            time.sleep(15 * (attempt + 1))
    return False


for sid, (name, lat, lon, src) in STATIONS.items():
    for kind in ("daily", "hourly"):
        fetch(f"https://bulk.meteostat.net/v2/{kind}/{sid}.csv.gz", os.path.join(OUT, f"obs_{kind}_{sid}.csv.gz"))
    for model in MODELS:
        hourly = "precipitation_previous_day1,precipitation_previous_day2"
        if model == "best_match":
            hourly += ",precipitation_probability_previous_day1,precipitation_probability_previous_day2"
        url = (f"https://previous-runs-api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
               f"&hourly={hourly}&models={model}&start_date={START}&end_date={END}&timezone=GMT")
        ok = fetch(url, os.path.join(OUT, f"fc_{sid}_{model}.json"))
        print(f"{sid} {name:22s} {model:32s} {'ok' if ok else 'FAILED'}", flush=True)
        time.sleep(3)
print("done")
