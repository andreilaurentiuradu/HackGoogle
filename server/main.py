from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from brain import DecisionBrain
from processor import DataStreamProcessor

import json
import uvicorn
from datetime import datetime
import os
import time

from dotenv import load_dotenv

try:
    import google.generativeai as genai
except Exception:
    genai = None


# ──────────────────────────────────────────────
# ENV + Gemini setup
# ──────────────────────────────────────────────

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

print("[ENV] cwd =", os.getcwd())
print("[ENV] GEMINI_API_KEY exists =", bool(GEMINI_API_KEY))
print("[ENV] genai imported =", genai is not None)

gemini_model = None

if genai is not None and GEMINI_API_KEY:
    try:
        genai.configure(api_key=GEMINI_API_KEY)
        gemini_model = genai.GenerativeModel("gemini-2.5-flash")
        print("[GEMINI] Enabled")
    except Exception as e:
        print(f"[GEMINI] Failed to initialize: {e}")
        gemini_model = None
else:
    print("[GEMINI] Disabled. Missing GEMINI_API_KEY or package.")


# ──────────────────────────────────────────────
# App setup
# ──────────────────────────────────────────────

app = FastAPI()
brain = DecisionBrain()
processor = DataStreamProcessor(brain)

connected_clients = set()

demo_anxiety = {
    "active": False,
    "hr": 135.0,
    "until": 0.0,
    "started_at": 0.0
}


# ──────────────────────────────────────────────
# Demo Anxiety / Fake HR helpers
# ──────────────────────────────────────────────

def get_demo_hr():
    if not demo_anxiety["active"]:
        return None

    now = time.time()

    if demo_anxiety["until"] > 0 and now > demo_anxiety["until"]:
        demo_anxiety["active"] = False
        demo_anxiety["until"] = 0.0
        print("[DEMO ANXIETY] Stopped automatically")
        return None

    return float(demo_anxiety["hr"])


def apply_demo_hr_to_sensor_payload(payload: dict) -> dict:
    demo_hr = get_demo_hr()

    if demo_hr is None:
        return payload

    payload = dict(payload)

    health_services = dict(payload.get("health_services") or {})
    health_services["heart_rate"] = demo_hr

    payload["health_services"] = health_services
    payload["_demo_fake_hr"] = True

    return payload


def apply_demo_hr_to_action_payload(payload: dict) -> dict:
    demo_hr = get_demo_hr()

    if demo_hr is None:
        return payload

    action = payload.get("action")

    if action in {
        "anxiety_checkin_start",
        "anxiety_answer",
        "anxiety_voice_answer"
    }:
        payload = dict(payload)
        payload["heart_rate"] = demo_hr

    return payload


async def broadcast_to_watches(message: dict):
    dead_clients = []

    for ws in list(connected_clients):
        try:
            await ws.send_text(json.dumps(message))
        except Exception:
            dead_clients.append(ws)

    for ws in dead_clients:
        connected_clients.discard(ws)


# ──────────────────────────────────────────────
# Demo REST endpoints
# ──────────────────────────────────────────────

@app.post("/demo/anxiety/start")
async def demo_anxiety_start(hr: float = 145.0, seconds: int = 60):
    """
    Pornește demo-ul de anxietate:
    - injectează puls fals mare în payload-urile primite de la ceas
    - trimite imediat anxiety_alert către ceas
    """

    now = time.time()

    demo_anxiety["active"] = True
    demo_anxiety["hr"] = float(hr)
    demo_anxiety["started_at"] = now
    demo_anxiety["until"] = now + seconds if seconds > 0 else 0.0

    response = {
        "state": "anxiety_alert",
        "message": f"Demo: puls ridicat detectat ({int(hr)} BPM). Hai să facem un exercițiu de grounding.",
        "_debug": {
            "demo_fake_hr": True,
            "hr": hr,
            "seconds": seconds
        }
    }

    print("\n[DEMO ANXIETY] START")
    print(f" Fake HR: {hr}")
    print(f" Duration: {seconds}s")

    _log(response)

    await broadcast_to_watches(response)

    return {
        "status": "ok",
        "demo": "anxiety",
        "active": True,
        "fake_hr": hr,
        "seconds": seconds,
        "connected_watches": len(connected_clients)
    }


@app.post("/demo/anxiety/stop")
async def demo_anxiety_stop():
    demo_anxiety["active"] = False
    demo_anxiety["until"] = 0.0

    print("\n[DEMO ANXIETY] STOP")

    return {
        "status": "ok",
        "demo": "anxiety",
        "active": False
    }


@app.get("/demo/anxiety/status")
async def demo_anxiety_status():
    demo_hr = get_demo_hr()

    remaining = 0
    if demo_anxiety["active"] and demo_anxiety["until"] > 0:
        remaining = max(0, int(demo_anxiety["until"] - time.time()))

    return {
        "active": demo_anxiety["active"],
        "fake_hr": demo_hr,
        "remaining_seconds": remaining,
        "connected_watches": len(connected_clients)
    }


# ──────────────────────────────────────────────
# WebSocket principal
# ──────────────────────────────────────────────

@app.websocket("/ws/health")
async def health_ws(websocket: WebSocket):
    await websocket.accept()
    connected_clients.add(websocket)

    print(f"\n[CONEXIUNE] Ceas conectat de la: {websocket.client.host}")

    pachete_count = 0
    checkin_active = False

    try:
        while True:
            raw_data = await websocket.receive_text()
            pachete_count += 1

            try:
                payload = json.loads(raw_data)
            except Exception:
                continue

            payload = apply_demo_hr_to_action_payload(payload)

            # ── Acțiuni inițiate de ceas ──────────────────
            if "action" in payload and "raw_sensors" not in payload:
                action = payload["action"]

                if action == "focus_start":
                    checkin_active = False
                    response = brain.pre_focus()
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "focus_ready":
                    response = brain.begin_focus(baseline_hr=processor.stats.hr)
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "focus_end":
                    checkin_active = False
                    response = brain.end_focus()
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "medication_taken":
                    brain.medication.log_intake(True, payload.get("time"))
                    await websocket.send_text(
                        json.dumps({
                            "status": "ok",
                            "logged": "taken"
                        })
                    )

                elif action == "medication_skipped":
                    brain.medication.log_intake(False, payload.get("time"))
                    await websocket.send_text(
                        json.dumps({
                            "status": "ok",
                            "logged": "skipped"
                        })
                    )

                elif action == "anxiety_checkin_start":
                    checkin_active = True
                    response = await start_anxiety_checkin(payload)
                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "anxiety_answer":
                    response = await next_anxiety_step(payload)

                    if response.get("state") in {
                        "anxiety_checkin_done",
                        "start_breathing",
                        "notify_contact"
                    }:
                        checkin_active = False

                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "anxiety_voice_answer":
                    checkin_active = True
                    response = await next_voice_grounding_step(payload)

                    if response.get("state") in {
                        "anxiety_checkin_done",
                        "start_breathing",
                        "notify_contact"
                    }:
                        checkin_active = False

                    _log(response)
                    await websocket.send_text(json.dumps(response))

                elif action == "set_profile":
                    profile = payload.get("profile", "adhd")

                    if profile not in {"adhd", "epilepsy"}:
                        profile = "adhd"

                    if hasattr(brain, "set_profile"):
                        brain.set_profile(profile)
                        print(f"\n[PROFIL] Schimbat la: {profile.upper()}")
                    else:
                        print(f"\n[PROFIL] Primit profil {profile}, dar brain.set_profile nu există.")

                    await websocket.send_text(
                        json.dumps({
                            "state": "profile_set",
                            "profile": profile,
                            "message": f"Profil activ: {profile}"
                        })
                    )

                continue

            # ── Date senzori normale ───────────────────────
            payload = apply_demo_hr_to_sensor_payload(payload)

            if pachete_count % 5 == 0:
                s = processor.stats
                fake = " DEMO_HR" if payload.get("_demo_fake_hr") else ""
                print(
                    f"  [#{pachete_count}{fake}] "
                    f"lin_var={s.linear_acc_variance:.3f} "
                    f"gyro={s.gyro_mean:.3f} "
                    f"HR={s.hr:.0f} "
                    f"samples={s.sample_count}"
                )

            analysis_result = processor.add_data(payload)

            # Dacă suntem deja în conversația de anxietate, NU mai trimitem NORMAL peste UI.
            if checkin_active:
                if pachete_count % 10 == 0:
                    print("[CHECK-IN] Activ. Ignor răspunsurile normale ca să nu acopăr UI-ul.")
                continue

            # Verifică reminder medicație la fiecare pachet
            try:
                med_alert = brain.medication.check_due()
            except Exception:
                med_alert = None

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

    finally:
        connected_clients.discard(websocket)


# ──────────────────────────────────────────────
# Anxiety Check-in + Voice Grounding + Gemini
# ──────────────────────────────────────────────

async def start_anxiety_checkin(payload: dict) -> dict:
    """
    Primul pas al modului de anxietate.
    Pornim un exercițiu vocal de grounding.
    """
    heart_rate = payload.get("heart_rate", 0)
    source_state = payload.get("source_state", "anxiety_alert")

    print("[DEBUG] gemini_model =", gemini_model)

    if gemini_model is not None:
        try:
            print("[GEMINI] Starting voice grounding...")
            response = await gemini_start_voice_grounding(
                heart_rate=heart_rate,
                source_state=source_state
            )
            response = normalize_voice_question(response)
            print("[GEMINI] Response:", response)
            return response
        except Exception as e:
            print("[GEMINI START ERROR]", repr(e))

    print("[FALLBACK] Using hardcoded voice grounding start")
    return {
        "state": "anxiety_voice_question",
        "question_id": "grounding_see",
        "question": "Spune-mi trei lucruri pe care le vezi în jur.",
        "positive": "Da",
        "negative": "Skip",
        "message": "Începem un exercițiu de grounding."
    }


async def gemini_start_voice_grounding(heart_rate, source_state) -> dict:
    prompt = f"""
You are a calm smartwatch grounding assistant.

Context:
- Triggered state: {source_state}
- Heart rate: {heart_rate}

Start a short voice-based grounding exercise.

Return STRICT JSON only, no markdown, no explanation.

Schema:
{{
  "state": "anxiety_voice_question",
  "question_id": "grounding_see",
  "question": "...",
  "positive": "Da",
  "negative": "Skip",
  "message": "..."
}}

Rules:
- Use Romanian.
- Ask the user to name 3 things they can see around them.
- Keep it short, suitable for a small smartwatch screen.
- Warm tone.
- Do not diagnose.
- Do not mention AI.
"""

    response = gemini_model.generate_content(prompt)
    return _parse_gemini_json(response.text)


async def next_voice_grounding_step(payload: dict) -> dict:
    """
    Continuă conversația vocală:
    grounding_see -> grounding_hear -> grounding_touch -> done
    Gemini primește transcriptul liber al utilizatorului.
    """
    if gemini_model is not None:
        try:
            question_id = payload.get("question_id", "grounding")
            question = payload.get("question", "")
            transcript = payload.get("transcript", "")
            heart_rate = payload.get("heart_rate", 0)

            print("\n[VOICE ANSWER]")
            print(f" Question id: {question_id}")
            print(f" Question:    {question}")
            print(f" Transcript:  {transcript}")
            print(f" HR:          {heart_rate}")

            prompt = f"""
You are a calm smartwatch grounding assistant.

The user is doing a short anxiety grounding exercise.

Previous question id: {question_id}
Previous question: {question}
User answered: {transcript}
Heart rate: {heart_rate}

Return STRICT JSON only, no markdown, no explanation.

Allowed states:
- anxiety_voice_question
- start_breathing
- anxiety_checkin_done

Schema for next voice question:
{{
  "state": "anxiety_voice_question",
  "question_id": "...",
  "question": "...",
  "positive": "Da",
  "negative": "Skip",
  "message": "..."
}}

Schema for done:
{{
  "state": "anxiety_checkin_done",
  "message": "..."
}}

Conversation goal:
- If previous question id is "grounding_see", briefly validate the answer and ask for 2 sounds they hear.
- If previous question id is "grounding_hear", briefly validate the answer and ask for 1 thing they can touch or feel.
- If previous question id is "grounding_touch", finish kindly.
- If the user's answer sounds very distressed or unsafe, you may ask one short safety question, but do not diagnose.

Rules:
- Use Romanian.
- Be warm and short.
- Ask only one question at a time.
- Keep question short for smartwatch.
- Do not mention AI.
- Do not give medical diagnosis.
"""

            response = gemini_model.generate_content(prompt)
            parsed = _parse_gemini_json(response.text)

            if parsed.get("state") not in {
                "anxiety_voice_question",
                "start_breathing",
                "anxiety_checkin_done"
            }:
                return fallback_voice_grounding(payload)

            if parsed.get("state") == "anxiety_voice_question":
                parsed = normalize_voice_question(parsed)

            print("[GEMINI VOICE] Response:", parsed)
            return parsed

        except Exception as e:
            print("[GEMINI VOICE ERROR]", repr(e))

    return fallback_voice_grounding(payload)


def normalize_voice_question(result: dict) -> dict:
    result.setdefault("state", "anxiety_voice_question")
    result.setdefault("question_id", "grounding_see")
    result.setdefault("question", "Spune-mi trei lucruri pe care le vezi în jur.")
    result.setdefault("positive", "Da")
    result.setdefault("negative", "Skip")
    result.setdefault("message", "Continuăm exercițiul.")
    return result


def fallback_voice_grounding(payload: dict) -> dict:
    question_id = payload.get("question_id", "")

    print("\n[VOICE FALLBACK]")
    print(f" Question id: {question_id}")
    print(f" Transcript:  {payload.get('transcript', '')}")

    if question_id == "grounding_see":
        return {
            "state": "anxiety_voice_question",
            "question_id": "grounding_hear",
            "question": "Foarte bine. Acum spune-mi două sunete pe care le auzi.",
            "positive": "Da",
            "negative": "Skip",
            "message": "Continuăm exercițiul."
        }

    if question_id == "grounding_hear":
        return {
            "state": "anxiety_voice_question",
            "question_id": "grounding_touch",
            "question": "Perfect. Acum spune-mi un lucru pe care îl poți atinge.",
            "positive": "Da",
            "negative": "Skip",
            "message": "Mai avem un pas."
        }

    if question_id == "grounding_touch":
        return {
            "state": "anxiety_checkin_done",
            "message": "Ai făcut foarte bine. Respiră lent. Sunt aici cu tine."
        }

    return {
        "state": "anxiety_checkin_done",
        "message": "Exercițiul s-a încheiat. Sunt aici cu tine."
    }


# ──────────────────────────────────────────────
# Old button-based anxiety fallback
# ──────────────────────────────────────────────

async def next_anxiety_step(payload: dict) -> dict:
    question_id = payload.get("question_id", "")
    positive = bool(payload.get("positive"))

    if question_id.startswith("grounding") and not positive:
        return {
            "state": "anxiety_checkin_done",
            "message": "În regulă. Oprim aici. Respiră lent, sunt aici cu tine."
        }

    if gemini_model is not None:
        try:
            print("[GEMINI] Generating button fallback step...")
            response = await gemini_next_checkin(payload)
            print("[GEMINI] Response:", response)
            return response
        except Exception as e:
            print("[GEMINI NEXT ERROR]", repr(e))

    return fallback_anxiety_answer(payload)


async def gemini_next_checkin(payload: dict) -> dict:
    question_id = payload.get("question_id")
    answer = payload.get("answer")
    positive = payload.get("positive")
    heart_rate = payload.get("heart_rate")

    prompt = f"""
You are a calm smartwatch mental health check-in assistant.

User context:
- Previous question id: {question_id}
- User answer: {answer}
- Positive button selected: {positive}
- Heart rate: {heart_rate}

Decide the NEXT step in the anxiety check-in.

Return STRICT JSON only, no markdown, no explanation.

Allowed states:
1. anxiety_checkin_question
2. start_breathing
3. notify_contact
4. anxiety_checkin_done

Schema for question:
{{
  "state": "anxiety_checkin_question",
  "question_id": "...",
  "question": "...",
  "positive": "...",
  "negative": "...",
  "message": "..."
}}

Schema for action:
{{
  "state": "start_breathing" | "notify_contact" | "anxiety_checkin_done",
  "message": "..."
}}

Rules:
- Use Romanian.
- Keep it short, suitable for a smartwatch.
- Ask only one question at a time.
- IMPORTANT: If previous question id is "safe" and positive is False, do not return notify_contact directly.
- Instead return an anxiety_checkin_question asking: "Vrei să anunț pe cineva?"
- Only return notify_contact if previous question id is "notify" and positive is True.
- Do not give medical diagnosis.
- Do not mention AI.
- Buttons must be short: "Da", "Nu", "Start", "Skip".
"""

    response = gemini_model.generate_content(prompt)
    parsed = _parse_gemini_json(response.text)

    allowed_states = {
        "anxiety_checkin_question",
        "start_breathing",
        "notify_contact",
        "anxiety_checkin_done"
    }

    if parsed.get("state") not in allowed_states:
        return fallback_anxiety_answer(payload)

    if parsed.get("state") == "anxiety_checkin_question":
        parsed.setdefault("question_id", "followup")
        parsed.setdefault("question", "Vrei să continuăm?")
        parsed.setdefault("positive", "Da")
        parsed.setdefault("negative", "Nu")
        parsed.setdefault("message", "Continuăm check-in-ul")

    if parsed.get("state") != "anxiety_checkin_question":
        parsed.setdefault("message", "Check-in actualizat.")

    return parsed


def fallback_anxiety_answer(payload: dict) -> dict:
    question_id = payload.get("question_id")
    positive = bool(payload.get("positive"))
    answer = payload.get("answer", "")
    heart_rate = payload.get("heart_rate", 0)

    print("\n[CHECK-IN FALLBACK]")
    print(f" Question: {question_id}")
    print(f" Answer:   {answer}")
    print(f" Positive: {positive}")
    print(f" HR:       {heart_rate}")

    if question_id == "safe":
        if not positive:
            return {
                "state": "anxiety_checkin_question",
                "question_id": "notify",
                "question": "Vrei să anunț pe cineva?",
                "positive": "Da",
                "negative": "Nu",
                "message": "Utilizatorul nu se simte în siguranță"
            }

        return {
            "state": "anxiety_checkin_question",
            "question_id": "breathing",
            "question": "Vrei să facem un exercițiu scurt de respirație?",
            "positive": "Start",
            "negative": "Skip",
            "message": "Utilizatorul se simte în siguranță"
        }

    if question_id == "breathing":
        if positive:
            return {
                "state": "start_breathing",
                "message": "Pornesc exercițiul de respirație"
            }

        return {
            "state": "anxiety_checkin_done",
            "message": "În regulă. Rămân aici cu tine."
        }

    if question_id == "notify":
        if positive:
            return {
                "state": "notify_contact",
                "message": "Anunț contactul de urgență"
            }

        return {
            "state": "anxiety_checkin_question",
            "question_id": "breathing",
            "question": "Hai să facem un exercițiu de respirație?",
            "positive": "Start",
            "negative": "Nu",
            "message": "Utilizatorul nu vrea notificare"
        }

    return {
        "state": "anxiety_checkin_done",
        "message": "Check-in finalizat."
    }


# ──────────────────────────────────────────────
# JSON parsing
# ──────────────────────────────────────────────

def _parse_gemini_json(text: str) -> dict:
    text = text.strip()

    start = text.find("{")
    end = text.rfind("}") + 1

    if start == -1 or end <= start:
        raise ValueError("Gemini did not return valid JSON")

    clean = text[start:end]
    return json.loads(clean)


# ──────────────────────────────────────────────
# REST endpoints vechi
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
    return {
        "status": "ok",
        "schedule": times
    }


@app.post("/medication/log")
async def medication_log(body: dict):
    brain.medication.log_intake(
        body.get("taken", False),
        body.get("time")
    )
    return {"status": "ok"}


@app.get("/report/daily")
async def daily_report(date: str = None):
    return brain.report_gen.generate(date)


# ──────────────────────────────────────────────
# Logging
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

    if result.get("question"):
        print(f" INTREBARE: {result.get('question')}")
        print(
            f" BUTOANE:   "
            f"{result.get('negative', 'Nu')} / "
            f"{result.get('positive', 'Da')}"
        )

    if result.get("transcript"):
        print(f" TRANSCRIPT: {result.get('transcript')}")

    if d:
        print(f" ┌─ Analiză ──────────────────────────────")
        print(
            f" │  linear_acc_var = {d.get('lin_var', '?'):<8}  "
            f"(fidgeting: 0.05–0.5, mers: >1.0)"
        )
        print(
            f" │  gyro_mean      = {d.get('gyro_mean', '?'):<8}  "
            f"(rotație: >0.2 = mișcă, >0.5 = mare)"
        )
        print(
            f" │  gyro_var       = {d.get('gyro_var', '?'):<8}  "
            f"(repetitiv: <0.3)"
        )

        print(f" │  HR             = {d.get('hr', '?'):<8}  ", end="")

        if d.get("focus_active") and d.get("baseline_hr"):
            diff = round(d["hr"] - d["baseline_hr"], 1)
            sign = "+" if diff >= 0 else ""
            print(f"(baseline {d['baseline_hr']} → {sign}{diff} BPM)")
        else:
            print(f"(anxietate: >110)")

        if d.get("light_diff", 0) > 0:
            print(
                f" │  light_diff     = {d.get('light_diff', '?'):<8}  "
                f"(epilepsie: >200)"
            )

        print(f" │  samples        = {d.get('samples', '?')}")

        if d.get("focus_active"):
            print(
                f" │  focus activ    = DA  "
                f"(exits: {result.get('exits_count', 0)})"
            )

        if d.get("demo_fake_hr"):
            print(f" │  demo_fake_hr   = DA")

        print(f" └────────────────────────────────────────")

    print("=" * 48)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)