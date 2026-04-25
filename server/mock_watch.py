import asyncio
import websockets
import json
import random
import time

async def send_payload(websocket, scenario_name, heart_rate, accel, light):
    payload = {
        "heart_rate": float(heart_rate),
        "accelerometer": accel,
        "gyroscope": [0.0, 0.0, 0.0],
        "light": float(light),
        "timestamp": int(time.time() * 1000)
    }
    await websocket.send(json.dumps(payload))
    # Nu printăm la fiecare 0.1s ca să nu umplem ecranul, doar la decizii
    
    # Așteptăm răspunsul (va fi ori 'collecting', ori decizia)
    response = await websocket.recv()
    res_json = json.loads(response)
    if "state" in res_json:
        print(f"\n[DECIZIE SERVER] Scenariu: {scenario_name} -> Stare: {res_json['state']} ({res_json['message']})")

async def simulate_watch():
    uri = "ws://localhost:8000/ws/health"
    try:
        async with websockets.connect(uri) as websocket:
            print("--- START TEST 10Hz ---")

            print("\n>>> Faza 1: NORMAL (10s)")
            for _ in range(100):
                await send_payload(websocket, "NORMAL", 72, [0.1, -6.0, 9.8], 300)
                await asyncio.sleep(0.1)

            print("\n>>> Faza 2: ADHD (Agitatie X/Z)")
            for _ in range(100):
                accel_adhd = [random.uniform(-10.0, 10.0), -6.0, random.uniform(5.0, 15.0)]
                await send_payload(websocket, "ADHD", 95, accel_adhd, 300)
                await asyncio.sleep(0.1)

            # Faza intermediara: puls crescut + palpaire usoara de lumina (pre-ictal)
            print("\n>>> Faza 2.5: PRE-CRITICA (Puls crescut + lumina inconsistenta)")
            for i in range(50):
                # Palpaiere lenta ~1.25 Hz — sub zona periculoasa, dar HR creste
                light_pre = 300 + (180 if i % 8 < 4 else 0)
                hr_rising = int(88 + i * 0.24)  # 88 -> 100 bpm
                accel_pre = [random.uniform(-1.5, 1.5), -6.0, random.uniform(8.5, 11.5)]
                await send_payload(websocket, "PRE-CRITICA", hr_rising, accel_pre, light_pre)
                await asyncio.sleep(0.1)

            # Faza 3: stroboscop la 5 Hz (zona periculoasa 3-30 Hz) + convulsii
            print("\n>>> Faza 3: EPILEPSIE (Stroboscop 5 Hz + convulsii)")
            for i in range(100):
                # Alternare fiecare pachet (0.1s) => perioada 0.2s => 5 Hz
                light_strobe = 2000 if i % 2 == 0 else 50
                accel_seizure = [random.uniform(-3.0, 3.0), -6.0, random.uniform(11.0, 19.0)]
                await send_payload(websocket, "EPILEPSIE", 125, accel_seizure, light_strobe)
                await asyncio.sleep(0.1)

    except Exception as e:
        print(f"Eroare: {e}")

if __name__ == "__main__":
    asyncio.run(simulate_watch())