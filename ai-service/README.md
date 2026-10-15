# LivingDocs — FastAPI AI Service Baseline

## Overview

This project provides the initial FastAPI development environment for the LivingDocs AI service.

It establishes a reproducible Python API baseline. AI capabilities such as source-code analysis, RAG, LLM integration, and documentation drift detection will be implemented in future tasks.

This baseline does not define the final AI service architecture.

## Technology Stack

- Python: 3.12.x
- Framework: FastAPI
- ASGI Server: Uvicorn
- Dependency Management: pip + requirements.txt
- Virtual Environment: venv

FastAPI and Uvicorn versions are declared in `requirements.txt`.

## Prerequisites

- Python 3.12 installed
- Git installed

## Local Setup

Run the following commands from the `ai-service/` directory.

**1. Create a virtual environment**

`python3.12 -m venv .venv`

**2. Activate the virtual environment**

macOS / Linux:

`source .venv/bin/activate`

Windows:

`.venv\Scripts\activate`

**3. Install dependencies**

`python -m pip install -r requirements.txt`

## Run Application

`python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000`

The application runs locally at:

`http://127.0.0.1:8000`

## Health Check

Endpoint: `GET /health`

Test using:

`curl -i http://127.0.0.1:8000/health`

Expected response (HTTP 200):

`{"status":"ok","service":"livingdocs-ai"}`

## API Documentation

FastAPI provides automatically generated API documentation:

- Swagger UI: http://127.0.0.1:8000/docs
- OpenAPI Schema: http://127.0.0.1:8000/openapi.json

## Notes

- `.venv/` must not be committed to GitHub.
- Python dependencies are declared in `requirements.txt`.
- No API keys or machine-specific absolute paths are required.
- This is a technical bootstrap baseline, not the final AI processing implementation.

**Jira:** LIV-63 — TECH-04 Bootstrap FastAPI AI Service.


