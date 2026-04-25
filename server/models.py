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

        lin_var  = stats.linear_acc_variance
        gyro_avg = stats.gyro_mean
        gyro_var = stats.gyro_variance

        # Mers sau mișcare mare → nu fidgeting
        if lin_var > 1.0:
            return {"state": "normal", "confidence": 0.85, "message": "Mers detectat"}

        is_small_movement = 0.05 < lin_var < 0.5
        is_wrist_moving   = gyro_avg > 0.2
        is_repetitive     = gyro_var < 0.3   # mișcare regulată, nu aleatoare

        if not (is_small_movement and is_wrist_moving and is_repetitive):
            return {"state": "normal", "confidence": 0.9, "message": "Comportament stabil"}

        severity = min(1.0, lin_var / 0.5)

        if severity < 0.3:
            return {
                "state": "adhd_fidgeting_mild",
                "severity": round(severity, 2),
                "confidence": 0.7,
                "message": "Fidgeting ușor",
            }
        elif severity < 0.6:
            return {
                "state": "adhd_fidgeting",
                "action": "vibrate_anchor",
                "severity": round(severity, 2),
                "confidence": 0.8,
                "message": "Fidgeting detectat",
            }
        else:
            return {
                "state": "adhd_fidgeting_strong",
                "action": "vibrate_anchor",
                "severity": round(severity, 2),
                "confidence": 0.85,
                "message": "Un singur lucru acum 🎯",
            }


class EpilepsyCondition(HealthCondition):
    def __init__(self):
        super().__init__("Epilepsie")

    def analyze(self, stats, raw_data):
        if stats.sample_count < 5:
            return {"state": "insufficient_data", "confidence": 0}

        # EMA al diferenței de lumină: valoare mare = flash-uri repetate
        if stats.light_diff > 200:
            raw = raw_data.get("raw_sensors", {})
            acc = raw.get("accelerometer", [0, 0, 9.8])
            import math
            acc_z_mag = abs(acc[2])

            if acc_z_mag > 14.0:
                return {"state": "epilepsy_alert", "confidence": 0.95,
                        "message": "CRITIC: Posibile convulsii declanșate de lumină!"}
            return {"state": "epilepsy_alert", "confidence": 0.7,
                    "message": "Lumină stroboscopică detectată!"}

        return {"state": "normal", "confidence": 0.9, "message": "Fără stimuli fotosensibili"}


class AnxietyCondition(HealthCondition):
    def __init__(self):
        super().__init__("Anxietate")

    def analyze(self, stats, raw_data):
        if stats.hr > 110:
            return {"state": "anxiety_alert", "confidence": 0.7, "message": "Puls ridicat în repaus."}
        return {"state": "normal", "confidence": 0.9, "message": "Puls normal."}
