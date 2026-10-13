FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN chmod +x mvnw && ./mvnw -B -ntp verify

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system orderflow && useradd --system --gid orderflow orderflow \
    && mkdir -p /app/data && chown -R orderflow:orderflow /app
USER orderflow

FROM runtime AS order-service
COPY --from=build /build/order-service/target/order-service-0.1.0.jar /app/app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS payment-simulator
COPY --from=build /build/payment-simulator/target/payment-simulator-0.1.0.jar /app/app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

