class HealthCondition:
    def __init__(self, name):
        self.name = name

    def analyze(self, window_data):
        raise NotImplementedError("Fiecare condiție trebuie să implementeze propria metodă.")

class ADHDCondition(HealthCondition):
    def __init__(self):
        super().__init__("ADHD")

    def analyze(self, window_data):
        if not window_data or len(window_data) < 5:
            return {"state": "insufficient_data", "confidence": 0}

        # Extragem axa X a accelerometrului (indexul 0 din lista 'accelerometer')
        accel_x_values = [data.get("accelerometer", [0, 0, 0])[0] for data in window_data]
        
        # Calculăm variația pentru a detecta fâstâceală/agitație
        variation = max(accel_x_values) - min(accel_x_values)
        
        if variation > 4.0:
            return {
                "state": "adhd_high_activity", 
                "confidence": 0.85, 
                "message": "Agitație motorie detectată!"
            }
        
        return {"state": "normal", "confidence": 0.9, "message": "Comportament stabil"}

class EpilepsyCondition(HealthCondition):
    # Zona periculoasă de fotosensibilitate: 3-30 Hz (vârf risc: 15-25 Hz)
    DANGER_FREQ_LOW = 3.0
    DANGER_FREQ_HIGH = 30.0
    LIGHT_AMPLITUDE_THRESHOLD = 200  # lux

    def __init__(self):
        super().__init__("Epilepsie")

    def analyze(self, window_data):
        if not window_data or len(window_data) < 5:
            return {"state": "insufficient_data", "confidence": 0}

        light_values = [data.get("light", 0) for data in window_data]
        timestamps = [
            data.get("timestamp") or int(data.get("_server_time", 0) * 1000)
            for data in window_data
        ]
        accel_values = [data.get("accelerometer", [0, 0, 0]) for data in window_data]
        hr_values = [data.get("heart_rate", 70) for data in window_data]

        freq = self._calculate_strobe_frequency(light_values, timestamps)
        light_amplitude = max(light_values) - min(light_values)
        accel_z = [v[2] for v in accel_values]
        movement_variance = max(accel_z) - min(accel_z)
        avg_hr = sum(hr_values) / len(hr_values)

        in_danger_zone = self.DANGER_FREQ_LOW <= freq <= self.DANGER_FREQ_HIGH
        has_strong_strobe = light_amplitude >= self.LIGHT_AMPLITUDE_THRESHOLD

        # Scor de risc compus din mai mulți factori
        risk_score = 0.0

        if in_danger_zone and has_strong_strobe:
            risk_score += 0.55
            # Factor frecvență: maximul riscului în jurul valorii de 17.5 Hz
            freq_factor = 1.0 - abs(freq - 17.5) / 17.5
            risk_score += 0.15 * max(0.0, freq_factor)

        if movement_variance > 5.0:
            risk_score += 0.20

        if avg_hr > 100:
            risk_score += 0.10

        extra = {
            "strobe_freq_hz": round(freq, 2),
            "light_amplitude_lux": round(light_amplitude, 1),
            "movement_variance": round(movement_variance, 2),
            "avg_heart_rate": round(avg_hr, 1),
        }

        if risk_score >= 0.70:
            return {
                "state": "epilepsy_alert",
                "confidence": min(0.95, risk_score),
                "message": f"CRITIC: Stroboscop la {freq:.1f} Hz + convulsii detectate!",
                **extra,
            }

        if risk_score >= 0.40 or (in_danger_zone and has_strong_strobe):
            return {
                "state": "epilepsy_warning",
                "confidence": min(0.80, max(0.65, risk_score + 0.10)),
                "message": f"AVERTIZARE: Stroboscop la {freq:.1f} Hz in zona periculoasa (3-30 Hz)!",
                **extra,
            }

        # Pre-ictal: puls crescut + variații de lumină, fără stroboscop clar
        if avg_hr > 95 and light_amplitude > 80:
            return {
                "state": "epilepsy_preictal",
                "confidence": 0.65,
                "message": "Pre-ictal posibil: puls crescut si variatie de lumina detectata.",
                **extra,
            }

        return {"state": "normal", "confidence": 0.90, "message": "Fara stimuli fotosensibili"}

    def _calculate_strobe_frequency(self, light_values, timestamps):
        """Calculeaza frecventa stroboscopului in Hz prin metoda zero-crossing."""
        if len(light_values) < 4:
            return 0.0

        amplitude = max(light_values) - min(light_values)
        if amplitude < self.LIGHT_AMPLITUDE_THRESHOLD:
            return 0.0

        mean_light = sum(light_values) / len(light_values)
        crossings = sum(
            1 for i in range(1, len(light_values))
            if (light_values[i - 1] > mean_light) != (light_values[i] > mean_light)
        )

        valid_ts = [t for t in timestamps if t and t > 0]
        if len(valid_ts) >= 2:
            duration_s = (valid_ts[-1] - valid_ts[0]) / 1000.0
        else:
            duration_s = len(light_values) * 0.1  # presupunem 10 Hz sampling

        if duration_s <= 0 or crossings < 2:
            return 0.0

        return crossings / (2.0 * duration_s)

class AnxietyCondition(HealthCondition):
    def __init__(self):
        super().__init__("Anxietate")

    def analyze(self, window_data):
        # O logică simplă pentru viitor (ex: puls ridicat fără mișcare)
        hr_values = [data.get("heart_rate", 70) for data in window_data]
        avg_hr = sum(hr_values) / len(hr_values) if hr_values else 0

        if avg_hr > 110:
            return {"state": "anxiety_alert", "confidence": 0.7, "message": "Puls ridicat în repaus."}
        
        return {"state": "normal", "confidence": 0.9, "message": "Puls normal."}