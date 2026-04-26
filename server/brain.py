import time

from models import ADHDCondition, EpilepsyCondition, AnxietyCondition
from adhd_manager import (
    FocusSessionManager,
    HyperfocusDetector,
    MedicationManager,
    MedicalReportGenerator,
)


STRESS_ALERT_THRESHOLD = 0.55
STRESS_COOLDOWN_SECONDS = 90.0


class DecisionBrain:
    def __init__(self):
        self.profile = "adhd"  # profil activ: "adhd" sau "epilepsy"

        self.conditions = {
            "epilepsy": EpilepsyCondition(),
            "adhd": ADHDCondition(),
            "anxiety": AnxietyCondition(),
        }

        self.focus = FocusSessionManager()
        self.hyperfocus = HyperfocusDetector()
        self.medication = MedicationManager()
        self.report_gen = MedicalReportGenerator()

        self._last_stress_alert_at = 0.0

    def set_profile(self, profile: str):
        """
        Schimbă profilul activ.
        Dacă suntem în focus și schimbăm profilul, oprim sesiunea ca să nu rămână state vechi.
        """
        if profile not in ("adhd", "epilepsy"):
            profile = "adhd"

        self.profile = profile

        if self.focus.active:
            self.focus.end()

        self.hyperfocus.reset()
        self._last_stress_alert_at = 0.0

    def process_stats(self, stats, raw_data):
        """
        Decide ce stare trimitem către ceas.
        Important:
        - profil ADHD => rulează ADHD + Anxiety
        - profil Epilepsy => rulează Epilepsy + Anxiety
        - focus/hyperfocus/medication doar pe ADHD
        - stress predictor poate produce stress_alert dacă există _stress_score
        """

        # ──────────────────────────────────────────────
        # Condiții active în funcție de profil
        # ──────────────────────────────────────────────

        if self.profile == "epilepsy":
            active_conditions = [
                self.conditions["epilepsy"],
                self.conditions["anxiety"],
            ]
        else:
            active_conditions = [
                self.conditions["adhd"],
                self.conditions["anxiety"],
            ]

        result = None

        for condition in active_conditions:
            r = condition.analyze(stats, raw_data)

            if r.get("state") != "normal" and r.get("confidence", 0) > 0.6:
                result = r
                break

        # ──────────────────────────────────────────────
        # Stress score venit din processor.py
        # ──────────────────────────────────────────────

        stress_score = raw_data.get("_stress_score") if isinstance(raw_data, dict) else None

        if result is None:
            stress_alert = self._maybe_stress_alert(stress_score)

            if stress_alert is not None:
                result = stress_alert
            else:
                result = {
                    "state": "normal",
                    "confidence": 1.0,
                    "message": "Totul este in regula.",
                }

        # ──────────────────────────────────────────────
        # Debug
        # ──────────────────────────────────────────────

        result["_debug"] = {
            "lin_var": round(stats.linear_acc_variance, 4),
            "gyro_mean": round(stats.gyro_mean, 4),
            "gyro_var": round(stats.gyro_variance, 4),
            "hr": round(stats.hr, 1),
            "light_diff": round(stats.light_diff, 1),
            "samples": stats.sample_count,
            "focus_active": self.focus.active,
            "baseline_hr": round(self.focus.baseline_hr, 1) if self.focus.active else None,
            "profile": self.profile,
            "stress_score": round(stress_score, 3) if stress_score is not None else None,
        }

        # ──────────────────────────────────────────────
        # Focus + hyperfocus doar pe ADHD
        # ──────────────────────────────────────────────

        if self.profile == "adhd":
            if self.focus.active:
                self.focus.record_stats(stats)

                is_fidgeting = result.get("state", "").startswith("adhd_fidgeting")

                if self._is_focus_exit(stats, is_fidgeting):
                    self.focus.record_exit()

                    return {
                        "state": "focus_exit",
                        "action": "show_notification",
                        "exits_count": self.focus.exits,
                        "message": f"Ai ieșit din focus! ({self.focus.exits}x)",
                        "notification": {
                            "title": "Focus întrerupt",
                            "body": f"Ai ieșit din focus de {self.focus.exits}x. Revino!",
                        },
                    }

                # În focus mode nu trimitem fidgeting/anxiety peste UI.
                return {
                    "state": "normal",
                    "confidence": 1.0,
                    "message": "Focus activ.",
                }

            hyperfocus_alert = self.hyperfocus.update(result, stats)

            if hyperfocus_alert:
                return hyperfocus_alert

        return result

    def _maybe_stress_alert(self, score):
        if score is None:
            return None

        try:
            score = float(score)
        except Exception:
            return None

        if score < STRESS_ALERT_THRESHOLD:
            return None

        now = time.time()

        if now - self._last_stress_alert_at < STRESS_COOLDOWN_SECONDS:
            return None

        self._last_stress_alert_at = now

        severity = round(min(1.0, score), 2)

        return {
            "state": "stress_alert",
            "action": "show_notification",
            "confidence": round(min(0.95, 0.6 + score * 0.3), 2),
            "severity": severity,
            "message": "Nivel ridicat de stres detectat",
            "notification": {
                "title": "Stres detectat",
                "body": "Respiră adânc. Ia o pauză de un minut.",
            },
        }

    def _is_focus_exit(self, stats, is_fidgeting: bool = False) -> bool:
        is_physical_activity = stats.linear_acc_variance > 2.0

        linear_acc_high = stats.linear_acc_variance > 0.3
        wrist_rotating = stats.gyro_mean > 0.5
        hr_elevated = stats.hr > self.focus.baseline_hr + 8

        return (
            not is_physical_activity
            and ((linear_acc_high and wrist_rotating) or hr_elevated or is_fidgeting)
        )

    def pre_focus(self):
        if self.profile != "adhd":
            return {
                "state": "error",
                "message": "Focus disponibil doar în profilul ADHD.",
            }

        return {
            "state": "pre_focus_ritual",
            "action": "breathing_exercise",
            "breaths": 3,
            "inhale_ms": 5000,
            "exhale_ms": 5000,
            "message": "Inspiră 5s · Expiră 5s · × 3",
        }

    def begin_focus(self, baseline_hr: float = 70.0):
        if self.profile != "adhd":
            return {
                "state": "error",
                "message": "Focus disponibil doar în profilul ADHD.",
            }

        self.focus.start(baseline_hr)
        self.hyperfocus.reset()

        return {
            "state": "focus_started",
            "action": "start_timer",
            "message": "Focus început! 🎯",
        }

    def end_focus(self):
        if self.profile != "adhd":
            return {
                "state": "error",
                "message": "Focus disponibil doar în profilul ADHD.",
            }

        report = self.focus.end()

        if report is None:
            return {
                "state": "error",
                "message": "Nicio sesiune activă.",
            }

        return {
            "state": "focus_complete",
            "action": "show_report",
            "message": f"{report['duration_minutes']} minute | Calitate {report['quality_percent']}%",
            **report,
        }