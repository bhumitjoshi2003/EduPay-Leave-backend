# Use Eclipse Temurin base image for Java 17
FROM eclipse-temurin:17-jdk

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven wrapper and pom.xml first to cache dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies
RUN ./mvnw dependency:resolve

# Copy the rest of the source code
COPY . .

# Package the application
RUN ./mvnw package -DskipTests

# Run the JAR file
CMD ["java", "-jar", "target/ias-management-0.0.1-SNAPSHOT.jar"]