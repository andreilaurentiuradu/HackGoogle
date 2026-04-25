from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from brain import DecisionBrain
from processor import DataStreamProcessor
import json
import uvicorn
from datetime import datetime

app = FastAPI()
brain = DecisionBrain()
# Analizăm ultimele 10 secunde la fiecare 1 secundă
processor = DataStreamProcessor(brain, window_duration=20, process_interval=5)

@app.websocket("/ws/health")
async def health_ws(websocket: WebSocket):
    await websocket.accept()
    print(f"\n[CONEXIUNE] Ceas conectat de la: {websocket.client.host}")
    
    pachete_count = 0
    try:
        while True:
            raw_data = await websocket.receive_text()
            pachete_count += 1
            try:
                payload = json.loads(raw_data)
            except: continue

            analysis_result = processor.add_data(payload)

            if analysis_result:
                # AFISARE IN SERVER
                print("\n" + "="*40)
                print(f" DECIZIE | {datetime.now().strftime('%H:%M:%S')}")
                print(f" STARE: {analysis_result['state'].upper()}")
                print(f" MESAJ: {analysis_result['message']}")
                if "strobe_freq_hz" in analysis_result:
                    print(f" STROBOSCOP: {analysis_result['strobe_freq_hz']} Hz"
                          f" | Amplitudine: {analysis_result['light_amplitude_lux']} lux"
                          f" | HR mediu: {analysis_result['avg_heart_rate']} bpm")
                print(f" PACHETE IN BUFFER: {len(processor.buffer)}")
                print("="*40)
                
                await websocket.send_text(json.dumps(analysis_result))
            else:
                # Confirmare silențioasă pentru a menține fluxul
                await websocket.send_text(json.dumps({"status": "collecting"}))
                if pachete_count % 10 == 0: print(".", end="", flush=True)

    except WebSocketDisconnect:
        print("\n[INFO] Ceas deconectat.")

if __name__ == "__main__":
    # 0.0.0.0 permite conexiuni din rețeaua Wi-Fi
    uvicorn.run(app, host="0.0.0.0", port=8000)