# ─── сборка ───
FROM gradle:8.5-jdk17 AS build
WORKDIR /src
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle jar --no-daemon

# ─── запуск ───
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN adduser -D -h /app bot
COPY --from=build /src/build/libs/lms-bot.jar ./lms-bot.jar
USER bot
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tashkent -XX:MaxRAMPercentage=75"
CMD ["java", "-jar", "lms-bot.jar"]
