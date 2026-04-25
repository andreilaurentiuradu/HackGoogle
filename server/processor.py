import time

class DataStreamProcessor:
    def __init__(self, brain, window_duration=10, process_interval=1):
        self.brain = brain
        self.window_duration = window_duration 
        self.process_interval = process_interval
        self.buffer = []
        self.last_analysis_time = time.time()

    def add_data(self, data):
        current_time = time.time()
        data['_server_time'] = current_time # Timestamp intern pentru curățenie
        self.buffer.append(data)
        
        # Curățăm buffer-ul: eliminăm datele mai vechi de 10 secunde
        self.buffer = [d for d in self.buffer if current_time - d['_server_time'] < self.window_duration]

        # Analizăm la fiecare secundă
        if current_time - self.last_analysis_time >= self.process_interval:
            self.last_analysis_time = current_time
            return self.brain.process_window(self.buffer)
        
        return None