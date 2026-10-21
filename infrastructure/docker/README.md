# LivingDocs — Local Docker PoC

**Jira:** TECH-11 (LIV-61)

## Prerequisites

- Docker Engine / Docker Desktop
- Docker Compose

## Components

| Application | Host Port | Container Port |
|---|---|---|
| API Gateway | 8081 | 8081 |
| Demo Service A | 8082 | 8082 |
| Demo Service B | 8083 | 8083 |

## Startup

From the LivingDocs repository root:

```bash
cd infrastructure/docker

cp .env.example .env

docker compose config

docker compose up --build -d
```

## Verify

```bash
docker compose ps

curl -i http://127.0.0.1:8081/service-a/api/identity

curl -i http://127.0.0.1:8081/service-b/api/identity

curl -i http://127.0.0.1:8081/actuator/health

curl -i http://127.0.0.1:8082/actuator/health

curl -i http://127.0.0.1:8083/actuator/health
```

Expected routing results:

- Service A: HTTP 200, `{"service":"A"}`
- Service B: HTTP 200, `{"service":"B"}`
- Health endpoints: HTTP 200, status `UP`

## Logs

```bash
docker compose logs --tail=100
```

## Shutdown

```bash
docker compose down
```

## Notes and limitations

- This configuration is for the local technical PoC only.
- Containers communicate through Docker Compose service names.
- Published HTTP ports are restricted to the host loopback interface.
- The `/backend/**` route from TECH-05 is retained, but Spring Boot Baseline is not part of this three-service Compose environment.
- `depends_on` does not guarantee application readiness.
- Final LivingDocs deployment topology remains subject to architecture review at G2.