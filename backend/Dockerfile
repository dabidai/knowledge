# ==================== 构建阶段 ====================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build
# 先复制 pom.xml 利用 Docker 缓存加速
COPY pom.xml .
RUN mvn dependency:go-offline -q 2>/dev/null || true

# 复制源码并打包（跳过测试）
COPY src ./src
RUN mvn package -DskipTests -q

# ==================== 运行阶段 ====================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/*.jar app.jar

# JVM 参数：限制堆内存，适配 Docker 环境
ENV JAVA_OPTS="-Xms2g -Xmx6g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
