import time
import math


class StreamingStats:
    """
    Statistici EMA (Exponential Moving Average) cu alpha time-adaptive.
    Acceptă ambele formate:
    1. Nested:
       {
         "health_services": {"heart_rate": 76},
         "raw_sensors": {"accelerometer": [...], "gyroscope": [...]}
       }

    2. Flat:
       {
         "heart_rate": 76,
         "accelerometer": [...],
         "gyroscope": [...]
       }
    """

    TAU = 10.0

    def __init__(self):
        self._last_time = None

        self.acc_mean = None
        self.acc_variance = 0.0

        self.linear_acc_mean = None
        self.linear_acc_variance = 0.0

        self.gyro_mean = 0.0
        self.gyro_variance = 0.0

        self.hr = 70.0

        self.light = 0.0
        self._last_light = None
        self.light_diff = 0.0

        self.sample_count = 0

    def _vector3(self, value, default):
        if not isinstance(value, list) or len(value) < 3:
            return default

        try:
            return [
                float(value[0]),
                float(value[1]),
                float(value[2])
            ]
        except Exception:
            return default

    def _number(self, value, default=0.0):
        try:
            if value is None:
                return default
            return float(value)
        except Exception:
            return default

    def update(self, data):
        now = time.time()
        dt = (now - self._last_time) if self._last_time else 1.0
        self._last_time = now

        alpha = 1.0 - math.exp(-dt / self.TAU)

        raw = data.get("raw_sensors", data)
        hs = data.get("health_services", data)

        acc = self._vector3(
            raw.get("accelerometer"),
            [0.0, 0.0, 9.8]
        )

        lin_acc = self._vector3(
            raw.get("linear_acceleration") or raw.get("accelerometer"),
            [0.0, 0.0, 0.0]
        )

        gyro = self._vector3(
            raw.get("gyroscope"),
            [0.0, 0.0, 0.0]
        )

        hr = self._number(
            hs.get("heart_rate") or data.get("heart_rate"),
            0.0
        )

        light_raw = raw.get("light") or data.get("light") or [0.0]

        if isinstance(light_raw, list):
            light = self._number(light_raw[0] if light_raw else 0.0)
        else:
            light = self._number(light_raw)

        acc_mag = math.sqrt(sum(x * x for x in acc))
        lin_acc_mag = math.sqrt(sum(x * x for x in lin_acc))
        gyro_mag = math.sqrt(sum(x * x for x in gyro))

        # Debug util. După ce merge, poți comenta liniile astea.
        if self.sample_count % 10 == 0:
            print(
                f"[DEBUG SENSOR] "
                f"acc={acc} lin_acc={lin_acc} gyro={gyro} "
                f"gyro_mag={gyro_mag:.8f} hr={hr} light={light}"
            )

        if self.acc_mean is None:
            self.acc_mean = acc_mag
            self.linear_acc_mean = lin_acc_mag
            self.gyro_mean = gyro_mag
            self.light = light
            self._last_light = light

            if hr > 0:
                self.hr = hr

        else:
            acc_diff = acc_mag - self.acc_mean
            self.acc_variance = (
                alpha * acc_diff * acc_diff +
                (1 - alpha) * self.acc_variance
            )
            self.acc_mean = (
                alpha * acc_mag +
                (1 - alpha) * self.acc_mean
            )

            lin_diff = lin_acc_mag - self.linear_acc_mean
            self.linear_acc_variance = (
                alpha * lin_diff * lin_diff +
                (1 - alpha) * self.linear_acc_variance
            )
            self.linear_acc_mean = (
                alpha * lin_acc_mag +
                (1 - alpha) * self.linear_acc_mean
            )

            gyro_diff = gyro_mag - self.gyro_mean
            self.gyro_variance = (
                alpha * gyro_diff * gyro_diff +
                (1 - alpha) * self.gyro_variance
            )
            self.gyro_mean = (
                alpha * gyro_mag +
                (1 - alpha) * self.gyro_mean
            )

            if hr > 0:
                self.hr = (
                    alpha * hr +
                    (1 - alpha) * self.hr
                )

            light_diff = abs(light - self._last_light)
            self.light_diff = (
                alpha * light_diff +
                (1 - alpha) * self.light_diff
            )
            self.light = (
                alpha * light +
                (1 - alpha) * self.light
            )
            self._last_light = light

        self.sample_count += 1


class DataStreamProcessor:
    WARMUP_SECONDS = 10
    VERDICT_SECONDS = 10

    def __init__(self, brain):
        self.brain = brain
        self.stats = StreamingStats()
        self._start_time = time.time()
        self._last_verdict = None

    def add_data(self, data):
        self.stats.update(data)

        now = time.time()
        elapsed = now - self._start_time

        if elapsed < self.WARMUP_SECONDS:
            return None

        if (
            self._last_verdict is None or
            now - self._last_verdict >= self.VERDICT_SECONDS
        ):
            self._last_verdict = now
            return self.brain.process_stats(self.stats, data)

        return None