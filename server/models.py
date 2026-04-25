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
    def __init__(self):
        super().__init__("Epilepsie")

    def analyze(self, window_data):
        if not window_data or len(window_data) < 5:
            return {"state": "insufficient_data", "confidence": 0}

        light_values = [data.get("light", 0) for data in window_data]
        accel_values = [data.get("accelerometer", [0, 0, 0]) for data in window_data]

        # 1. Analiza Stroboscopului (variații bruște de lumină)
        light_diffs = []
        for i in range(1, len(light_values)):
            light_diffs.append(abs(light_values[i] - light_values[i-1]))
        
        threshold_light_change = 500 
        rapid_changes = [d for d in light_diffs if d > threshold_light_change]

        # 2. Analiza mișcării (axa Z)
        accel_z = [v[2] for v in accel_values]
        movement_variance = max(accel_z) - min(accel_z)

        # Dacă vedem lumini intermitente rapide
        if len(rapid_changes) >= 3:
            confidence = 0.7
            message = "Lumină stroboscopică detectată!"
            
            # Dacă adăugăm și mișcare violentă, e alertă maximă
            if movement_variance > 5.0:
                confidence = 0.95
                message = "CRITIC: Posibile convulsii declanșate de lumină!"
            
            return {
                "state": "epilepsy_alert",
                "confidence": confidence,
                "message": message
            }

        return {"state": "normal", "confidence": 0.9, "message": "Fără stimuli fotosensibili"}

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