# ─── мини-приложение ───
FROM node:22-alpine AS web
WORKDIR /src/webapp
COPY webapp/package.json webapp/package-lock.json ./
RUN npm ci
COPY webapp ./
# vite кладёт сборку в ../src/main/resources/webapp — оттуда она попадёт в jar
RUN npm run build

# ─── сборка ───
FROM gradle:8.5-jdk17 AS build
WORKDIR /src
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
COPY --from=web /src/src/main/resources/webapp ./src/main/resources/webapp
RUN gradle jar --no-daemon

# ─── запуск ───
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN adduser -D -h /app bot
COPY --from=build /src/build/libs/lms-bot.jar ./lms-bot.jar
USER bot
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tashkent -XX:MaxRAMPercentage=75"
CMD ["java", "-jar", "lms-bot.jar"]
