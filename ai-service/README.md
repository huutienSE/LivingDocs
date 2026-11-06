# LivingDocs — S05 FastAPI Runtime

## 1. Mục đích

S05 là AI Semantic Processing Service, sử dụng Python 3.12 và FastAPI.

Trong LIV-117, service được container hóa và cung cấp health, OpenAPI, Prometheus metrics. Đây mới là runtime baseline, chưa triển khai LLM, RAG hay xử lý Kafka nghiệp vụ.

## 2. Chạy trực tiếp trên máy

### macOS

Từ thư mục `ai-service`:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### Windows PowerShell

Từ thư mục `ai-service`:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

## 3. Chạy bằng Docker Compose

Từ thư mục `infrastructure/docker`.

### macOS

```bash
docker compose --profile platform up -d --build --wait
docker compose --profile platform ps
```

### Windows PowerShell

```powershell
docker compose --profile platform up -d --build --wait
docker compose --profile platform ps
```

## 4. Kiểm tra API và metrics

| Endpoint | Mục đích |
|---|---|
| `GET /health` | Health của tiến trình FastAPI |
| `GET /openapi.json` | OpenAPI schema |
| `GET /docs` | Swagger UI |
| `GET /metrics` | Prometheus metrics |

### macOS

```bash
curl -i http://127.0.0.1:8000/health
curl -i http://127.0.0.1:8000/openapi.json
curl -i http://127.0.0.1:8000/metrics
```

### Windows PowerShell

```powershell
curl.exe -i http://127.0.0.1:8000/health
curl.exe -i http://127.0.0.1:8000/openapi.json
curl.exe -i http://127.0.0.1:8000/metrics
```

Health thành công không chứng minh Kafka/Redis đã được tích hợp với S05.

## 5. Giới hạn hiện tại

- Kafka và Redis được cung cấp như các thành phần hạ tầng riêng.
- Chưa có consumer, producer hoặc AI orchestration thực tế.
- Metrics baseline chưa thay thế tracing, dashboard hoặc business observability.
- Không commit `.env`, API keys hoặc secrets.
- Cấu hình local chưa được xem là cấu hình bảo mật staging/production.

**Jira:** LIV-117 — FastAPI, Kafka, Redis baselines and Compose smoke.