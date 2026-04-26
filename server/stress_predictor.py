import os
import numpy as np
from collections import deque


_MODEL_PATH = os.path.join(
    os.path.dirname(__file__),
    "stress_model",
    "mindtrack_cpu.tflite",
)

_GRAVITY = 9.80665


class StressPredictor:
    ACC_LEN = 480
    BVP_LEN = 240
    EDA_LEN = 60
    TEMP_LEN = 60
    MIN_SAMPLES = 30

    def __init__(self):
        self._interp = None
        self._out = None
        self._model_ok = False

        self._load_model()

        self._acc = deque(maxlen=self.ACC_LEN)
        self._hr = deque(maxlen=self.BVP_LEN)
        self._acc_var_buf = deque(maxlen=60)

        self._count = 0

    def _load_model(self):
        if not os.path.exists(_MODEL_PATH):
            print(
                f"[StressPredictor] Model lipsă la {_MODEL_PATH}, "
                f"folosesc rule-based."
            )
            self._model_ok = False
            return

        try:
            import tensorflow as tf

            try:
                interp = tf.lite.Interpreter(
                    model_path=_MODEL_PATH,
                    experimental_op_resolver_type=tf.lite.experimental.OpResolverType.AUTO,
                )
            except TypeError:
                interp = tf.lite.Interpreter(model_path=_MODEL_PATH)

            interp.allocate_tensors()

            for d in interp.get_input_details():
                interp.set_tensor(
                    d["index"],
                    np.zeros(d["shape"], dtype=np.float32),
                )

            interp.invoke()

            self._interp = interp
            self._out = interp.get_output_details()[0]
            self._model_ok = True

            print("[StressPredictor] Model TFLite încărcat cu succes.")

        except Exception as e:
            short = str(e).split("\n")[0][:160]

            print(
                f"[StressPredictor] Model TFLite indisponibil ({short}); "
                f"folosesc rule-based."
            )

            self._model_ok = False

    def update(self, acc_xyz: list, hr: float):
        normalized = [v / _GRAVITY for v in acc_xyz[:3]]

        self._acc.append(normalized)
        self._hr.append(hr)

        mag = float(np.sqrt(sum(v ** 2 for v in normalized)))
        self._acc_var_buf.append(mag)

        self._count += 1

    def predict(self) -> float | None:
        if self._count < self.MIN_SAMPLES:
            return None

        if self._model_ok:
            return self._predict_model()

        return self._predict_rules()

    def _predict_model(self) -> float:
        def _pad(buf, length, width):
            arr = list(buf)

            while len(arr) < length:
                arr = [arr[0]] + arr

            data = arr[-length:]

            if width == 1:
                data = [[x] for x in data]

            return np.array(data, dtype=np.float32).reshape(1, length, width)

        acc_t = _pad(self._acc, self.ACC_LEN, 3)
        bvp_t = _pad(self._hr, self.BVP_LEN, 1)
        eda_t = np.zeros((1, self.EDA_LEN, 1), dtype=np.float32)
        temp_t = np.zeros((1, self.TEMP_LEN, 1), dtype=np.float32)

        for detail in self._interp.get_input_details():
            name = detail["name"].lower()

            if "acc" in name:
                self._interp.set_tensor(detail["index"], acc_t)

            elif "bvp" in name:
                self._interp.set_tensor(detail["index"], bvp_t)

            elif "eda" in name:
                self._interp.set_tensor(detail["index"], eda_t)

            elif "temp" in name:
                self._interp.set_tensor(detail["index"], temp_t)

        self._interp.invoke()

        output = self._interp.get_tensor(self._out["index"])[0]

        return float(output[1])

    def _predict_rules(self) -> float:
        hr_vals = list(self._hr)
        avg_hr = sum(hr_vals) / len(hr_vals)

        mags = list(self._acc_var_buf)
        mean_mag = sum(mags) / len(mags)

        acc_var = sum((m - mean_mag) ** 2 for m in mags) / len(mags)

        score = 0.0

        # Puls ridicat în repaus = semn de stres.
        if avg_hr > 100:
            score += 0.40
        elif avg_hr > 90:
            score += 0.25
        elif avg_hr > 80:
            score += 0.10

        # Agitație fizică / fidgeting.
        if 0.001 < acc_var < 0.5:
            score += 0.30
        elif acc_var >= 0.5:
            score += 0.10

        return min(score, 1.0)