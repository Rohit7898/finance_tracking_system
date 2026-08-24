FROM eclipse-temurin:21-jdk

WORKDIR /app

ADD https://jdbc.postgresql.org/download/postgresql-42.7.4.jar /app/postgresql.jar
COPY src/Main.java /app/src/Main.java

RUN javac -cp /app/postgresql.jar -d /app/out /app/src/Main.java

EXPOSE 8080

CMD ["java", "-cp", "/app/out:/app/postgresql.jar", "Main"]
