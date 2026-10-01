# Employee Leave Management - Deployment Guide

## Prerequisites
- MySQL 8.0 installed and running
- Database: leave_management (auto-created)
- Credentials: root/mysqladmin (configurable via env vars)

## Run with JAR (recommended)
Double-click un.bat OR run:
`powershell
.\start.ps1
`

Or manually:
`ash
java -jar target/leave-manager-1.0.0.jar
`

## Environment Variables (optional override)
- SPRING_DATASOURCE_URL
- SPRING_DATASOURCE_USERNAME  
- SPRING_DATASOURCE_PASSWORD

## Access
http://localhost:8080/login

## Default Credentials
- HR: hr@company.com / password123
- Employee: aisha@company.com / password123
- Employee: sam@company.com / password123
