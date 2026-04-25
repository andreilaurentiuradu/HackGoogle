from models import ADHDCondition, EpilepsyCondition, AnxietyCondition
from adhd_manager import FocusSessionManager, HyperfocusDetector, MedicationManager, MedicalReportGenerator


class DecisionBrain:
    def __init__(self):
        self.conditions = {
            "epilepsy": EpilepsyCondition(),
            "adhd": ADHDCondition(),
            "anxiety": AnxietyCondition(),
        }
        self.focus = FocusSessionManager()
        self.hyperfocus = HyperfocusDetector()
        self.medication = MedicationManager()
        self.report_gen = MedicalReportGenerator()

    def process_stats(self, stats, raw_data):
        result = None

        for condition in self.conditions.values():
            r = condition.analyze(stats, raw_data)
            if r.get("state") != "normal" and r.get("confidence", 0) > 0.6:
                result = r
                break

        if result is None:
            result = {"state": "normal", "confidence": 1.0, "message": "Totul este in regula."}

        # Focus session: înregistrează stats și detectează ieșirile
        if self.focus.active:
            self.focus.record_stats(stats)
            is_fidgeting = result.get("state", "").startswith("adhd_fidgeting")
            if self._is_focus_exit(stats, is_fidgeting):
                self.focus.record_exit()
                return {
                    "state": "focus_exit",
                    "action": "vibrate_alert",
                    "exits_count": self.focus.exits,
                    "message": f"Ai ieșit din focus! ({self.focus.exits}x)",
                }
            # Fidgeting în sesiune → nu trimite intervenție separată
            if is_fidgeting:
                return {"state": "normal", "confidence": 1.0, "message": "Totul este in regula."}

        hyperfocus_alert = self.hyperfocus.update(result, stats)
        if hyperfocus_alert:
            return hyperfocus_alert

        return result

    def _is_focus_exit(self, stats, is_fidgeting: bool = False) -> bool:
        is_physical_activity = stats.linear_acc_variance > 2.0

        linear_acc_high = stats.linear_acc_variance > 0.3
        wrist_rotating  = stats.gyro_mean > 0.5
        hr_elevated     = stats.hr > self.focus.baseline_hr + 8

        return (
            not is_physical_activity
            and ((linear_acc_high and wrist_rotating) or hr_elevated or is_fidgeting)
        )

    def pre_focus(self):
        """Pasul 1 — trimite exercițiul de respirație. Sesiunea NU a pornit încă."""
        return {
            "state": "pre_focus_ritual",
            "action": "breathing_exercise",
            "breaths": 3,
            "inhale_ms": 5000,
            "exhale_ms": 5000,
            "message": "Inspiră 5s · Expiră 5s · × 3",
        }

    def begin_focus(self, baseline_hr: float = 70.0):
        """Pasul 2 — user a confirmat 'Gata ✓', pornim sesiunea."""
        self.focus.start(baseline_hr)
        self.hyperfocus.reset()
        return {
            "state": "focus_started",
            "action": "start_timer",
            "message": "Focus început! 🎯",
        }

    def end_focus(self):
        report = self.focus.end()
        if report is None:
            return {"state": "error", "message": "Nicio sesiune activă."}
        return {
            "state": "focus_complete",
            "action": "show_report",
            "message": f"{report['duration_minutes']} minute | Calitate {report['quality_percent']}%",
            **report,
        }
