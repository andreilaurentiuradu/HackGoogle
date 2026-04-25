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
