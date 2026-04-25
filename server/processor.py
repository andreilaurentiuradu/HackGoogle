import time
import math


class StreamingStats:
    """
    Statistici EMA (Exponential Moving Average) cu alpha time-adaptive.
    Nu stochează date brute — actualizare O(1) per pachet.
    tau = fereastra de memorie în secunde (~10s).
    """
    TAU = 10.0

    def __init__(self):
        self._last_time          = None
        self.acc_mean            = None
        self.acc_variance        = 0.0
        self.linear_acc_mean     = None
        self.linear_acc_variance = 0.0
        self.gyro_mean           = 0.0
        self.gyro_variance       = 0.0
        self.hr                  = 70.0
        self.light               = 0.0
        self._last_light         = None
        self.light_diff          = 0.0
        self.sample_count        = 0

    def update(self, data):
        now = time.time()
        dt  = (now - self._last_time) if self._last_time else 1.0
        self._last_time = now

        # Alpha time-adaptive: memorie constantă de TAU secunde indiferent de rată
        alpha = 1.0 - math.exp(-dt / self.TAU)

        raw   = data.get("raw_sensors", {})
        hs    = data.get("health_services", {})

        acc       = raw.get("accelerometer",        [0.0, 0.0, 9.8])
        lin_acc   = raw.get("linear_acceleration", [0.0, 0.0, 0.0])
        gyro      = raw.get("gyroscope",           [0.0, 0.0, 0.0])
        hr        = hs.get("heart_rate", 0.0)
        light_raw = raw.get("light", [0.0])
        light     = light_raw[0] if isinstance(light_raw, list) else float(light_raw)

        acc_mag     = math.sqrt(sum(x * x for x in acc))
        lin_acc_mag = math.sqrt(sum(x * x for x in lin_acc))
        gyro_mag    = math.sqrt(sum(x * x for x in gyro))

        if self.acc_mean is None:
            self.acc_mean            = acc_mag
            self.linear_acc_mean     = lin_acc_mag
            self.gyro_mean           = gyro_mag
            self.light               = light
            self._last_light         = light
            if hr > 0:
                self.hr = hr
        else:
            diff                     = acc_mag - self.acc_mean
            self.acc_variance        = alpha * diff * diff + (1 - alpha) * self.acc_variance
            self.acc_mean            = alpha * acc_mag     + (1 - alpha) * self.acc_mean

            lin_diff                 = lin_acc_mag - self.linear_acc_mean
            self.linear_acc_variance = alpha * lin_diff * lin_diff + (1 - alpha) * self.linear_acc_variance
            self.linear_acc_mean     = alpha * lin_acc_mag + (1 - alpha) * self.linear_acc_mean

            gyro_diff          = gyro_mag - self.gyro_mean
            self.gyro_variance = alpha * gyro_diff * gyro_diff + (1 - alpha) * self.gyro_variance
            self.gyro_mean     = alpha * gyro_mag + (1 - alpha) * self.gyro_mean

            if hr > 0:
                self.hr = alpha * hr + (1 - alpha) * self.hr

            light_diff      = abs(light - self._last_light)
            self.light_diff = alpha * light_diff + (1 - alpha) * self.light_diff
            self.light      = alpha * light      + (1 - alpha) * self.light
            self._last_light = light

        self.sample_count += 1


class DataStreamProcessor:
    WARMUP_SECONDS  = 20   # așteptăm să se stabilizeze EMA
    VERDICT_SECONDS = 20   # verdict la fiecare 20s

    def __init__(self, brain):
        self.brain            = brain
        self.stats            = StreamingStats()
        self._start_time      = time.time()
        self._last_verdict    = None

    def add_data(self, data):
        self.stats.update(data)
        now     = time.time()
        elapsed = now - self._start_time

        if elapsed < self.WARMUP_SECONDS:
            return None

        if (self._last_verdict is None
                or now - self._last_verdict >= self.VERDICT_SECONDS):
            self._last_verdict = now
            return self.brain.process_stats(self.stats, data)

        return None
