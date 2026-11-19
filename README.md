# Weather Alert

An Android app that watches a list of places and notifies you when rain, dust, poor air quality
or a sharp change in temperature or wind is coming in the next few days — and tells you what
that means for hanging laundry, wearing a mask, and what to put on.

No backend, no account, no API key. Everything runs on the device.

- **Kotlin + Jetpack Compose**, minSdk 26, targetSdk 36
- **Open-Meteo** for forecast, air quality and place search
- **WorkManager** for the periodic check, **DataStore** for all persistence

Open the folder in Android Studio and Sync. `./gradlew :app:assembleDebug` also works from the
command line.

---

## What triggers an alert

Alerts come in two tiers, on two separate notification channels, so the routine "rain on Thursday"
stream can be silenced in system settings while a sharp temperature drop still makes a sound.

| | Notable | Major |
|---|---|---|
| **Rain** | ≥ 0.5 mm **and** ≥ 50% chance | ≥ 10 mm, at any stated probability |
| **Dust** | PM10 ≥ 100 µg/m³ or dust ≥ 50 µg/m³ | PM10 ≥ 180 or dust ≥ 150 |
| **Air quality** | EU AQI ≥ 80 | EU AQI ≥ 100 |
| **Temperature change** | ≥ 3 °C day-over-day (raw) / ≥ 4.2 °C (feels-like) | ≥ 5.5 °C / ≥ 7.7 °C |
| **Wind change** | peak gust moves ≥ 12 km/h | ≥ 18 km/h |
| **Heat** | high crosses 35 °C | high crosses 38 °C |
| **Cold / frost** | low crosses 8 °C | low crosses 0 °C |

Every number above is a slider in Settings. Thresholds are always stored in Celsius, so switching
the display to Fahrenheit never silently changes what fires.

### Measured signal volume

Backtested over the 60 days ending 2026-09-09, on the real daily series for each city:

| | signals | per week | of which major |
|---|---|---|---|
| Tel Aviv | 6 | 0.7 | 0 |
| Jerusalem | 17 | 2.0 | 0 |
| Beersheba | 18 | 2.1 | 2 |
| Haifa | 11 | 1.3 | 1 |

Across 240 city-days that is 3 major events — roughly one loud interruption per city per 11 weeks.
The window is Israel's *quietest* season (no rain at all, stable temperatures), so treat these as
a floor; winter will produce considerably more.

### Why 3 °C and 5.5 °C

The rule compares **like with like**: each day's high against the *previous day's* high, and each
night's low against the previous night's low. Comparing a high against a low would just
re-discover the normal daily heating cycle every single day and alert constantly.

The starting point was the meteorological literature: below ~5 °C day-over-day is inside normal
noise in most climates, and ≥ 8 °C in 24 h is roughly where national weather services treat a
swing as a cold or heat surge in its own right (China's cold wave standard, for instance, is
≥ 8 °C over 48 h). Health work on temperature swings tends to find effects appearing from about
5 °C upward.

Those levels turned out to be too strict in practice. Backtested over 60 quiet days, 5/8 fired
0.1–0.2 times per week per city and caught **no** major events at all — the app felt broken rather
than calm. **3 / 5.5** raises that to 0.5–1.8 per week, which is a change you would actually
notice walking outside, and is the shipped default.

Two refinements are built in:

1. **Feels-like counts too.** A 5 °C drop under a 40 km/h wind lands like a 10 °C one, so
   `apparent_temperature_max/min` is tested alongside the real temperature and whichever moves
   most is what gets reported. Switchable off in Settings.
2. **Absolute crossings fire independently of the delta**, because 28 → 33 °C matters more than
   5 → 10 °C even though both are +5. Heat, cold and frost alerts trigger only on the day the
   forecast *crosses* the line, not on every day of a week-long heat wave.

### Why wind change is measured on gusts, at 12 km/h

Mean wind is useless for this. Over 90 days across five Israeli cities, the day-over-day change in
mean wind speed **never once reached 15 km/h** (p98 = 10), so a rule built on it would be
permanently silent. Peak gusts are both the actionable quantity and the one that actually moves.

Gust deltas measured across those same 90 days:

| median | p90 | p95 | p98 | max |
|---|---|---|---|---|
| 3 | 10 | 13 | 17 | 26 |

So the 20–30 km/h bar that felt right by intuition would have fired on **0.6% of days**. 12 km/h
sits around the 93rd percentile and 18 above the 98th.

Wind is also ratio-ish in a way temperature is not: 5 → 20 km/h is technically a 15 km/h jump and
is not weather. `windMinKmh` (default 35) is a floor on the windier of the two days, so a calm day
turning into a light breeze does not count.

### Why feels-like is judged against a higher bar

Apparent temperature is a non-linear function of temperature *and* humidity, and in warm humid air
that curve is steep. Measured over 60 days across six Israeli cities, day-over-day feels-like
deltas ran a **median 1.37× the raw ones** (p90 2.73×).

Testing both against the same 3 °C bar therefore let feels-like do almost all the alerting:

| firing days | share |
|---|---|
| feels-like only | **42 (78%)** |
| both | 9 (17%) |
| raw only | 3 (6%) |

In practice that meant Hadera reporting *"Turning colder — overnight feels-like 26° → 22° (−4°)"*
on a night whose actual low moved 2.2 °C, with the daily high flat at 31 °C. The rule had quietly
become a humidity detector.

Feels-like is now scaled by `apparentMultiplier` (default **1.4**, from the measured amplification),
so it needs 4.2 °C where raw needs 3 °C, and the two candidates compete *normalised by their own
bars* rather than on raw magnitude. Genuine wind-chill events still fire — a 9 °C apparent swing
clears 4.2 easily — but humidity noise no longer does.

Alerts driven by feels-like now print the thermometer reading alongside, so
*"Overnight feels-like 26° to 22° (−4°), actual 22° to 20°"* can never read as a wrong forecast.

### Why crossings have a 2 °C deadband

A bare crossing test is not enough. Backtesting 60 days of real Beersheba highs against a plain
35 °C line produced **7 heat alerts** — the desert daily max sits right at the threshold and
oscillates 34/36/34/36, so every upswing counted as a fresh crossing. Requiring the previous day
to sit a clear 2 °C *below* the line before a heat alert can fire cut that to 2 alerts, both real
transitions, while a genuine climb from 28 °C still fires normally. The same margin applies to the
cold and frost rules. It is `extremeDeadbandC` in `Thresholds`.

### Why the air-quality bar sits at 80, not 60

The European AQI scale calls 60 the start of "Poor", which looks like the obvious threshold. It
is not, here. Sampling 17 days of daily-max EU AQI in September 2026:

| | median | p90 | worst |
|---|---|---|---|
| Tel Aviv | 51 | 60 | 64 |
| Jerusalem | **69** | 80 | 81 |
| Beersheba | 62 | 67 | 67 |

Background ozone and dust keep the whole eastern Mediterranean sitting inside the "Poor" band, so
a threshold of 60 would alert in Jerusalem *every single day*. The default starts at 80 — the next
band up — so an air-quality alert means something genuinely unusual. Adjust in Settings if your
locations run cleaner or dirtier.

PM10 needs no such correction: those same 17 days ran a median daily max of 24–28 µg/m³ and never
exceeded 45, so the 100 µg/m³ bar is comfortably clear of the baseline. During a khamsin, PM10 in
Israel routinely runs 200–500.

### Deliberate suppressions

- **Air quality is suppressed on days that already fired a dust alert.** A dust event is exactly
  what drives AQI into the red, so reporting both is the same news twice; dust is the more
  specific and more actionable message.
- **Cold-night is suppressed on days that fired frost.** Frost is strictly the stronger claim.
- **Clothing advice stands down when a temperature alert covers the same day** and the advice is
  thermal-only. Otherwise the card stacked "Dress warmer — feels 4° colder than yesterday"
  directly above "Turning colder — … (−4°)": one fact printed twice. Advice that also mentions
  rain, wind or a cold evening still earns its place.
- **Heat, cold and frost fire on crossings only**, not on levels.

---

## Advice signals

The alerts say *what the weather is doing*. The three advice signals say *what to do about it*,
and each is shown per day on the location card with its own colour.

On the location card, today's and tomorrow's advice and the active alerts are folded behind one
tappable line ("3 alerts (1 major) · 2 tips", the kinds as emoji, a chevron). It is bold when
anything is major or a "don't", so folding hides detail but never the fact that something serious
is coming. The open/closed state is kept per place.

Opened, it shows **one row per day**, not one per alert (`domain/DayDigest.kt`): the alerts as
short phrases on one line ("Tomorrow · 🌧 Heavy rain 12 mm (70%) · 💨 Windier, gusts 25→60
km/h"), the tips underneath. A tip keeps its action but loses any reason an alert on that day
already states, so a wet day reads "🧺 Do not hang laundry · 🧥 Take a raincoat" instead of
repeating "70% chance of rain" three times. A reason no alert covers stays ("Slow to dry
(humidity 88%)"). The detail screen still lists every alert and tip in full.

These are not a re-skin of the alerts. Each reads different variables at different levels, because
the physics differ.

### 🧺 Laundry

Two independent ways for an outdoor line to be a bad idea — it gets rained on, or it gets dusted —
plus humidity, which merely means it will still be wet at sundown.

| | trigger |
|---|---|
| **Do not hang** | ≥ 0.2 mm rain at ≥ 40% chance, or PM10 ≥ 100 |
| **Whites may dull** | PM10 ≥ 60, or airborne dust ≥ 25 |
| **Slow to dry** | mean humidity ≥ 80% |
| **Peg it down** | gusts ≥ 60 km/h |

The dust bars sit **well below** the health ones on purpose. Clean air in Israel measures PM10
24–28 µg/m³, and wet fabric is an efficient deposition surface, so whites start to grey long
before the air is unhealthy to breathe. PM10 70 raises no dust alert and no mask advice, but it
will dull a white sheet — that gap is the entire reason this signal exists separately.

The rain bar is also lower than the rain alert's (40% vs 50%): being surprised by drizzle is
cheap, re-washing a load is not.

### 😷 Mask

**Driven by particulate concentration only — deliberately not by the headline AQI.**

A mask filters particles. It does essentially nothing for ozone or nitrogen dioxide. Over 30
sampled days, the European AQI peak was driven by:

| | what drove the peak |
|---|---|
| Jerusalem | ozone 100% |
| Beersheba | ozone 100% |
| Tel Aviv | NO₂ 52%, ozone 48% |

Not one day was particulate-driven. Wiring mask advice to the AQI would therefore have told you to
put on a mask, on almost every bad-air day here, against a pollutant the mask cannot touch.

| | trigger |
|---|---|
| **Wear an N95** | PM2.5 ≥ 50 or PM10 ≥ 100 |
| **Mask if sensitive** | PM2.5 ≥ 25 or PM10 ≥ 60 |
| **A mask will not help** | particulates low, but O₃ or NO₂ sub-index ≥ 80 |

That last row is the useful one. Instead of bad advice it names the gas, gives the hour it peaks
(ozone is strongly diurnal), and says to keep hard exercise indoors. When particulates are *also*
high, they win and the N95 advice stands.

### 🧥 Clothing

Phrased relative to yesterday, which is how people actually dress, and measured on **apparent**
temperature throughout, since that is the quantity a coat is chosen against.

| | trigger |
|---|---|
| **Dress much warmer / lighter** | feels-like moves ≥ 5.5 ° vs yesterday |
| **Dress warmer / lighter** | ≥ 3 ° |
| **Take a raincoat** | ≥ 0.2 mm at ≥ 40% chance |
| **Windproof layer** | gusts ≥ 55 km/h |
| **Layer for the evening** | warm day, but feels ≤ 10 ° after dark |

The gust bar started at 45 km/h and was wrong: measured over 60 days, Jerusalem's *median* daily
gust is 49 km/h and Haifa's is 42, so 45 flagged **77% of Jerusalem days** and made the signal
meaningless. 55 sits near the top of the normal range everywhere sampled.

### How advice reaches you

- **Only a red "bad" verdict sends a notification.** Amber ones stay in the app. Promoting them
  would roughly triple the volume for very little added value — across 240 backtested city-days,
  amber laundry advice appeared on 2–10% of days and amber clothing advice on up to 77% before
  the gust fix.
- **Advice leads the notification text.** "Do not hang laundry" is what you act on; "PM10 140" is
  the evidence for it and belongs after.
- Advice dedupes through the same ledger as alerts, keyed by location + date + kind + level.

---

## How notifications behave

Alerts go out as soon as a check finds them — the periodic worker runs every 3 hours by default,
which matches how often the weather models behind the data are actually re-run. Checking more
often mostly re-reads the same numbers at the cost of battery.

Two things keep this from becoming spam:

- **One notification, full stop**, not one per alert or per place. A single incoming front can trip
  rain, dust and a temperature drop across three days and two places at once; a stack of
  notifications for one weather system would train you to swipe them away. Lines are sorted by
  day (advice first within a day) and name the place when there is more than one. If a later check
  finds something new while the notification is still showing, the new lines are merged in rather
  than replacing what you have not read; lines for days already past drop off. Dismissing it
  clears it.
- **Tapping it opens the home screen**, the overview of every place and day, rather than one
  place.
- **An alert ledger.** Each alert is identified by location + date + kind + severity and shown
  once. A storm four days out is announced when it appears, not re-announced at every check until
  it arrives. Severity is part of that identity on purpose: an alert that escalates from Notable
  to Major *is* worth interrupting you a second time; one that quietly fades is not.

Settings has a **Re-send current alerts** button that clears the ledger, for when you dismissed
something and want it back.

---

## Layout

```
model/Model.kt         Data classes + Thresholds (every tunable number, with its rationale)
domain/AlertEngine.kt  What the weather is doing. Pure, side-effect free, 30 unit tests
domain/AdviceEngine.kt What to do about it: laundry, mask, clothing. 22 unit tests
domain/Insights.kt     Plain-language readings for the detail screen. 15 unit tests
data/OpenMeteo.kt      Forecast, air quality and geocoding; hourly air data → daily peaks
data/Stores.kt         DataStore: locations, settings, forecast cache, alert ledger
work/WeatherWork.kt    The periodic worker and its scheduling
notify/Notifier.kt     Two channels, one merged alert notification
ui/                    Compose screens: home, location detail, add-location, settings
```

`./gradlew :app:testDebugUnitTest` runs the engine tests. They are the only practical way to
check threshold behaviour without waiting for real weather.

### Design notes

- **Why yesterday is fetched.** The forecast request uses `past_days=1` so the day-over-day
  temperature rule has a real observed baseline for the first day it reports on, rather than
  comparing a forecast against a forecast.
- **Why air quality is aggregated to a daily peak, not a mean.** A six-hour dust plume that
  doubles PM10 is exactly the thing worth a notification, and averaging across 24 hours hides it.
- **Why DataStore instead of Room.** Everything persisted is a few KB of JSON — a handful of
  locations, one settings object, a cache and a ledger. That is well under the point where a
  database and a KSP round-trip pay for themselves.
- **Why polling instead of push.** With no server there is nothing to host, no account to create,
  and no location data leaving the phone beyond the coordinates in the Open-Meteo request itself.
- **Graceful degradation.** Air quality is a separate service and a separate model run; if it is
  down, rain and temperature alerts still work. One unreachable location does not stop the others
  from being checked.
- **Failed places are retried, not the whole run.** Failures are usually partial — a real check
  timed out on 2 of 3 places while the third succeeded. Only the failed ones are retried, after
  20 s and again after 60 s; if any still fail, WorkManager retries the run at 10, 20 and 40 min
  before falling back to the normal schedule. The ledger keeps repeats from re-notifying.

---

## Location detail

Tapping a place opens it one day at a time, picked from a row of day
tabs rather than one long scroll, since on e-paper a scroll is a run of panel refreshes.

| | what it answers |
|---|---|
| **Rain** | *When*, not just whether: windows like "14:00–18:00 · moderate · up to 70% · 3.2 mm", a 24-bar chart of hourly chance (black bar = 0.2 mm or more expected, white = a chance but little or none), and an **hour-by-hour table** with each hour's chance and millimetres; quiet stretches fold into one "dry" line, and today's table starts at the current hour |
| **Air quality** | The band and what it means for you, a six-step meter, worst and cleanest hours, which pollutant drives it, and whether a mask would help |
| **Humidity** | Daily range, how sticky it feels, and the dampest and driest hours |
| **Sun and wind** | UV band with advice, sunrise and sunset, wind and gusts with direction |

Implementation notes:

- **Rain hours are shifted back one hour.** Open-Meteo stamps each hourly precipitation value at
  the *end* of the hour it covers, so the 14:00 value is rain from 13:00 to 14:00. Reading it
  naïvely puts every shower an hour late and puts yesterday's last hour on today.
- **An hour counts as rain at ≥ 40% chance or ≥ 0.2 mm**, the same bars the laundry and clothing
  advice use. One dry hour inside a shower does not split it into two windows.
- **"Sticky" is judged on dew point, not relative humidity.** 80% at dawn in October feels fine;
  60% at 33 °C is miserable. Dew point is the absolute water content, which decides whether sweat
  can evaporate: below 16 °C comfortable, 18–21 muggy, 21+ oppressive.
- **The cleanest air hour is looked for between 06:00 and 21:00 only.** "Cleanest at 04:00" is
  true and useless; the question is when to go for a run.
- **Air bands follow the scale chosen in Settings** — European bands are half-open (0–20, 20–40…)
  so 80 already reads "Very poor", in line with the alert bar; US bands use the EPA's 0–50, 51–100…
  Pollutant rows always use European sub-indices, the only per-pollutant breakdown available.
- Forecasts cached by an older version have no hourly data; the screen says so and fills in after
  the next refresh.

---

## Rain data source

Rain (hourly and daily amount and chance) comes from **ECMWF IFS** (`models=ecmwf_ifs`, a
second small request); everything else still comes from Open-Meteo's `best_match`. If the ECMWF
request fails, rain falls back to `best_match` for that refresh, and the detail screen's rain
section says which source it is showing. With `models=ecmwf_ifs` the chance is the share of
ECMWF's 51-run ensemble with rain (checked 2026-11-07: within 3-4 points on average of a count
over the raw members).

Why: for Israel `best_match` is DWD ICON-EU alone (its numbers match `icon_eu` to the decimal),
and a backtest (`research/rain-backtest/`, rerunnable) found it the weakest source:

- Archived forecasts (Open-Meteo Previous Runs API, Jan 2024 - Feb 2026) scored against daily
  gauge totals at Bet Dagan, Mount Kenaan, Beer Sheva and Ben Gurion (Meteostat daily,
  cross-checked against NOAA GSOD; Haifa and Eilat dropped because their recent "daily" rows are
  model-filled). Rainy season only, wet day = 1 mm.
- 2025-26 season, forecast ~1 day ahead: ICON-EU caught 62% of rain days and 41% of 10 mm+ days
  and forecast about half the rain that fell (amount bias 0.57). ECMWF IFS 9 km caught 93% and
  75%, amounts right on average (bias 1.06), 16% false alarms; CSI 0.79 vs 0.59. ~2 days ahead the
  gap is the same. Over 2.3 seasons ECMWF IFS 0.25° leads too (CSI 0.54 vs 0.43).
- A live case the same day: Jerusalem, 72 h ahead, ICON said 0.2 mm at most 45%; ECMWF said
  9.8 mm at 100%; IMS had 35-55% hours with 1-2 mm.
- Not testable: hourly timing (no trustworthy keyless hourly gauges), the probabilities
  (not archived) and IMS forecasts (no archive).
- Open-Meteo lists ICON-EU as discontinued from 2026-11-30, so `best_match` for Israel was about
  to change regardless.

Consequence for alerts: ECMWF forecasts roughly twice the rain ICON did, so the same thresholds
(0.5 mm and 50%; 10 mm for major) fire more often, which is the point: ICON was missing over a
third of rain days. The thresholds were not re-tuned; watch for false alarms over a few weeks.
Temperature, wind, humidity and UV stay on `best_match` because their thresholds were tuned on it.

---

## Not built yet

- **Adaptive thresholds.** Open-Meteo's historical archive API is free and keyless. Pulling ~10
  years of daily max per location and computing the standard deviation of day-over-day change for
  that calendar window would auto-tune the temperature bar to roughly 4 °C on a Mediterranean
  coast and 11 °C in a high desert, with no user tuning at all. This is the single highest-value
  addition.
- **Multi-model rain probability.** ECMWF's own ensemble now supplies the chance (see "Rain
  data source"). A further step, still keyless: one Ensemble API request with
  `models=ecmwf_ifs025_ensemble,ecmwf_aifs025_ensemble` (+ `icon_eu_eps` while it lasts), hourly
  probability = share of members with >= 0.2 mm, amount = median, "up to" = 90th percentile. In
  the backtest, agreement across models was well calibrated, but ECMWF alone already scored
  almost as well (Brier 0.050 vs 0.043), so this is a refinement, not a fix.
- **IMS cross-check.** `https://ims.gov.il/en/full_forecast_data/{lid}` (175 points listed at
  `/en/locations_info`) returns hourly `rain_chance` (5 days) and `rain` mm. It is the
  forecaster-tuned local product but an undocumented website endpoint, not an API, so only worth
  using as a secondary "IMS says" line that can fail silently.
- **Per-location thresholds.** Currently global. Worth having if you watch both a coastal city and
  a desert site.

---

## Place search

Search runs in **English or Hebrew**, routed by script:

| input | geocoder | why |
|---|---|---|
| Latin | Open-Meteo | fast, unlimited, no key |
| Hebrew / Arabic / Cyrillic / Greek | OpenStreetMap Nominatim | Open-Meteo's `language` parameter covers only en/de/fr/es/it/pt/ru/tr/hi — typing `חדרה` into it returns nothing at all |

Nominatim asks for at most one request per second, so Hebrew queries use a 900 ms debounce against
350 ms for Latin, and send an identifying `User-Agent` as its usage policy requires. Duplicate OSM
objects for the same town (a node and a boundary, say) collapse via the coordinate-rounded id.

There is also a **"Use my location"** button. It uses the platform `LocationManager` rather than
Play Services, deliberately: many e-paper phones ship without Google services. Coarse permission
only — the forecast grid is ~11 km wide, so a fine fix buys nothing. It prefers a cached fix and
degrades to plain coordinates if the device has no reverse-geocoding backend.

### Forecast where you are

The 📍 button in the home screen's top bar opens the detail screen for the device's current
position, without adding it to the watched places. It is a look, not a subscription: nothing is
saved and nothing notifies. **Watch** in its top bar turns it into a watched place, reusing the
forecast already fetched. Unlike "Use my location", it accepts a cached fix only if it is under
10 minutes old (otherwise it waits up to 20 s for a fresh one, then falls back to the stale one),
because the last cached fix can be yesterday's, in another city. Alerts and advice for it come
from the current settings, like every card.

---

## Staying alive in the background

The failure mode for an alert app is specific and quiet. Android demotes apps you do not open
through ACTIVE → WORKING_SET → FREQUENT → **RARE**, and in RARE it defers background work to
roughly once a day. An alert app is by definition one you never open, so it decays straight into
the bucket that breaks it — while continuing to look fine.

Two guards:

- **A battery-optimisation banner** on the home screen when the app is not exempt, with a button
  that opens the system prompt (three fallback intents, since some builds hide the direct dialog).
  Exemption removes the app from that ladder entirely. Re-checked on every resume, because the
  system screen reports nothing back.
- **A stall watchdog** — a warning when the last successful check exceeds **2.5× the configured
  interval**. Deliberately generous: WorkManager's period is a floor and Doze batches jobs, so a
  3-hour schedule firing at 5 hours is normal, not broken.

### Why there is a permanent "Watching the forecast" notification

Neither guard above is enough on MediaTek phones that ship **DuraSpeed** (the Bigme HiBreak Pro,
and many budget devices). DuraSpeed sets the app's `RUN_ANY_IN_BACKGROUND` app-op to `ignore`,
which is what the system's "Unrestricted" battery option quietly unticking itself looks like.
Measured on a HiBreak Pro:

```
19:16:56  app leaves the screen
19:33:26  Killing com.weatheralert: cached idle & background restricted
19:33:26  Force stopping com.weatheralert            (10 ms later, by system_server)
```

A force-stop deletes every job WorkManager scheduled, so no check ran again until the app was
opened by hand. This happened after every single use, for three weeks.

`KeepAliveService` is a foreground service that does nothing but exist. DuraSpeed's own policy
(disassembled from `/system_ext/framework/duraspeed.jar`) marks every package with a process at
oom_adj ≤ 200 as protected and never restricts it, and a foreground-service process sits at exactly
200. The checks themselves still run through WorkManager. On the same phone, two other apps with
this arrangement went six weeks without a single kill.

It prevents the restriction; it cannot undo one. If the app-op is already `ignore` when the app
leaves the screen, Android strips the foreground status of background-restricted apps within
seconds and the kill follows (verified by applying the restriction by hand). That is why the
service starts on every process start and on boot, ahead of DuraSpeed's screen-off sweep.

Its notification channel is minimum importance: silent, no status-bar icon, folded to the bottom
of the shade. It can be swiped away on Android 13+ without stopping the service, or hidden for
good by turning off the "Background checks" category (long-press it). Verified on the HiBreak Pro:
with the channel at importance 0 the service stays foreground at oom_adj 200, which is all
DuraSpeed looks at. The alert channels are separate and unaffected.

---

## E Ink support

Auto-enabled on e-paper hardware (Bigme, Onyx/Boox, Boyue, Dasung, PocketBook, Meebook, Supernote,
reMarkable, Tolino, Mudita…), decided **once** so a manual override is never undone on the next
launch. Toggle lives in Settings → Display.

- **Severity is a shape, not a hue.** Red `#C62828` and amber `#E08700` sit 59 points apart in
  greyscale luminance — enough on an LCD, not enough after e-paper dithering. MAJOR is a filled
  block reading `!!`, NOTABLE a hollow one reading `!`; advice uses `NO` / `!` / `OK` the same way.
  Colour still renders on an LCD but never carries the meaning alone.
- **No sliders anywhere.** All ~30 thresholds are `− value +` steppers. Dragging on e-paper is a
  burst of full-panel refreshes for a value you cannot read until you release — and steppers are
  simply better for numbers like 5.5 °C on any screen. Values snap to the step so they do not
  drift to 5.4999999.
- **No animation.** The refresh spinner is the word "Checking…"; ripples are a no-op `Indication`.
- **Pure black on pure white**, every tonal grey removed and container roles overridden, since
  Material's tinted surfaces dither into visible stipple. Cards separate by border, not shadow.
- **Settings collapse into ten sections**, each showing its live values when closed, so the whole
  screen fits without a long scroll.
