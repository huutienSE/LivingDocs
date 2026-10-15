# LivingDocs

**LivingDocs — Design and Implementation of an AI-Assisted Self-Updating Document System for Engineering Teams**

LivingDocs is an AI-assisted system designed to help engineering teams detect documentation drift, generate documentation from source code, and maintain documentation consistency as software evolves.

## Project Status

Technical Foundation — Initial Repository Setup.

Architecture and technology decisions are subject to the project's formal review and approval process.

## Project Management

- **Jira:** [LivingDocs (LIV)](https://huutiense25.atlassian.net/jira/software/projects/LIV)
- **Confluence:** [LivingDocs Project Knowledge Base](https://huutiense25.atlassian.net/wiki/spaces/LPKB)

## Repository


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

**Status: INITIAL STRUCTURE — NOT ARCHITECTURE BASELINED**

This repository structure is an initial organizational scaffold, not a final system architecture.

- Directory names do not define final microservice boundaries.
- Backend and AI areas may contain multiple components or experimental implementations.
- Technology selections and deployment topology remain subject to formal evaluation and approval.
- The structure may evolve through future technical tasks and approved Architecture Decision Records (ADRs).
- Confluence remains the source of truth for controlled project documentation and architecture decisions.


## Development

The initial repository structure has been established as part of TECH-02 (LIV-54).

Development environments, application-specific structures, build configurations, testing tools, and deployment instructions will be introduced progressively through subsequent technical tasks.

Technical implementations and proof-of-concept activities must follow the project's governance and architecture decision process.
