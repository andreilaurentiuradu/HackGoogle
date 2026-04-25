import asyncio
import websockets
import json
import numpy as np

# ── Date mock care simuleaza ce trimite ceasul ─────────
def generate_mock_data(state="stress"):
    """
    Genereaza date sintetice pentru testare
    state: 'stress', 'neutral', 'meditation'
    """
    if state == "stress":
        hr_base = 95
        eda_base = 7.0
    elif state == "meditation":
        hr_base = 58
        eda_base = 1.8
    else:
        hr_base = 70
        eda_base = 2.5

    return {
        "user_id": "test_user",
        "eda":  (np.random.normal(eda_base, 0.5, 60)).tolist(),
        "bvp":  (np.random.normal(hr_base, 5, 240)).tolist(),
        "acc":  (np.random.normal(0, 0.1, (480, 3))).tolist(),
        "temp": (np.random.normal(36.0, 0.2, 60)).tolist()
    }

async def test_websocket():
    uri = "ws://localhost:8001/ws/health"

    print(f"Conectare la {uri}...\n")

    async with websockets.connect(uri) as ws:

        # Testeaza 3 stari
        for state in ["neutral", "stress", "meditation"]:
            print(f"{'='*40}")
            print(f"Test: {state.upper()}")
            print(f"{'='*40}")

            data = generate_mock_data(state)
            await ws.send(json.dumps(data))

            response = await ws.recv()
            result   = json.loads(response)

            print(f"Stare detectata: {result['state']}")
            print(f"Confidence:      {result['confidence']:.0%}")
            print(f"Model mode:      {result['model_mode']}")
            print(f"Interventie:     {result['intervention']['type']}")
            print(f"Probabilitati:")
            for cls, prob in result['probabilities'].items():
                bar = '█' * int(prob * 20)
                print(f"  {cls:10s}: {prob:.0%} {bar}")
            print()

            await asyncio.sleep(1)

if __name__ == "__main__":
    asyncio.run(test_websocket())