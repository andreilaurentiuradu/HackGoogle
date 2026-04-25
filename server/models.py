class HealthCondition:
    def __init__(self, name):
        self.name = name

    def analyze(self, stats, raw_data):
        raise NotImplementedError


class ADHDCondition(HealthCondition):
    def __init__(self):
        super().__init__("ADHD")

    def analyze(self, stats, raw_data):
        if stats.sample_count < 5:
            return {"state": "insufficient_data", "confidence": 0}

        lin_var = stats.linear_acc_variance
        gyro_avg = stats.gyro_mean
        gyro_var = stats.gyro_variance

        raw = raw_data.get("raw_sensors", raw_data)
        acc = raw.get("accelerometer", [0, 0, 9.8])

        # Dacă accelerometrul are variație mare și giroscopul e mare,
        # e mișcare activă de mână, nu neapărat mers.
        is_hand_agitated = (
            lin_var > 5.0 and
            gyro_avg > 0.8
        )

        # Fidgeting clasic: mișcare mică, repetitivă.
        is_small_fidgeting = (
            0.05 < lin_var < 7.0 and
            0.25 < gyro_avg < 1.2 and
            gyro_var < 0.4
        )

        # Mișcare puternică/dezorganizată de mână.
        is_strong_restlessness = (
            lin_var >= 5.0 and
            gyro_avg > 1.0
        )

        if is_small_fidgeting:
            severity = min(1.0, lin_var / 5.0)

            return {
                "state": "adhd_fidgeting",
                "action": "vibrate_anchor",
                "severity": round(severity, 2),
                "confidence": 0.8,
                "message": "Fidgeting detectat",
            }

        if is_strong_restlessness:
            severity = min(1.0, (lin_var / 10.0 + gyro_avg / 5.0) / 2)

            return {
                "state": "adhd_high_activity",
                "action": "vibrate_anchor",
                "severity": round(severity, 2),
                "confidence": 0.75,
                "message": "Mișcare agitată a mâinii detectată",
            }

        return {
            "state": "normal",
            "confidence": 0.9,
            "message": "Comportament stabil"
        }

class EpilepsyCondition(HealthCondition):
    def __init__(self):
        super().__init__("Epilepsie")

    def analyze(self, stats, raw_data):
        window = raw_data.get("_epilepsy_window") if isinstance(raw_data, dict) else None

        if window and len(window) >= 10:
            return self._analyze_window(window, stats)

        if stats.sample_count < 5:
            return {"state": "insufficient_data", "confidence": 0}
        return {"state": "normal", "confidence": 0.9, "message": "Fără stimuli fotosensibili"}

    def _analyze_window(self, window, stats):
        lights = [s[1] for s in window]
        accel_z_vals = [s[2] for s in window]
        hr_vals = [s[3] for s in window if s[3] > 0]

        duration = window[-1][0] - window[0][0]
        if duration < 1.0:
            return {"state": "normal", "confidence": 0.9, "message": "Fără stimuli fotosensibili"}

        light_amplitude = max(lights) - min(lights)
        strobe_freq = self._strobe_frequency(lights, duration, light_amplitude)
        z_movement = max(accel_z_vals) - min(accel_z_vals)
        avg_hr = sum(hr_vals) / len(hr_vals) if hr_vals else stats.hr

        n = len(lights)
        n_big_changes = sum(1 for i in range(1, n) if abs(lights[i] - lights[i - 1]) > 100)
        change_ratio = n_big_changes / (n - 1) if n > 1 else 0.0

        score = 0.0
        in_strobe_range = 3.0 <= strobe_freq <= 30.0

        if in_strobe_range and light_amplitude >= 200:
            score += 0.55
            freq_bonus = 0.15 * max(0.0, 1.0 - abs(strobe_freq - 17.5) / 17.5)
            score += freq_bonus
        elif change_ratio > 0.3 and light_amplitude >= 200:
            # Schimbari rapide confirmate → direct alert, indiferent de frecventa masurabila.
            score += 0.70
        elif light_amplitude >= 200:
            # Amplitudine mare in fereastra (a existat high+low) dar schimbari rare.
            score += 0.40

        if z_movement > 5.0:
            score += 0.20

        if avg_hr > 100:
            score += 0.10

        if score >= 0.70:
            return {
                "state": "epilepsy_alert",
                "confidence": round(min(0.95, 0.70 + score * 0.25), 2),
                "freq": round(strobe_freq, 1),
                "score": round(score, 2),
                "message": f"ALERTĂ EPILEPSIE! Stroboscop {strobe_freq:.1f} Hz, mișcare Z {z_movement:.1f} m/s²",
            }

        if score >= 0.40 or (in_strobe_range and light_amplitude >= 200):
            return {
                "state": "epilepsy_warning",
                "confidence": round(min(0.80, 0.65 + score * 0.15), 2),
                "freq": round(strobe_freq, 1),
                "score": round(score, 2),
                "message": f"Avertisment: lumină stroboscopică {strobe_freq:.1f} Hz",
            }

        if avg_hr > 95 and light_amplitude > 50 and not in_strobe_range:
            return {
                "state": "epilepsy_preictal",
                "confidence": 0.65,
                "freq": round(strobe_freq, 1),
                "score": round(score, 2),
                "message": "Stare pre-ictală posibilă: puls ridicat + lumină variabilă",
            }

        return {"state": "normal", "confidence": 0.9, "message": "Fără stimuli fotosensibili"}

    @staticmethod
    def _strobe_frequency(lights, duration, amplitude):
        if amplitude < 200:
            return 0.0
        mean_light = sum(lights) / len(lights)
        crossings = sum(
            1 for i in range(1, len(lights))
            if (lights[i - 1] < mean_light) != (lights[i] < mean_light)
        )
        return crossings / (2.0 * duration)


class AnxietyCondition(HealthCondition):
    def __init__(self):
        super().__init__("Anxietate")

    def analyze(self, stats, raw_data):
        if stats.hr > 110:
            return {"state": "anxiety_alert", "confidence": 0.7, "message": "Puls ridicat în repaus."}
        return {"state": "normal", "confidence": 0.9, "message": "Puls normal."}
