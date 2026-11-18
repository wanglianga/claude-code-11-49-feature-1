# 构建阶段：Maven + JDK 编译打包（测试需数据库，镜像构建时跳过；测试运行方式见 README）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 运行阶段：仅 JRE
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/mpi-service-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
