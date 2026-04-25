from models import ADHDCondition, EpilepsyCondition, AnxietyCondition

class DecisionBrain:
    def __init__(self):
        # Aici înregistrăm toți "doctorii" noștri
        self.conditions = {
            "epilepsy": EpilepsyCondition(),
            "adhd": ADHDCondition(),
            "anxiety": AnxietyCondition()
        }

    def process_window(self, data_window):
        # Trecem datele din ultimele 10 secunde prin toate filtrele
        for key, condition in self.conditions.items():
            result = condition.analyze(data_window)
            
            # Dacă un model este foarte sigur că a detectat o problemă, oprim căutarea
            if result.get("state") != "normal" and result.get("confidence", 0) > 0.6:
                return result
        
        # Dacă niciun model nu s-a activat, totul e ok
        return {"state": "normal", "confidence": 1.0, "message": "Totul este in regula."}