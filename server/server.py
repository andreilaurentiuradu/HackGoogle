from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from datetime import datetime
import json

app = FastAPI()

@app.websocket("/ws/health")
async def health_ws(websocket: WebSocket):
    await websocket.accept()
    print("Watch connected")

    try:
        while True:
            data = await websocket.receive_text()

            try:
                payload = json.loads(data)
            except json.JSONDecodeError:
                print("Invalid JSON:", data)
                continue

            print("\n--- HEALTH DATA ---")
            print("Time:", datetime.now().isoformat())
            print(json.dumps(payload, indent=2))

            # aici ulterior bagi modelul ML
            response = {
                "state": "normal",
                "confidence": 0.75,
                "message": "Data received"
            }

            await websocket.send_text(json.dumps(response))

    except WebSocketDisconnect:
        print("Watch disconnected")