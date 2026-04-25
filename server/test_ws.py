import asyncio
import json
import websockets

async def main():
    uri = "ws://10.200.22.124:8000/ws/health"

    async with websockets.connect(uri) as websocket:
        payload = {
            "heart_rate": 77,
            "steps_daily": 96,
            "calories_daily": 5.7,
            "distance_daily": 120,
            "floors_daily": 0,
            "activity_state": "ACTIVE",
            "timestamp": 123456789
        }

        await websocket.send(json.dumps(payload))
        print("Sent:", payload)

        response = await websocket.recv()
        print("Received:", response)

asyncio.run(main())