FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

RUN apk add --no-cache maven

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache python3 python3-dev py3-pip py3-setuptools gcc musl-dev && \
    ln -sf /usr/bin/python3 /usr/bin/python && \
    pip3 install --no-cache-dir --break-system-packages \
        clickhouse-connect \
        pocketbase \
        pyarrow \
        pandas \
        python-dotenv \
        psycopg2-binary

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /etl/data/stage /etl/data/agg

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]