FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ridge-orders-0.6.1-rc.1.jar app.jar
USER 10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
