FROM python:3.12-slim

# ip コマンド(iproute2)が /sys/class/net の情報から実IPを引くのに必要
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py wsgi.py .
COPY templates/ templates/

EXPOSE 8080
CMD ["python", "wsgi.py"]
