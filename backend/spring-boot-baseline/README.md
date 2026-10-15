# LivingDocs — Spring Boot Baseline

## Environment
- Java: 21 LTS
- Spring Boot: 4.1.1
- Build Tool: Maven Wrapper
- Configuration: application.yml
- Maven: Apache Maven 3.10.0
## Build
./mvnw clean verify

## Run
./mvnw spring-boot:run

## Verify
GET http://localhost:8080/api/ping

Expected response:
{"status":"ok"}

## Notes
This is a technical bootstrap application.
It does not define final microservice boundaries.