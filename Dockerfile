FROM azul/zulu-openjdk:27.0.1.0.3-27.30@sha256:d81de5ab0629b0376173e06fca63e51184b1212a2bac2e1eb1148780f7b770f8

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"

WORKDIR /action
COPY *.java .
RUN ["chmod", "a-wx", "Action.java"]

RUN ["javac", "Action.java"]
ENTRYPOINT ["java", "--class-path", "/action", "Action"]
