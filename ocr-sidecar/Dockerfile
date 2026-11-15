FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir opencv-python-headless rapidocr_onnxruntime fastapi uvicorn python-multipart

COPY app.py .

EXPOSE 9090

CMD ["python", "app.py"]
