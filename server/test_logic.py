"""
Teste unitare pentru logica ADHD — rulează fără server.
python test_logic.py
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from processor import StreamingStats
from models import ADHDCondition, AnxietyCondition
from brain import DecisionBrain

PASS = "✅"
FAIL = "❌"
results = []

def check(name, condition):
    status = PASS if condition else FAIL
    results.append((status, name))
    print(f"  {status}  {name}")


# ─────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────

def make_stats(lin_var=0.0, gyro_mean=0.0, gyro_var=0.0, hr=70.0, acc_var=0.0, light_diff=0.0):
    s = StreamingStats()
    s.acc_mean            = 9.8
    s.acc_variance        = acc_var
    s.linear_acc_mean     = 0.0
    s.linear_acc_variance = lin_var
    s.gyro_mean           = gyro_mean
    s.gyro_variance       = gyro_var
    s.hr                  = hr
    s.light_diff          = light_diff
    s.sample_count        = 20
    return s

RAW_EMPTY = {}


# ─────────────────────────────────────────────────────────
# 1. Fidgeting detection
# ─────────────────────────────────────────────────────────
print("\n── 1. Fidgeting Detection ──────────────────────────")
adhd = ADHDCondition()

r = adhd.analyze(make_stats(lin_var=0.01, gyro_mean=0.1, gyro_var=0.1), RAW_EMPTY)
check("Calm → normal", r["state"] == "normal")

r = adhd.analyze(make_stats(lin_var=0.1, gyro_mean=0.25, gyro_var=0.1), RAW_EMPTY)
check("Fidgeting ușor (severity<0.3) → adhd_fidgeting_mild", r["state"] == "adhd_fidgeting_mild")

r = adhd.analyze(make_stats(lin_var=0.25, gyro_mean=0.4, gyro_var=0.2), RAW_EMPTY)
check("Fidgeting moderat (severity 0.3-0.6) → adhd_fidgeting", r["state"] == "adhd_fidgeting")
check("Fidgeting moderat → are action vibrate_anchor", r.get("action") == "vibrate_anchor")

r = adhd.analyze(make_stats(lin_var=0.45, gyro_mean=0.6, gyro_var=0.15), RAW_EMPTY)
check("Fidgeting puternic (severity>0.6) → adhd_fidgeting_strong", r["state"] == "adhd_fidgeting_strong")
check("Fidgeting puternic → mesaj motivațional", "🎯" in r.get("message", ""))

r = adhd.analyze(make_stats(lin_var=1.5, gyro_mean=1.2, gyro_var=0.5), RAW_EMPTY)
check("Mers (lin_var>1.0) → normal", r["state"] == "normal")

r = adhd.analyze(make_stats(lin_var=0.2, gyro_mean=0.1, gyro_var=0.1), RAW_EMPTY)
check("Mișcare mică fără rotație gyro → normal", r["state"] == "normal")

r = adhd.analyze(make_stats(lin_var=0.2, gyro_mean=0.4, gyro_var=0.8), RAW_EMPTY)
check("Mișcare neregulată (gyro_var>0.3) → normal (nu repetitiv)", r["state"] == "normal")


# ─────────────────────────────────────────────────────────
# 2. Focus exit detection
# ─────────────────────────────────────────────────────────
print("\n── 2. Focus Exit Detection ─────────────────────────")
brain = DecisionBrain()
brain.focus.start(baseline_hr=70.0)

# Exit prin mișcare + rotație încheietură
s = make_stats(lin_var=0.4, gyro_mean=0.6, hr=72.0)
check("Exit: mișcare + rotație → is_focus_exit True",
      brain._is_focus_exit(s, is_fidgeting=False))

# Exit prin HR crescut
s = make_stats(lin_var=0.02, gyro_mean=0.1, hr=80.0)
check("Exit: HR +10 față de baseline → is_focus_exit True",
      brain._is_focus_exit(s, is_fidgeting=False))

# Exit prin fidgeting moderat
s = make_stats(lin_var=0.05, gyro_mean=0.15, hr=71.0)
check("Exit: fidgeting contribuie → is_focus_exit True",
      brain._is_focus_exit(s, is_fidgeting=True))

# Nu exit — calm
s = make_stats(lin_var=0.02, gyro_mean=0.1, hr=70.0)
check("Nu exit: calm, HR normal → is_focus_exit False",
      not brain._is_focus_exit(s, is_fidgeting=False))

# Nu exit — mers (activitate fizică)
s = make_stats(lin_var=2.5, gyro_mean=1.5, hr=95.0)
check("Nu exit: mers (lin_var>2.0) → is_focus_exit False",
      not brain._is_focus_exit(s, is_fidgeting=False))

# HR crescut DAR mers → nu exit
s = make_stats(lin_var=2.5, gyro_mean=1.2, hr=85.0)
check("Nu exit: HR crescut + mers → is_physical_activity blochează",
      not brain._is_focus_exit(s, is_fidgeting=False))


# ─────────────────────────────────────────────────────────
# 3. Fidgeting în sesiune focus → fără alert separat
# ─────────────────────────────────────────────────────────
print("\n── 3. Edge case: fidgeting în focus ────────────────")
brain2 = DecisionBrain()
brain2.focus.start(baseline_hr=70.0)

# Fidgeting slab în focus → nu exit, nu alert separat
s_mild = make_stats(lin_var=0.1, gyro_mean=0.25, gyro_var=0.1, hr=71.0)
result = brain2.process_stats(s_mild, RAW_EMPTY)
check("Fidgeting ușor în focus → state normal (fără alert separat)",
      result["state"] in ("normal", "focus_exit"))

# Fidgeting puternic în focus → focus_exit (nu adhd_fidgeting_strong separat)
brain3 = DecisionBrain()
brain3.focus.start(baseline_hr=70.0)
s_strong = make_stats(lin_var=0.45, gyro_mean=0.6, gyro_var=0.15, hr=71.0)
result = brain3.process_stats(s_strong, RAW_EMPTY)
check("Fidgeting puternic în focus → focus_exit (nu alert fidgeting separat)",
      result["state"] == "focus_exit")


# ─────────────────────────────────────────────────────────
# 4. Anxiety detection
# ─────────────────────────────────────────────────────────
print("\n── 4. Anxiety Detection ────────────────────────────")
anxiety = AnxietyCondition()

r = anxiety.analyze(make_stats(hr=75.0), RAW_EMPTY)
check("HR normal (75) → normal", r["state"] == "normal")

r = anxiety.analyze(make_stats(hr=115.0), RAW_EMPTY)
check("HR ridicat (115) → anxiety_alert", r["state"] == "anxiety_alert")


# ─────────────────────────────────────────────────────────
# 5. StreamingStats — EMA se actualizează corect
# ─────────────────────────────────────────────────────────
print("\n── 5. StreamingStats EMA ───────────────────────────")
import time, math

def make_packet(lin_acc, gyro, hr=70.0):
    return {
        "raw_sensors": {
            "accelerometer": [0.0, 0.0, 9.8],
            "linear_acceleration": lin_acc,
            "gyroscope": gyro,
            "light": [300.0],
        },
        "health_services": {"heart_rate": hr},
        "device": {"timestamp": int(time.time() * 1000)},
    }

def feed(stats, packet, dt=1.0):
    """Injectează un pachet cu un dt simulat (fără sleep real)."""
    stats._last_time = time.time() - dt
    stats.update(packet)

stats = StreamingStats()
for _ in range(30):
    feed(stats, make_packet([0.0, 0.0, 0.0], [0.0, 0.0, 0.0], hr=70.0), dt=1.0)

check("După 30 pachete calme: linear_acc_variance ≈ 0", stats.linear_acc_variance < 0.01)
check("După 30 pachete calme: gyro_variance ≈ 0", stats.gyro_variance < 0.01)
check("HR EMA converge spre 70", abs(stats.hr - 70.0) < 2.0)

for _ in range(20):
    feed(stats, make_packet([0.3, -0.2, 0.1], [0.4, -0.3, 0.2], hr=70.0), dt=1.0)

check("După mișcare: linear_acc_variance crescut", stats.linear_acc_variance > 0.01)
check("După mișcare: gyro_mean crescut", stats.gyro_mean > 0.1)


# ─────────────────────────────────────────────────────────
# Sumar
# ─────────────────────────────────────────────────────────
print("\n" + "═" * 50)
passed = sum(1 for s, _ in results if s == PASS)
failed = sum(1 for s, _ in results if s == FAIL)
print(f"  Rezultat: {passed}/{len(results)} teste trecute")
if failed:
    print(f"\n  Eșuate:")
    for s, name in results:
        if s == FAIL:
            print(f"    {FAIL} {name}")
print("═" * 50)
sys.exit(0 if failed == 0 else 1)
