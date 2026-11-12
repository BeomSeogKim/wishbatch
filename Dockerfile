FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY src/ src/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
USER app
ENV SERVER_PORT=8200
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:MaxMetaspaceSize=128m -XX:TieredStopAtLevel=1"
EXPOSE 8200
ENTRYPOINT ["sh", "-c", "export SERVER_PORT=\"${PORT:-$SERVER_PORT}\"; case \"$DATABASE_URL\" in postgresql://*@*) export DATABASE_URL=\"jdbc:postgresql://${DATABASE_URL##*@}\";; postgresql://*) exit 1;; esac; exec java -jar app.jar"]
