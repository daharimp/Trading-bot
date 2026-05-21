FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd -r -u 1001 -g root bot
COPY --from=build /app/target/trading-bot-1.0-SNAPSHOT.jar /app/trading-bot.jar
USER 1001
ENTRYPOINT ["java", "-jar", "/app/trading-bot.jar"]
