# ============================================================
# 后端镜像：两阶段构建
# 第一阶段用 Maven 编译出 jar，第二阶段只留 JRE + jar，镜像更小
# ============================================================

# ---------- 第一阶段：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 容器里用阿里云镜像加速（不要用宿主机的 maven-settings.xml，里面的本地仓库路径是 Windows 的）
COPY deploy/maven-settings.xml /root/.m2/settings.xml

# 只 COPY 需要的文件，故意不 COPY .mvn 目录：
# 项目里的 .mvn/maven.config 指定了 Windows 绝对路径的 settings.xml，
# 带进容器会让 mvn 直接报「settings file does not exist」而构建失败。
COPY opsai-backend/pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY opsai-backend/src ./src
RUN mvn -B -q -DskipTests package

# ---------- 第二阶段：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区设成上海，否则日志和数据库时间会差 8 小时
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
