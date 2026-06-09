FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 传入构建产物
COPY target/*.jar app.jar

# JVM 参数：限制堆内存，适配 Docker 环境
ENV JAVA_OPTS="-Xms2g -Xmx6g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
