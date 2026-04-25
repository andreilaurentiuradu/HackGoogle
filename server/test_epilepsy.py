"""
Test unitar pentru noua logica EpilepsyCondition + EpilepsyWindowBuffer.
Ruleaza fara server: python test_epilepsy.py
"""
import sys, os, time, math
sys.path.insert(0, os.path.dirname(__file__))

from processor import EpilepsyWindowBuffer, StreamingStats
from models import EpilepsyCondition

PASS = "✅"
FAIL = "❌"
results = []

def check(name, condition):
    status = PASS if condition else FAIL
    results.append((status, name))
    print(f"  {status}  {name}")


def make_window(light_vals, accel_z_vals, hr_vals, duration_s=20.0):
    """Construieste o fereastra sintetica de `duration_s` secunde."""
    n = len(light_vals)
    t0 = time.time() - duration_s
    step = duration_s / max(n - 1, 1)
    return [
        (t0 + i * step, light_vals[i], accel_z_vals[i], hr_vals[i])
        for i in range(n)
    ]


def make_stats(hr=70.0):
    s = StreamingStats()
    s.acc_mean = 9.8
    s.acc_variance = 0.0
    s.linear_acc_mean = 0.0
    s.linear_acc_variance = 0.0
    s.gyro_mean = 0.0
    s.gyro_variance = 0.0
    s.hr = hr
    s.light_diff = 0.0
    s.sample_count = 200
    return s


epilepsy = EpilepsyCondition()


# ─────────────────────────────────────────────────────────────────
# 1. Fereastra insuficienta → fallback normal
# ─────────────────────────────────────────────────────────────────
print("\n── 1. Fereastra insuficienta ────────────────────────")

r = epilepsy.analyze(make_stats(), {"_epilepsy_window": []})
check("Fereastra goala → normal", r["state"] == "normal")

r = epilepsy.analyze(make_stats(), {"_epilepsy_window": make_window([300]*10, [9.8]*10, [70]*10, 5.0)})
check("Fereastra cu 10 samplesuri, lumina stabila → normal", r["state"] == "normal")


# ─────────────────────────────────────────────────────────────────
# 2. Fara stroboscop (amplitudine mica)
# ─────────────────────────────────────────────────────────────────
print("\n── 2. Lumina stabila, fara stroboscop ───────────────")

lights_stable = [300.0] * 200
window = make_window(lights_stable, [9.8]*200, [70]*200)
r = epilepsy.analyze(make_stats(), {"_epilepsy_window": window})
check("Lumina constanta → normal", r["state"] == "normal")

lights_small_var = [290 + 5*(i % 2) for i in range(200)]  # amplitudine 5 lux
window = make_window(lights_small_var, [9.8]*200, [70]*200)
r = epilepsy.analyze(make_stats(), {"_epilepsy_window": window})
check("Amplitudine 5 lux (< 200) → normal", r["state"] == "normal")


# ─────────────────────────────────────────────────────────────────
# 3. Stroboscop 5 Hz, fara convulsii
# ─────────────────────────────────────────────────────────────────
print("\n── 3. Stroboscop 5 Hz ───────────────────────────────")
# 200 samples in 20s = 10 Hz sampling
# 5 Hz strobe: toggle la fiecare 2 samplesuri => 100 crossings => 100/(2*20) = 2.5 Hz
# Trebuie toggle la fiecare sample pentru 5 Hz: 200 crossings / (2*20) = 5 Hz
lights_5hz = [2000.0 if i % 2 == 0 else 50.0 for i in range(200)]
window = make_window(lights_5hz, [9.8]*200, [70]*200)
r = epilepsy.analyze(make_stats(hr=70), {"_epilepsy_window": window})
print(f"    → state={r['state']} freq={r.get('freq')} score={r.get('score')}")
check("Stroboscop 5 Hz, HR normal → epilepsy_warning sau alert", r["state"] in ("epilepsy_warning", "epilepsy_alert"))
check("Frecventa raportata ~5 Hz", r.get("freq") is not None and 4.0 <= r["freq"] <= 6.0)


# ─────────────────────────────────────────────────────────────────
# 4. Stroboscop 17.5 Hz + miscari Z + HR ridicat → alert
# ─────────────────────────────────────────────────────────────────
print("\n── 4. Epilepsy alert complet ────────────────────────")
# 17.5 Hz la 10 Hz sampling nu e posibil (aliasing), testam cu maxim posibil: toggle fiecare sample = 5 Hz
# Testam scorul direct: stroboscop in range + Z mare + HR > 100
lights_strobe = [2000.0 if i % 2 == 0 else 50.0 for i in range(200)]   # 5 Hz, amplitudine 1950
accel_z_seizure = [15.0 if i % 3 == 0 else 5.0 for i in range(200)]    # variatie Z = 10 m/s²
hr_high = [120.0] * 200
window = make_window(lights_strobe, accel_z_seizure, hr_high)
r = epilepsy.analyze(make_stats(hr=120), {"_epilepsy_window": window})
print(f"    → state={r['state']} freq={r.get('freq')} score={r.get('score')}")
check("Stroboscop + Z mare + HR 120 → epilepsy_alert", r["state"] == "epilepsy_alert")
check("Score >= 0.70", r.get("score", 0) >= 0.70)
check("Freq in [3, 30]", 3.0 <= r.get("freq", 0) <= 30.0)


# ─────────────────────────────────────────────────────────────────
# 5. Preictal: HR ridicat + lumina variabila, fara stroboscop clar
# ─────────────────────────────────────────────────────────────────
print("\n── 5. Preictal ──────────────────────────────────────")
lights_var = [300 + 30*math.sin(i*0.3) for i in range(200)]  # variatie 60 lux, sub 200
accel_z_calm = [9.8] * 200
hr_preictal = [98.0] * 200
window = make_window(lights_var, accel_z_calm, hr_preictal)
r = epilepsy.analyze(make_stats(hr=98), {"_epilepsy_window": window})
print(f"    → state={r['state']} freq={r.get('freq')} score={r.get('score')}")
check("HR 98 + lumina variabila fara stroboscop → epilepsy_preictal", r["state"] == "epilepsy_preictal")


# ─────────────────────────────────────────────────────────────────
# 6. EpilepsyWindowBuffer — trimming corect
# ─────────────────────────────────────────────────────────────────
print("\n── 6. EpilepsyWindowBuffer ──────────────────────────")
buf = EpilepsyWindowBuffer()
for i in range(300):
    buf.add(light=float(i), accel_z=9.8, heart_rate=70.0)

check("Buffer pastreaza max ~WINDOW_SECONDS de samplesuri (nu 300)", buf.count < 300)
check("Durata buffer <= 20.1s", buf.duration <= 20.1)
check("Buffer nu e gol", buf.count > 0)


# ─────────────────────────────────────────────────────────────────
# 7. Calcul frecventa strobe (formula zero-crossing)
# ─────────────────────────────────────────────────────────────────
print("\n── 7. Formula zero-crossing ─────────────────────────")
# 200 crossings / (2 * 20s) = 5 Hz
lights_exact = [2000.0 if i % 2 == 0 else 50.0 for i in range(201)]
duration = 20.0
amplitude = 1950.0
freq = EpilepsyCondition._strobe_frequency(lights_exact, duration, amplitude)
print(f"    → freq calculata: {freq:.2f} Hz")
check("200 crossings / 40s = 5.0 Hz", abs(freq - 5.0) < 0.1)

freq_zero = EpilepsyCondition._strobe_frequency(lights_exact, duration, amplitude=100.0)
check("Amplitudine < 200 → freq = 0.0", freq_zero == 0.0)


# ─────────────────────────────────────────────────────────────────
print("\n" + "═" * 52)
passed = sum(1 for s, _ in results if s == PASS)
failed = sum(1 for s, _ in results if s == FAIL)
print(f"  Rezultat: {passed}/{len(results)} teste trecute")
if failed:
    print(f"\n  Esecuri:")
    for s, name in results:
        if s == FAIL:
            print(f"    {FAIL} {name}")
print("═" * 52)
sys.exit(0 if failed == 0 else 1)
