FROM eclipse-temurin:21-jdk-jammy

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Source code will be mounted as a volume
VOLUME /tmp