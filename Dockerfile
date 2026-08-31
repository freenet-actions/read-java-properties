FROM azul/zulu-openjdk:26.0.2.1-26.32@sha256:7ee4c7d3f328b98a18b0277b32b2642e756145055bc61fa1450ac646bbaadf34

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"

WORKDIR /action
COPY *.java .
RUN ["chmod", "a-wx", "Action.java"]

RUN ["javac", "Action.java"]
ENTRYPOINT ["java", "--class-path", "/action", "Action"]
