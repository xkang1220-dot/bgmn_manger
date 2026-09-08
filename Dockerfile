FROM amazoncorretto:21

ENV TZ=Asia/Shanghai
WORKDIR /app

COPY kk-starter/target/kk-starter-1.0.0.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8999

ENTRYPOINT ["sh", "-c", "exec java -Xms256m -Xmx512m -jar app.jar"]
