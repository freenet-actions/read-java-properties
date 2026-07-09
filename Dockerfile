FROM azul/zulu-openjdk:26.0.0-26.28@sha256:1ff97904e9019c0dbf1fb4fd9728968ac4c46f88ea09c10c6c2d50ec4006323e

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"

WORKDIR /action
COPY *.java .
RUN ["chmod", "a-wx", "Action.java"]

RUN ["javac", "Action.java"]
ENTRYPOINT ["java", "--class-path", "/action", "Action"]
