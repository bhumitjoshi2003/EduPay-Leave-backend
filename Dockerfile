# Stage 1: Build the application
FROM eclipse-temurin:21-jdk-alpine AS build

# Set the working directory inside the container
WORKDIR /app

# Highlight: Copy the Maven wrapper scripts and directory first
COPY .mvn .mvn
COPY mvnw pom.xml ./

# This allows Docker to cache the dependencies if pom.xml doesn't change
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw clean package -DskipTests

# Highlight: Change to a JRE-only image for the same Java version (21)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Highlight: Corrected typo: 'target' instead of 'tagret'
COPY --from=build /app/target/ias-management-0.0.1-SNAPSHOT.jar .

EXPOSE 8081

# Define the command to run your application
ENTRYPOINT ["java", "-Xmx512m", "-jar", "ias-management-0.0.1-SNAPSHOT.jar"]