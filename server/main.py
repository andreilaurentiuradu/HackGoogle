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
            if pachete_count % 5 == 0:
                s = processor.stats
                print(f"  [#{pachete_count}] lin_var={s.linear_acc_variance:.3f} gyro={s.gyro_mean:.3f} HR={s.hr:.0f} samples={s.sample_count}")
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
    d = result.get("_debug", {})
    state = result.get("state", "?").upper()

    print("\n" + "=" * 48)
    print(f" VERDICT | {datetime.now().strftime('%H:%M:%S')}")
    print(f" STARE:   {state}")
    if result.get("action"):
        print(f" ACTIUNE: {result['action']}")
    print(f" MESAJ:   {result.get('message', '')}")

    if d:
        print(f" ┌─ Analiză ──────────────────────────────")
        print(f" │  linear_acc_var = {d.get('lin_var', '?'):<8}  (fidgeting: 0.05–0.5, mers: >1.0)")
        print(f" │  gyro_mean      = {d.get('gyro_mean', '?'):<8}  (rotație: >0.2 = mișcă, >0.5 = mare)")
        print(f" │  gyro_var       = {d.get('gyro_var', '?'):<8}  (repetitiv: <0.3)")
        print(f" │  HR             = {d.get('hr', '?'):<8}  ", end="")
        if d.get("focus_active") and d.get("baseline_hr"):
            diff = round(d["hr"] - d["baseline_hr"], 1)
            sign = "+" if diff >= 0 else ""
            print(f"(baseline {d['baseline_hr']} → {sign}{diff} BPM)")
        else:
            print(f"(anxietate: >110)")
        if d.get("light_diff", 0) > 0:
            print(f" │  light_diff    = {d.get('light_diff', '?'):<8}  (epilepsie: >200)")
        print(f" │  samples        = {d.get('samples', '?')}")
        if d.get("focus_active"):
            print(f" │  focus activ    = DA  (exits: {result.get('exits_count', 0)})")
        print(f" └────────────────────────────────────────")
    print("=" * 48)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
