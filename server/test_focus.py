import asyncio
import websockets
import json
import random
import time
import requests

SERVER_HTTP = "http://localhost:8000"
SERVER_WS   = "ws://localhost:8000/ws/health"

def http_post(path, body=None):
    r = requests.post(f"{SERVER_HTTP}{path}", json=body or {})
    return r.json()

async def send(ws, accel, hr=72, label="", gyro=None, activity="USER_ACTIVITY_PASSIVE", light=300.0):
    payload = {
        "health_services": {
            "heart_rate": float(hr),
            "activity_state": activity,
        },
        "raw_sensors": {
            "accelerometer": accel,
            "gyroscope": gyro or [0.0, 0.0, 0.0],
            "light": [light],
        },
        "battery": {"level_percent": 85.0, "is_charging": False},
        "device": {"source": "pixel_watch", "timestamp": int(time.time() * 1000)},
    }
    await ws.send(json.dumps(payload))
    raw = await ws.recv()
    msg = json.loads(raw)
    state = msg.get("state", msg.get("status", "?"))
    if state not in ("collecting", "normal"):
        action = msg.get("action", "")
        print(f"  [{label}] ► {state}  action={action}  {msg.get('message','')}")
    return msg

async def run():
    print("=" * 50)
    print("TEST: Focus session cu spike-uri ADHD")
    print("=" * 50)

    async with websockets.connect(SERVER_WS) as ws:

        # ── 1. Inițiem exercițiul de respirație ───────────
        print("\n[1] POST /focus/start  (pre-focus ritual)")
        resp = http_post("/focus/start")
        print(f"    → {resp}")
        print(f"    [simulez 3 respirații: {resp.get('breaths')}x inhale {resp.get('inhale_ms')}ms / hold {resp.get('hold_ms')}ms / exhale {resp.get('exhale_ms')}ms]")

        # ── 1b. User a terminat respirațiile → "Gata ✓" ─
        import asyncio as _a; await _a.sleep(1)  # simulăm timpul de respirație
        print("\n[1b] POST /focus/ready  (user a confirmat 'Gata ✓')")
        resp2 = http_post("/focus/ready")
        print(f"    → {resp2}")

        # ── 2. Date calme — în focus (15s) ───────────────
        print("\n[2] Date calme (15s) — ar trebui să rămână în focus...")
        for _ in range(150):  # 150 pachete * 0.1s = 15s
            await send(ws, [random.uniform(-0.3, 0.3), -6.0, 9.8], hr=68, label="CALM")
            await asyncio.sleep(0.1)

        # ── 3. Spike #1 — agitație puternică ─────────────
        print("\n[3] SPIKE #1 — agitație motorie (5s)...")
        for _ in range(50):
            await send(ws,
                accel=[random.uniform(-9.0, 9.0), -6.0, random.uniform(5.0, 14.0)],
                gyro=[random.uniform(-3.0, 3.0), random.uniform(-2.0, 2.0), random.uniform(-1.0, 1.0)],
                hr=90, activity="IN_VEHICLE", label="SPIKE1")
            await asyncio.sleep(0.1)

        # ── 4. Revenire la calm (10s) ────────────────────
        print("\n[4] Revenire calm (10s)...")
        for _ in range(100):
            await send(ws, [random.uniform(-0.2, 0.2), -6.0, 9.8], hr=70, label="CALM")
            await asyncio.sleep(0.1)

        # ── 5. Spike #2 ───────────────────────────────────
        print("\n[5] SPIKE #2 — agitație motorie (5s)...")
        for _ in range(50):
            await send(ws,
                accel=[random.uniform(-8.0, 8.0), -6.0, random.uniform(4.0, 13.0)],
                gyro=[random.uniform(-2.5, 2.5), random.uniform(-1.5, 1.5), random.uniform(-0.8, 0.8)],
                hr=95, activity="IN_VEHICLE", label="SPIKE2")
            await asyncio.sleep(0.1)

        # ── 6. Calm final (10s) ───────────────────────────
        print("\n[6] Calm final (10s)...")
        for _ in range(100):
            await send(ws, [random.uniform(-0.3, 0.3), -6.0, 9.8], hr=68, label="CALM")
            await asyncio.sleep(0.1)

        # ── 7. Terminăm sesiunea ──────────────────────────
        print("\n[7] POST /focus/end")
        report = http_post("/focus/end")
        print(f"\n{'='*50}")
        print(f"  RAPORT FINAL:")
        print(f"  Durată:          {report.get('duration_minutes')} minute")
        print(f"  Calitate:        {report.get('quality_percent')}%")
        print(f"  Ieșiri din focus:{report.get('exits_count')}")
        print(f"  Stabilitate ACC: {report.get('acc_stability_percent')}%")
        print(f"  Variabilitate HR:{report.get('hr_variability')}")
        print(f"  Mesaj:           {report.get('message')}")
        print("=" * 50)

if __name__ == "__main__":
    asyncio.run(run())
