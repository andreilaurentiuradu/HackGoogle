import time
import json
import os
from datetime import datetime, date

DATA_FILE = os.path.join(os.path.dirname(__file__), "adhd_data.json")


def _load():
    if os.path.exists(DATA_FILE):
        with open(DATA_FILE, "r") as f:
            return json.load(f)
    return {"sessions": [], "medication_logs": [], "medication_schedule": []}


def _save(data):
    with open(DATA_FILE, "w") as f:
        json.dump(data, f, indent=2, default=str)


class FocusSessionManager:
    def __init__(self):
        self.active = False
        self.start_time = None
        self.exits = 0
        self._acc_values = []
        self._hr_values = []

    def start(self, baseline_hr: float = 70.0):
        self.active      = True
        self.start_time  = time.time()
        self.exits       = 0
        self.baseline_hr = baseline_hr
        self._acc_values = []
        self._hr_values  = []

    def record_stats(self, stats):
        if not self.active:
            return
        self._acc_values.append(stats.acc_variance)
        if stats.hr > 0:
            self._hr_values.append(stats.hr)

    def record_exit(self):
        self.exits += 1

    def end(self):
        if not self.active or self.start_time is None:
            return None

        duration_minutes = max(1, int((time.time() - self.start_time) / 60))

        if self._acc_values:
            avg_acc_variance = sum(self._acc_values) / len(self._acc_values)
        else:
            avg_acc_variance = 0.0

        acc_stability = max(0, 100 - int((avg_acc_variance / 1.0) * 100))

        if len(self._hr_values) > 3:
            hr_range = max(self._hr_values) - min(self._hr_values)
            hr_variability = "variabil" if hr_range > 15 else "constant"
        else:
            hr_variability = "constant"

        quality = max(0, min(100,
            100 - (self.exits * 8) - max(0, (50 - acc_stability) / 2)
        ))

        report = {
            "duration_minutes": duration_minutes,
            "quality_percent": quality,
            "exits_count": self.exits,
            "acc_stability_percent": acc_stability,
            "hr_variability": hr_variability,
        }

        data = _load()
        data["sessions"].append({
            "date": str(date.today()),
            "start_time": datetime.fromtimestamp(self.start_time).isoformat(),
            "end_time": datetime.now().isoformat(),
            **report,
        })
        _save(data)

        self.active = False
        self.start_time = None
        return report


class HyperfocusDetector:
    # Flow = 60 ferestre × 20s verdict = 20 minute continue
    WINDOWS_FOR_FLOW = 60

    def __init__(self):
        self.flow_start_time = None
        self.alerted_hours = set()
        self.consecutive_calm = 0

    def reset(self):
        self.flow_start_time = None
        self.alerted_hours.clear()
        self.consecutive_calm = 0

    def update(self, analysis_result, stats):
        is_calm = (
            analysis_result.get("state") == "normal"
            and stats.acc_variance < 0.15
            and stats.gyro_mean    < 0.3
            and 60 < stats.hr      < 90
        )

        if not is_calm:
            self.consecutive_calm = 0
            self.flow_start_time = None
            self.alerted_hours.clear()
            return None

        self.consecutive_calm += 1

        if self.consecutive_calm >= self.WINDOWS_FOR_FLOW and self.flow_start_time is None:
            self.flow_start_time = time.time()

        if self.flow_start_time is None:
            return None

        elapsed_hours = (time.time() - self.flow_start_time) / 3600
        for h in [1, 2, 3]:
            if elapsed_hours >= h and h not in self.alerted_hours:
                self.alerted_hours.add(h)
                return {
                    "state": "hyperfocus_alert",
                    "action": "vibrate_soft",
                    "hours": h,
                    "message": f"Ești în flow de {h}h! Ia o pauză.",
                }

        return None


class MedicationManager:
    def __init__(self):
        data = _load()
        self.schedule = data.get("medication_schedule", [])
        self._notified_today = {}  # date_str -> [time_str, ...]

    def set_schedule(self, times):
        self.schedule = times
        data = _load()
        data["medication_schedule"] = times
        _save(data)

    def check_due(self):
        now = datetime.now()
        today = str(date.today())
        notified = self._notified_today.get(today, [])

        for time_str in self.schedule:
            try:
                h, m = map(int, time_str.split(":"))
                scheduled = now.replace(hour=h, minute=m, second=0, microsecond=0)
                if abs((now - scheduled).total_seconds()) < 60 and time_str not in notified:
                    notified.append(time_str)
                    self._notified_today[today] = notified
                    return {
                        "state": "medication_reminder",
                        "action": "vibrate_medication",
                        "time": time_str,
                        "message": "Medicație? ✓/✗",
                    }
            except Exception:
                continue
        return None

    def log_intake(self, taken: bool, time_str: str = None):
        data = _load()
        data["medication_logs"].append({
            "date": str(date.today()),
            "time": time_str or datetime.now().strftime("%H:%M"),
            "taken": taken,
        })
        _save(data)

    def get_today_logs(self):
        data = _load()
        today = str(date.today())
        return [l for l in data["medication_logs"] if l.get("date") == today]


class MedicalReportGenerator:
    def generate(self, target_date: str = None):
        if target_date is None:
            target_date = str(date.today())

        data = _load()
        sessions = [s for s in data["sessions"] if s.get("date") == target_date]
        med_logs = [l for l in data["medication_logs"] if l.get("date") == target_date]

        total_focus_minutes = sum(s.get("duration_minutes", 0) for s in sessions)
        avg_quality = (
            int(sum(s.get("quality_percent", 0) for s in sessions) / len(sessions))
            if sessions else 0
        )
        total_exits = sum(s.get("exits_count", 0) for s in sessions)

        best_hour = None
        if sessions:
            best_session = max(sessions, key=lambda s: s.get("quality_percent", 0))
            try:
                best_hour = datetime.fromisoformat(best_session["start_time"]).strftime("%H:00")
            except Exception:
                best_hour = "necunoscut"

        med_taken = sum(1 for l in med_logs if l.get("taken"))
        med_total = len(data.get("medication_schedule", []))

        medication_correlation = _medication_correlation(data["sessions"], data["medication_logs"])

        return {
            "date": target_date,
            "total_focus_hours": round(total_focus_minutes / 60, 1),
            "focus_sessions_count": len(sessions),
            "avg_focus_quality": avg_quality,
            "fidgeting_episodes": total_exits,
            "best_focus_hour": best_hour,
            "medication_taken": med_taken,
            "medication_scheduled": med_total,
            "medication_correlation": medication_correlation,
            "sessions": sessions,
        }


def _medication_correlation(all_sessions, all_logs):
    """Compară focus_hours mediu pe zilele cu vs fără medicație."""
    by_date_taken  = {}  # date -> True/False (a luat cel puțin o dată)
    for log in all_logs:
        d = log.get("date")
        if d:
            by_date_taken[d] = by_date_taken.get(d, False) or log.get("taken", False)

    hours_with    = []
    hours_without = []
    by_date_hours = {}
    for s in all_sessions:
        d = s.get("date")
        if d:
            by_date_hours[d] = by_date_hours.get(d, 0) + s.get("duration_minutes", 0)

    for d, minutes in by_date_hours.items():
        hours = minutes / 60
        if by_date_taken.get(d):
            hours_with.append(hours)
        else:
            hours_without.append(hours)

    avg_with    = round(sum(hours_with)    / len(hours_with),    2) if hours_with    else None
    avg_without = round(sum(hours_without) / len(hours_without), 2) if hours_without else None
    difference  = round(avg_with - avg_without, 2) if (avg_with is not None and avg_without is not None) else None

    return {
        "avg_focus_hours_with_medication":    avg_with,
        "avg_focus_hours_without_medication": avg_without,
        "difference": difference,
    }
