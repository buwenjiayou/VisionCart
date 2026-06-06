#!/bin/bash
# Load .env file and start backend
set -a
# Read .env file, remove carriage returns, and source
source <(cat .env | tr -d '\r')
set +a
java -jar backend/build/libs/backend-0.1.0.jar
