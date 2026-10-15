from fastapi import FastAPI


# khởi tạo spring boot application
app = FastAPI(
    title = "LivingDocs AI Service",
    version = "0.1.0"
)

# @GetMapping("/health")
@app.get("/health")
def health(): # method xu ly requestß
    # tra ve JSON response
    return{
        "status": "ok",
        "service": "livingdocs-ai"
    }

