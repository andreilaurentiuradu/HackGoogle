from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from brain import DecisionBrain
from processor import DataStreamProcessor
import json
import uvicorn
from datetime import datetime

app = FastAPI()
brain = DecisionBrain()
processor = DataStreamProcessor(brain)

# ──────────────────────────────────────────────
# WebSocket principal
# ──────────────────────────────────────────────

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
            except Exception:
                continue

            # ── Acțiuni inițiate de ceas ──────────────────
            if "action" in payload and "raw_sensors" not in payload:
                action = payload["action"]

                if action == "focus_start":
                    response = brain.pre_focus()
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "focus_ready":
                    response = brain.begin_focus(baseline_hr=processor.stats.hr)
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "focus_end":
                    response = brain.end_focus()
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "medication_taken":
                    brain.medication.log_intake(True, payload.get("time"))
                    await websocket.send_text(json.dumps({"status": "ok", "logged": "taken"}))

                elif action == "medication_skipped":
                    brain.medication.log_intake(False, payload.get("time"))
                    await websocket.send_text(json.dumps({"status": "ok", "logged": "skipped"}))

                continue

            # ── Date senzori normali ───────────────────────
            analysis_result = processor.add_data(payload)

            # Verifică reminder medicație la fiecare pachet
            med_alert = brain.medication.check_due()
            if med_alert:
                _log(med_alert)
                await websocket.send_text(json.dumps(med_alert))

            if analysis_result:
                _log(analysis_result)
                await websocket.send_text(json.dumps(analysis_result))
            else:
                await websocket.send_text(json.dumps({"status": "collecting"}))
                if pachete_count % 10 == 0:
                    print(".", end="", flush=True)

    except WebSocketDisconnect:
        print("\n[INFO] Ceas deconectat.")


# ──────────────────────────────────────────────
# REST endpoints ADHD
# ──────────────────────────────────────────────

@app.post("/focus/start")
async def focus_start():
    return brain.pre_focus()

@app.post("/focus/ready")
async def focus_ready():
    return brain.begin_focus(baseline_hr=processor.stats.hr)


@app.post("/focus/end")
async def focus_end():
    return brain.end_focus()


@app.post("/medication/schedule")
async def medication_schedule(body: dict):
    times = body.get("times", [])
    brain.medication.set_schedule(times)
    return {"status": "ok", "schedule": times}


@app.post("/medication/log")
async def medication_log(body: dict):
    brain.medication.log_intake(body.get("taken", False), body.get("time"))
    return {"status": "ok"}


@app.get("/report/daily")
async def daily_report(date: str = None):
    return brain.report_gen.generate(date)


# ──────────────────────────────────────────────

def _log(result: dict):
    print("\n" + "=" * 40)
    print(f" DECIZIE | {datetime.now().strftime('%H:%M:%S')}")
    print(f" STARE: {result.get('state', '?').upper()}")
    if result.get('action'):
        print(f" ACTIUNE: {result['action']}")
    print(f" MESAJ: {result.get('message', '')}")
    print("=" * 40)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
