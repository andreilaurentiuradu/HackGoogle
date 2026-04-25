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
    uri = "ws://localhost:8001/ws/health"
    try:
        async with websockets.connect(uri) as websocket:
            print("--- START TEST 10Hz ---")

            print("\n>>> Faza 1: NORMAL (Așteaptă 10s...)")
            for _ in range(100): # 100 pachete * 0.1s = 10 secunde
                await send_payload(websocket, "NORMAL", 72, [0.1, -6.0, 9.8], 300)
                await asyncio.sleep(0.1)

            print("\n>>> Faza 2: ADHD (Agitație X/Z)")
            for _ in range(100):
                accel_adhd = [random.uniform(-10.0, 10.0), -6.0, random.uniform(5.0, 15.0)]
                await send_payload(websocket, "ADHD", 95, accel_adhd, 300)
                await asyncio.sleep(0.1)

            print("\n>>> Faza 3: EPILEPSIE (Lumină rapidă)")
            for i in range(100):
                light_strobe = 2000 if i % 4 < 2 else 100 # Schimbă lumina la fiecare 0.2s
                accel_seizure = [random.uniform(-2.0, 2.0), -6.0, random.uniform(12.0, 18.0)]
                await send_payload(websocket, "EPILEPSY", 120, accel_seizure, light_strobe)
                await asyncio.sleep(0.1)

    except Exception as e:
        print(f"Eroare: {e}")

if __name__ == "__main__":
    asyncio.run(simulate_watch())