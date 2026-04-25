from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from datetime import datetime
import json
import numpy as np
import os

app = FastAPI()

# ── Incarca modelul la startup ─────────────────────────
try:
    import tflite_runtime.interpreter as tflite
    Interpreter = tflite.Interpreter
except ImportError:
    import tensorflow as tf
    Interpreter = tf.lite.Interpreter

MODEL_PATH  = os.path.join(os.path.dirname(__file__), 'model', 'mindtrack.tflite')
CLASS_NAMES = ['Neutru', 'Stres', 'Meditatie']
interpreter = None

def load_model():
    global interpreter
    if not os.path.exists(MODEL_PATH):
        print(f"⚠️  Model negasit la {MODEL_PATH} — mock mode")
        return False
    interpreter = Interpreter(model_path=MODEL_PATH)
    interpreter.allocate_tensors()
    print(f"✅ Model incarcat: {MODEL_PATH}")
    return True

MODEL_LOADED = load_model()

# ── Preprocesare ───────────────────────────────────────
def zscore(arr):
    a = np.array(arr, dtype=np.float32)
    return (a - a.mean()) / (a.std() + 1e-8)

def preprocess(payload: dict):
    eda  = zscore(payload['eda']).reshape(1, -1, 1)
    bvp  = zscore(payload['bvp']).reshape(1, -1, 1)
    acc  = zscore(payload['acc']).reshape(1, -1, 3)
    temp = zscore(payload['temp']).reshape(1, -1, 1)
    return eda, bvp, acc, temp

# ── Inferenta ──────────────────────────────────────────
def run_inference(payload: dict) -> dict:
    if not MODEL_LOADED:
        return mock_prediction(payload)
    try:
        eda, bvp, acc, temp = preprocess(payload)
        inp = interpreter.get_input_details()
        out = interpreter.get_output_details()
        interpreter.set_tensor(inp[0]['index'], eda)
        interpreter.set_tensor(inp[1]['index'], bvp)
        interpreter.set_tensor(inp[2]['index'], acc)
        interpreter.set_tensor(inp[3]['index'], temp)
        interpreter.invoke()
        output     = interpreter.get_tensor(out[0]['index'])[0]
        class_id   = int(np.argmax(output))
        confidence = float(np.max(output))
        return {
            'class_id':      class_id,
            'state':         CLASS_NAMES[class_id],
            'confidence':    confidence,
            'probabilities': {
                'Neutru':    float(output[0]),
                'Stres':     float(output[1]),
                'Meditatie': float(output[2])
            }
        }
    except Exception as e:
        print(f"❌ Eroare inferenta: {e}")
        return mock_prediction(payload)

def mock_prediction(payload: dict) -> dict:
    hr    = float(np.mean(payload.get('bvp', [70])))
    if hr > 90:
        class_id, probs = 1, [0.10, 0.85, 0.05]
    elif hr < 65:
        class_id, probs = 2, [0.15, 0.05, 0.80]
    else:
        class_id, probs = 0, [0.80, 0.12, 0.08]
    return {
        'class_id':      class_id,
        'state':         CLASS_NAMES[class_id],
        'confidence':    float(max(probs)),
        'probabilities': {
            'Neutru':    probs[0],
            'Stres':     probs[1],
            'Meditatie': probs[2]
        }
    }

# ── Interventie ────────────────────────────────────────
def get_intervention(class_id: int, confidence: float) -> dict:
    if class_id == 1 and confidence > 0.8:
        return {'type': 'breathing', 'pattern': '4-7-8',
                'message': 'Respiră cu mine 🌬️', 'haptic': 'severe'}
    elif class_id == 1 and confidence > 0.6:
        return {'type': 'breathing', 'pattern': '4-4-4-4',
                'message': 'Moment de pauză 🤲', 'haptic': 'moderate'}
    elif class_id == 1:
        return {'type': 'ping', 'pattern': None,
                'message': None, 'haptic': 'light'}
    elif class_id == 2:
        return {'type': 'focus_lock', 'pattern': None,
                'message': 'Ești în flow 🎯', 'haptic': 'none'}
    else:
        return {'type': 'none', 'pattern': None,
                'message': None, 'haptic': 'none'}

# ── WebSocket ──────────────────────────────────────────
@app.websocket("/ws/health")
async def health_ws(websocket: WebSocket):
    await websocket.accept()
    print("✅ Watch connected")

    try:
        while True:
            data = await websocket.receive_text()

            try:
                payload = json.loads(data)
            except json.JSONDecodeError:
                print("❌ Invalid JSON")
                await websocket.send_text(json.dumps({'error': 'Invalid JSON'}))
                continue

            print(f"\n--- [{datetime.now().isoformat()}] ---")
            print(f"   User: {payload.get('user_id', 'unknown')}")

            prediction   = run_inference(payload)
            intervention = get_intervention(
                prediction['class_id'],
                prediction['confidence']
            )

            response = {
                'timestamp':     datetime.now().isoformat(),
                'user_id':       payload.get('user_id', 'unknown'),
                'state':         prediction['state'],
                'class_id':      prediction['class_id'],
                'confidence':    prediction['confidence'],
                'probabilities': prediction['probabilities'],
                'intervention':  intervention,
                'model_mode':    'ml' if MODEL_LOADED else 'mock'
            }

            print(f"   → {prediction['state']} "
                  f"({prediction['confidence']:.0%}) "
                  f"[{response['model_mode']}]")

            await websocket.send_text(json.dumps(response))

    except WebSocketDisconnect:
        print("⚠️  Watch disconnected")

# ── Health check ───────────────────────────────────────
@app.get("/health")
async def health_check():
    return {
        'status':       'ok',
        'model_loaded': MODEL_LOADED,
        'classes':      CLASS_NAMES
    }