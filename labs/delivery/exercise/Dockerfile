FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY ContainerApp.java .
RUN javac --release 21 ContainerApp.java && jar --create --file app.jar --main-class ContainerApp ContainerApp.class

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
# TODO: 数値UIDの非rootユーザーを作成し、そのユーザーへ切り替える
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
