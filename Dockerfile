FROM azul/zulu-openjdk:26.0.1-26.30@sha256:07386924c002a50edac06043218cfc95370d4e57bbcf94149dc89a9a6739e2b5

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"

WORKDIR /action
COPY *.java .
RUN ["chmod", "a-wx", "Action.java"]

RUN ["javac", "Action.java"]
ENTRYPOINT ["java", "--class-path", "/action", "Action"]
