# LivingDocs

**LivingDocs — Design and Implementation of an AI-Assisted Self-Updating Document System for Engineering Teams**

LivingDocs is an AI-assisted system designed to help engineering teams detect documentation drift, generate documentation from source code, and maintain documentation consistency as software evolves.

## Project Status

Sprint 3 đang triển khai các service theo từng PR. S01 LIV-138 PR 1 có scoped authority, operator provisioning và API-038; S02/S04 Golden Flow còn phụ thuộc các PR tích hợp.

Architecture and technology decisions are subject to the project's formal review and approval process.

## Project Management

- **Jira:** [LivingDocs (LIV)](https://huutiense25.atlassian.net/jira/software/projects/LIV)
- **Confluence:** [LivingDocs Project Knowledge Base](https://huutiense25.atlassian.net/wiki/spaces/LPKB)

## Repository

This repository serves as the shared source-code and technical implementation workspace for the LivingDocs project.

## Repository Structure

LivingDocs uses an initial monorepo structure to support parallel development across different technical areas.

```text
LivingDocs/
├── backend/
├── ai-service/
├── frontend/
├── infrastructure/
├── tests/
├── docs/
├── .github/
├── .gitignore
├── README.md
└── LICENSE
```

### Directory Responsibilities

| Directory | Purpose |
|-----------|---------|
| `backend/` | Backend application development and technical experiments. |
| `ai-service/` | AI-related development, source-code analysis, and evaluation. |
| `frontend/` | Frontend application development. |
| `infrastructure/` | Infrastructure, local environment, and deployment configurations. |
| `tests/` | Cross-component integration, end-to-end, and performance tests. |
| `docs/` | Repository-local technical documentation and implementation guides. |
| `.github/` | GitHub-specific configuration and future automation workflows. |

### Architecture Status

**Status: implementation theo từng service/PR; xem contract và ADR đã duyệt trước khi dùng một API candidate.**

This repository structure is an initial organizational scaffold, not a final system architecture.

- Các service S01–S04, Gateway và S05 có runtime riêng; xem README theo module cho trạng thái triển khai.
- OpenAPI candidate không đồng nghĩa Controller hoặc Golden Flow đã hoạt động.
- Những thay đổi topology/contract tiếp tục theo Architecture Decision Records (ADRs) được duyệt.
- Confluence remains the source of truth for controlled project documentation and architecture decisions.


## Quick Start — LIV-138 PR 1

Fresh clone: cài Java 21, Docker Compose, PostgreSQL `psql`, Python 3 và Node.js; từ `infrastructure/docker` tạo `.env` từ `.env.example`, khởi động PostgreSQL/Redis, tạo DB roles, rồi chạy S01 để Flyway áp dụng V1–V4. Sau đó Register A/B/C, bootstrap Platform Admin A một lần, dùng named Operator CLI tạo Tenant với Initial Owner B và các Workspace/Role/Grant `PENDING`. Chạy Postman trước Newman. Không có bước reset volume/database.

- [Hướng dẫn fresh clone và kiểm thử LIV-138 PR 1](docs/implementation/authorization/LIV-138-s01-authority-and-testing.md)
- [Docker, PostgreSQL roles và Compose](infrastructure/docker/README.md)
- [Backend và Maven Wrapper](backend/README.md)
- [OpenAPI và route validator](contracts/openapi/README.md)

Mỗi service có Maven Wrapper riêng; root repository không phải Maven reactor. Hướng dẫn trên phân biệt kết quả local của PR 1 với S02/S04 E2E và CI.
