from fastapi import FastAPI
from fastapi.responses import Response
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Counter,
    generate_latest,
)

# khởi tạo spring boot application
app = FastAPI(
    title = "LivingDocs AI Service",
    version = "0.1.0"
)

health_requests = Counter(
    "livingdocs_ai_health_requests_total",
    "Total S05 health requests",
)

# @GetMapping("/health")
@app.get("/health", tags=["Operations"])
def health(): # method xu ly request
    # tra ve JSON response
    health_requests.inc()
    return{
        "status": "ok",
        "service": "livingdocs-ai"
    }

# prometheus metrics
@app.get("/metrics", include_in_schema=False)
def metrics():
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )
