# Library Manager - Deployment Guide

## Prerequisites
- JDK 21
- MySQL 8.0 installed and running
- Database: library_management (auto-created)
- Credentials: root/mysqladmin (configurable via env vars)

## Run with JAR (recommended)
Double-click run.bat, or from PowerShell:

```powershell
.\start.ps1
```

Or manually:

```bash
java -jar target/library-manager-1.0.0.jar
```

## Environment Variables (optional override)
- SPRING_DATASOURCE_URL
- SPRING_DATASOURCE_USERNAME
- SPRING_DATASOURCE_PASSWORD
- SERVER_PORT
- APP_SEED_ENABLED
- APP_SEED_PASSWORD

## Access
http://localhost:8081/login

The port is 8081 rather than the usual 8080 so this app can run at the same time
as another application already on 8080. Override with SERVER_PORT.

## Default Credentials
- Admin: admin@library.test / password123
- Librarian: librarian@library.test / password123
- Member: member@library.test / password123

Demo data is only seeded when the database has no users at all. Set
APP_SEED_ENABLED=false in production.

## Container
```bash
docker build -t library-manager .
docker run -p 8081:8081 library-manager
```

See README.md for the full run instructions and an explanation of the lending
rules.