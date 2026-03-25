FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the simulator script
COPY till_simulator.py .

# Make the script executable
RUN chmod +x till_simulator.py

# Set environment variables
ENV API_BASE_URL=http://localhost:8080
ENV SIMULATION_INTERVAL_SECONDS=1

# Run the simulator
CMD ["python", "till_simulator.py"] 